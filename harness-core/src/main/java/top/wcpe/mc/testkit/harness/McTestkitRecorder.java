package top.wcpe.mc.testkit.harness;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 录屏 / 截图证据能力（可选降级，套件与外部 driver 复用同一契约）。
 *
 * <p>面向需要视觉证据的套件（如 LAN Host→Join 真客户端场景）：声明录制后经环境变量契约注入
 * （[McTestkitEnv.RECORD]=1、[McTestkitEnv.RECORD_DIR]=输出目录），套件内 JVM 调本类截屏 / 开录；
 * driver 车道的真客户端进程（calcite 等）则用自己的截图能力读同一组 env，证据统一落
 * [McTestkitEnv.RECORD_DIR]（编排默认指向结果目录下 {@code recordings/}，随场景归档）。
 *
 * <p><b>降级硬规则</b>：录制依赖（ffmpeg / 显示环境）缺失、录制开关未开或开录失败时，
 * 全部方法只打中文告警并返回 null——测试照常 PASS，只是没有人工验收的截图 / 视频证据，
 * 无头（headless）环境必须能正确跑。本类任何路径不向调用方抛异常。
 *
 * <p>服务端侧录制走 ffmpeg 屏幕捕获：Linux 走 x11grab（须 DISPLAY，无显示即降级）、
 * Windows 走 gdigrab、macOS 走 avfoundation；外部可覆写捕获源命令行（[.withCaptureSource]）。
 */
public final class McTestkitRecorder {

    private static final long FFMPEG_PROBE_TIMEOUT_MS = 10_000;
    private static final long STOP_GRACE_MS = 5_000;

    private McTestkitRecorder() {
    }

    /**
     * 录屏是否可用：录制已声明（RECORD=1）+ 输出目录已下发 + ffmpeg 可执行 + 本机存在可捕获的显示源。
     * 任何一环不满足都返回 false——调用方据此把证据记为「未产出」，不判 FAIL。
     */
    public static boolean available() {
        if (!McTestkitEnv.recordRequested()) {
            return false;
        }
        if (McTestkitEnv.recordDirOrNull() == null) {
            return false;
        }
        if (ffmpegPath() == null) {
            warn("录屏降级：未找到 ffmpeg 可执行（PATH 上），本场景将无截图 / 视频证据");
            return false;
        }
        if (captureSourceArgs() == null) {
            warn("录屏降级：本机无可捕获显示源（如 headless 无 DISPLAY），本场景将无截图 / 视频证据");
            return false;
        }
        return true;
    }

    /**
     * 截一帧屏：{@code <recordDir>/<name>-<时间戳>.png}。
     *
     * @return 落盘的 PNG 文件；不可用或捕获失败返回 null（已告警）。
     */
    public static File screenshot(String name) {
        if (!available()) {
            return null;
        }
        File out = outputFile(name, "png");
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath());
        cmd.add("-y");
        cmd.addAll(captureSourceArgs());
        cmd.add("-frames:v");
        cmd.add("1");
        cmd.add(out.getAbsolutePath());
        if (!runBlocking(cmd, FFMPEG_PROBE_TIMEOUT_MS)) {
            warn("截图失败（ffmpeg 非零退出）：" + out.getAbsolutePath());
            return null;
        }
        return out.isFile() ? out : null;
    }

    /**
     * 开录一段视频：{@code <recordDir>/<name>-<时间戳>.mp4}（H.264、15fps），
     * 返回的 [Recording] 由调用方在证据窗口结束时 {@link Recording#stop()} 收尾。
     *
     * @return 录制句柄；不可用或开录失败返回 null（已告警）。
     */
    public static Recording start(String name) {
        if (!available()) {
            return null;
        }
        File out = outputFile(name, "mp4");
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath());
        cmd.add("-y");
        cmd.addAll(captureSourceArgs());
        cmd.add("-r");
        cmd.add("15");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add(out.getAbsolutePath());
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(ffmpegLogFile(out))).start();
        } catch (IOException ex) {
            warn("开录失败（ffmpeg 未能启动）：" + ex.getMessage());
            return null;
        }
        return new Recording(process, out);
    }

    /** 一段在录的视频；{@link #stop()} 收尾定稿文件，重复调用安全。 */
    public static final class Recording {
        private final Process process;
        private final File file;
        private boolean stopped;

        private Recording(Process process, File file) {
            this.process = process;
            this.file = file;
        }

        /** 计划落盘的视频文件（stop 后才有有效内容）。 */
        public File file() {
            return file;
        }

        /**
         * 收尾：先向 ffmpeg stdin 发 'q' 温和收尾（定稿 mp4 moov），限时未退升级强杀。
         * 任何失败都只告警不抛——录制失败不影响测试判定。
         */
        public void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            try {
                try (OutputStream in = process.getOutputStream()) {
                    in.write('q');
                    in.flush();
                }
            } catch (IOException ignored) {
                // stdin 已关即走 destroy 路径
            }
            try {
                if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            if (!file.isFile()) {
                warn("录制收尾后未产出文件：" + file.getAbsolutePath());
            }
        }
    }

    /**
     * 覆写捕获源命令行（测试 / 特殊平台用）；null 恢复自动探测。
     * 形如 {@code ["-f","x11grab","-i",":0"]}——须完整给出 ffmpeg 的输入参数段。
     */
    public static void withCaptureSource(List<String> args) {
        captureSourceOverride = args;
    }

    private static volatile List<String> captureSourceOverride;

    /** 本机显示源的 ffmpeg 输入参数；无可用显示源返回 null（headless 即降级）。 */
    private static List<String> captureSourceArgs() {
        List<String> override = captureSourceOverride;
        if (override != null) {
            return override;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return Arrays.asList("-f", "gdigrab", "-i", "desktop");
        }
        if (os.contains("mac")) {
            // avfoundation 的屏幕设备号因机而异；「1」为常见屏幕索引，失败由 runBlocking 降级兜底
            return Arrays.asList("-f", "avfoundation", "-i", "1:none");
        }
        String display = System.getenv("DISPLAY");
        if (display != null && !display.trim().isEmpty()) {
            return Arrays.asList("-f", "x11grab", "-i", display.trim());
        }
        return null;
    }

    /** PATH 上找 ffmpeg；找不到返回 null。 */
    private static String ffmpegPath() {
        for (String dir : System.getenv("PATH") == null ? new String[0] : System.getenv("PATH").split(File.pathSeparator)) {
            File f = new File(dir, isWindows() ? "ffmpeg.exe" : "ffmpeg");
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** ffmpeg 自身输出也留证：排障时能看到降级原因（无权、缺编码器等）。 */
    private static File ffmpegLogFile(File mediaFile) {
        return new File(mediaFile.getParentFile(), mediaFile.getName() + ".ffmpeg.log");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static File outputFile(String name, String ext) {
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        File dir = new File(McTestkitEnv.recordDirOrNull());
        // 目录缺失时创建失败也交由 ffmpeg 报错降级，不提前抛
        dir.mkdirs();
        return new File(dir, safe + "-" + System.currentTimeMillis() + "." + ext);
    }

    private static boolean runBlocking(List<String> cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static void warn(String message) {
        System.err.println("[mc-testkit][录屏] " + message);
    }
}
