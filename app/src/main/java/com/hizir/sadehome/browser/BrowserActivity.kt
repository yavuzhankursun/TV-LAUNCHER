package com.hizir.sadehome.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Patterns
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.hizir.sadehome.data.Prefs
import com.hizir.sadehome.ui.Fonts
import com.hizir.sadehome.ui.Glyph
import com.hizir.sadehome.ui.MATCH
import com.hizir.sadehome.ui.Palette
import com.hizir.sadehome.ui.Pill
import com.hizir.sadehome.ui.dp
import com.hizir.sadehome.ui.focusStates
import com.hizir.sadehome.ui.lp
import com.hizir.sadehome.ui.margins
import com.hizir.sadehome.ui.roundRect
import kotlin.math.min

/**
 * Google TV'de olmayan internet tarayıcısı. Sistem WebView'i (Chromium, Play Store'dan güncellenir)
 * kullanır, yani siteler günceldir. Kumandanın yön tuşları fare imlecini hareket ettirir, OK tıklar,
 * imleç kenara dayanınca sayfa kayar. Ayrı süreçte (:browser) çalışır; kapatılınca süreç tamamen
 * sonlanır ve bellek geri verilir.
 */
class BrowserActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var webBox: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var url: EditText
    private lateinit var progress: View
    private lateinit var cursor: CursorView
    private lateinit var star: Pill
    private lateinit var modePill: Pill
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenBox: FrameLayout

    // İmleç hareketi
    private var dirX = 0
    private var dirY = 0
    private var holdStart = 0L
    private var lastFrame = 0L
    private var moving = false
    private var touchDownTime = 0L
    private var defaultUa = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        buildUi()
        setContentView(root)
        setupWeb()
        val data = intent?.data?.toString()
        if (data != null) web.loadUrl(data) else if (savedInstanceState == null) showStart()
        web.post {
            cursor.moveTo(webBox.width / 2f, webBox.height / 2f + toolbar.height)
            if (data == null) url.requestFocus() else web.requestFocus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web.loadUrl(it.toString()); web.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
        web.pauseTimers()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            web.destroy()
            // Tarayıcı kendi sürecinde: kapanınca WebView'in tuttuğu belleğin tamamı geri verilsin.
            Process.killProcess(Process.myPid())
        }
    }

    // ---------------------------------------------------------------- arayüz

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0E0E12.toInt()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(10))
            setBackgroundColor(0xFF15151B.toInt())
        }
        fun tool(g: Glyph, label: String?, action: () -> Unit) = Pill(this, g, label, heightDp = 32).also {
            it.setOnClickListener { action() }
            toolbar.addView(it, lp(if (label == null) dp(32) else com.hizir.sadehome.ui.WRAP, dp(32)).margins(r = dp(8)))
        }
        tool(Glyph.ARROW_LEFT, null) { if (web.canGoBack()) web.goBack() }
        tool(Glyph.ARROW_RIGHT, null) { if (web.canGoForward()) web.goForward() }
        tool(Glyph.REFRESH, null) { web.reload() }
        tool(Glyph.HOME, null) { showStart() }

        url = EditText(this).apply {
            setSingleLine()
            textSize = 15f
            typeface = Fonts.medium
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_FAINT)
            hint = "Ara ya da adres yaz"
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            background = focusStates(
                roundRect(0x1AFFFFFF, dp(16f), 0x26FFFFFF, dp(1)),
                roundRect(0x33FFFFFF, dp(16f), 0xFFFFFFFF.toInt(), dp(2)),
            )
            setPadding(dp(16), 0, dp(16), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ ->
                go(text.toString())
                true
            }
        }
        toolbar.addView(url, LinearLayout.LayoutParams(0, dp(34), 1f).margins(l = dp(4), r = dp(10)))
        star = tool(Glyph.STAR, null) { toggleBookmark() }
        modePill = tool(Glyph.DISPLAY, if (prefs.browserDesktop) "Masaüstü" else "Mobil") { toggleMode() }
        column.addView(toolbar, lp(MATCH, com.hizir.sadehome.ui.WRAP))

        progress = View(this).apply { setBackgroundColor(0xFF3B82F6.toInt()); pivotX = 0f; scaleX = 0f }
        column.addView(progress, lp(MATCH, dp(2)))

        webBox = FrameLayout(this)
        column.addView(webBox, LinearLayout.LayoutParams(MATCH, 0, 1f))

        fullscreenBox = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE }
        root.addView(fullscreenBox, FrameLayout.LayoutParams(MATCH, MATCH))

        cursor = CursorView(this)
        root.addView(cursor, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web = WebView(this)
        webBox.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.setBackgroundColor(0xFF0E0E12.toInt())
        defaultUa = WebSettings.getDefaultUserAgent(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgent()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") return false
                // intent:, market:, mailto: vb. → ilgili uygulama varsa ona ver.
                runCatching {
                    val i = if (scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, u)
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }

            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                showUrl(u)
            }

            override fun onPageFinished(view: WebView, u: String?) {
                showUrl(u)
                progress.animate().alpha(0f).setDuration(300).start()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.alpha = 1f
                progress.animate().scaleX(newProgress / 100f).setDuration(150).start()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView?.let { onHideCustomView() }
                fullscreenView = view
                fullscreenCallback = callback
                fullscreenBox.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                fullscreenBox.visibility = View.VISIBLE
                cursor.shown = false
            }

            override fun onHideCustomView() {
                fullscreenBox.removeAllViews()
                fullscreenBox.visibility = View.GONE
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                cursor.shown = true
                web.requestFocus()
            }
        }
        web.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "Tarayıcı dosya indirmeyi desteklemiyor", Toast.LENGTH_SHORT).show()
        }
        web.onFocusChangeListener = View.OnFocusChangeListener { _, has -> cursor.shown = has || fullscreenView != null }
    }

    private fun userAgent(): String? {
        if (!prefs.browserDesktop) return null
        val chrome = Regex("Chrome/[\\d.]+").find(defaultUa)?.value ?: "Chrome/124.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun toggleMode() {
        prefs.browserDesktop = !prefs.browserDesktop
        web.settings.userAgentString = userAgent()
        modePill.setText(if (prefs.browserDesktop) "Masaüstü" else "Mobil")
        web.reload()
    }

    private fun showUrl(u: String?) {
        val shown = if (u == null || u.startsWith("about:") || u.startsWith("data:")) "" else u
        if (!url.hasFocus()) url.setText(shown)
        val marked = prefs.bookmarks.any { it.second == shown }
        star.setGlyph(if (marked) Glyph.CHECK else Glyph.STAR)
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && Patterns.WEB_URL.matcher(t).matches() -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(target)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(url.windowToken, 0)
        web.requestFocus()
    }

    private fun toggleBookmark() {
        val u = web.url ?: return
        if (u.startsWith("about:") || u.startsWith("data:")) return
        val list = prefs.bookmarks.toMutableList()
        val i = list.indexOfFirst { it.second == u }
        if (i >= 0) {
            list.removeAt(i)
            Toast.makeText(this, "Yer imi kaldırıldı", Toast.LENGTH_SHORT).show()
        } else {
            val title = web.title?.takeIf { it.isNotBlank() } ?: Uri.parse(u).host ?: u
            list.add(title.take(40) to u)
            Toast.makeText(this, "Ana sayfaya eklendi", Toast.LENGTH_SHORT).show()
        }
        prefs.bookmarks = list
        showUrl(u)
    }

    private fun showStart() {
        web.loadDataWithBaseURL(null, StartPage.html(prefs.bookmarks), "text/html", "utf-8", null)
        url.setText("")
    }

    // ---------------------------------------------------------------- kumanda → imleç

    private fun target(): View = fullscreenView ?: web

    /** İmlecin hareket edebildiği alan (root koordinatlarında). */
    private fun area(): IntArray {
        val t = target()
        val loc = IntArray(2)
        t.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        return intArrayOf(loc[0] - rootLoc[0], loc[1] - rootLoc[1], t.width, t.height)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        val cursorActive = fullscreenView != null || web.hasFocus()
        if (!cursorActive) {
            // Araç çubuğundan aşağı inince sayfaya (imleç moduna) geç.
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && toolbar.hasFocus()) {
                web.requestFocus()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                handleDirection(event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) touch(MotionEvent.ACTION_DOWN)
                else if (event.action == KeyEvent.ACTION_UP) touch(MotionEvent.ACTION_UP)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) web.pageDown(false)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) web.pageUp(false)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBack() {
        when {
            fullscreenView != null -> web.webChromeClient?.onHideCustomView()
            toolbar.hasFocus() -> web.requestFocus()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private fun handleDirection(e: KeyEvent) {
        val (dx, dy) = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            else -> 0 to 1
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount > 0) return
            // Sayfanın en üstünde, imleç üst kenardayken yukarı → adres çubuğu.
            val a = area()
            if (dy < 0 && fullscreenView == null && cursor.cy <= a[1] + 1 && !web.canScrollVertically(-1)) {
                url.requestFocus()
                return
            }
            if (dx != 0) dirX = dx
            if (dy != 0) dirY = dy
            if (!moving) {
                moving = true
                holdStart = SystemClock.uptimeMillis()
                lastFrame = holdStart
                // İlk basışta küçük, hassas bir adım.
                step(dp(10f))
                root.postOnAnimation(mover)
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            if (dx != 0 && dirX == dx) dirX = 0
            if (dy != 0 && dirY == dy) dirY = 0
            if (dirX == 0 && dirY == 0) moving = false
        }
    }

    private val mover = object : Runnable {
        override fun run() {
            if (!moving) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(1, 50) / 1000f
            lastFrame = now
            val held = now - holdStart
            if (held > 180) {
                // Basılı tuttukça hızlanır: ~300 dp/sn'den ~1100 dp/sn'ye.
                val speed = dp(min(1100f, 300f + (held - 180) * 1.1f))
                step(speed * dt)
            }
            root.postOnAnimation(this)
        }
    }

    private fun step(dist: Float) {
        val a = area()
        val minX = a[0].toFloat()
        val minY = a[1].toFloat()
        val maxX = (a[0] + a[2] - 1).toFloat()
        val maxY = (a[1] + a[3] - 1).toFloat()
        val nx = cursor.cx + dirX * dist
        val ny = cursor.cy + dirY * dist
        // Kenara dayanınca sayfayı kaydır (fare tekerleği olayı: iç kaydırma alanlarında da çalışır).
        val overX = if (nx < minX) nx - minX else if (nx > maxX) nx - maxX else 0f
        val overY = if (ny < minY) ny - minY else if (ny > maxY) ny - maxY else 0f
        cursor.moveTo(nx.coerceIn(minX, maxX), ny.coerceIn(minY, maxY))
        if (overX != 0f || overY != 0f) wheel(overX * 1.6f, overY * 1.6f)
        hover()
    }

    private fun obtain(action: Int, downTime: Long, source: Int, tool: Int, vScroll: Float = 0f, hScroll: Float = 0f): MotionEvent {
        val a = area()
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursor.cx - a[0]
            y = cursor.cy - a[1]
            pressure = 1f
            size = 1f
            if (vScroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            if (hScroll != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        })
        return MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun touch(action: Int) {
        val t = target()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = SystemClock.uptimeMillis()
        val ev = obtain(action, touchDownTime, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
        t.dispatchTouchEvent(ev)
        ev.recycle()
        cursor.clicking = action == MotionEvent.ACTION_DOWN
    }

    private fun hover() {
        val ev = obtain(MotionEvent.ACTION_HOVER_MOVE, SystemClock.uptimeMillis(), InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE)
        target().dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun wheel(px: Float, py: Float) {
        val factor = ViewConfiguration.get(this).scaledVerticalScrollFactor.coerceAtLeast(1f)
        // AXIS_VSCROLL pozitif = yukarı kaydır; imleç aşağı itiyorsa (py>0) sayfa aşağı gitmeli.
        val ev = obtain(
            MotionEvent.ACTION_SCROLL, SystemClock.uptimeMillis(), InputDevice.SOURCE_MOUSE,
            MotionEvent.TOOL_TYPE_MOUSE, vScroll = -py / factor, hScroll = px / factor,
        )
        target().dispatchGenericMotionEvent(ev)
        ev.recycle()
    }
}
