package top.wcpe.mc.testkit.evidence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 录屏控制器：ffmpeg x11grab 录屏 + 锚点剪段，**可选依赖**。
 *
 * <h2>降级纪律（用户硬规则）</h2>
 *
 * ffmpeg / Xvfb / display 是可选依赖：缺失时测试照常跑、断言照常判，
 * 只是没有视频截图证据。因此本类的所有入口在依赖缺失时返回明确的
 * 「不可用」而不是抛异常——fail-fast 只针对测试本体依赖（server jar/JDK），
 * 视觉依赖缺席绝不翻转测试结论。
 *
 * <h2>锚点剪段</h2>
 *
 * 录屏全程只有一条 ffmpeg 长录像；每个语义步骤在动作总线上落 ts，
 * 剪段时按相邻步骤的 ts 区间从长录像切出片段——步骤标记永不烧进画面，
 * HTML 报告里用覆盖层标记（用户规格）。
 */
public final class RecordingController {

    /** 依赖体检结果。 */
    public static final class Availability {
        /** ffmpeg 是否可用。 */
        public final boolean ffmpegPresent;
        /** DISPLAY 是否就位。 */
        public final boolean displayPresent;
        /** 不可用时给人读的原因（中文）；可用时为 null。 */
        public final String reason;

        private Availability(boolean ffmpegPresent, boolean displayPresent, String reason) {
            this.ffmpegPresent = ffmpegPresent;
            this.displayPresent = displayPresent;
            this.reason = reason;
        }

        /** 两个条件都满足才可以录。 */
        public boolean usable() {
            return ffmpegPresent && displayPresent;
        }
    }

    /** 体检视觉依赖；结果只陈述事实，调用方决定降级路径。 */
    public static Availability probe() {
        boolean ffmpeg = commandExists("ffmpeg");
        String display = System.getenv("DISPLAY");
        boolean hasDisplay = display != null && !display.isEmpty();
        String reason = null;
        if (!ffmpeg) {
            reason = "ffmpeg 不在 PATH——视频证据降级缺席（测试与断言不受影响）";
        } else if (!hasDisplay) {
            reason = "DISPLAY 未配置——无显示服务器可录（测试与断言不受影响）";
        }
        return new Availability(ffmpeg, hasDisplay, reason);
    }

    private static boolean commandExists(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, name).canExecute()) {
                return true;
            }
        }
        return false;
    }

    private Process recording;

    /** 是否在录。 */
    public synchronized boolean isRecording() {
        return recording != null && recording.isAlive();
    }

    /**
     * 起一条 x11grab 长录像。
     *
     * @param output   输出 mp4
     * @param display  X display（如 ":0.0"）
     * @param offsetX  截取区域左上 x
     * @param offsetY  截取区域左上 y
     * @param width    宽
     * @param height   高
     * @param fps      帧率
     * @throws IOException 启动失败（调用方应先 probe()，到这里失败即真故障）
     */
    public synchronized void start(File output, String display, int offsetX, int offsetY,
            int width, int height, int fps) throws IOException {
        if (isRecording()) {
            throw new IllegalStateException("已在录制中：" + output);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.add("-f");
        cmd.add("x11grab");
        cmd.add("-video_size");
        cmd.add(width + "x" + height);
        cmd.add("-framerate");
        cmd.add(String.valueOf(fps));
        cmd.add("-i");
        cmd.add(display + "+" + offsetX + "," + offsetY);
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-preset");
        cmd.add("fast");
        cmd.add(output.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(output.getParentFile(), output.getName() + ".ffmpeg.log"));
        recording = pb.start();
    }

    /**
     * 收尾：给 ffmpeg 发 SIGINT 让它写完 moov 原子再退出。
     *
     * <p>直接 kill（SIGKILL）会丢掉 moov——mp4 没有索引头就是废文件，
     * 这是用 ffmpeg 录屏最容易踩的坑。</p>
     */
    public synchronized void stopGracefully(long timeoutMillis) {
        Process p = recording;
        if (p == null) {
            return;
        }
        recording = null;
        if (!p.isAlive()) {
            return;
        }
        // Java 8 没有发信号的 API：pid() 是 Java 9+ 方法，字节码锁 8 时只能反射拿，
        // 拿不到（真的跑在 8 上）退回 destroy——证据链降级不抛。
        long pid = -1L;
        try {
            pid = (Long) p.getClass().getMethod("pid").invoke(p);
        } catch (Exception ignored) {
            // 反射失败按无 pid 处理。
        }
        try {
            if (pid > 0) {
                new ProcessBuilder("kill", "-INT", String.valueOf(pid)).start().waitFor();
            } else {
                p.destroy();
            }
        } catch (Exception e) {
            p.destroy();
        }
        try {
            if (!p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    /**
     * 从长录像按时间区间剪出片段。
     *
     * @param source       长录像
     * @param output       片段输出
     * @param startSeconds 起点（秒）
     * @param endSeconds   终点（秒）
     * @return true 剪成功
     */
    public boolean clip(File source, File output, double startSeconds, double endSeconds) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(String.format("%.3f", startSeconds));
        cmd.add("-to");
        cmd.add(String.format("%.3f", endSeconds));
        cmd.add("-i");
        cmd.add(source.getAbsolutePath());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add(output.getAbsolutePath());
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(new File(output.getParentFile(), output.getName() + ".clip.log"))
                    .start();
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
