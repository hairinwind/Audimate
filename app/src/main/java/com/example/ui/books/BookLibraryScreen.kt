package com.example.ui.books

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.BookEntity
import com.example.data.repository.BookRepository
import com.example.parser.DocumentChunker
import com.example.parser.DocumentParser
import com.example.parser.SampleBooks
import com.example.parser.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookLibraryScreen(
    bookRepository: BookRepository,
    onSelectBook: (bookId: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToErrors: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by bookRepository.allBooks.collectAsState(initial = emptyList())
    var isImporting by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf("正在解析文档与识别编码...") }
    var showSampleChooser by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            importStatusMessage = "正在读取文件并智能识别编码/PDF..."
            scope.launch {
                val displayName = DocumentParser.getDisplayName(context, uri)
                val rawText = withContext(Dispatchers.IO) {
                    DocumentParser.parseUri(context, uri, displayName)
                }
                if (rawText.isBlank()) {
                    Toast.makeText(context, "未能从《$displayName》提取到有效文本，请确认文件是否受加密保护或为纯图片", Toast.LENGTH_LONG).show()
                    isImporting = false
                    return@launch
                }

                importStatusMessage = "正在进行文本清洗与语义分块 (500~800字)..."
                val cleaned = withContext(Dispatchers.IO) { TextCleaner.cleanText(rawText) }
                if (cleaned.isBlank()) {
                    Toast.makeText(context, "文档清洗后内容为空", Toast.LENGTH_SHORT).show()
                    isImporting = false
                    return@launch
                }
                val bookId = UUID.randomUUID().toString()
                val chunks = withContext(Dispatchers.IO) { DocumentChunker.chunkText(bookId, cleaned) }

                val title = displayName.substringBeforeLast(".").ifEmpty { "导入书籍" }
                val book = BookEntity(
                    id = bookId,
                    title = title,
                    author = "本地导入",
                    sourceFileName = displayName,
                    totalChunks = chunks.size
                )
                bookRepository.saveNewBookWithChunks(book, chunks)
                isImporting = false
                Toast.makeText(context, "导入成功！《$title》已拆分为 ${chunks.size} 个语义段落", Toast.LENGTH_SHORT).show()
                onSelectBook(bookId)
            }
        }
    }

    if (showSampleChooser) {
        AlertDialog(
            onDismissRequest = { showSampleChooser = false },
            title = { Text("载入精选示范书籍", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择一本精选书籍即可立即播放，无需等待：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SampleBooks.samples.forEach { sample ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSampleChooser = false
                                    isImporting = true
                                    importStatusMessage = "正在载入《${sample.title}》..."
                                    scope.launch {
                                        val cleaned = TextCleaner.cleanText(sample.content)
                                        val bookId = UUID.randomUUID().toString()
                                        val chunks = DocumentChunker.chunkText(bookId, cleaned)

                                        val book = BookEntity(
                                            id = bookId,
                                            title = sample.title,
                                            author = sample.author,
                                            totalChunks = chunks.size
                                        )
                                        bookRepository.saveNewBookWithChunks(book, chunks)
                                        isImporting = false
                                        Toast.makeText(context, "已载入《${sample.title}》", Toast.LENGTH_SHORT).show()
                                        onSelectBook(bookId)
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(sample.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("作者：${sample.author}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSampleChooser = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("车载书库", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = onNavigateToErrors,
                        modifier = Modifier.padding(end = 4.dp).testTag("library_error_history_button")
                    ) {
                        Text("7天错误日志", fontSize = 12.sp)
                    }
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("library_settings_button")) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Import and preset actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("import_file_button"),
                    enabled = !isImporting
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导入本地书籍", fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = {
                        showSampleChooser = true
                    },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("load_sample_book_button"),
                    enabled = !isImporting
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("精选示范书籍", fontSize = 14.sp)
                }
            }

            if (isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(importStatusMessage, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "全部书籍 (${books.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("书库暂无书籍", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "点击上方“载入示范书籍”或“导入本地书籍”即可开始随身听书",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(books, key = { it.id }) { book ->
                        BookItemCard(
                            book = book,
                            onClick = { onSelectBook(book.id) },
                            onClearCache = {
                                scope.launch {
                                    bookRepository.clearAudioCacheForBook(book.id)
                                    Toast.makeText(context, "已清空本书已下载的音频缓存", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    bookRepository.deleteBook(book.id)
                                    Toast.makeText(context, "已删除书籍及对应音频文件", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BookItemCard(
    book: BookEntity,
    onClick: () -> Unit,
    onClearCache: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("book_card_${book.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "作者：${book.author} · 共 ${book.totalChunks} 个分块",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = onClearCache,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("clear_cache_book_${book.id}")
                    ) {
                        Text("清空缓存", fontSize = 11.sp)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_book_${book.id}")) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Cache & Reading Progress
            val cacheFraction = if (book.totalChunks > 0) book.completedChunks.toFloat() / book.totalChunks else 0f
            LinearProgressIndicator(
                progress = { cacheFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = Color(0xFF10B981),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "离线缓存: ${book.completedChunks} / ${book.totalChunks} 块 (${(cacheFraction * 100).toInt()}%)",
                    fontSize = 11.sp,
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "当前收听: 第 ${book.currentChunkIndex + 1} 块",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
