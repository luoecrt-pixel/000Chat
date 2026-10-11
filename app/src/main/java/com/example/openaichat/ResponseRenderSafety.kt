package com.example.openaichat

import java.net.URI

object ResponseRenderSafety {
    fun externalLink(value: String): Boolean = try {
        val uri = URI(value)
        value.none { it.isISOControl() || it.isWhitespace() } &&
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) { false }

    fun textFilename(value: String): Boolean = value.isNotBlank() && value.length <= 160 &&
        value.none { it == '/' || it == '\\' || it.isISOControl() } && !value.contains("..") &&
        TextFileFormatPolicy.extension(value) !in TextFileFormatPolicy.office +
        setOf("pdf", "doc", "xls", "ppt", "png", "jpg", "jpeg", "gif", "webp", "zip", "mp3", "mp4", "exe", "apk")

    fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private val nativeTag = Regex("</?(?:sup|sub)>|<br\\s*/?>", RegexOption.IGNORE_CASE)
    fun nativeInlineTag(value: String): Boolean = nativeTag.matches(value)
    fun nativeHtmlBlock(value: String): Boolean {
        val tags = Regex("<[^>]*>").findAll(value).toList()
        return tags.isNotEmpty() && tags.all { nativeInlineTag(it.value) } &&
            !Regex("<[^>]*>").replace(value, "").contains('<')
    }

    /** Host document has no model markup outside an opaque, script-free sandbox. */
    fun previewDocument(source: String): String {
        val csp = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'"
        val inner = "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$csp\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>$source</body></html>"
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; frame-src about:; style-src 'unsafe-inline'; base-uri 'none'\"></head><body style=\"margin:0;background:#1c1d22\"><iframe title=\"Превью\" sandbox srcdoc=\"${html(inner)}\" style=\"border:0;width:100%;height:100vh;background:white\"></iframe></body></html>"
    }
}
