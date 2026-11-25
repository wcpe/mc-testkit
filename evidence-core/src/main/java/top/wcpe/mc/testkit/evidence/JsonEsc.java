package top.wcpe.mc.testkit.evidence;

/**
 * 手写 JSON 转义与拼接，零依赖。
 *
 * <p>evidence-core 锁死零第三方依赖（与 harness-core 同一纪律：消费方会把构件
 * 打进插件 jar 或编排类路径，任何外部库都是冲突与体积的来源）。JSON 写出面
 * 非常窄——只有字符串、数字、布尔与扁平结构——手写转义比引库更诚实。</p>
 */
final class JsonEsc {

    private JsonEsc() {
    }

    /** 把字符串按 JSON 规则转义并加上引号。 */
    static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
