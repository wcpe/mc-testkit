package top.wcpe.mc.testkit.harness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端侧断言的登记表：把「只有服务端内部才看得见的事实」变成可被编排判定的一等公民。
 *
 * <h2>为什么需要它（而不是继续用聊天文本）</h2>
 *
 * 原先的判定链是：机器人在客户端侧发命令 → 服务端把状态打成**聊天文本** → 机器人解析文本 → 下结论。
 *
 * <p>这条链有三个结构性弱点，且都在真实项目里发生过：
 *
 * <ol>
 *   <li><b>服务端不一定回话</b>。例如 Folia 上 {@code /save-all} 在主线程执行时
 *       取不到"当前区域"，它什么都不写也不报错——那条成功回执**永远不来**。
 *       判据于是卡在"等一句不会出现的话"上，而真正的原因完全看不见。</li>
 *   <li><b>文本不是结论</b>。就算回执来了，它也只证明"服务端跑过某段流程"，
 *       不证明"我关心的那个对象真的处于期望状态"。</li>
 *   <li><b>解析即耦合</b>。文案一改，判据就坏；而文案从来不是稳定契约。</li>
 * </ol>
 *
 * <p>断言登记表把判定挪到**数据真正存在的地方**：桩在服务端内部读世界、读实体列表、
 * 读插件内存状态，直接给出布尔结论。机器人只负责"触发"，不再参与判定。
 *
 * <h2>为什么放在 harness-core</h2>
 *
 * 结果是经 {@link McTestkitResultWriter} 原子写出的，而"结果文件是结论唯一真源"
 * 这条契约正是由 harness-core 持有。让每个消费方各写一份文件，就会复制契约逻辑并漂移出格式 bug。
 * 这里统一走同一条写出路径，也天然复用了桩基类的幂等门（防重复写、防在机器人之前抢跑）。
 *
 * <h2>线程模型</h2>
 *
 * 登记表本身是并发安全的；但**断言体通常在服务端线程上执行**（要读世界对象）。
 * 因此触发时由 {@link McTestkitHarnessPlugin} 负责把调用投到主线程，
 * 断言体可以放心假设自己运行在归属线程上，不必自己再判断线程。
 */
public final class McTestkitServerAssertions {

    /**
     * 一条断言的执行结果。
     *
     * <p>用类型而不是 {@code boolean}：失败必须能带上**原因**，
     * 否则"断言失败"这句话对排查毫无帮助，而排查正是这类探针存在的全部意义。
     */
    public static final class Outcome {

        /** 断言是否成立。 */
        public final boolean passed;

        /** 给人读的说明；失败时是原因，成功时是证据摘要。 */
        public final String message;

        private Outcome(boolean passed, String message) {
            this.passed = passed;
            this.message = message;
        }

        /**
         * 构造一条通过的断言结果。
         *
         * @param evidence 证据摘要（应当带上具体数字，例如「3 个世界、12 个区域已写盘」）
         * @return 结果
         */
        public static Outcome pass(String evidence) {
            return new Outcome(true, evidence);
        }

        /**
         * 构造一条失败的断言结果。
         *
         * @param reason 具体原因（直接进结果文件的 message，会被人读到）
         * @return 结果
         */
        public static Outcome fail(String reason) {
            return new Outcome(false, reason);
        }
    }

    /**
     * 断言体：返回结论。
     *
     * <h2>线程约定</h2>
     *
     * 默认在<b>主线程</b>执行，因此可以直接读世界对象（{@code world.getEntities()}、
     * {@code getPersistentDataContainer()}）——这些在 Folia 上跨线程读会抛线程检查异常。
     *
     * <p>但有些断言要<b>等 I/O</b>：例如"保存是否真的落到磁盘"必须先等文件写出来。
     * 在主线程上等会卡住整个 tick，服务端会因此掉 TPS 甚至看门狗重启。
     * 这类断言应重写 {@link #isAsync()} 返回 {@code true}，改由异步线程执行；
     * 结论仍会被切回主线程落定（写结果文件、关服都是主线程动作）。
     *
     * <p>异步断言内**不得**直接读世界对象。正确做法是在主线程先取快照
     * （世界名、实体数、目录路径），把快照交给异步阶段去等磁盘。
     */
    public interface Body {
        /**
         * 执行断言。
         *
         * @return 结论
         */
        Outcome run();

        /**
         * 是否在异步线程执行（默认 {@code false}，即主线程）。
         *
         * @return {@code true} 表示该断言会阻塞等待 I/O，应放到异步线程
         */
        default boolean isAsync() {
            return false;
        }
    }

    /** 登记表：断言名 → 断言体。README 级别的名字唯一性由 {@link #register} 强制。 */
    private final Map<String, Body> bodies = new ConcurrentHashMap<>();

    /**
     * 登记一条服务端断言。
     *
     * <p>重名**直接失败**而不是覆盖：两个不同来源登记同名断言时，
     * 静默覆盖会让"我明明登记了却没生效"变成无从追查的现象，
     * 而它恰恰最容易被误读成"断言没通过"。
     *
     * @param name 断言名（与机器人侧触发时使用的名字逐字一致）
     * @param body 断言体
     * @throws IllegalArgumentException 名字为空或已登记时抛出，消息为中文
     */
    public void register(String name, Body body) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("断言名不得为空白——空名字无法被机器人触发，也就永远不会有结论");
        }
        if (body == null) {
            throw new IllegalArgumentException("断言体不得为 null——登记一条空断言等于承诺一个永远不会到来的结论");
        }
        Body previous = bodies.putIfAbsent(name, body);
        if (previous != null) {
            throw new IllegalArgumentException(
                "断言「" + name + "」已被登记过——重名会让其中一方的结论永远不生效，因此这里直接拒绝覆盖");
        }
    }

    /**
     * 查找一条断言。
     *
     * @param name 断言名
     * @return 断言体；未登记时为 null
     */
    public Body find(String name) {
        return name == null ? null : bodies.get(name);
    }

    /**
     * 已登记的断言名（用于诊断：让"我登记了哪些"可被读到）。
     *
     * @return 断言名集合（拷贝，修改它不影响登记表）
     */
    public java.util.Set<String> names() {
        return new java.util.HashSet<>(bodies.keySet());
    }
}
