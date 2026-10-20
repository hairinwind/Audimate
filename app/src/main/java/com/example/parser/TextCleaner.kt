package com.example.parser

object TextCleaner {
    // Regexes for common junk in ebooks, headers, footers, and page numbers
    private val pageNumberRegex = Regex("(?m)^\\s*(?:Page|第|P\\.)?\\s*\\d+\\s*(?:/|of|页)?\\s*\\d*\\s*$")
    private val bookFooterWithPageRegex = Regex("(?m)^\\s*[\u4e00-\u9fa5a-zA-Z0-9·：:「」『』]{1,20}\\s+\\d{1,4}\\s*$")
    private val headerFooterRegex = Regex("(?m)^\\s*(?:Copyright|All rights reserved|版权所有|翻印必究|内容简介|Chapter\\s*\\d+).{0,30}$")
    private val webLinkRegex = Regex("https?://\\S+|www\\.\\S+")
    private val bracketNotesRegex = Regex("\\[(?:\\d+|注|图|Ref)\\]")
    private val repeatedNewlinesRegex = Regex("\\n{3,}")
    private val repeatedSpacesRegex = Regex("[ \\t]{2,}")
    private val cjkSpacedRegex = Regex("(?<=[\u4e00-\u9fa5\u3040-\u30ff\u3400-\u4dbf])\\s+(?=[\u4e00-\u9fa5\u3040-\u30ff\u3400-\u4dbf])")

    fun cleanText(rawText: String): String {
        var text = rawText
        // Normalize line endings
        text = text.replace("\r\n", "\n").replace("\r", "\n")

        // Remove links and bracket annotations
        text = webLinkRegex.replace(text, "")
        text = bracketNotesRegex.replace(text, "")

        // Remove page numbers and book footer page marks (e.g. "不反應的練習 002")
        text = pageNumberRegex.replace(text, "")
        text = bookFooterWithPageRegex.replace(text, "")

        // Filter lines that look like headers/footers
        val lines = text.lines().filterNot { line ->
            val trimmed = line.trim()
            trimmed.isEmpty() ||
            (trimmed.length < 35 && headerFooterRegex.matches(trimmed) && !trimmed.endsWith("。") && !trimmed.endsWith("！") && !trimmed.endsWith("？"))
        }

        text = lines.joinToString("\n")
        // Remove spaces between CJK characters
        text = cjkSpacedRegex.replace(text, "")
        text = repeatedSpacesRegex.replace(text, " ")
        text = repeatedNewlinesRegex.replace(text, "\n\n")

        return text.trim()
    }
}
