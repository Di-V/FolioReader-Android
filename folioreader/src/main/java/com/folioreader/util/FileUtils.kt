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

//    var out: FileOutputStream? = null
//    try {
//        out = FileOutputStream(outputFile)
//        bitmap.compress(Bitmap.CompressFormat.PNG, 0 /* ignored for PNG */, out)
//    } finally {
//        out?.let {
//            try {
//                it.close()
//            } catch (ignore: IOException) {
//            }
//        }
//    }
//
//    return Uri.fromFile(outputFile)
}