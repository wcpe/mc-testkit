package top.wcpe.mc.testkit.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端断言登记表的契约。
 *
 * <p>这些断言钉住的都是"静默失效"形态——它们不会抛异常，只会让结论
 * 在某个环节悄悄消失。而结论消失与结论为失败在外部看起来完全不同，
 * 因此必须在这里逐条钉死。
 */
class McTestkitServerAssertionsTest {

    @Test
    @DisplayName("登记后可按名字找到")
    void registerThenFind() {
        McTestkitServerAssertions table = new McTestkitServerAssertions();
        table.register("persistence", new McTestkitServerAssertions.Body() {
            @Override
            public McTestkitServerAssertions.Outcome run() {
                return McTestkitServerAssertions.Outcome.pass("3 世界 12 区域");
            }
        });

        assertNotNull(table.find("persistence"), "已登记的断言必须能找到");
    }

    @Test
    @DisplayName("未登记时查找返回 null，而不是抛异常或造假")
    void findUnknownReturnsNull() {
        McTestkitServerAssertions table = new McTestkitServerAssertions();
        assertNull(table.find("nothing"), "未登记必须如实返回 null");
        // null 名字同样返回 null：调用方据此走"未登记"分支，而不是 NPE 把调用链打断。
        assertNull(table.find(null));
    }

    @Test
    @DisplayName("重名登记直接拒绝，不静默覆盖")
    void duplicateNameIsRejected() {
        McTestkitServerAssertions table = new McTestkitServerAssertions();
        table.register("persistence", new McTestkitServerAssertions.Body() {
            @Override
            public McTestkitServerAssertions.Outcome run() {
                return McTestkitServerAssertions.Outcome.pass("第一次");
            }
        });

        // 覆盖是静默的：后登记的那条生效、前一条永远不执行，
        // 外部只会看到"我明明登记了却没生效"——因此这里必须出声。
        // 显式匿名实现而不是嵌套 lambda：Java 8 在 assertThrows 的 Executable
        // 与 Body 之间推断不出嵌套 lambda 的返回类型（实测报"非法的表达式开始"）。
        McTestkitServerAssertions.Body second = new McTestkitServerAssertions.Body() {
            @Override
            public McTestkitServerAssertions.Outcome run() {
                return McTestkitServerAssertions.Outcome.pass("第二次");
            }
        };
        org.junit.jupiter.api.function.Executable dupCall =
            () -> table.register("persistence", second);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, dupCall);
        assertTrue(error.getMessage().contains("persistence"), "错误消息要点名冲突的断言：" + error.getMessage());
    }

    @Test
    @DisplayName("空名字或被拒：空名字永远无法被触发")
    void blankNameIsRejected() {
        McTestkitServerAssertions table = new McTestkitServerAssertions();
        // 断言体给一个合法的匿名实现：空名字应当在**检查名字时**就被拒，
        // 而不是因为 lambda 推断不出来而失败——那两者是不同原因。
        McTestkitServerAssertions.Body ok = new McTestkitServerAssertions.Body() {
            @Override
            public McTestkitServerAssertions.Outcome run() {
                return McTestkitServerAssertions.Outcome.pass("ok");
            }
        };
        org.junit.jupiter.api.function.Executable blank = () -> table.register("", ok);
        org.junit.jupiter.api.function.Executable spaces = () -> table.register("   ", ok);
        org.junit.jupiter.api.function.Executable nul = () -> table.register(null, ok);
        assertThrows(IllegalArgumentException.class, blank);
        assertThrows(IllegalArgumentException.class, spaces);
        assertThrows(IllegalArgumentException.class, nul);
    }

    @Test
    @DisplayName("空断言体被拒：登记空断言等于承诺一个永远不会到来的结论")
    void nullBodyIsRejected() {
        McTestkitServerAssertions table = new McTestkitServerAssertions();
        org.junit.jupiter.api.function.Executable nullBody = () -> table.register("persistence", null);
        assertThrows(IllegalArgumentException.class, nullBody);
    }

    @Test
    @DisplayName("names() 返回拷贝，改它不影响登记表")
    void namesIsACopy() {
        McTestkitServerAssertions table = new McTestkitServerAssertions();
        table.register("persistence", new McTestkitServerAssertions.Body() {
            @Override
            public McTestkitServerAssertions.Outcome run() {
                return McTestkitServerAssertions.Outcome.pass("ok");
            }
        });

        java.util.Set<String> names = table.names();
        names.clear();

        assertEquals(1, table.names().size(), "外部清空返回的集合不得改变登记表");
    }

    @Test
    @DisplayName("通过/失败都能带上原因")
    void outcomesCarryMessage() {
        McTestkitServerAssertions.Outcome ok = McTestkitServerAssertions.Outcome.pass("3 个世界、12 个区域已写盘");
        assertTrue(ok.passed);
        // 成功也必须带证据：只报"通过"时，读者无法分辨"真做了事"与"什么都没做也没报错"。
        assertTrue(ok.message.contains("12"), "成功结论必须带具体数字：" + ok.message);

        McTestkitServerAssertions.Outcome bad = McTestkitServerAssertions.Outcome.fail("区域数为 0");
        assertFalse(bad.passed);
        // 失败必须带原因：没有原因的"断言失败"对排查毫无帮助。
        assertTrue(bad.message.contains("0"), "失败结论必须带原因：" + bad.message);
    }
}
