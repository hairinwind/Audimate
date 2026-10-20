package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.logging.AppLogger
import com.example.parser.DocumentChunker
import com.example.parser.SampleBooks
import com.example.parser.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("车载听书", appName)
  }

  @Test
  fun `text cleaner removes header and links`() {
    val dirtyText = """
      Page 1 of 50
      https://example.com/ebook
      [1]
      这是正文的第一段。叙述了故事的开端。
      Copyright 2024 All rights reserved
    """.trimIndent()

    val cleaned = TextCleaner.cleanText(dirtyText)
    assertTrue("Should contain story text", cleaned.contains("这是正文的第一段"))
    assertTrue("Should not contain page numbers", !cleaned.contains("Page 1"))
    assertTrue("Should not contain web links", !cleaned.contains("https://"))
  }

  @Test
  fun `document chunker produces ordered chunks`() {
    val sample = SampleBooks.samples.first()
    val cleaned = TextCleaner.cleanText(sample.content)
    val chunks = DocumentChunker.chunkText("test_book_1", cleaned)

    assertTrue("Should produce at least one chunk", chunks.isNotEmpty())
    chunks.forEachIndexed { index, chunk ->
      assertEquals("Chunk index must be sequential", index, chunk.chunkIndex)
      assertEquals("Book ID must match", "test_book_1", chunk.bookId)
      assertTrue("Chunk content should not be empty", chunk.textContent.isNotBlank())
    }
  }

  @Test
  fun `app logger captures and formats stack traces`() {
    val exception = IllegalStateException("Test crash for in-car debugging")
    AppLogger.logError(
      category = "TEST",
      tag = "TestTag",
      message = "Verification of error logging",
      throwable = exception
    )

    val latest = AppLogger.latestError.value
    assertNotNull("Latest error must be recorded", latest)
    assertEquals("TEST", latest?.category)
    assertTrue("Stack trace must contain method name", latest?.stackTrace?.contains("IllegalStateException") == true)
  }
}
