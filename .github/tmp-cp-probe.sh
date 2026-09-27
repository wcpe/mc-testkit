#!/usr/bin/env bash
# 临时诊断脚本：定位 thin jar（清单 Class-Path）在 Windows 下的解析失败原因。
# 所有传给 javac/java 的参数都用相对路径，避免 Git Bash 的 MSYS 路径转换干扰。
set -u
root="$PWD/.tmp/probe-work"
mkdir -p "$root/classes"

cat > "$root/Main.java" <<'JAVA'
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("    base=" + Main.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("    cp=" + System.getProperty("java.class.path"));
        System.out.println("    dir=" + System.getProperty("user.dir"));
        System.out.println("    jnu=" + System.getProperty("sun.jnu.encoding")
            + " native=" + System.getProperty("native.encoding")
            + " file=" + System.getProperty("file.encoding"));
        try {
            Class.forName("Probe");
            System.out.println("    probe=OK");
        } catch (Throwable t) {
            System.out.println("    probe=FAIL " + t);
        }
    }
}
JAVA

echo 'public class Probe { }' > "$root/Probe.java"

cat > "$root/BuildCase.java" <<'JAVA'
import java.io.*;
import java.nio.file.Files;
import java.util.jar.*;

public class BuildCase {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        File classes = new File(a[3]);
        File libs = new File(root, "libs");
        libs.mkdirs();
        File probeJar = new File(libs, a[1]);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(probeJar))) {
            jos.putNextEntry(new JarEntry("Probe.class"));
            jos.write(Files.readAllBytes(new File(classes, "Probe.class").toPath()));
            jos.closeEntry();
        }
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        if (!a[2].isEmpty()) {
            mf.getMainAttributes().put(Attributes.Name.CLASS_PATH, a[2]);
        }
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(new File(root, "main.jar")), mf)) {
            jos.putNextEntry(new JarEntry("Main.class"));
            jos.write(Files.readAllBytes(new File(classes, "Main.class").toPath()));
            jos.closeEntry();
        }
        Files.writeString(new File(root, "abs-uri.txt").toPath(), probeJar.toURI().toASCIIString());
        System.out.println("    abs-uri=" + probeJar.toURI().toASCIIString());
    }
}
JAVA

( cd "$root" && javac -d classes Main.java Probe.java BuildCase.java ) || exit 1

# 铺一个用例目录（$1 为用例目录名，相对 $root）
lay() {
  ( cd "$root" && java -cp classes BuildCase "$1" "$2" "$3" classes )
}

# 在用例目录里跑命令（$1 为用例目录名，$3 为命令）
run() {
  echo "---- $2"
  ( cd "$root/$1" && eval "$3"; echo "    exit=$?" )
}

sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac

echo "==================== ① 清单转义（当前实现）"
lay "caseA 空格 中文" "probe 副本.jar" "libs/probe%20%E5%89%AF%E6%9C%AC.jar"
run "caseA 空格 中文" "java -cp main.jar Main" "java -cp main.jar Main"

echo "==================== ② argv 直接给 classpath"
run "caseA 空格 中文" "java -cp 'main.jar${sep}libs/probe 副本.jar' Main" "java -cp 'main.jar${sep}libs/probe 副本.jar' Main"

echo "==================== ③ 清单原文中文（仅空格转义）"
lay "caseB 空格 中文" "probe 副本.jar" "libs/probe%20副本.jar"
run "caseB 空格 中文" "java -cp main.jar Main" "java -cp main.jar Main"

echo "==================== ④ 清单绝对 file URI"
lay "caseC 空格 中文" "probe 副本.jar" ""
absUri="$(cat "$root/caseC 空格 中文/abs-uri.txt")"
echo "    abs-uri=$absUri"
( cd "$root" && java -cp classes BuildCase "caseC 空格 中文" "probe 副本.jar" "$absUri" classes > /dev/null )
run "caseC 空格 中文" "java -cp main.jar Main" "java -cp main.jar Main"

echo "==================== ⑤ 只有空格（无中文）"
lay "caseD 空格 中文" "probe copy.jar" "libs/probe%20copy.jar"
run "caseD 空格 中文" "java -cp main.jar Main" "java -cp main.jar Main"

echo "==================== ⑥ ASCII 目录 + 中文库名"
lay "caseE-ascii" "probe 副本.jar" "libs/probe%20%E5%89%AF%E6%9C%AC.jar"
run "caseE-ascii" "java -cp main.jar Main" "java -cp main.jar Main"

echo "==================== 平台信息"
java -XshowSettings:properties -version 2>&1 | grep -E "sun.jnu.encoding|native.encoding|file.encoding|os.name"
