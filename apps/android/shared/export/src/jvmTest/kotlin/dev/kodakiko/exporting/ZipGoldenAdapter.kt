package dev.kodakiko.exporting

import dev.kodakiko.js.JsDate
import java.io.ByteArrayOutputStream

object ZipGoldenAdapter {
    fun writeUtc(files: List<ZipTestFile>): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ZipWriter({ out.write(it) }, ZipTimeZone.UTC)
        for (file in files) writer.addFile(file.name, file.compress, JsDate.parse(file.mtime)).push(file.content, true)
        writer.finish()
        return out.toByteArray()
    }

    fun read(zip: ByteArray): List<Pair<String, ByteArray>> = readZip(zip).map { it.name to it.content }
}
