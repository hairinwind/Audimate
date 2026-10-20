package com.example

import com.example.parser.DocumentParser
import com.example.parser.TextCleaner
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun questionParticleDetection_isAccurate() {
    val questionParticles = listOf(
      "吗", "呢", "吧", "呀", "嘛", "么", "？", "?",
      "是谁", "是什么", "为什么", "怎么回事", "啥意思", "什么意思",
      "怎么样", "如何", "哪一个", "哪些", "怎么看", "怎么做"
    )

    fun looksLikeCompleteQuestion(text: String): Boolean {
      val trimmed = text.trim()
      if (trimmed.length < 2) return false
      return questionParticles.any { trimmed.endsWith(it) || (trimmed.contains(it) && trimmed.length >= 4) }
    }

    assertTrue(looksLikeCompleteQuestion("这段话的主角是谁？"))
    assertTrue(looksLikeCompleteQuestion("他们去哪里了呢"))
    assertTrue(looksLikeCompleteQuestion("这是什么意思"))
    assertTrue(looksLikeCompleteQuestion("后面会发生什么"))
    assertTrue(looksLikeCompleteQuestion("他为什么离开家乡"))
    assertFalse(looksLikeCompleteQuestion("你好"))
    assertFalse(looksLikeCompleteQuestion("今天天气不错"))
  }

  @Test
  fun explicitFinishCommandDetection_andCleaning() {
    val finishCommands = listOf(
      "我说完了", "我问完了", "问完了", "说完了", "回答我", "解答一下",
      "回答一下", "就这些", "就这个", "解答吧", "回答吧"
    )

    fun containsExplicitFinishCommand(text: String): Boolean {
      return finishCommands.any { text.contains(it) }
    }

    fun cleanQuestionText(text: String): String {
      var result = text.trim()
      for (keyword in finishCommands) {
        if (result.endsWith(keyword)) {
          result = result.removeSuffix(keyword).trim()
        }
      }
      return if (result.isNotBlank()) result else text.trim()
    }

    val input1 = "这段话的主角是谁我说完了"
    assertTrue(containsExplicitFinishCommand(input1))
    assertEquals("这段话的主角是谁", cleanQuestionText(input1))

    val input2 = "为什么会发生这场战争解答一下"
    assertTrue(containsExplicitFinishCommand(input2))
    assertEquals("为什么会发生这场战争", cleanQuestionText(input2))
  }

  @Test
  fun testAutoCharsetDetection_GBK_and_UTF8() {
    val sampleChineseText = "前言：我们失败的原因皆因多虑，尤其是因为太多消极想法所致。《不思考的练习》"

    // 1. UTF-8
    val utf8Bytes = sampleChineseText.toByteArray(Charsets.UTF_8)
    val parsedUtf8 = DocumentParser.parseTxt(utf8Bytes)
    assertEquals(sampleChineseText, parsedUtf8)

    // 2. GBK / GB18030 (the most common cause of 乱码 in Chinese txt files)
    val gbkBytes = sampleChineseText.toByteArray(Charset.forName("GB18030"))
    val parsedGbk = DocumentParser.parseTxt(gbkBytes)
    assertEquals(sampleChineseText, parsedGbk)

    // 3. UTF-8 with BOM
    val bomUtf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8Bytes
    val parsedBom = DocumentParser.parseTxt(bomUtf8)
    assertEquals(sampleChineseText, parsedBom)

    // 4. Traditional Chinese Big5
    val traditionalText = "前言：這樣思考，輕鬆消除所有煩惱。《不反應的練習》"
    val big5Bytes = traditionalText.toByteArray(Charset.forName("Big5"))
    val parsedBig5 = DocumentParser.parseTxt(big5Bytes)
    assertEquals(traditionalText, parsedBig5)
  }

  @Test
  fun testPdfCleanText_removesCjkSpaces_and_preservesParagraphs() {
    val rawPdfText = "不 反 應 的 練 習\n這 樣 思 考，輕 鬆 消 除 所 有 煩 惱。\n\n第 一 章 反 應 之 前「先 理 解」"
    val cleaned = DocumentParser.cleanPdfExtractedText(rawPdfText)
    assertTrue(cleaned.contains("不反應的練習"))
    assertTrue(cleaned.contains("這樣思考，輕鬆消除所有煩惱。"))
    assertTrue(cleaned.contains("第一章 反應之前「先理解」"))
  }

  @Test
  fun testTextCleaner_stripsPageHeadersAndFooters() {
    val input = """
      不反應的練習 002
      佛陀教導我們「不反應練習」的，是古代印度的得道智者——佛陀。
      
      不反應的練習 003
      佛陀的教誨，指的是藉停止內心無謂反應，來消除所有煩惱和痛苦的方法。
    """.trimIndent()

    val cleaned = TextCleaner.cleanText(input)
    assertFalse(cleaned.contains("不反應的練習 002"))
    assertFalse(cleaned.contains("不反應的練習 003"))
    assertTrue(cleaned.contains("古代印度的得道智者"))
    assertTrue(cleaned.contains("消除所有煩惱和痛苦的方法"))
  }
}
