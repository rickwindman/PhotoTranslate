package com.destinywind.dcim.core.download

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** zip 安全解压：防路径穿越、只写目标目录内、限制总体积 */
object ZipSafe {

    const val MAX_TOTAL_BYTES: Long = 500L * 1024 * 1024 // 500MB

    fun extract(input: InputStream, targetDir: File, onEntry: (String) -> Unit = {}) {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalPath + File.separator
        var total = 0L
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (name.contains("..") || name.startsWith("/") || name.contains('\\')) {
                    throw SecurityException("zip 条目路径非法: $name")
                }
                val outFile = File(targetDir, name)
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                    throw SecurityException("zip 路径穿越: $name")
                }
                if (entry.isDirectory) { outFile.mkdirs(); continue }
                outFile.parentFile?.mkdirs()
                var entryBytes = 0L
                outFile.outputStream().use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n; entryBytes += n
                        if (total > MAX_TOTAL_BYTES) throw SecurityException("zip 解压超出体积上限")
                        fos.write(buf, 0, n)
                    }
                }
                onEntry(name)
                zis.closeEntry()
            }
        }
    }

    /** 校验 NCNN 模型 zip 内容齐全 */
    fun validateNcnnModelDir(dir: File): String? {
        val required = listOf("det.param", "det.bin", "rec.param", "rec.bin", "keys.txt")
        val missing = required.filter { !File(dir, it).isFile }
        return if (missing.isEmpty()) null else "缺少必要文件: ${missing.joinToString(", ")}"
    }
}
