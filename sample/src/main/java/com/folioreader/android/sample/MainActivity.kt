package com.folioreader.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.folioreader.android.sample.ui.screens.HomeScreen
import com.folioreader.android.sample.ui.theme.FolioReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FolioReaderTheme {
                HomeScreen()
            }
        }
    }
}
