package com.folioreader.ui.activity

import androidx.lifecycle.*
import kotlinx.android.synthetic.main.folio_activity.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChapterState(
    val chapter: String = "",
    val currentPage: Int = 0,
    val allPages: String = ""
)

class FolioViewModel : ViewModel() {
    private val _chapter = MutableStateFlow(ChapterState())
    val chapter: StateFlow<ChapterState> = _chapter.asStateFlow()

    private var allPages: Int = 0

    fun setChapter(chapter: String) {
        _chapter.update { it.copy(chapter = chapter) }
    }

    fun setCurrentPage(currentChapter: Int) {
        _chapter.update { it.copy(currentPage = currentChapter, allPages = "${it.currentPage}/$allPages") }
    }

    fun setAllPages(num: Int) {
        allPages = num
        _chapter.update { it.copy(allPages = "${it.currentPage}/$num") }
    }
}

class FolioViewModelFactory : ViewModelProvider.Factory {
    @Suppress("unchecked_cast")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FolioViewModel::class.java)) {
            return FolioViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}