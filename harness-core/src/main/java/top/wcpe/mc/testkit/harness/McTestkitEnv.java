package top.wcpe.mc.testkit.harness;

/**
 * mc-testkit 冻结契约的环境变量与保留场景 id（docs/API.md §3.3），纯 JDK、零 Bukkit 依赖。
 *
 * <p>编排起后端时经这些环境变量下发场景与结果路径；桩读取它们与编排对齐，
 * 不依赖任何 Bukkit 类，任意 JVM 插件桩（Java / Kotlin）均可直接使用。
 */
public final class McTestkitEnv {

    /** 环境变量统一前缀（冻结契约，docs/API.md §3.3）。 */
    public static final String PREFIX = "MC_TESTKIT_E2E_";

    /** 本次场景 id（桩据此选场景，须与编排 DSL 场景名、机器人 action 三处一致）。 */
    public static final String SCENARIO = PREFIX + "SCENARIO";

    /** 结果文件绝对路径（桩写到这里，编排 verify 从这里读，二者对齐）。 */
    public static final String RESULT_FILE = PREFIX + "RESULT_FILE";

    /**
     * console 命令通道（{@code E2E_CMD}）鉴权令牌（编排每构建随机生成，双侧下发）。
     *
     * <p>未配置时整条命令通道 <b>fail-closed</b>：拒绝一切命令请求。令牌不进日志。
     */
    public static final String CMD_TOKEN = PREFIX + "CMD_TOKEN";

    /**
     * console 命令通道的许可发送者玩家名清单（逗号分隔）。
     *
     * <p>第二道闸：令牌正确之外还要求发送者在清单内；未配置或空串按「无 bot 场景」处理——拒绝全部命令。
     */
    public static final String CMD_SENDERS = PREFIX + "CMD_SENDERS";

    /** 本后端的声明名（集群 / 压测下各服不同，每后端身份注入；消费方据此 per-backend 派生身份）。 */
    public static final String BACKEND_NAME = PREFIX + "BACKEND_NAME";

    /** 持续压测施压秒数（与机器人同源，压测编排）。 */
    public static final String STRESS_DURATION_SECONDS = PREFIX + "STRESS_DURATION_SECONDS";

    /** 录制证据开关：`1` 表示本进程声明要录屏 / 截图证据（`driver { recordEvidence = true }`）。 */
    public static final String RECORD = PREFIX + "RECORD";

    /** 视觉证据输出目录绝对路径（编排默认指向结果目录下 `recordings/`）。 */
    public static final String RECORD_DIR = PREFIX + "RECORD_DIR";

    /** 持久手测（serve）保留场景 id（对齐插件侧契约常量 McTestkitContract.SERVE_SCENARIO_ID，持久手测 serve）。 */
    public static final String SERVE_SCENARIO_ID = "__mc_testkit_serve__";

    private McTestkitEnv() {
    }

    /** 读环境变量，去空白；未设置或空串返回 null。 */
    public static String envOrNull(String name) {
        String value = System.getenv(name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 当前编排下发的场景 id（未下发返回 null）。 */
    public static String scenarioIdOrNull() {
        return envOrNull(SCENARIO);
    }

    /** 当前是否为持久手测（serve）空闲模式：桩不驱动、不判定、不关服。 */
    public static boolean isServeIdle() {
        return SERVE_SCENARIO_ID.equals(scenarioIdOrNull());
    }

    /** 录制证据是否被声明（[RECORD] == "1"）。 */
    public static boolean recordRequested() {
        return "1".equals(envOrNull(RECORD));
    }

    /** 视觉证据输出目录（未下发返回 null）。 */
    public static String recordDirOrNull() {
        return envOrNull(RECORD_DIR);
    }
}
