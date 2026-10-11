package com.example.openaichat

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextFileFormatPolicy {
    val extensions = setOf(
        "txt", "text", "md", "markdown", "mdx", "rst", "adoc", "tex", "bib", "log",
        "csv", "tsv", "tab", "json", "jsonl", "ndjson", "geojson", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "xml", "xsd", "xsl", "xslt", "html", "htm", "css", "scss", "svg", "sql", "graphql", "gql",
        "kt", "kts", "java", "py", "pyi", "ipynb", "r", "rmd", "jl", "m", "f", "f90", "for",
        "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "swift", "dart", "scala", "rb", "php",
        "js", "jsx", "ts", "tsx", "vue", "svelte", "sh", "bash", "zsh", "ps1", "bat", "cmake", "gradle",
        "srt", "vtt", "ics", "vcf", "fasta", "fa", "fna", "faa", "fastq", "fq", "sam", "bed", "gff", "gff3", "gtf", "pdb", "cif",
        "dat", "out", "err", "properties", "proto", "xyz", "mol", "mol2", "sdf", "obj", "stl", "gcode"
    )
    val selectableEncodings = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "windows-1251", "KOI8-R", "IBM866", "windows-1252").filter { Charset.isSupported(it) }
    val office = setOf("docx", "pptx", "xlsx", "odt", "odp", "ods")
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    fun knownText(name: String) = extension(name) in extensions ||
        name.substringAfterLast('/').lowercase() in setOf("dockerfile", "makefile", "license", "readme", ".gitignore")

    /** Normalize to a disk file with constant-size buffers, not a giant String. */
    fun normalize(source: File, target: File, allowLegacy: Boolean, forcedEncoding: String? = null): String {
        val header = source.inputStream().use { val buffer = ByteArray(4096); buffer.copyOf(it.read(buffer).coerceAtLeast(0)) }
        val charsets = when {
            forcedEncoding != null -> listOf(forcedEncoding)
            header.size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xFE.toByte() -> listOf("UTF-16LE")
            header.size >= 2 && header[0] == 0xFE.toByte() && header[1] == 0xFF.toByte() -> listOf("UTF-16BE")
            else -> if (allowLegacy) listOf("UTF-8", "windows-1251") else listOf("UTF-8")
        }
        var last: Exception? = null
        for (name in charsets) try {
            val decoder = Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            java.io.InputStreamReader(source.inputStream(), decoder).use { reader ->
                target.writer(Charsets.UTF_8).use { writer ->
                    val chars = CharArray(8192)
                    var first = true
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Чтение отменено")
                        val n = reader.read(chars); if (n < 0) break
                        for (i in 0 until n) if (chars[i] < ' ' && chars[i] !in charArrayOf('\t','\n','\r','\u000C','\u001B')) {
                            throw IOException("Файл содержит бинарные управляющие символы")
                        }
                        val from = if (first && n > 0 && chars[0] == '\uFEFF') 1 else 0
                        writer.write(chars, from, n - from); first = false
                    }
                }
            }
            return name
        } catch (e: Exception) { last = e; target.delete() }
        throw IOException("Не удалось декодировать текст. Выберите другую кодировку; оригинал сохранён", last)
    }
}
