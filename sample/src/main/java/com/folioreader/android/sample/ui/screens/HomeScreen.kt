package com.folioreader.android.sample.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folioreader.FolioReader
import com.folioreader.android.sample.writeRawToLocalCache
import com.folioreader.android.sample.ui.theme.FolioReaderTheme
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val raw = com.folioreader.android.sample.R.raw.accessible_epub_3

    HomeContent(
        onOpenBookClick = { scope.launch { openBook(raw = raw, context = context) } },
        onOpenRawClick = { scope.launch { openRawBook(raw) } }
    )
}

@Composable
private fun HomeContent(
    onOpenBookClick: () -> Unit,
    onOpenRawClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "Epub Reader", fontSize = 24.sp)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onOpenBookClick, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Open Uri")
            }
            OutlinedButton(onClick = onOpenRawClick, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Open Raw")
            }
        }
    }
}

private suspend fun openBook(@RawRes raw: Int, context: Context) {
    val fileUri: Uri? = try {
        val uri = writeRawToLocalCache(raw = raw, "test_book.epub", context)
        uri
    } catch (e: Exception) {
        Log.e("HomeScreen", "::openBook(), write file filed, e=$e")
        null
    }

    val reader = FolioReader.get()
    reader.openBook(fileUri)
}

private suspend fun openRawBook(@RawRes raw: Int) {
    val reader = FolioReader.get()
    reader.openBook(raw)
}

@Preview
@Composable
private fun HomePreview() {
    FolioReaderTheme {

    }
}