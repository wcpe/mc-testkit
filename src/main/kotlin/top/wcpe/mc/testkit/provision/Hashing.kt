package top.wcpe.mc.testkit.provision

import java.io.File
import java.security.MessageDigest

/**
 * 计算文件 SHA-256（小写十六进制）。
 *
 * 用于下载缓存的 hash 校验复用：命中缓存时重算本地文件 hash 与记录值比对，
 * 不一致则失效重下（防本地损坏）。
 */
internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return toHexString(digest.digest())
}

/** 字节数组转小写十六进制字符串。 */
internal fun toHexString(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        if (value < 0x10) builder.append('0')
        builder.append(Integer.toHexString(value))
    }
    return builder.toString()
}
