package top.wcpe.mc.testkit.evidence;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 证据编译器：把动作总线 JSONL + 证据产物编译成一份**自包含 HTML 报告**。
 *
 * <h2>报告形态（用户拍板规格）</h2>
 *
 * <ul>
 *   <li>操作概要：步骤列表（树），每步状态与耗时；</li>
 *   <li>视频播放器：嵌入录像（base64 data URL），不依赖外部文件；</li>
 *   <li>canvas 时间轴：拖动平移、滚轮缩放、点击跳转到对应播放时刻，
 *       步骤标记是 HTML/canvas 覆盖层——**绝不烧进视频画面**；</li>
 *   <li>每步断言数据面板：点步骤看该步全部断言与自由数据；</li>
 *   <li>截图面板：各步骤截图缩略图。</li>
 * </ul>
 *
 * <h2>为什么是编译器而不是在线生成</h2>
 *
 * 测试进程崩了报告也要出得来：编译在测试结束后从 JSONL 重建，
 * 任何已落盘的事件前缀都能编译出对应进度的报告。JSONL 是唯一真源，
 * HTML 是它的一种视图——这保证证据链永远可重编译、可审计。
 *
 * <h2>CLI</h2>
 *
 * <pre>
 * java -cp evidence-core.jar top.wcpe.mc.testkit.evidence.EvidenceCompiler \
 *   --bus steps.jsonl --artifacts evid/ --out report.html [--video-long full.mp4]
 * </pre>
 */
public final class EvidenceCompiler {

    private EvidenceCompiler() {
    }

    /** 解析后的一条总线事件（字段全 nullable，编译器对残缺前缀宽容）。 */
    static final class Event {
        String type;
        long ts;
        int seq;
        String step;
        String status;
        String raw;

        /**
         * 定位字段值的起始下标：匹配 `"key"` 后跳过冒号与任意空白。
         *
         * <p>为什么不写死 `"key":` 紧挨形态：JSONL 的生产者不止本仓库的 Java writer——
         * bot 侧 JSON.stringify 输出紧凑形，但 python json.dumps、人工拼排查样本
         * 都会带空格。总线是跨语言契约，解析器必须容忍合法 JSON 的全部空白形态，
         * 否则「换种语言写 producer 就静默解析零事件」会成为最难查的哑弹。
         */
        int valueStart(String key) {
            String needle = "\"" + key + "\"";
            // 键名必须带冒号才是真键——否则 `"type":"step"` 里的值 step 会被
            // 当成 `"step"` 键命中（子串匹配的经典陷阱），字段抽取静默全空。
            int i = raw.indexOf(needle);
            while (i >= 0) {
                int j = i + needle.length();
                while (j < raw.length() && raw.charAt(j) == ' ') {
                    j++;
                }
                if (j < raw.length() && raw.charAt(j) == ':') {
                    j++;
                    while (j < raw.length() && raw.charAt(j) == ' ') {
                        j++;
                    }
                    return j;
                }
                i = raw.indexOf(needle, i + 1);
            }
            return -1;
        }

        /** 宽松抽取：从原始 JSON 行里抓一个字符串字段。 */
        String str(String key) {
            int start = valueStart(key);
            if (start < 0 || start >= raw.length() || raw.charAt(start) != '"') {
                return null;
            }
            start++;
            StringBuilder sb = new StringBuilder();
            for (int j = start; j < raw.length(); j++) {
                char c = raw.charAt(j);
                if (c == '\\' && j + 1 < raw.length()) {
                    char n = raw.charAt(j + 1);
                    switch (n) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        default:
                            sb.append(n);
                    }
                    j++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /** 宽松抽取数字字段。 */
        Long num(String key) {
            int start = valueStart(key);
            if (start < 0) {
                return null;
            }
            int end = start;
            while (end < raw.length() && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '-')) {
                end++;
            }
            if (end == start) {
                return null;
            }
            try {
                return Long.parseLong(raw.substring(start, end));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** 宽松抽取布尔字段。 */
        Boolean bool(String key) {
            int start = valueStart(key);
            if (start < 0) {
                return null;
            }
            if (raw.startsWith("true", start)) {
                return Boolean.TRUE;
            }
            if (raw.startsWith("false", start)) {
                return Boolean.FALSE;
            }
            return null;
        }
    }

    /** 读 JSONL，跳过非法行（崩溃残缺尾巴），按 ts 排序。 */
    static List<Event> parseBus(File busFile) throws IOException {
        List<Event> events = new ArrayList<>();
        for (String line : Files.readAllLines(busFile.toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                continue;
            }
            Event e = new Event();
            e.raw = trimmed;
            e.type = e.str("type");
            Long ts = e.num("ts");
            e.ts = ts == null ? 0L : ts;
            Long seq = e.num("seq");
            e.seq = seq == null ? 0 : seq.intValue();
            e.step = e.str("step");
            e.status = e.str("status");
            if (e.type != null) {
                events.add(e);
            }
        }
        Collections.sort(events, Comparator.comparingLong(ev -> ev.ts));
        return events;
    }

    /** 编译入口。 */
    public static void main(String[] args) throws Exception {
        File bus = null;
        File artifacts = null;
        File out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bus":
                    bus = new File(args[++i]);
                    break;
                case "--artifacts":
                    artifacts = new File(args[++i]);
                    break;
                case "--out":
                    out = new File(args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }
        if (bus == null || out == null) {
            System.err.println("用法：EvidenceCompiler --bus steps.jsonl [--artifacts 目录] --out report.html");
            System.exit(2);
            return;
        }
        if (!bus.isFile()) {
            System.err.println("动作总线文件不存在：" + bus + "——无证据可编译");
            System.exit(2);
            return;
        }
        List<Event> events = parseBus(bus);
        String html = compile(events, artifacts == null ? bus.getParentFile() : artifacts);
        Files.write(out.toPath(), html.getBytes(StandardCharsets.UTF_8));
        System.out.println("[mc-testkit][evidence] 报告已编译：" + out.getAbsolutePath()
                + "（事件 " + events.size() + " 条）");
    }

    /** 编译 HTML（全量内存拼装——单套件事件量与产物体积都有限）。 */
    static String compile(List<Event> events, File artifactsDir) {
        String suite = "";
        String scenario = "";
        long t0 = events.isEmpty() ? 0L : events.get(0).ts;
        long t1 = events.isEmpty() ? 0L : events.get(events.size() - 1).ts;
        List<Event> steps = new ArrayList<>();
        List<Event> assertions = new ArrayList<>();
        List<Event> artifacts = new ArrayList<>();
        Event suiteEnd = null;
        for (Event e : events) {
            if (suite.isEmpty()) {
                String s = e.str("suite");
                if (s != null) {
                    suite = s;
                }
            }
            if (scenario.isEmpty()) {
                String s = e.str("scenario");
                if (s != null) {
                    scenario = s;
                }
            }
            switch (e.type) {
                case "step":
                    steps.add(e);
                    break;
                case "assertion":
                    assertions.add(e);
                    break;
                case "artifact":
                    artifacts.add(e);
                    break;
                case "suite-end":
                    suiteEnd = e;
                    break;
                default:
                    break;
            }
        }

        // 内嵌产物：视频与截图 base64 进 HTML——自包含是硬规格。
        StringBuilder media = new StringBuilder();
        List<String> shotEntries = new ArrayList<>();
        String videoDataUrl = null;
        long videoStartTs = -1L;
        long videoEndTs = -1L;
        for (Event a : artifacts) {
            String path = a.str("path");
            String kind = a.str("artifactKind");
            if (path == null || kind == null) {
                continue;
            }
            File direct = new File(path);
            File f = direct.isAbsolute() ? direct : new File(artifactsDir, path);
            if (!f.isFile()) {
                continue;
            }
            if ("video".equals(kind)) {
                String b64 = base64(f);
                if (b64 != null) {
                    videoDataUrl = "data:video/mp4;base64," + b64;
                    Long vs = a.num("videoStart");
                    Long ve = a.num("videoEnd");
                    videoStartTs = vs == null ? t0 : vs;
                    videoEndTs = ve == null ? t1 : ve;
                }
            } else if ("shot".equals(kind)) {
                String b64 = base64(f);
                if (b64 != null) {
                    String mime = path.endsWith(".jpg") || path.endsWith(".jpeg") ? "image/jpeg" : "image/png";
                    shotEntries.add("<div class=\"shot\"><div class=\"shot-label\">"
                            + escHtml(a.step == null ? path : a.step) + " · " + escHtml(path)
                            + "</div><img src=\"data:" + mime + ";base64," + b64 + "\"/></div>");
                }
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"t0\":").append(t0).append(",\"t1\":").append(t1)
                .append(",\"videoStart\":").append(videoStartTs)
                .append(",\"videoEnd\":").append(videoEndTs)
                .append(",\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            Event s = steps.get(i);
            if (i > 0) {
                json.append(',');
            }
            long endTs = t1;
            for (Event later : steps) {
                if (later.ts > s.ts) {
                    endTs = later.ts;
                    break;
                }
            }
            json.append("{\"seq\":").append(s.seq)
                    .append(",\"name\":").append(quote(s.step))
                    .append(",\"ts\":").append(s.ts)
                    .append(",\"endTs\":").append(endTs)
                    .append(",\"status\":").append(quote(s.status == null ? "" : s.status))
                    .append(",\"assertions\":[");
            boolean first = true;
            for (Event a : assertions) {
                if (a.step != null && a.step.equals(s.step)) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    Boolean passed = a.bool("passed");
                    json.append("{\"ts\":").append(a.ts)
                            .append(",\"passed\":").append(passed == null ? "null" : passed)
                            .append(",\"message\":").append(quote(a.str("message")))
                            .append('}');
                }
            }
            json.append("]}");
        }
        json.append("]}");

        int passedCount = 0;
        int failedCount = 0;
        for (Event a : assertions) {
            if (Boolean.TRUE.equals(a.bool("passed"))) {
                passedCount++;
            } else if (Boolean.FALSE.equals(a.bool("passed"))) {
                failedCount++;
            }
        }
        boolean overallPass = suiteEnd != null && Boolean.TRUE.equals(suiteEnd.bool("passed"));
        String summary = suiteEnd == null ? "（套件未正常结束——报告来自残缺前缀）" : suiteEnd.str("message");

        String page = PAGE_TEMPLATE
                .replace("__TITLE__", escHtml(suite + " / " + scenario))
                .replace("__PASSED_COUNT__", String.valueOf(passedCount))
                .replace("__FAILED_COUNT__", String.valueOf(failedCount))
                .replace("__SUMMARY__", escHtml(summary == null ? "" : summary))
                .replace("__VIDEO_TAG__", videoDataUrl == null
                        ? "<div class=\"novideo\">本套件无视频证据（视觉依赖降级或车道不提供画面）——断言数据不受影响</div>"
                        : "<video id=\"vid\" preload=\"auto\" src=\"" + videoDataUrl + "\"></video>")
                .replace("__DATA_JSON__", json.toString());
        return page;
    }


    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p);
        }
        return sb.toString();
    }

    private static String base64(File f) {
        try {
            byte[] buf;
            try (InputStream in = Files.newInputStream(f.toPath())) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] tmp = new byte[65536];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    bos.write(tmp, 0, n);
                }
                buf = bos.toByteArray();
            }
            return Base64.getEncoder().encodeToString(buf);
        } catch (IOException e) {
            return null;
        }
    }

    private static String quote(String s) {
        return JsonEsc.quote(s);
    }

    private static String escHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * 报告模板（v2 版式）：左右分栏桌面布局，对齐 Devin e2e 验收报告参考——
     * 左侧视频+步骤着色分段时间轴（拖动播放头/点击跳转/Ctrl+click 循环当前步骤段）、
     * 右侧步骤树（步骤标题+passed 计数徽章+逐条断言绿勾）与总览计数及诚实总结。
     * 全部交互 vanilla JS 零外部依赖（自包含硬规格）。
     */
    private static final String PAGE_TEMPLATE = "<!DOCTYPE html>\n"
            + "<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>\n"
            + "<title>__TITLE__</title>\n"
            + "<style>\n"
            + "*{box-sizing:border-box}\n"
            + "body{font-family:-apple-system,'Segoe UI','Noto Sans CJK SC',sans-serif;margin:0;background:#f3f4f6;color:#1f2328}\n"
            + "header{padding:14px 22px;background:#fff;border-bottom:1px solid #e2e5e9;display:flex;align-items:center}\n"
            + "h1{font-size:17px;margin:0;font-weight:600}\n"
            + "main{display:flex;gap:0;height:calc(100vh - 54px)}\n"
            + ".leftcol{flex:1 1 62%;background:#fff;display:flex;flex-direction:column;border-right:1px solid #e2e5e9;min-width:0}\n"
            + ".rightcol{flex:0 0 38%;max-width:520px;background:#fff;overflow-y:auto;padding:16px 22px}\n"
            + ".video-wrap{flex:1 1 auto;background:#000;position:relative;min-height:0;display:flex;align-items:center;justify-content:center}\n"
            + "#vid{width:100%;height:100%;object-fit:contain;background:#000}\n"
            + ".step-overlay{position:absolute;left:0;right:0;bottom:0;background:#fff;color:#111827;padding:10px 16px;font-size:14px;display:flex;align-items:baseline;gap:14px;border-top:1px solid #e5e7eb}\n"
            + ".step-overlay .so-title{font-weight:600;font-size:15px;color:#111827;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n"
            + ".step-overlay .so-range{color:#6b7280;font-size:12.5px;font-variant-numeric:tabular-nums}\n"
            + ".step-overlay .so-hint{margin-left:auto;color:#9ca3af;font-size:12px}\n"
            + ".tl-zone{padding:10px 16px 4px;background:#fff}\n"
            + ".tl-track{position:relative;height:16px;background:#e5e7eb;border-radius:999px;cursor:pointer;overflow:hidden}\n"
            + ".tl-seg{position:absolute;top:0;bottom:0;border-right:2px solid #fff;border-radius:2px;cursor:pointer}\n"+ ".tl-seg:last-of-type{border-right:none}\n"
            + ".tl-seg.fail{background:#ef4444!important}.tl-seg.unknown{background:#9ca3af}\n"+ ".tl-seg.h0{background:#22c55e}.tl-seg.h1{background:#0ea5e9}.tl-seg.h2{background:#8b5cf6}.tl-seg.h3{background:#f59e0b}.tl-seg.h4{background:#14b8a6}.tl-seg.h5{background:#ec4899}\n"
            + ".tl-seg.active{outline:2px solid #1f2328;outline-offset:-2px;z-index:2}\n"
            + ".tl-head{position:absolute;top:-3px;bottom:-3px;width:12px;margin-left:-6px;background:#fff;border:2px solid #374151;border-radius:50%;z-index:3;pointer-events:none;box-shadow:0 1px 2px rgba(0,0,0,.2)}\n"
            + ".tl-scale{display:flex;justify-content:space-between;font-size:11px;color:#6b7280;padding-top:3px;font-variant-numeric:tabular-nums}\n"
            + ".controls{display:flex;align-items:center;gap:10px;padding:8px 16px 12px;background:#fff;font-size:13px;flex-wrap:nowrap;white-space:nowrap;overflow:hidden}\n"
            + ".controls button{background:none;border:none;cursor:pointer;font-size:15px;color:#374151;padding:2px 6px;flex:0 0 auto}\n"
            + ".controls button:hover{color:#111}\n"
            + ".ctrl-time{font-variant-numeric:tabular-nums;color:#374151;white-space:nowrap;flex:0 0 auto}\n"
            + ".ctrl-step{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#6b7280;font-size:12px;flex:1 1 auto;min-width:0}\n"
            + ".ctrl-right{margin-left:auto;display:flex;gap:8px;align-items:center;color:#6b7280;font-size:12px;flex:0 0 auto;white-space:nowrap}\n"
            + ".ovr{display:flex;gap:16px;align-items:center;font-size:15px;margin-bottom:10px}\n"
            + ".ovr .p{color:#16a34a;font-weight:600}.ovr .f{color:#dc2626;font-weight:600}\n"
            + ".dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:5px}\n"
            + ".dot.g{background:#22c55e}.dot.r{background:#ef4444}\n"
            + ".summary{font-size:13.5px;line-height:1.65;color:#374151;margin-bottom:16px;white-space:pre-wrap}\n"
            + ".step{border-top:1px solid #e5e7eb;padding:10px 0 6px}\n"
            + ".step-head{display:flex;align-items:baseline;gap:8px;cursor:pointer}\n"
            + ".step-head .pen{color:#6b7280;font-size:13px}\n"
            + ".step-title{font-weight:500;font-size:15px;color:#111827;line-height:1.45;flex:1 1 auto}\n"
            + ".step-badge{font-size:12px;color:#6b7280;font-variant-numeric:tabular-nums}\n"
            + ".step-subcount{display:none;color:#6b7280;font-size:13px;margin:2px 0 4px 22px}.step.open .step-subcount{display:block}\n"
            + ".step-badge.ok{color:#16a34a}.step-badge.bad{color:#dc2626}\n"
            + ".asserts{margin:6px 0 4px 22px;display:none}\n"
            + ".step.open .asserts{display:block}\n"
            + ".assert{display:flex;gap:8px;font-size:13.5px;line-height:1.55;margin:7px 0 7px 4px;color:#1f2937;padding-left:18px;border-left:2px solid #e5e7eb;margin-left:14px}\n"
            + ".assert .ck{width:18px;height:18px;border-radius:50%;color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center;flex:0 0 auto;margin-top:1px}.assert .ck.pass{background:#22c55e}.assert .ck.fail{background:#ef4444}\n"
            + ".assert.fail .ck{color:#dc2626}\n"
            + ".novideo{color:#6b7280;padding:40px;text-align:center;font-size:13px}\n"+ ".video-wrap:fullscreen{background:#000}\n"+ ".video-wrap:fullscreen #vid{object-fit:contain}\n"+ ".video-wrap:fullscreen .step-overlay{position:absolute}\n"
            + "</style></head><body>\n"
            + "<header><h1>mc-testkit 验收证据报告 · __TITLE__</h1></header>\n"
            + "<main>\n"
            + "<div class=\"leftcol\">\n"
            + "<div class=\"video-wrap\">__VIDEO_TAG__\n"
            + "<div class=\"step-overlay\" id=\"sov\" style=\"display:none\">\n"
            + "<span class=\"so-title\" id=\"so-title\"></span><span class=\"so-range\" id=\"so-range\"></span>\n"
            + "<span class=\"so-hint\"><kbd>Ctrl</kbd> click to loop</span></div></div>\n"
            + "<div class=\"tl-zone\">\n"
            + "<div class=\"tl-track\" id=\"tl\"></div>\n"
            + "<div class=\"tl-scale\" id=\"tlscale\"></div></div>\n"
            + "<div class=\"controls\">\n"
            + "<button id=\"b-prev\" title=\"上一断言/步骤\"><svg width=\"12\" height=\"12\" viewBox=\"0 0 12 12\"><path d=\"M2 2v8h1.5V2zM10 2L4.5 6 10 10z\" fill=\"currentColor\"/></svg></button>\n"
            + "<button id=\"b-play\" title=\"播放/暂停\"><svg width=\"12\" height=\"12\" viewBox=\"0 0 12 12\"><path d=\"M3 2l7 4-7 4z\" fill=\"currentColor\"/></svg></button>\n"
            + "<button id=\"b-next\" title=\"下一步骤\"><svg width=\"12\" height=\"12\" viewBox=\"0 0 12 12\"><path d=\"M8.5 2v8H10V2zM2 2l5.5 4L2 10z\" fill=\"currentColor\"/></svg></button>\n"
            + "<span class=\"ctrl-time\" id=\"ctime\">0:00 / 0:00</span>\n"
            + "<span class=\"ctrl-step\" id=\"cstep\"></span>\n"
            + "<span class=\"ctrl-right\"><span id=\"b-loop\" title=\"循环当前步骤\" style=\"cursor:pointer;display:inline-flex\"><svg width=\"13\" height=\"13\" viewBox=\"0 0 13 13\"><path d=\"M6.5 1a5.5 5.5 0 1 1-5.3 4l1.4.4A4.1 4.1 0 1 0 6.5 2.4V4l3-2.2-3-2.2v1.4z\" fill=\"currentColor\"/></svg></span><span>1x</span><span id=\"b-fs\" title=\"全屏观看\" style=\"cursor:pointer;display:inline-flex\"><svg width=\"13\" height=\"13\" viewBox=\"0 0 13 13\"><path d=\"M1 1h4v1.5H2.5V5H1zM9 1h4v4h-1.5V2.5H9zM1 9h1.5v2.5H5V13H1zM11.5 9H13v4h-4v-1.5h2.5z\" fill=\"currentColor\"/></svg></span></span>\n"
            + "</div></div>\n"
            + "<div class=\"rightcol\">\n"
            + "<div class=\"ovr\"><span class=\"p\"><span class=\"dot g\"></span>__PASSED_COUNT__ passed</span><span class=\"f\"><span class=\"dot r\"></span>__FAILED_COUNT__ failed</span></div>\n"
            + "<div class=\"summary\">__SUMMARY__</div>\n"
            + "<div id=\"steps\"></div>\n"
            + "</div>\n"
            + "</main>\n"
            + "<script>\n"
            + "var DATA=__DATA_JSON__;\n"
            + "var vid=document.getElementById('vid');\n"
            + "var dur=(DATA.videoEnd-DATA.videoStart)/1000;\n"
            + "if(!vid||!(dur>0)){dur=(DATA.t1-DATA.t0)/1000}\n"
            + "function ts2v(ts){return Math.max(0,(ts-DATA.videoStart)/1000)}\n"
            + "function fmt(sec){sec=Math.max(0,sec);var m=Math.floor(sec/60),s=Math.floor(sec%60);return m+':'+(s<10?'0':'')+s}\n"
            + "var steps=DATA.steps;\n"
            + "var cur=-1,loopTs0=-1,loopTs1=-1;\n"
            + "// 右侧步骤树\n"
            + "var box=document.getElementById('steps');\n"
            + "steps.forEach(function(s,i){\n"
            + "  var passed=0,total=0;\n"
            + "  s.assertions.forEach(function(a){total++;if(a.passed)passed++});\n"
            + "  var div=document.createElement('div');div.className='step';\n"
            + "  var badgeCls=(total>0&&passed===total)?'ok':(passed<total?'bad':'');\n"
            + "  div.innerHTML='<div class=\"step-head\"><span class=\"pen\"><svg width=\"11\" height=\"11\" viewBox=\"0 0 12 12\"><path d=\"M8.5 1l2.5 2.5L4 10H1.5V7.5z\" fill=\"currentColor\"/></svg></span><span class=\"step-title\">'+esc(s.name)+'</span><span class=\"step-badge '+badgeCls+'\">'+passed+'/'+total+' passed</span></div><div class=\"step-subcount\">'+passed+'/'+total+' passed</div>';\n"
            + "  var al=document.createElement('div');al.className='asserts';\n"
            + "  s.assertions.forEach(function(a){\n"
            + "    var d=document.createElement('div');d.className='assert'+(a.passed?'':' fail');\n"
            + "    d.innerHTML='<span class=\"ck '+(a.passed?'pass':'fail')+'\">'+(a.passed?'✓':'✗')+'</span><span>'+esc(a.message||'')+'</span>';\n"
            + "    al.appendChild(d);\n"
            + "  });\n"
            + "  div.appendChild(al);\n"
            + "  if(i===0){div.classList.add('open');}\n"
            + "  div.querySelector('.step-head').addEventListener('click',function(){\n"
            + "    div.classList.toggle('open');\n"
            + "    jumpStep(i);\n"
            + "  });\n"
            + "  box.appendChild(div);\n"
            + "});\n"
            + "function esc(t){var d=document.createElement('div');d.textContent=t==null?'':t;return d.innerHTML}\n"
            + "// 时间轴\n"
            + "var tl=document.getElementById('tl');\n"
            + "var scale=document.getElementById('tlscale');\n"
            + "steps.forEach(function(s,i){\n"
            + "  var l=ts2v(s.ts)/dur*100,r=ts2v(s.endTs)/dur*100;\n"
            + "  var seg=document.createElement('div');\n"
            + "  var st=s.status==='pass'?'pass':(s.status==='fail'?'fail':'unknown');\n"
            + "  seg.className='tl-seg '+st+(st==='pass'?' h'+(i%6):'');seg.id='seg'+i;\n"
            + "  seg.style.left=l+'%';seg.style.width=Math.max(0.8,r-l)+'%';\n"
            + "  seg.title=s.name;\n"
            + "  tl.appendChild(seg);\n"
            + "});\n"
            + "var head=document.createElement('div');head.className='tl-head';tl.appendChild(head);\n"
            + "var ticks=6,sc='';\n"
            + "for(var i=0;i<=ticks;i++){sc+='<span>'+fmt(dur*i/ticks)+'</span>'}\n"
            + "scale.innerHTML=sc;\n"
            + "function stepIdxAt(vt){\n"
            + "  for(var i=steps.length-1;i>=0;i--){if(vt>=ts2v(steps[i].ts)-0.001)return i}\n"
            + "  return 0;\n"
            + "}\n"
            + "function refresh(){\n"
            + "  if(!vid)return;\n"
            + "  var vt=vid.currentTime;\n"
            + "  var idx=stepIdxAt(vt);\n"
            + "  if(idx!==cur){\n"
            + "    cur=idx;\n"
            + "    steps.forEach(function(s,i){var el=document.getElementById('seg'+i);if(el)el.classList.toggle('active',i===cur)});\n"
            + "    var s=steps[cur];\n"
            + "    document.getElementById('so-title').textContent=s.name;\n"
            + "    document.getElementById('so-range').textContent=fmt(ts2v(s.ts))+' – '+fmt(ts2v(s.endTs));\n"
            + "    document.getElementById('cstep').textContent=s.name;\n"
            + "    document.getElementById('sov').style.display='flex';\n"
            + "  }\n"
            + "  head.style.left=(vt/dur*100)+'%';\n"
            + "  document.getElementById('ctime').textContent=fmt(vt)+' / '+fmt(dur);\n"
            + "  if(loopTs0>=0&&vt>=loopTs1){vid.currentTime=loopTs0}\n"
            + "}\n"
            + "function jumpStep(i){\n"
            + "  if(!vid)return;\n"
            + "  vid.currentTime=ts2v(steps[i].ts)+0.01;vid.play();\n"
            + "  cur=i;refresh();\n"
            + "}\n"
            + "if(vid){\n"
            + "  vid.addEventListener('timeupdate',refresh);\n"
            + "  vid.addEventListener('loadedmetadata',refresh);\n"
            + "}\n"
            + "document.getElementById('b-play').addEventListener('click',function(){\n"
            + "  if(!vid)return;vid.paused?vid.play():vid.pause()});\n"
            + "document.getElementById('b-next').addEventListener('click',function(){jumpStep(Math.min(steps.length-1,cur+1))});\n"
            + "document.getElementById('b-prev').addEventListener('click',function(){jumpStep(Math.max(0,cur-1))});\n"
            + "document.getElementById('b-fs').addEventListener('click',function(){\n"
+ "  var vw=document.querySelector('.video-wrap');\n"
+ "  if(document.fullscreenElement){document.exitFullscreen()}else if(vw&&vw.requestFullscreen){vw.requestFullscreen()}\n"
+ "});\n"
+ "if(vid){vid.addEventListener('dblclick',function(){\n"
+ "  var vw=document.querySelector('.video-wrap');\n"
+ "  if(document.fullscreenElement){document.exitFullscreen()}else if(vw&&vw.requestFullscreen){vw.requestFullscreen()}\n"
+ "})}\n"
+ "document.getElementById('b-loop').addEventListener('click',function(){\n"
            + "  if(cur<0)return;loopTs0=ts2v(steps[cur].ts);loopTs1=ts2v(steps[cur].endTs)});\n"
            + "// 时间轴交互：点击跳转、拖动播放头、ctrl+click 设循环段\n"
            + "var dragging=false;\n"
            + "function seekAt(e){\n"
            + "  var r=tl.getBoundingClientRect();\n"
            + "  var frac=Math.min(1,Math.max(0,(e.clientX-r.left)/r.width));\n"
            + "  if(vid){vid.currentTime=frac*dur}\n"
            + "}\n"
            + "tl.addEventListener('click',function(e){\n"
            + "  if(e.ctrlKey||e.metaKey){\n"
            + "    var r=tl.getBoundingClientRect();\n"
            + "    var frac=(e.clientX-r.left)/r.width;\n"
            + "    var t=frac*dur;\n"
            + "    var idx=stepIdxAt(t);\n"
            + "    loopTs0=ts2v(steps[idx].ts);loopTs1=ts2v(steps[idx].endTs);\n"
            + "    jumpStep(idx);\n"
            + "    return;\n"
            + "  }\n"
            + "  seekAt(e);\n"
            + "});\n"
            + "tl.addEventListener('mousedown',function(e){dragging=true;seekAt(e)});\n"
            + "window.addEventListener('mouseup',function(){dragging=false});\n"
            + "window.addEventListener('mousemove',function(e){if(dragging)seekAt(e)});\n"
            + "refresh();\n"
            + "</script></body></html>\n"
            + "\n";
}
