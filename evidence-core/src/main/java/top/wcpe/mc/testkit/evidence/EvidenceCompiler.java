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
            File f = new File(artifactsDir, path);
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
            json.append("{\"seq\":").append(s.seq)
                    .append(",\"name\":").append(quote(s.step))
                    .append(",\"ts\":").append(s.ts)
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

        boolean overallPass = suiteEnd != null && Boolean.TRUE.equals(suiteEnd.bool("passed"));
        String summary = suiteEnd == null ? "（套件未正常结束——报告来自残缺前缀）" : suiteEnd.str("message");

        String page = PAGE_TEMPLATE
                .replace("__TITLE__", escHtml(suite + " / " + scenario))
                .replace("__VERDICT_CLASS__", suiteEnd == null ? "unknown" : (overallPass ? "pass" : "fail"))
                .replace("__VERDICT__", escHtml(suiteEnd == null ? "UNKNOWN" : (overallPass ? "PASS" : "FAIL")))
                .replace("__SUMMARY__", escHtml(summary == null ? "" : summary))
                .replace("__SUITE__", escHtml(suite))
                .replace("__SCENARIO__", escHtml(scenario))
                .replace("__WINDOW__", t0 + " → " + t1 + "（" + (t1 - t0) + "ms）")
                .replace("__VIDEO_TAG__", videoDataUrl == null
                        ? "<div class=\"novideo\">本套件无视频证据（视觉依赖降级或车道不提供画面）——断言数据不受影响</div>"
                        : "<video id=\"vid\" controls preload=\"auto\" src=\"" + videoDataUrl + "\"></video>")
                .replace("__STEPS_HTML__", stepsHtml(steps, assertions))
                .replace("__SHOTS_HTML__", shotEntries.isEmpty() ? "<div class=\"novideo\">无截图</div>" : join(shotEntries))
                .replace("__DATA_JSON__", json.toString());
        return page;
    }

    private static String stepsHtml(List<Event> steps, List<Event> assertions) {
        if (steps.isEmpty()) {
            return "<div class=\"novideo\">无步骤事件</div>";
        }
        StringBuilder sb = new StringBuilder();
        for (Event s : steps) {
            int count = 0;
            int failed = 0;
            for (Event a : assertions) {
                if (a.step != null && a.step.equals(s.step)) {
                    count++;
                    if (Boolean.FALSE.equals(a.bool("passed"))) {
                        failed++;
                    }
                }
            }
            String cls = failed > 0 ? "fail" : (count > 0 ? "pass" : "unknown");
            sb.append("<li class=\"step ").append(cls).append("\" data-seq=\"").append(s.seq)
                    .append("\" onclick=\"jumpStep(").append(s.ts).append(")\">")
                    .append("<span class=\"step-name\">#").append(s.seq).append(' ')
                    .append(escHtml(s.step == null ? "" : s.step)).append("</span>")
                    .append("<span class=\"step-meta\">断言 ").append(count)
                    .append(failed > 0 ? "（失败 " + failed + "）" : "")
                    .append("</span></li>");
        }
        return sb.toString();
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

    /** 报告模板：所有交互在 vanilla JS 里，零外部依赖（自包含硬规格）。 */
    private static final String PAGE_TEMPLATE = "<!DOCTYPE html>\n"
            + "<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>\n"
            + "<title>__TITLE__</title>\n"
            + "<style>\n"
            + "body{font-family:-apple-system,'Noto Sans CJK SC',sans-serif;margin:0;background:#14161a;color:#dde1e6}\n"
            + "header{padding:14px 20px;background:#1d2026;border-bottom:1px solid #2c313a}\n"
            + "h1{font-size:18px;margin:0}\n"
            + ".verdict{display:inline-block;padding:2px 12px;border-radius:10px;font-weight:700;margin-left:10px}\n"
            + ".verdict.pass{background:#1e4620;color:#7ee787}.verdict.fail{background:#5a1d1d;color:#ff8a80}.verdict.unknown{background:#4a3d15;color:#ffd76e}\n"
            + ".meta{color:#8b929c;font-size:12px;margin-top:6px}\n"
            + "main{display:flex;gap:14px;padding:14px 20px;flex-wrap:wrap}\n"
            + ".panel{background:#1d2026;border:1px solid #2c313a;border-radius:8px;padding:12px}\n"
            + "#vid{width:100%;max-width:860px;background:#000}\n"
            + ".videocol{flex:1 1 640px;min-width:480px}\n"
            + ".sidecol{flex:1 1 320px;min-width:280px}\n"
            + "canvas{width:100%;height:120px;background:#0c0e11;border-radius:6px;cursor:crosshair}\n"
            + "ul.steps{list-style:none;padding:0;margin:0;max-height:420px;overflow:auto}\n"
            + "li.step{padding:7px 10px;border-left:3px solid #555;margin:4px 0;cursor:pointer;background:#23262e;border-radius:4px}\n"
            + "li.step.pass{border-left-color:#3fb950}li.step.fail{border-left-color:#f85149}\n"
            + "li.step:hover{background:#2c313a}\n"
            + ".step-name{font-weight:600}.step-meta{float:right;color:#8b929c;font-size:12px}\n"
            + "#detail{white-space:pre-wrap;font-size:13px;max-height:300px;overflow:auto}\n"
            + ".novideo{color:#8b929c;padding:18px;text-align:center}\n"
            + ".shot{margin:8px 0}.shot img{max-width:100%;border-radius:6px}\n"
            + ".shot-label{font-size:12px;color:#8b929c;margin-bottom:4px}\n"
            + "h2{font-size:14px;margin:4px 0 10px}\n"
            + "</style></head><body>\n"
            + "<header><h1>mc-testkit 验收证据报告<span class=\"verdict __VERDICT_CLASS__\">__VERDICT__</span></h1>\n"
            + "<div class=\"meta\">套件 __SUITE__ / 场景 __SCENARIO__ · 窗口 __WINDOW__<br/>__SUMMARY__</div></header>\n"
            + "<main>\n"
            + "<div class=\"panel videocol\"><h2>录像与时间轴（拖动平移 · 滚轮缩放 · 点击跳转）</h2>\n"
            + "__VIDEO_TAG__\n"
            + "<canvas id=\"tl\" width=\"860\" height=\"120\"></canvas></div>\n"
            + "<div class=\"sidecol\"><div class=\"panel\"><h2>操作步骤</h2><ul class=\"steps\">__STEPS_HTML__</ul></div>\n"
            + "<div class=\"panel\" style=\"margin-top:14px\"><h2>断言明细（点步骤查看）</h2><div id=\"detail\">点击左侧步骤查看该步断言数据</div></div>\n"
            + "<div class=\"panel\" style=\"margin-top:14px\"><h2>截图面板</h2>__SHOTS_HTML__</div></div>\n"
            + "</main>\n"
            + "<script>\n"
            + "var DATA=__DATA_JSON__;\n"
            + "var vid=document.getElementById('vid');\n"
            + "var cv=document.getElementById('tl');var cx=cv.getContext('2d');\n"
            + "var viewStart=DATA.t0,viewEnd=Math.max(DATA.t1,DATA.t0+1);\n"
            + "function ts2x(ts){return (ts-viewStart)/(viewEnd-viewStart)*cv.width}\n"
            + "function x2ts(x){return viewStart+x/cv.width*(viewEnd-viewStart)}\n"
            + "function draw(){cx.clearRect(0,0,cv.width,cv.height);\n"
            + "cx.fillStyle='#0c0e11';cx.fillRect(0,0,cv.width,cv.height);\n"
            + "cx.strokeStyle='#2c313a';cx.fillStyle='#8b929c';cx.font='10px sans-serif';\n"
            + "var span=viewEnd-viewStart;var stepG=span/8;\n"
            + "for(var t=viewStart-viewStart%stepG;t<=viewEnd;t+=stepG){var x=ts2x(t);\n"
            + "cx.beginPath();cx.moveTo(x,100);cx.lineTo(x,120);cx.stroke();\n"
            + "cx.fillText(((t-DATA.t0)/1000).toFixed(1)+'s',x+2,112)}\n"
            + "if(vid&&DATA.videoStart>=0){var vt=DATA.videoStart+vid.currentTime*1000;var x=ts2x(vt);\n"
            + "cx.strokeStyle='#ffd76e';cx.beginPath();cx.moveTo(x,0);cx.lineTo(x,120);cx.stroke()}\n"
            + "DATA.steps.forEach(function(s){var x=ts2x(s.ts);if(x<-4||x>cv.width+4)return;\n"
            + "var c=s.status==='pass'?'#3fb950':(s.status==='fail'?'#f85149':'#58a6ff');\n"
            + "cx.fillStyle=c;cx.beginPath();cx.arc(x,50,6,0,7);cx.fill();\n"
            + "cx.fillStyle='#dde1e6';cx.fillText('#'+s.seq,x-6,70)});\n"
            + "cx.fillStyle='#8b929c';cx.fillText('点击圆点或步骤跳转 · 拖动平移 · 滚轮缩放',8,14)}\n"
            + "function jumpStep(ts){if(!vid||DATA.videoStart<0)return;\n"
            + "vid.currentTime=Math.max(0,(ts-DATA.videoStart)/1000);vid.play()}\n"
            + "cv.addEventListener('click',function(e){var r=cv.getBoundingClientRect();\n"
            + "var x=(e.clientX-r.left)*(cv.width/r.width);\n"
            + "var best=null,bd=1e9;DATA.steps.forEach(function(s){var sx=ts2x(s.ts);var d=Math.abs(sx-x);if(d<bd){bd=d;best=s}});\n"
            + "if(best&&bd<24){jumpStep(best.ts);showDetail(best)}else if(vid&&DATA.videoStart>=0){vid.currentTime=Math.max(0,(x2ts(x)-DATA.videoStart)/1000)}});\n"
            + "var dragX=-1;\n"
            + "cv.addEventListener('mousedown',function(e){dragX=e.clientX});\n"
            + "window.addEventListener('mouseup',function(){dragX=-1});\n"
            + "cv.addEventListener('mousemove',function(e){if(dragX<0)return;var dx=e.clientX-dragX;dragX=e.clientX;\n"
            + "var span=viewEnd-viewStart;var dt=-dx/cv.width*span;viewStart+=dt;viewEnd+=dt;draw()});\n"
            + "cv.addEventListener('wheel',function(e){e.preventDefault();\n"
            + "var r=cv.getBoundingClientRect();var fx=(e.clientX-r.left)/r.width;var center=x2ts(fx*cv.width);\n"
            + "var span=viewEnd-viewStart;var k=e.deltaY>0?1.25:0.8;var ns=Math.max(500,span*k);\n"
            + "viewStart=center-ns*fx;viewEnd=center+ns*(1-fx);draw()},{passive:false});\n"
            + "function showDetail(s){var el=document.getElementById('detail');var out='# '+s.seq+' '+s.name+'（'+s.status+'）\\n\\n';\n"
            + "if(!s.assertions.length){out+='本步无断言事件'}else{s.assertions.forEach(function(a){\n"
            + "out+=(a.passed?'✓':'✗')+' '+((a.ts-DATA.t0)/1000).toFixed(2)+'s '+(a.message||'')+'\\n'})}\n"
            + "el.textContent=out}\n"
            + "document.querySelectorAll('li.step').forEach(function(li){li.addEventListener('click',function(){\n"
            + "var seq=+li.getAttribute('data-seq');var s=null;DATA.steps.forEach(function(x){if(x.seq===seq)s=x});\n"
            + "if(s)showDetail(s)})});\n"
            + "if(vid){vid.addEventListener('timeupdate',draw)}\n"
            + "draw();\n"
            + "</script></body></html>\n";
}
