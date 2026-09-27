#!/usr/bin/env bash
# 临时诊断脚本（第二轮）：Windows 下 argv 非 ASCII 编码丢失的确切边界。
# 中文路径一律由 Java 侧派生，脚本只用 ASCII 参数。
set -u
root="$PWD/.tmp/probe-work2"
mkdir -p "$root/classes"

cat > "$root/Main.java" <<'JAVA'
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("      dir=" + Diag.esc(System.getProperty("user.dir")));
        System.out.println("      cp=" + Diag.esc(System.getProperty("java.class.path")));
        System.out.println("      prop=" + Diag.esc(String.valueOf(System.getProperty("probe.prop"))));
        System.out.println("      arg0=" + Diag.esc(args.length > 0 ? args[0] : "<none>"));
        Diag.rest();
    }
}
JAVA

cat > "$root/Lib.java" <<'JAVA'
public class Lib {
    public static void main(String[] args) throws Exception {
        System.out.println("      dir=" + Diag.esc(System.getProperty("user.dir")));
        System.out.println("      cp=" + Diag.esc(System.getProperty("java.class.path")));
        System.out.println("      prop=" + Diag.esc(String.valueOf(System.getProperty("probe.prop"))));
        System.out.println("      arg0=" + Diag.esc(args.length > 0 ? args[0] : "<none>"));
        Diag.rest();
    }
}
JAVA

cat > "$root/Diag.java" <<'JAVA'
public class Diag {
    public static void rest() {
        System.out.println("      jnu=" + System.getProperty("sun.jnu.encoding")
            + " native=" + System.getProperty("native.encoding"));
        try {
            Class.forName("Marker");
            System.out.println("      marker=OK");
        } catch (Throwable t) {
            System.out.println("      marker=FAIL " + t);
        }
    }

    public static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : c);
        }
        return sb.toString();
    }
}
JAVA

echo 'public class Marker { }' > "$root/Marker.java"

cat > "$root/Driver.java" <<'JAVA'
import java.io.*;
import java.nio.file.Files;
import java.util.jar.*;

public class Driver {
    static final String CN = "\u7a7a\u683c \u4e2d\u6587";
    static final String CN_NAME = "probe \u526f\u672c.jar";
    static final String LIB_ENTRY = "libs/probe%20%E5%89%AF%E6%9C%AC.jar";
    static File classes;
    static String javaExe;

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("user.dir"));
        classes = new File(root, "classes");
        File javaBin = new File(System.getProperty("java.home"), "bin");
        javaExe = new File(javaBin, "java.exe").isFile()
            ? new File(javaBin, "java.exe").getAbsolutePath()
            : new File(javaBin, "java").getAbsolutePath();
        System.out.println("root=" + Diag.esc(root.getAbsolutePath()));
        System.out.println("jnu=" + System.getProperty("sun.jnu.encoding"));

        File dir = new File(root, "case " + CN);
        File libs = new File(dir, "libs");
        libs.mkdirs();
        File probeJar = new File(libs, CN_NAME);
        writeJar(probeJar, "probe \u526f\u672c.jar", new String[] {"Lib.class", "Marker.class", "Diag.class"},
            null, null);
        File launcher = new File(dir, "launcher.jar");
        writeJar(launcher, "launcher.jar", new String[] {"Main.class", "Diag.class"}, null, LIB_ENTRY);
        File self = new File(dir, "self.jar");
        writeJar(self, "self.jar", new String[] {"Main.class", "Diag.class"}, "Main", null);
        System.out.println("dir=" + Diag.esc(dir.getAbsolutePath()));
        System.out.println("probe-exists=" + probeJar.isFile() + " launcher-exists=" + launcher.isFile());

        exec(dir, "A-绝对 -cp 启动器（当前产品形态）", javaExe,
            "-cp", launcher.getAbsolutePath(), "Lib");
        exec(dir, "B-相对 -cp 启动器", javaExe, "-cp", "launcher.jar", "Lib");
        exec(dir, "C-绝对 -jar", javaExe, "-jar", self.getAbsolutePath());
        exec(dir, "D-相对 -jar", javaExe, "-jar", "self.jar");
        exec(dir, "E-非 ASCII 属性与参数", javaExe, "-Dprobe.prop=" + CN, "-cp", "self.jar",
            "Main", CN);
        exec(dir, "F-中文工作目录下的 -cp 相对路径解析", javaExe, "-cp", "self.jar", "Main");
    }

    static void exec(File dir, String label, String... command) throws Exception {
        System.out.println("==================== " + label);
        System.out.println("  cmd=" + Diag.esc(String.join(" ", command)));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process child = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append("      ").append(line).append('\n');
            }
        }
        int code = child.waitFor();
        System.out.print(out);
        System.out.println("  exit=" + code);
    }

    static void writeJar(File target, String label, String[] entries, String mainClass,
                         String classPath) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        if (classPath != null) {
            mf.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        }
        boolean hasManifest = mainClass != null || classPath != null;
        try (JarOutputStream jos = hasManifest
                ? new JarOutputStream(new FileOutputStream(target), mf)
                : new JarOutputStream(new FileOutputStream(target))) {
            for (String entry : entries) {
                jos.putNextEntry(new JarEntry(entry));
                jos.write(Files.readAllBytes(new File(classes, entry).toPath()));
                jos.closeEntry();
            }
        }
    }
}
JAVA

( cd "$root" && javac -d classes Main.java Lib.java Diag.java Marker.java Driver.java ) || exit 1
( cd "$root" && java -cp classes Driver )
