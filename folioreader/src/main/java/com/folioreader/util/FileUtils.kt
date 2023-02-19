package com.folioreader.util

import android.content.Context
import java.io.File

const val FOLIO_READER_ROOT = "folioreader"

fun getFolioEpubFolderPath(epubFileName: String, context: Context): String {
    val outputDir = File(context.filesDir, FOLIO_READER_ROOT)
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }

    val outputFile = File(outputDir, epubFileName)
    return outputFile.path
}