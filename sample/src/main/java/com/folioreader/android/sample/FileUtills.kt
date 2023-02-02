package com.folioreader.android.sample

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RawRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val BOOK_DIR_PATH = "books"

suspend fun writeRawToLocalCache(@RawRes raw: Int, name: String, context: Context): Uri {

    val outputDir = File(context.filesDir, BOOK_DIR_PATH)
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }

    val outputFile = File(outputDir, name)

    var out: FileOutputStream? = null
    try {
        out = FileOutputStream(outputFile)

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            withContext(Dispatchers.IO) {
                context.resources.openRawResource(raw).transferTo(out)
            }
        } else {
            context.resources.openRawResource(raw).copyTo(out)
        }

    } finally {
        out?.let {
            try {
                it.close()
            } catch (ignore: IOException) {
            }
        }
    }

    return Uri.fromFile(outputFile)
}