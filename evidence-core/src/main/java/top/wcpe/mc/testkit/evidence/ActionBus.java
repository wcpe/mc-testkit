package top.wcpe.mc.testkit.evidence;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 语义动作总线：把验收过程的**步骤事件**按时间顺序写成 JSONL（每行一个事件）。
 *
 * <h2>为什么存在</h2>
 *
 * 证据编译器（{@link EvidenceCompiler}）的唯一数据源就是这份 JSONL：
 * 视频剪辑对轴、步骤树渲染、断言面板全部从事件流重建。把它写成独立的一等公民，
 * 而不是散在日志里的副产物，是因为「时间戳对齐」是证据可信度的根基——
 * 只有统一时钟（本类落盘时取 {@link System#currentTimeMillis()}）的语义事件，
 * 才能把录屏画面和断言数据钉在同一条时间轴上。
 *
 * <h2>事件模型</h2>
 *
 * <ul>
 *   <li>{@code suite-start}：套件开始，携带套件名与环境摘要。</li>
 *   <li>{@code step}：一个语义步骤（如 spawn-mob / pursue / kill），
 *       携带序号、名称、状态与自由数据。</li>
 *   <li>{@code assertion}：一次断言判定，归属某个步骤，携带 passed 与说明。</li>
 *   <li>{@code artifact}：一件证据产物落盘（视频段、截图），携带路径与类型，
 *       可归属某个步骤。</li>
 *   <li>{@code suite-end}：套件结束，携带总判定。</li>
 * </ul>
 *
 * <h2>线程与落盘</h2>
 *
 *  writer 内置锁，事件按写入顺序落盘；每事件一行立即 flush——
 *  验收进程崩溃时已写出的部分必须是合法 JSONL（残缺尾巴最多损失最后半行，
 *  编译器按行解析、跳过非法行）。这是证据链与「结果文件原子写」不同的取舍：
 *  总线要的是**时间顺序的完整前缀**，不是原子性。
 */
public final class ActionBus implements AutoCloseable {

    /** 事件格式版本，编译器据此拒绝解读未来的格式。 */
    public static final int FORMAT_VERSION = 1;

    private final Writer writer;
    private final String suite;
    private final String scenario;

    /**
     * 打开总线文件（覆盖写）。
     *
     * @param file     JSONL 目标文件；父目录须已存在
     * @param suite    套件名（如 ve-basic）
     * @param scenario 场景名（如 spawn-pursuit-kill）
     */
    public ActionBus(File file, String suite, String scenario) throws IOException {
        this.writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
        this.suite = suite;
        this.scenario = scenario;
    }

    /** 从环境变量 {@code MC_TESTKIT_ACTION_BUS_FILE} 解析路径开总线；未配置返回 null（总线缺席降级）。 */
    public static ActionBus fromEnv(String suite, String scenario) {
        String path = System.getenv("MC_TESTKIT_ACTION_BUS_FILE");
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return new ActionBus(new File(path), suite, scenario);
        } catch (IOException e) {
            // 总线是证据增强，不是测试本体：开不起来降级为无总线，绝不让测试因证据链而红。
            System.err.println("[mc-testkit][action-bus] 总线开启失败（降级为无总线）：" + e.getMessage());
            return null;
        }
    }

    /** 套件开始。 */
    public synchronized void suiteStart(String environmentSummary) {
        emit("suite-start", 0, null, null, null, "\"environment\":" + JsonEsc.quote(environmentSummary));
    }

    /** 一个语义步骤。 */
    public synchronized void step(int seq, String name, String status, String dataJson) {
        emit("step", seq, name, status, null,
                dataJson == null ? null : "\"data\":" + dataJson);
    }

    /** 一次断言。 */
    public synchronized void assertion(int seq, String stepName, boolean passed, String message, String dataJson) {
        String extra = "\"passed\":" + passed + ",\"message\":" + JsonEsc.quote(message)
                + (dataJson == null ? "" : ",\"data\":" + dataJson);
        emit("assertion", seq, stepName, passed ? "pass" : "fail", null, extra);
    }

    /** 一件证据产物。path 相对报告输出目录。 */
    public synchronized void artifact(int seq, String stepName, String kind, String path, Long videoStartMillis, Long videoEndMillis) {
        StringBuilder extra = new StringBuilder("\"artifactKind\":").append(JsonEsc.quote(kind))
                .append(",\"path\":").append(JsonEsc.quote(path));
        if (videoStartMillis != null) {
            extra.append(",\"videoStart\":").append(videoStartMillis);
        }
        if (videoEndMillis != null) {
            extra.append(",\"videoEnd\":").append(videoEndMillis);
        }
        emit("artifact", seq, stepName, null, null, extra.toString());
    }

    /** 套件结束。 */
    public synchronized void suiteEnd(boolean passed, String message) {
        emit("suite-end", 0, null, passed ? "pass" : "fail", null,
                "\"passed\":" + passed + ",\"message\":" + JsonEsc.quote(message));
    }

    private void emit(String type, int seq, String step, String status, String ignored, String extra) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"v\":").append(FORMAT_VERSION)
                .append(",\"type\":").append(JsonEsc.quote(type))
                .append(",\"ts\":").append(System.currentTimeMillis())
                .append(",\"suite\":").append(JsonEsc.quote(suite))
                .append(",\"scenario\":").append(JsonEsc.quote(scenario));
        if (seq > 0) {
            sb.append(",\"seq\":").append(seq);
        }
        if (step != null) {
            sb.append(",\"step\":").append(JsonEsc.quote(step));
        }
        if (status != null) {
            sb.append(",\"status\":").append(JsonEsc.quote(status));
        }
        if (extra != null) {
            sb.append(',').append(extra);
        }
        sb.append('}');
        try {
            writer.write(sb.toString());
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            // 同上：证据链故障不翻转测试结论，只留 stderr 痕迹。
            System.err.println("[mc-testkit][action-bus] 事件写盘失败：" + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // 关闭失败无可挽回，静默。
        }
    }
}
