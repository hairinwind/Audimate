package com.example.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.logging.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

object DocumentParser {

    @Volatile
    private var isPdfBoxInitialized = false

    fun getDisplayName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            name = cursor.getString(index) ?: ""
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.logInfo("DocumentParser", "Could not query DISPLAY_NAME: ${e.message}")
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "用户文档"
        }
        return name
    }

    fun parseUri(context: Context, uri: Uri, fileName: String?): String {
        val resolvedName = if (!fileName.isNullOrBlank() && fileName.contains(".")) {
            fileName
        } else {
            getDisplayName(context, uri)
        }

        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                AppLogger.logInfo("DocumentParser", "File stream was empty for $uri")
                return ""
            }

            val lowerName = resolvedName.lowercase()
            val isPdf = isPdfBytes(bytes) || lowerName.endsWith(".pdf")
            val isEpub = isZipOrEpubBytes(bytes) || lowerName.endsWith(".epub")

            AppLogger.logInfo(
                "DocumentParser",
                "Parsing file: $resolvedName (${bytes.size} bytes), isPdf=$isPdf, isEpub=$isEpub"
            )

            when {
                isPdf -> parsePdf(context, bytes)
                isEpub -> parseEpub(ByteArrayInputStream(bytes))
                else -> parseTxt(bytes)
            }
        } catch (e: Exception) {
            AppLogger.logError(
                category = "PARSER",
                tag = "DocumentParser",
                message = "Failed to parse document: ${e.message}",
                throwable = e,
                extraInfo = "fileName=$resolvedName, uri=$uri"
            )
            ""
        }
    }

    private fun isPdfBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // %PDF magic bytes (0x25, 0x50, 0x44, 0x46)
        return bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
               bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
    }

    private fun isZipOrEpubBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PK\x03\x04
        return bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
               (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
    }

    /**
     * Parses PDF using PdfBox-Android with Chinese/CJK text layout post-processing
     */
    fun parsePdf(context: Context, bytes: ByteArray): String {
        if (!isPdfBoxInitialized) {
            try {
                PDFBoxResourceLoader.init(context)
                isPdfBoxInitialized = true
            } catch (e: Exception) {
                AppLogger.logInfo("DocumentParser", "Failed to init PDFBoxResourceLoader: ${e.message}")
            }
        }

        return try {
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                val totalPages = document.numberOfPages
                AppLogger.logInfo("DocumentParser", "Loaded PDF document with $totalPages pages")

                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    startPage = 1
                    endPage = totalPages
                }

                val extracted = stripper.getText(document)
                if (extracted.isNotBlank() && extracted.trim().length > 30) {
                    AppLogger.logInfo("DocumentParser", "PdfBox extracted ${extracted.length} raw chars from $totalPages pages")
                    cleanPdfExtractedText(extracted)
                } else {
                    AppLogger.logInfo("DocumentParser", "PdfBox extracted very little text (${extracted.length} chars). Trying stream fallback.")
                    val fallback = fallbackPdfStreamExtraction(bytes)
                    if (fallback.isNotBlank()) cleanPdfExtractedText(fallback) else extracted
                }
            }
        } catch (e: Exception) {
            AppLogger.logError(
                category = "PARSER",
                tag = "DocumentParser",
                message = "PDF parsing failed with PdfBox: ${e.message}",
                throwable = e
            )
            val fallback = fallbackPdfStreamExtraction(bytes)
            cleanPdfExtractedText(fallback)
        }
    }

    /**
     * Cleans common CJK formatting issues in PDF extracted text:
     * 1. Removes unneeded spaces between CJK characters (e.g. "不 反 應 的 練 習" -> "不反應的練習")
     * 2. Re-joins soft line-breaks inside sentences while preserving paragraph breaks
     */
    fun cleanPdfExtractedText(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace("\r", "\n")
        // Remove page break / form feed characters
        text = text.replace("\u000C", "\n\n")

        // 1. Remove horizontal spaces/tabs between Chinese/Japanese/Korean characters and punctuation
        val cjkCharAndPunctuation = "[\u4e00-\u9fa5\u3040-\u30ff\u3400-\u4dbf\u3000-\u303f\uff00-\uffef]"
        val cjkSpaceRegex = Regex("(?<=$cjkCharAndPunctuation)[ \\t\\u3000]+(?=$cjkCharAndPunctuation)")
        text = cjkSpaceRegex.replace(text, "")

        // 2. Normalize multiple blank lines into paragraph breaks
        val paragraphs = text.split(Regex("\\n{2,}"))
        val joinedParagraphs = paragraphs.mapNotNull { p ->
            val trimmed = p.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            // Inside a paragraph, join lines that don't end with sentence-ending punctuation
            val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val sb = StringBuilder()
            for (i in lines.indices) {
                val line = lines[i]
                sb.append(line)
                val lastChar = line.lastOrNull()
                // If line ends without sentence punctuation and next line exists, join without newline
                val isPunctuation = lastChar in listOf('。', '！', '？', '；', '…', '!', '?', ';', ':', '：', '”', '’', '」', '』')
                if (!isPunctuation && i < lines.size - 1) {
                    val nextChar = lines[i + 1].firstOrNull()
                    val isBothCjk = (lastChar != null && lastChar.isCjk()) && (nextChar != null && nextChar.isCjk())
                    if (!isBothCjk) {
                        sb.append(" ")
                    }
                } else if (i < lines.size - 1) {
                    sb.append("\n")
                }
            }
            sb.toString()
        }

        return joinedParagraphs.joinToString("\n\n")
    }

    private fun Char.isCjk(): Boolean {
        val code = this.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) || (code in 0x3040..0x30FF)
    }

    /**
     * Fallback for streams in PDF that might contain raw text objects
     */
    private fun fallbackPdfStreamExtraction(bytes: ByteArray): String {
        try {
            val content = String(bytes, Charsets.ISO_8859_1)
            val sb = StringBuilder()
            val textRegex = Regex("\\(([^\\(\\)]+)\\)\\s*(?:Tj|'|\")")
            val matches = textRegex.findAll(content)
            for (m in matches) {
                val segment = m.groupValues[1].trim()
                if (segment.isNotBlank()) {
                    sb.append(segment).append(" ")
                }
            }
            if (sb.length > 50) {
                return sb.toString()
            }
        } catch (e: Exception) {
            // Ignore
        }
        return ""
    }

    /**
     * Robust TXT parser that auto-detects UTF-8 (with or without BOM), UTF-16, GB18030/GBK/GB2312, and Big5
     */
    fun parseTxt(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. Check BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }

        // 2. Strict UTF-8 decoder check
        try {
            val decoder = Charsets.UTF_8.newDecoder().apply {
                onMalformedInput(CodingErrorAction.REPORT)
                onUnmappableCharacter(CodingErrorAction.REPORT)
            }
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // Not valid UTF-8
        }

        // 3. Try Big5 and GB18030, scoring their readable characters
        var big5Text: String? = null
        try {
            val decoder = Charset.forName("Big5").newDecoder().apply {
                onMalformedInput(CodingErrorAction.REPORT)
                onUnmappableCharacter(CodingErrorAction.REPORT)
            }
            big5Text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // Not Big5
        }

        var gbkText: String? = null
        try {
            val decoder = Charset.forName("GB18030").newDecoder().apply {
                onMalformedInput(CodingErrorAction.REPORT)
                onUnmappableCharacter(CodingErrorAction.REPORT)
            }
            gbkText = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // Not GB18030
        }

        if (big5Text != null && gbkText != null) {
            val big5Score = calculateChineseTextScore(big5Text)
            val gbkScore = calculateChineseTextScore(gbkText)
            return if (big5Score > gbkScore) big5Text else gbkText
        }

        if (big5Text != null) return big5Text
        if (gbkText != null) return gbkText

        // 4. Fallback: decode with GB18030 with replacement action to avoid crashes
        return String(bytes, Charset.forName("GB18030"))
    }

    private fun calculateChineseTextScore(text: String): Int {
        var score = 0
        val commonPunctuation = "，。！？、“”‘’：；（）《》「」『』"
        for (ch in text) {
            if (ch in '\u4E00'..'\u9FFF') {
                score += 2
            } else if (ch in commonPunctuation) {
                score += 3
            } else if (ch.code in 0x00..0x7F) {
                score += 1
            } else {
                score -= 1
            }
        }
        return score
    }

    fun parseTxt(inputStream: InputStream): String {
        return parseTxt(inputStream.readBytes())
    }

    fun parseEpub(inputStream: InputStream): String {
        val sb = StringBuilder()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) {
                    val entryBytes = zip.readBytes()
                    val content = parseTxt(entryBytes)
                    val stripped = stripHtml(content)
                    if (stripped.isNotBlank()) {
                        sb.append(stripped).append("\n\n")
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return sb.toString()
    }

    private fun stripHtml(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<style.*?>.*?</style>"), " ")
        text = text.replace(Regex("(?is)<script.*?>.*?</script>"), " ")
        text = text.replace(Regex("(?i)</p>|<br\\s*/?>|</div>"), "\n\n")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "——")
            .replace("&hellip;", "……")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
        return text
    }
}
