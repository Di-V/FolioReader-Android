/*
 * Copyright (C) 2016 Pedro Paulo de Amorim
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.folioreader.ui.activity

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.folioreader.Config
import com.folioreader.Constants
import com.folioreader.Constants.*
import com.folioreader.FolioReader
import com.folioreader.R
import com.folioreader.data.EpubSourceType
import com.folioreader.model.DisplayUnit
import com.folioreader.model.HighlightImpl
import com.folioreader.model.event.ReloadDataEvent
import com.folioreader.model.locators.ReadLocator
import com.folioreader.ui.adapter.FolioPageFragmentAdapter
import com.folioreader.ui.fragment.FolioPageFragment
import com.folioreader.ui.view.DirectionalViewpager
import com.folioreader.ui.view.FolioAppBarLayout
import com.folioreader.util.AppUtil
import com.folioreader.util.FileUtil
import com.folioreader.util.UiUtil
import kotlinx.android.synthetic.main.folio_activity.*
import kotlinx.android.synthetic.main.view_config.*
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.readium.r2.shared.Link
import org.readium.r2.shared.Publication
import org.readium.r2.streamer.parser.EpubParser
import org.readium.r2.streamer.parser.PubBox
import org.readium.r2.streamer.server.Server
import java.lang.ref.WeakReference

class FolioActivity : AppCompatActivity(), FolioActivityCallback,
    View.OnSystemUiVisibilityChangeListener {

    private var bookFileName: String? = null

    private var mFolioPageViewPager: DirectionalViewpager? = null
    private var actionBar: ActionBar? = null
    private var appBarLayout: FolioAppBarLayout? = null
    private var toolbar: Toolbar? = null
    private var distractionFreeMode: Boolean = false
    private var handler: Handler? = null

    private var currentChapterIndex: Int = 0
    private var mFolioPageFragmentAdapter: FolioPageFragmentAdapter? = null
    private var entryReadLocator: ReadLocator? = null
    private var lastReadLocator: ReadLocator? = null
    private var outState: Bundle? = null
    private var savedInstanceState: Bundle? = null

    private var r2StreamerServer: Server? = null
    private var pubBox: PubBox? = null
    private var spine: List<Link>? = null

    private var mBookId: String? = null
    private var mEpubFilePath: String? = null
    private var mEpubSourceType: EpubSourceType? = null
    private var mEpubRawId = 0
    private var direction: Config.Direction = Config.Direction.VERTICAL
    private var portNumber: Int = DEFAULT_PORT_NUMBER
    private var streamerUri: Uri? = null

    private var displayMetrics: DisplayMetrics? = null
    private var density: Float = 0.toFloat()
    private var topActivity: Boolean? = null
    private var taskImportance: Int = 0

    private var pagesBar: LinearLayout? = null
    private var pagesSeekbar: SeekBar? = null
    private var chapterTextView: TextView? = null
    private var pagesAllTextView: TextView? = null
    private var currentPageTextView: TextView? = null

    private lateinit var viewModel: FolioViewModel

    companion object {

        @JvmField
        val LOG_TAG: String = FolioActivity::class.java.simpleName

        const val INTENT_EPUB_SOURCE_PATH = "com.folioreader.epub_asset_path"
        const val INTENT_EPUB_SOURCE_TYPE = "epub_source_type"
        const val EXTRA_READ_LOCATOR = "com.folioreader.extra.READ_LOCATOR"
        private const val BUNDLE_READ_LOCATOR_CONFIG_CHANGE = "BUNDLE_READ_LOCATOR_CONFIG_CHANGE"
        private const val BUNDLE_DISTRACTION_FREE_MODE = "BUNDLE_DISTRACTION_FREE_MODE"
        private const val HIGHLIGHT_ITEM = "highlight_item"
    }

    private val closeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.v(LOG_TAG, "-> closeBroadcastReceiver -> onReceive -> " + intent.action!!)

            val action = intent.action
            if (action != null && action == FolioReader.ACTION_CLOSE_FOLIOREADER) {

                try {
                    val activityManager =
                        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val tasks = activityManager.runningAppProcesses
                    taskImportance = tasks[0].importance
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "-> ", e)
                }

                val closeIntent = Intent(applicationContext, FolioActivity::class.java)
                closeIntent.flags =
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                closeIntent.action = FolioReader.ACTION_CLOSE_FOLIOREADER
                this@FolioActivity.startActivity(closeIntent)
            }
        }
    }

    val statusBarHeight: Int
        get() {
            var result = 0
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0)
                result = resources.getDimensionPixelSize(resourceId)
            return result
        }

    private val currentFragment: FolioPageFragment?
        get() = if (mFolioPageFragmentAdapter != null && mFolioPageViewPager != null) {
            mFolioPageFragmentAdapter!!
                .getItem(mFolioPageViewPager!!.currentItem) as FolioPageFragment
        } else {
            null
        }

    private enum class RequestCode private constructor(internal val value: Int) {
        CONTENT_HIGHLIGHT(77),
        SEARCH(101)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.v(LOG_TAG, "-> onNewIntent")

        val action = getIntent().action
        if (action != null && action == FolioReader.ACTION_CLOSE_FOLIOREADER) {

            if (topActivity == null || topActivity == false) {
                // FolioActivity was already left, so no need to broadcast ReadLocator again.
                // Finish activity without going through onPause() and onStop()
                finish()

                // To determine if app in background or foreground
                var appInBackground = false
                if (Build.VERSION.SDK_INT < 26) {
                    if (ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND == taskImportance)
                        appInBackground = true
                } else {
                    if (ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED == taskImportance)
                        appInBackground = true
                }
                if (appInBackground)
                    moveTaskToBack(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.v(LOG_TAG, "-> onResume")
        topActivity = true

        val action = intent.action
        if (action != null && action == FolioReader.ACTION_CLOSE_FOLIOREADER) {
            // FolioActivity is topActivity, so need to broadcast ReadLocator.
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.v(LOG_TAG, "-> onStop")
        topActivity = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Need to add when vector drawables support library is used.
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        handler = Handler()
        val display = windowManager.defaultDisplay
        displayMetrics = resources.displayMetrics
        display.getRealMetrics(displayMetrics)
        density = displayMetrics!!.density
        LocalBroadcastManager.getInstance(this).registerReceiver(
            closeBroadcastReceiver,
            IntentFilter(FolioReader.ACTION_CLOSE_FOLIOREADER)
        )

        viewModel = ViewModelProvider(
            this,
            FolioViewModelFactory()
        )[FolioViewModel::class.java]

        // Fix for screen get turned off while reading
        // TODO -> Make this configurable
        // getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setConfig(savedInstanceState)
        initDistractionFreeMode(savedInstanceState)

        setContentView(R.layout.folio_activity)
        this.savedInstanceState = savedInstanceState

        mBookId = intent.getStringExtra(FolioReader.EXTRA_BOOK_ID)
        mEpubSourceType =
            intent.extras!!.getSerializable(FolioActivity.INTENT_EPUB_SOURCE_TYPE) as EpubSourceType
        when (mEpubSourceType) {
            EpubSourceType.RAW -> mEpubRawId = intent.extras!!
                .getInt(FolioActivity.INTENT_EPUB_SOURCE_PATH)
            else -> mEpubFilePath = intent.extras!!
                .getString(FolioActivity.INTENT_EPUB_SOURCE_PATH)
        }

        initActionBar()
        initPagesBar()
        initMode()
        setupBook()
    }

    private fun initMode() {
        val config = AppUtil.getSavedConfig(applicationContext)!!

        if (config.isNightMode) {
            setNightMode()
        } else {
            setDayMode()
        }
    }

    private fun initActionBar() {
        appBarLayout = findViewById(R.id.appBarLayout)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        actionBar = supportActionBar
    }

    private fun initPagesBar() {
        pagesBar = findViewById(R.id.pagesBar)
        chapterTextView = findViewById(R.id.chapter)
        pagesAllTextView = findViewById(R.id.chapterNumAndAll)
        currentPageTextView = findViewById(R.id.chapterNum)

        pagesSeekbar = findViewById(R.id.seekBar)
        pagesSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.setCurrentPage(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                Log.w("SeekBarListener", "onStartTrackingTouch")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                try {
                    val index = seekBar!!.progress
                    val href = pubBox!!.publication.readingOrder[index].href!!
                    goToChapter(href)
                } catch (e: Exception) {
                    Log.w("SeekBarListener", "onStopTrackingTouch Exception $e")
                }
            }
        })

        lifecycleScope.launch {
            viewModel.chapter.collect {
                currentPageTextView?.text = "${it.currentPage}"
                pagesSeekbar?.progress = it.currentPage - 1
                chapterTextView?.text = it.chapter
                pagesAllTextView?.text = it.allPages
            }
        }
    }

    override fun setDayMode() {
        Log.v(LOG_TAG, "::setDayMode()")

        window.navigationBarColor = ContextCompat.getColor(this, R.color.white)
        pagesBar?.setBackgroundColor(ContextCompat.getColor(this, R.color.light_gray))
        pagesSeekbar?.thumbTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.dark_gray))
        pagesSeekbar?.progressTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.dark_gray))
        pagesSeekbar?.secondaryProgressTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.gray_text))
        chapterTextView?.setTextColor(ContextCompat.getColor(this, R.color.black))
        pagesAllTextView?.setTextColor(ContextCompat.getColor(this, R.color.black))
        currentPageTextView?.setTextColor(ContextCompat.getColor(this, R.color.black))

        actionBar!!.setBackgroundDrawable(
            ColorDrawable(ContextCompat.getColor(this, R.color.white))
        )
        toolbar!!.setTitleTextColor(ContextCompat.getColor(this, R.color.black))

        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_back)
        UiUtil.setColorIntToDrawable(ContextCompat.getColor(this, R.color.black), drawable!!)
        toolbar!!.navigationIcon = drawable
    }

    override fun setNightMode() {
        Log.v(LOG_TAG, "::setNightMode()")

        window.navigationBarColor = ContextCompat.getColor(this, R.color.black)
        // Bottom pages bar
        pagesBar?.setBackgroundColor(ContextCompat.getColor(this, R.color.black))
        pagesSeekbar?.thumbTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        pagesSeekbar?.progressTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        pagesSeekbar?.progressBackgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.gray_text))
        chapterTextView?.setTextColor(ContextCompat.getColor(this, R.color.white))
        pagesAllTextView?.setTextColor(ContextCompat.getColor(this, R.color.white))
        currentPageTextView?.setTextColor(ContextCompat.getColor(this, R.color.white))

        actionBar!!.setBackgroundDrawable(
            ColorDrawable(ContextCompat.getColor(this, R.color.black))
        )
        toolbar!!.setTitleTextColor(ContextCompat.getColor(this, R.color.night_title_text_color))

        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_back)
        UiUtil.setColorIntToDrawable(ContextCompat.getColor(this, R.color.white), drawable!!)
        toolbar!!.navigationIcon = drawable
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val config = AppUtil.getSavedConfig(applicationContext)!!
        if (config.isNightMode) {
            UiUtil.setColorIntToDrawable(
                ContextCompat.getColor(this, R.color.white),
                menu.findItem(R.id.nightMode).icon
            )
        } else {
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_night_mode)
            UiUtil.setColorIntToDrawable(ContextCompat.getColor(this, R.color.black), drawable)
            menu.findItem(R.id.nightMode).icon = drawable
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                super.getOnBackPressedDispatcher().onBackPressed()
                return true
            }
            R.id.nightMode -> {
                // TODO: Night/Day mode
                val config = AppUtil.getSavedConfig(this)!!
                val isNightMode = !config.isNightMode

                if (isNightMode) {
                    val drawable = ContextCompat.getDrawable(this, R.drawable.ic_day_mode)
                    UiUtil.setColorIntToDrawable(
                        ContextCompat.getColor(this, R.color.white),
                        drawable
                    )
                    item.icon = drawable
                    setNightMode()
                } else {
                    setDayMode()
                    val drawable = ContextCompat.getDrawable(this, R.drawable.ic_night_mode)
                    UiUtil.setColorIntToDrawable(
                        ContextCompat.getColor(this, R.color.black),
                        drawable
                    )
                    item.icon = drawable
                }

                config.isNightMode = isNightMode
                AppUtil.saveConfig(this, config)
                EventBus.getDefault().post(ReloadDataEvent())

                return true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupBook() {
        Log.v(LOG_TAG, "-> setupBook")
        try {
            initBook()
            onBookInitSuccess()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "-> Failed to initialize book", e)
            onBookInitFailure()
        }

    }

    @Throws(Exception::class)
    private fun initBook() {
        Log.v(LOG_TAG, "::initBook(type=$mEpubSourceType, path=$mEpubFilePath)")

        bookFileName = FileUtil.getEpubFilename(this, mEpubSourceType, mEpubFilePath, mEpubRawId)
        val path = FileUtil.saveEpubFileAndLoadLazyBook(
            this, mEpubSourceType, mEpubFilePath,
            mEpubRawId, bookFileName
        )
        Log.v(LOG_TAG, "::initBook(), path=$path")
        val extension: Publication.EXTENSION
        var extensionString: String? = null
        try {
            extensionString = FileUtil.getExtensionUppercase(path)
            extension = Publication.EXTENSION.valueOf(extensionString)
        } catch (e: IllegalArgumentException) {
            throw Exception("-> Unknown book file extension `$extensionString`", e)
        }

        pubBox = when (extension) {
            Publication.EXTENSION.EPUB -> {
                val epubParser = EpubParser()
                epubParser.parse(path!!, "")
            }
//            Publication.EXTENSION.CBZ -> {
//                val cbzParser = CbzParser()
//                cbzParser.parse(path!!, "")
//            }
            else -> {
                null
            }
        }

        // TODO: test
//        val pub = pubBox?.publication!!
//        Log.e("FolioActivity", "readingOrder size=${pub.readingOrder.size}")
//        pub.readingOrder.forEachIndexed() { index, it ->
//            Log.w("FolioActivity", "index=$index, title=${it.title}, href=${it.href}")
//            //chapterTextView.text = pubBox!!.publication.links[0].title
//        }
//        Log.e("FolioActivity", "tableOfContents size=${pub.tableOfContents.size}")
//        pub.tableOfContents.forEachIndexed() { index, it ->
//            Log.w("FolioActivity", "index=$index,  title=${it.title}, url=${it.href}")
//        }
//        Log.e("FolioActivity", "listOfTables size=${pub.listOfTables.size}")
//        pub.listOfTables.forEach {
//            Log.w("FolioActivity", "title=${it.title}, url=${it.href}")
//        }
//        Log.e("FolioActivity", "links size=${pub.links.size}")
//        pub.links.forEach {
//            Log.w("FolioActivity", "title=${it.title}, url=${it.href}")
//        }
//        Log.e("FolioActivity", "pageList size=${pub.pageList.size}")
//        pub.pageList.forEach {
//            Log.w("FolioActivity", "title=${it.title}, url=${it.href}")
//        }
//        Log.e("FolioActivity", "listOfIllustrations size=${pub.listOfIllustrations.size}")
//        pub.listOfIllustrations.forEach {
//            Log.w("FolioActivity", "title=${it.title}, url=${it.href}")
//        }

        val allPages = pubBox?.publication?.readingOrder?.size
        viewModel.setAllPages(allPages ?: 0)
        if (allPages != null) {
            pagesSeekbar?.max = allPages - 1
        }

        portNumber =
            intent.getIntExtra(FolioReader.EXTRA_PORT_NUMBER, DEFAULT_PORT_NUMBER)
        portNumber = AppUtil.getAvailablePortNumber(portNumber)

        r2StreamerServer = Server(portNumber)
        r2StreamerServer!!.addEpub(
            pubBox!!.publication, pubBox!!.container,
            "/" + bookFileName!!, null
        )

        r2StreamerServer!!.start()

        FolioReader.initRetrofit(streamerUrl)
    }

    private fun onBookInitFailure() {
        //TODO -> Fail gracefully
    }

    private fun onBookInitSuccess() {

        val publication = pubBox!!.publication
        spine = publication.readingOrder
        title = publication.metadata.title

        if (mBookId == null) {
            mBookId = if (publication.metadata.identifier.isNotEmpty()) {
                publication.metadata.identifier
            } else {
                if (publication.metadata.title.isNotEmpty()) {
                    publication.metadata.title.hashCode().toString()
                } else {
                    bookFileName!!.hashCode().toString()
                }
            }
        }

        configFolio()
    }

    override fun getStreamerUrl(): String {

        if (streamerUri == null) {
            streamerUri =
                Uri.parse(String.format(STREAMER_URL_TEMPLATE, LOCALHOST, portNumber, bookFileName))
        }
        return streamerUri.toString()
    }

    override fun onDirectionChange(newDirection: Config.Direction) {
        Log.v(LOG_TAG, "-> onDirectionChange")

        var folioPageFragment: FolioPageFragment? = currentFragment ?: return
        entryReadLocator = folioPageFragment!!.getLastReadLocator()
        val searchLocatorVisible = folioPageFragment.searchLocatorVisible

        direction = newDirection

        mFolioPageViewPager!!.setDirection(newDirection)
        mFolioPageFragmentAdapter = FolioPageFragmentAdapter(
            supportFragmentManager,
            spine, bookFileName, mBookId
        )
        mFolioPageViewPager!!.adapter = mFolioPageFragmentAdapter
        mFolioPageViewPager!!.currentItem = currentChapterIndex

        folioPageFragment = currentFragment ?: return
        searchLocatorVisible?.let {
            folioPageFragment.highlightSearchLocator(searchLocatorVisible)
        }
    }

    private fun initDistractionFreeMode(savedInstanceState: Bundle?) {
        Log.v(LOG_TAG, "-> initDistractionFreeMode")

        window.decorView.setOnSystemUiVisibilityChangeListener(this)

        // Deliberately Hidden and shown to make activity contents lay out behind SystemUI
        hideSystemUI()
        showSystemUI()

        distractionFreeMode =
            savedInstanceState != null && savedInstanceState.getBoolean(BUNDLE_DISTRACTION_FREE_MODE)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        Log.v(LOG_TAG, "-> onPostCreate")

        if (distractionFreeMode) {
            handler!!.post { hideSystemUI() }
        }
    }

    /**
     * @return returns height of status bar + app bar as requested by param [DisplayUnit]
     */
    override fun getTopDistraction(unit: DisplayUnit): Int {

        var topDistraction = 0
        if (!distractionFreeMode) {
            topDistraction = statusBarHeight
            if (actionBar != null)
                topDistraction += actionBar!!.height
        }

        return when (unit) {
            DisplayUnit.PX -> topDistraction
            DisplayUnit.DP -> {
                topDistraction /= density.toInt()
                topDistraction
            }
            else -> throw IllegalArgumentException("-> Illegal argument -> unit = $unit")
        }
    }

    /**
     * Calculates the bottom distraction which can cause due to navigation bar.
     * In mobile landscape mode, navigation bar is either to left or right of the screen.
     * In tablet, navigation bar is always at bottom of the screen.
     *
     * @return returns height of navigation bar as requested by param [DisplayUnit]
     */
    override fun getBottomDistraction(unit: DisplayUnit): Int {

        var bottomDistraction = 0
        if (!distractionFreeMode)
            bottomDistraction = appBarLayout!!.navigationBarHeight

        when (unit) {
            DisplayUnit.PX -> return bottomDistraction

            DisplayUnit.DP -> {
                bottomDistraction /= density.toInt()
                return bottomDistraction
            }

            else -> throw IllegalArgumentException("-> Illegal argument -> unit = $unit")
        }
    }

    /**
     * Calculates the Rect for visible viewport of the webview in PX.
     * Visible viewport changes in following cases -
     * 1. In distraction free mode,
     * 2. In mobile landscape mode as navigation bar is placed either on left or right side,
     * 3. In tablets, navigation bar is always placed at bottom of the screen.
     */
    private fun computeViewportRect(): Rect {
        //Log.v(LOG_TAG, "-> computeViewportRect");

        val viewportRect = Rect(appBarLayout!!.insets)
        if (distractionFreeMode)
            viewportRect.left = 0
        viewportRect.top = getTopDistraction(DisplayUnit.PX)
        if (distractionFreeMode) {
            viewportRect.right = displayMetrics!!.widthPixels
        } else {
            viewportRect.right = displayMetrics!!.widthPixels - viewportRect.right
        }
        viewportRect.bottom = displayMetrics!!.heightPixels - getBottomDistraction(DisplayUnit.PX)

        return viewportRect
    }

    override fun getViewportRect(unit: DisplayUnit): Rect {

        val viewportRect = computeViewportRect()
        when (unit) {
            DisplayUnit.PX -> return viewportRect

            DisplayUnit.DP -> {
                viewportRect.left /= density.toInt()
                viewportRect.top /= density.toInt()
                viewportRect.right /= density.toInt()
                viewportRect.bottom /= density.toInt()
                return viewportRect
            }

            DisplayUnit.CSS_PX -> {
                viewportRect.left = Math.ceil((viewportRect.left / density).toDouble()).toInt()
                viewportRect.top = Math.ceil((viewportRect.top / density).toDouble()).toInt()
                viewportRect.right = Math.ceil((viewportRect.right / density).toDouble()).toInt()
                viewportRect.bottom = Math.ceil((viewportRect.bottom / density).toDouble()).toInt()
                return viewportRect
            }

            else -> throw IllegalArgumentException("-> Illegal argument -> unit = $unit")
        }
    }

    override fun getActivity(): WeakReference<FolioActivity> {
        return WeakReference(this)
    }

    override fun onSystemUiVisibilityChange(visibility: Int) {
        Log.v(LOG_TAG, "-> onSystemUiVisibilityChange -> visibility = $visibility")

        distractionFreeMode = visibility != View.SYSTEM_UI_FLAG_VISIBLE
        Log.v(LOG_TAG, "-> distractionFreeMode = $distractionFreeMode")

        if (distractionFreeMode) {
            actionBar?.hide()
            pagesBar?.visibility = GONE
        } else {
            actionBar?.show()
            pagesBar?.visibility = VISIBLE
        }

    }

    override fun toggleSystemUI() {

        if (distractionFreeMode) {
            showSystemUI()
        } else {
            hideSystemUI()
        }
    }

    private fun showSystemUI() {
        Log.v(LOG_TAG, "::showSystemUI()")

        val decorView = window.decorView
        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun hideSystemUI() {
        Log.v(LOG_TAG, "::hideSystemUI()")

        val decorView = window.decorView
        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                // Set the content to appear under the system bars so that the
                // content doesn't resize when the system bars hide and show.
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                // Hide the nav bar and status bar
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun getEntryReadLocator(): ReadLocator? {
        if (entryReadLocator != null) {
            val tempReadLocator = entryReadLocator
            entryReadLocator = null
            return tempReadLocator
        }
        return null
    }

    /**
     * Go to chapter specified by href
     *
     * @param href http link or relative link to the page or to the anchor
     * @return true if href is of EPUB or false if other link
     */
    override fun goToChapter(href: String): Boolean {

        for (link in spine!!) {
            if (href.contains(link.href!!)) {
                currentChapterIndex = spine!!.indexOf(link)
                mFolioPageViewPager!!.currentItem = currentChapterIndex
                val folioPageFragment = currentFragment
                folioPageFragment!!.scrollToFirst()
                folioPageFragment.scrollToAnchorId(href)
                return true
            }
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RequestCode.CONTENT_HIGHLIGHT.value && resultCode == Activity.RESULT_OK &&
            data!!.hasExtra(TYPE)
        ) {

            val type = data.getStringExtra(TYPE)

            if (type == CHAPTER_SELECTED) {
                goToChapter(data.getStringExtra(SELECTED_CHAPTER_POSITION).toString())

            } else if (type == HIGHLIGHT_SELECTED) {
                val highlightImpl = data.getParcelableExtra<HighlightImpl>(HIGHLIGHT_ITEM)
                currentChapterIndex = highlightImpl!!.pageNumber
                mFolioPageViewPager!!.currentItem = currentChapterIndex
                val folioPageFragment = currentFragment ?: return
                folioPageFragment.scrollToHighlightId(highlightImpl!!.rangy)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        outState?.putSerializable(BUNDLE_READ_LOCATOR_CONFIG_CHANGE, lastReadLocator)

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.unregisterReceiver(closeBroadcastReceiver)

        r2StreamerServer?.stop()

        if (isFinishing) {
            localBroadcastManager.sendBroadcast(Intent(FolioReader.ACTION_FOLIOREADER_CLOSED))
            FolioReader.get().retrofit = null
            FolioReader.get().r2StreamerApi = null
        }
    }

    override fun getCurrentChapterIndex(): Int {
        return currentChapterIndex
    }

    private fun configFolio() {

        mFolioPageViewPager = findViewById(R.id.folioPageViewPager)
        // Replacing with addOnPageChangeListener(), onPageSelected() is not invoked
        mFolioPageViewPager!!.setOnPageChangeListener(object :
            DirectionalViewpager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                viewModel.setCurrentPage(position)
            }

            override fun onPageSelected(position: Int) {
                Log.v(LOG_TAG, "-> onPageSelected -> DirectionalViewpager -> position = $position")
                currentChapterIndex = position
                viewModel.setCurrentPage(position)
                getChapterTitle(position)
            }

            fun getChapterTitle(position: Int) {
                val url = spine?.get(position)?.href
                var check = true
                pubBox?.publication?.tableOfContents?.forEach {
                    if (it.href == url && !url.isNullOrEmpty()) {
                        it.title?.let { it1 ->
                            check = false
                            viewModel.setChapter(it1)
                        }
                    }
                }
                if (check) {
                    viewModel.setChapter("")
                }
            }

            override fun onPageScrollStateChanged(state: Int) {

                if (state == DirectionalViewpager.SCROLL_STATE_IDLE) {
                    val position = mFolioPageViewPager!!.currentItem
                    Log.v(
                        LOG_TAG, "-> onPageScrollStateChanged -> DirectionalViewpager -> " +
                                "position = " + position
                    )

                    var folioPageFragment =
                        mFolioPageFragmentAdapter!!.getItem(position - 1) as FolioPageFragment?
                    if (folioPageFragment != null) {
                        folioPageFragment.scrollToLast()
                        if (folioPageFragment.mWebview != null)
                            folioPageFragment.mWebview!!.dismissPopupWindow()
                    }

                    folioPageFragment =
                        mFolioPageFragmentAdapter!!.getItem(position + 1) as FolioPageFragment?
                    if (folioPageFragment != null) {
                        folioPageFragment.scrollToFirst()
                        if (folioPageFragment.mWebview != null)
                            folioPageFragment.mWebview!!.dismissPopupWindow()
                    }
                }
            }
        })

        mFolioPageViewPager!!.setDirection(direction)
        mFolioPageFragmentAdapter = FolioPageFragmentAdapter(
            supportFragmentManager,
            spine, bookFileName, mBookId
        )
        mFolioPageViewPager!!.adapter = mFolioPageFragmentAdapter

        val readLocator: ReadLocator?
        if (savedInstanceState == null) {
            readLocator = intent.getParcelableExtra(FolioActivity.EXTRA_READ_LOCATOR)
            entryReadLocator = readLocator
        } else {
            readLocator = savedInstanceState!!.getParcelable(BUNDLE_READ_LOCATOR_CONFIG_CHANGE)
            lastReadLocator = readLocator
        }
        currentChapterIndex = getChapterIndex(readLocator)
        mFolioPageViewPager!!.currentItem = currentChapterIndex
    }

    private fun getChapterIndex(readLocator: ReadLocator?): Int {

        if (readLocator == null) {
            return 0
        } else if (!TextUtils.isEmpty(readLocator.href)) {
            return getChapterIndex(Constants.HREF, readLocator.href)
        }

        return 0
    }

    private fun getChapterIndex(caseString: String, value: String): Int {
        for (i in spine!!.indices) {
            when (caseString) {
                Constants.HREF -> if (spine!![i].href == value)
                    return i
            }
        }
        return 0
    }

    /**
     * If called, this method will occur after onStop() for applications targeting platforms
     * starting with Build.VERSION_CODES.P. For applications targeting earlier platform versions
     * this method will occur before onStop() and there are no guarantees about whether it will
     * occur before or after onPause()
     *
     * @see Activity.onSaveInstanceState
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.v(LOG_TAG, "-> onSaveInstanceState")
        this.outState = outState

        outState.putBoolean(BUNDLE_DISTRACTION_FREE_MODE, distractionFreeMode)
    }

    override fun storeLastReadLocator(lastReadLocator: ReadLocator) {
        Log.v(LOG_TAG, "-> storeLastReadLocator")
        this.lastReadLocator = lastReadLocator
    }

    private fun setConfig(savedInstanceState: Bundle?) {

        var config: Config?
        val intentConfig = intent.getParcelableExtra<Config>(Config.INTENT_CONFIG)
        val overrideConfig = intent.getBooleanExtra(Config.EXTRA_OVERRIDE_CONFIG, false)
        val savedConfig = AppUtil.getSavedConfig(this)

        if (savedInstanceState != null) {
            config = savedConfig
        } else if (savedConfig == null) {
            config = intentConfig ?: Config()
        } else {
            config = if (intentConfig != null && overrideConfig) {
                intentConfig
            } else {
                savedConfig
            }
        }

        // Code would never enter this if, just added for any unexpected error
        // and to avoid lint warning
        if (config == null) config = Config()

        AppUtil.saveConfig(this, config)
        direction = config.direction
    }

    override fun getDirection(): Config.Direction {
        return direction
    }
}