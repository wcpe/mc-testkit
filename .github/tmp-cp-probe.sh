#!/usr/bin/env bash
# 临时诊断脚本：定位 thin jar（清单 Class-Path）在 Windows 下的解析失败原因。
# Git Bash 会把含中文的参数转成 '?'，故中文路径一律由 Java 侧派生；脚本只用 ASCII 参数。
set -u
root="$PWD/.tmp/probe-work"
mkdir -p "$root/classes"

cat > "$root/Main.java" <<'JAVA'
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("      base=" + esc(String.valueOf(
            Main.class.getProtectionDomain().getCodeSource().getLocation())));
        System.out.println("      cp=" + esc(System.getProperty("java.class.path")));
        System.out.println("      dir=" + esc(System.getProperty("user.dir")));
        System.out.println("      jnu=" + System.getProperty("sun.jnu.encoding")
            + " native=" + System.getProperty("native.encoding"));
        try {
            Class.forName("Probe");
            System.out.println("      probe=OK");
        } catch (Throwable t) {
            System.out.println("      probe=FAIL " + t);
        }
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : c);
        }
        return sb.toString();
    }
}
JAVA

echo 'public class Probe { }' > "$root/Probe.java"

cat > "$root/Driver.java" <<'JAVA'
import java.io.*;
import java.nio.file.Files;
import java.util.jar.*;

public class Driver {
    static final String CN = "\u7a7a\u683c \u4e2d\u6587";
    static final String CN_NAME = "probe \u526f\u672c.jar";
    static File classes;

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("user.dir"));
        classes = new File(root, "classes");
        File javaBin = new File(System.getProperty("java.home"), "bin");
        String javaExe = new File(javaBin, "java.exe").isFile()
            ? new File(javaBin, "java.exe").getAbsolutePath()
            : new File(javaBin, "java").getAbsolutePath();
        System.out.println("root=" + esc(root.getAbsolutePath()));
        System.out.println("java=" + esc(javaExe));
        System.out.println("jnu=" + System.getProperty("sun.jnu.encoding")
            + " native=" + System.getProperty("native.encoding")
            + " file=" + System.getProperty("file.encoding"));

        run(root, javaExe, "1-manifest-escaped", "caseA " + CN, CN_NAME,
            "libs/probe%20%E5%89%AF%E6%9C%AC.jar", false);
        run(root, javaExe, "2-argv-classpath", "caseB " + CN, CN_NAME, null, true);
        run(root, javaExe, "3-manifest-raw-cn", "caseC " + CN, CN_NAME,
            "libs/probe%20\u526f\u672c.jar", false);
        run(root, javaExe, "4-manifest-abs-uri", "caseD " + CN, CN_NAME, "AUTO-URI", false);
        run(root, javaExe, "5-space-only", "caseE " + CN, "probe copy.jar",
            "libs/probe%20copy.jar", false);
        run(root, javaExe, "6-ascii-dir", "caseF-ascii", CN_NAME,
            "libs/probe%20%E5%89%AF%E6%9C%AC.jar", false);
        run(root, javaExe, "7-ascii-all", "caseG-ascii", "probe.jar", "libs/probe.jar", false);
    }

    static void run(File root, String javaExe, String label, String dirName, String probeName,
                    String cpValue, boolean viaArgv) throws Exception {
        File dir = new File(root, dirName);
        File libs = new File(dir, "libs");
        libs.mkdirs();
        File probeJar = new File(libs, probeName);
        writeJar(probeJar, "Probe.class", null, null);
        String classPath = cpValue;
        if ("AUTO-URI".equals(cpValue)) {
            classPath = probeJar.toURI().toASCIIString();
        }
        writeJar(new File(dir, "main.jar"), "Main.class", "Main", viaArgv ? null : classPath);

        System.out.println("==================== " + label);
        System.out.println("  dir=" + esc(dir.getAbsolutePath()));
        System.out.println("  probe=" + esc(probeJar.getName()) + " exists=" + probeJar.isFile());
        System.out.println("  classpath=" + esc(classPath == null ? "<argv>" : classPath));

        ProcessBuilder pb = viaArgv
            ? new ProcessBuilder(javaExe, "-cp",
                "main.jar" + File.pathSeparator + probeJar.getAbsolutePath(), "Main")
            : new ProcessBuilder(javaExe, "-cp", "main.jar", "Main");
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

    static void writeJar(File target, String entry, String mainClass, String classPath)
            throws IOException {
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
            jos.putNextEntry(new JarEntry(entry));
            jos.write(Files.readAllBytes(new File(classes, entry).toPath()));
            jos.closeEntry();
        }
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : c);
        }
        return sb.toString();
    }
}
JAVA

( cd "$root" && javac -d classes Main.java Probe.java Driver.java ) || exit 1
( cd "$root" && java -cp classes Driver )
