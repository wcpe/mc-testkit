package top.wcpe.mc.testkit.harness;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

/**
 * mc-testkit 结果文件原子写出器（docs/API.md §3.5），纯 JDK、零 Bukkit 依赖。
 *
 * <p>把场景判定结论写成 {@code <scenario>.properties}，键名严格对齐冻结契约：
 * {@code status}(PASS/FAIL) / {@code message}(结论说明) + 可选场景明细键。
 * 编排的 verify 任务**只认这个文件**判 PASS/FAIL，故这里是测试结论的唯一真源。
 *
 * <p>**原子落盘**：先写同目录临时文件，再原子移动替换目标——编排轮询结果文件时
 * 不会读到「写了一半」的结果而误判。
 */
public final class McTestkitResultWriter {

    /** 结果状态键（对齐契约 status）。 */
    public static final String KEY_STATUS = "status";

    /** 结果说明键（对齐契约 message）。 */
    public static final String KEY_MESSAGE = "message";

    /** 通过状态值。 */
    public static final String STATUS_PASS = "PASS";

    /** 失败状态值。 */
    public static final String STATUS_FAIL = "FAIL";

    private final File resultFile;

    /**
     * @param resultFile 结果文件；通常是编排经 {@code MC_TESTKIT_E2E_RESULT_FILE} 下发的绝对路径
     *                   （见 {@link McTestkitEnv#RESULT_FILE}）。
     */
    public McTestkitResultWriter(File resultFile) {
        this.resultFile = resultFile;
    }

    /** 判 PASS（无明细键）。 */
    public void pass(String message) {
        write(STATUS_PASS, message, java.util.Collections.emptyMap());
    }

    /** 判 PASS（带场景明细键，如 server / backendName / rewardCount）。 */
    public void pass(String message, Map<String, String> details) {
        write(STATUS_PASS, message, details);
    }

    /** 判 FAIL。 */
    public void fail(String message) {
        write(STATUS_FAIL, message, java.util.Collections.emptyMap());
    }

    /**
     * 写出结果文件。
     *
     * @param status  结果状态，取 {@link #STATUS_PASS} / {@link #STATUS_FAIL}。
     * @param message 结论说明（失败时写失败原因）。
     * @param details 场景特定明细键（如 rewardCount / txId），消费方按需补充。
     * @throws IllegalStateException 写入失败时抛出（结果文件是测试结论唯一真源，宁可失败出声）。
     */
    public void write(String status, String message, Map<String, String> details) {
        File target = resultFile.getAbsoluteFile();
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        Properties props = new Properties();
        // 先写明细，再写 status / message，保证后者不被同名明细键覆盖
        details.forEach(props::setProperty);
        props.setProperty(KEY_STATUS, status);
        props.setProperty(KEY_MESSAGE, message);
        File temp;
        try {
            temp = File.createTempFile("result-", ".tmp", dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建结果临时文件（目录=" + dir + "）", e);
        }
        try {
            try (OutputStream out = new FileOutputStream(temp)) {
                props.store(out, "mc-testkit E2E result");
            }
            moveAtomically(temp, target);
        } catch (IOException e) {
            throw new IllegalStateException("写入 E2E 结果文件失败：" + target, e);
        } finally {
            temp.delete();
        }
    }

    /** 原子替换目标；文件系统不支持原子移动时退化为普通替换（同盘 rename 通常已足够）。 */
    private static void moveAtomically(File from, File to) throws IOException {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
