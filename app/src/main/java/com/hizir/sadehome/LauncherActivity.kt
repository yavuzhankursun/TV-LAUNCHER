package com.hizir.sadehome

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hizir.sadehome.adb.Adb
import com.hizir.sadehome.airplay.AirPlayService
import com.hizir.sadehome.airplay.AirPlayState
import com.hizir.sadehome.browser.BrowserActivity
import com.hizir.sadehome.data.AppEntry
import com.hizir.sadehome.data.AppRepository
import com.hizir.sadehome.data.Bg
import com.hizir.sadehome.data.Memory
import com.hizir.sadehome.data.Prefs
import com.hizir.sadehome.data.SpeedTest
import com.hizir.sadehome.data.Weather
import com.hizir.sadehome.system.Cleaner
import com.hizir.sadehome.system.Debloat
import com.hizir.sadehome.system.HomeControl
import com.hizir.sadehome.ui.AppTile
import com.hizir.sadehome.ui.FrameStats
import com.hizir.sadehome.ui.Fonts
import com.hizir.sadehome.ui.Glyph
import com.hizir.sadehome.ui.GlyphDrawable
import com.hizir.sadehome.ui.HeroBackdrop
import com.hizir.sadehome.ui.MATCH
import com.hizir.sadehome.ui.Palette
import com.hizir.sadehome.ui.Pill
import com.hizir.sadehome.ui.SidePanel
import com.hizir.sadehome.ui.TrackRow
import com.hizir.sadehome.ui.WRAP
import com.hizir.sadehome.ui.dp
import com.hizir.sadehome.ui.keyBadge
import com.hizir.sadehome.ui.lp
import com.hizir.sadehome.ui.margins
import com.hizir.sadehome.ui.roundRect
import com.hizir.sadehome.ui.text
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Asıl ana ekran. */
class LauncherActivity : Activity() {
    private val tr = Locale.forLanguageTag("tr")
    private lateinit var prefs: Prefs

    private lateinit var root: FrameLayout
    private lateinit var backdrop: HeroBackdrop
    private lateinit var main: LinearLayout
    private lateinit var panel: SidePanel

    // Üst çubuk
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherIcon: GlyphDrawable
    private lateinit var weatherText: TextView
    private lateinit var weatherGroup: LinearLayout
    private lateinit var airPlayPill: Pill
    private lateinit var ramText: TextView
    private val ramDot = com.hizir.sadehome.ui.oval(Palette.GOOD)
    private var lastRam = -1

    // Hero
    private val accentBar = roundRect(Palette.ROSE, 0f)
    private lateinit var greeting: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView

    // Satırlar
    private lateinit var appsCount: TextView
    private lateinit var appsRow: TrackRow

    private var all: List<AppEntry> = emptyList()
    private var apps: List<AppEntry> = emptyList()
    private var current: AppEntry? = null
    /** Son kullanılan uygulama ("Kaldığın yerden devam et"); liste değişince bir kez hesaplanır. */
    private var mostRecent: AppEntry? = null
    private var frameStats: FrameStats? = null
    private var resumed = false
    private var speedTest: SpeedTest? = null

    private val adbListener: () -> Unit = {
        if (panel.isOpen && panel.tag == "adb") showAdbSetup(null)
    }

    private val airPlayListener: () -> Unit = {
        updateAirPlayChip()
        if (panel.isOpen && panel.tag == "airplay") showAirPlay()
    }

    // ---------------------------------------------------------------- yaşam döngüsü

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        prefs = Prefs.of(this)
        buildUi()
        setContentView(root)
        // Arka plan görünümü her pikseli opak çiziyor: pencere ve kök dolgusu fazladan iki tam ekran
        // boyama demekti (zayıf GPU'da her karede ~4 milyon piksel boşa).
        window.setBackgroundDrawable(null)
        AirPlayService.start(this)
        AirPlayState.addListener(airPlayListener)
        updateAirPlayChip()
        if (prefs.perfOverlay) frameStats = FrameStats.attach(this, root)
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerSystemReceiver(packageReceiver, pkgFilter)
        Adb.addListener(adbListener)
        AppRepository.cached()?.let { showApps(it, null) }
        loadApps(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(packageReceiver) }
        Adb.removeListener(adbListener)
        AirPlayState.removeListener(airPlayListener)
        frameStats?.detach()
        speedTest?.cancel()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        registerSystemReceiver(timeReceiver, IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
        Bg.main.removeCallbacks(ramTick)
        Bg.main.post(ramTick)
        if (AppRepository.dirty) loadApps(current?.pkg) else refreshUsage()
        if (Weather.isStale()) refreshWeather()
        if (prefs.mode == Prefs.Mode.NEW && !HomeControl.isWatcherEnabled(this)) HomeControl.enableWatcher(this)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        runCatching { unregisterReceiver(timeReceiver) }
        Bg.main.removeCallbacks(ramTick)
    }

    /** HOME'a ana ekrandayken basılınca: paneli kapat, ilk uygulamaya dön. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (resumed) {
            panel.close()
            appsRow.reset()
            (appsRow.content.getChildAt(0))?.requestFocus()
        }
    }

    private fun registerSystemReceiver(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppRepository.invalidate()
            if (resumed) loadApps(current?.pkg)
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateClock()
            if (Weather.isStale()) refreshWeather()
        }
    }

    private val ramTick = object : Runnable {
        override fun run() {
            updateRam()
            if (resumed) Bg.main.postDelayed(this, 4000)
        }
    }

    // ---------------------------------------------------------------- arayüz

    private fun buildUi() {
        root = FrameLayout(this).apply { clipChildren = false }
        backdrop = HeroBackdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(SIDE), dp(22), 0, dp(34))
        }
        root.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))

        main.addView(buildTopBar(), lp(MATCH, dp(34)).margins(r = dp(SIDE)))
        main.addView(buildHero(), lp(MATCH, WRAP).margins(t = dp(50), r = dp(SIDE)))
        main.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val appsHeader = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        appsHeader.addView(text("Uygulamalar", 13f, Palette.TEXT_DIM, Fonts.bold))
        appsCount = text("", 12f, Palette.TEXT_FAINT, Fonts.medium)
        appsHeader.addView(appsCount, lp(WRAP, WRAP).margins(l = dp(8)))
        main.addView(appsHeader, lp(MATCH, WRAP).margins(b = dp(10)))

        appsRow = TrackRow(this).apply { setPadding(0, 0, dp(SIDE), 0) }
        main.addView(appsRow, lp(MATCH, WRAP))
        // Satırın altında: odaktaki kartın adı + kumanda ipuçları için boşluk
        main.addView(View(this), lp(MATCH, dp(40)))

        root.addView(buildHints(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(SIDE), dp(14))
        })

        panel = SidePanel(root, main)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        // Saat · tarih · hava durumu
        val clock = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(Palette.GLASS, dp(17f), Palette.GLASS_STROKE, dp(1))
            setPadding(dp(14), 0, dp(14), 0)
        }
        clockText = text("", 15f, Palette.TEXT, Fonts.bold)
        dateText = text("", 13f, Palette.TEXT_DIM, Fonts.medium)
        clock.addView(clockText)
        clock.addView(dateText, lp(WRAP, WRAP).margins(l = dp(8)))
        weatherGroup = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        weatherGroup.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) }, lp(dp(1), dp(14)).margins(l = dp(12), r = dp(12)))
        weatherIcon = GlyphDrawable(Glyph.CLOUD, Palette.TEXT)
        weatherGroup.addView(ImageView(this).apply { setImageDrawable(weatherIcon) }, lp(dp(16), dp(16)).margins(r = dp(6)))
        weatherText = text("", 13f, Palette.TEXT_DIM, Fonts.medium)
        weatherGroup.addView(weatherText)
        clock.addView(weatherGroup)
        bar.addView(clock, lp(WRAP, MATCH))

        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        airPlayPill = Pill(this, Glyph.CAST, "AirPlay").apply { setOnClickListener { showAirPlay() } }
        bar.addView(airPlayPill, lp(WRAP, MATCH).margins(r = dp(10)))

        val speed = Pill(this, Glyph.GAUGE, "Hız testi").apply { setOnClickListener { showSpeedTest() } }
        bar.addView(speed, lp(WRAP, MATCH).margins(r = dp(10)))

        // RAM göstergesi + Boşalt
        val ram = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(Palette.GLASS, dp(17f), Palette.GLASS_STROKE, dp(1))
            setPadding(dp(12), 0, 0, 0)
            clipChildren = false
        }
        ram.addView(ImageView(this).apply { setImageDrawable(GlyphDrawable(Glyph.CHIP, Palette.TEXT_DIM)) }, lp(dp(15), dp(15)))
        // Sabit genişlik: değer değişince sadece bu metin yeniden çizilir, tüm ekran yeniden yerleşmez.
        ramText = text("%--", 13f, Palette.TEXT, Fonts.bold).apply { gravity = Gravity.CENTER }
        ram.addView(ramText, lp(dp(34), WRAP).margins(l = dp(4)))
        ram.addView(View(this).apply { background = ramDot }, lp(dp(7), dp(7)).margins(l = dp(3), r = dp(12)))
        bar.addView(ram, lp(WRAP, MATCH).margins(r = dp(10)))

        val settings = Pill(this, Glyph.GEAR, null).apply { setOnClickListener { showSettings() } }
        bar.addView(settings, lp(dp(34), MATCH))
        return bar
    }

    private fun buildHero(): View {
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val greetRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        accentBar.cornerRadius = dp(1f)
        greetRow.addView(View(this).apply { background = accentBar }, lp(dp(14), dp(2)).margins(r = dp(8)))
        greeting = text("", 13f, Palette.TEXT_DIM, Fonts.medium)
        greetRow.addView(greeting)
        hero.addView(greetRow)

        heroTitle = text("Sade Ana Ekran", 54f, Palette.TEXT, Fonts.black).apply {
            letterSpacing = -0.02f
            setShadowLayer(dp(12f), 0f, dp(2f), 0x66000000)
        }
        hero.addView(heroTitle, lp(dp(560), WRAP).margins(t = dp(6)))
        heroSubtitle = text("", 15f, Palette.TEXT_DIM, Fonts.medium)
        hero.addView(heroSubtitle, lp(dp(560), WRAP).margins(t = dp(8)))

        return hero
    }

    private fun buildHints(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0.85f }
        row.addView(keyBadge("◀ ▶"))
        row.addView(text("gez", 11f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(l = dp(6), r = dp(14)))
        row.addView(keyBadge("OK"))
        row.addView(text("aç · basılı tut: seçenekler", 11f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(l = dp(6)))
        return row
    }

    // ---------------------------------------------------------------- uygulamalar

    private fun loadApps(focusPkg: String?) {
        Bg.run {
            val list = AppRepository.load(this)
            Bg.ui { if (!isDestroyed) showApps(list, focusPkg) }
        }
    }

    private fun refreshUsage() {
        Bg.run {
            val changed = AppRepository.applyUsage(this)
            Bg.ui {
                if (isDestroyed) return@ui
                if (changed && prefs.sort == Prefs.Sort.RECENT && !appsRow.hasFocus()) showApps(all, null)
                else {
                    if (changed) mostRecent = apps.filter { it.pkg != packageName }.maxByOrNull { it.lastUsed }?.takeIf { it.lastUsed > 0 }
                    current?.let { updateHero(it) }
                }
            }
        }
    }

    private fun showApps(list: List<AppEntry>, focusPkg: String?) {
        all = list
        apps = AppRepository.arrange(this, list)
        mostRecent = apps.filter { it.pkg != packageName }.maxByOrNull { it.lastUsed }?.takeIf { it.lastUsed > 0 }
        appsCount.text = apps.size.toString()

        val hadFocus = appsRow.hasFocus() || root.findFocus() == null
        val banner = prefs.cardStyle == Prefs.CardStyle.BANNER
        appsRow.content.removeAllViews()
        appsRow.reset()
        var target: AppTile? = null
        for (e in apps) {
            val tile = AppTile(this, e, banner)
            tile.setOnClickListener { launch(e, tile.card) }
            tile.setOnLongClickListener { showAppMenu(e); true }
            tile.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    showAppMenu(e); true
                } else false
            }
            tile.onFocusChangeListener = View.OnFocusChangeListener { _, has -> if (has) onAppFocused(e) }
            appsRow.content.addView(tile, lp(WRAP, WRAP).margins(r = dp(14)))
            if (e.pkg == focusPkg) target = tile
        }
        val first = target ?: appsRow.content.getChildAt(0) as? AppTile
        if (first != null) {
            appsRow.remember(first)
            if (hadFocus && !panel.isOpen) first.requestFocus()
            if (current == null || target != null || !hadFocus) onAppFocused(first.entry)
        }
    }

    private fun onAppFocused(e: AppEntry) {
        val changed = current?.pkg != e.pkg
        current = e
        updateHero(e)
        if (!changed && backdropPkg == e.pkg) return
        Bg.main.removeCallbacks(backdropUpdate)
        Bg.main.postDelayed(backdropUpdate, 140)
    }

    private var backdropPkg: String? = null
    private val backdropUpdate = Runnable {
        val e = current ?: return@Runnable
        val dm = resources.displayMetrics
        val size = (dm.heightPixels * 0.36f).toInt().coerceAtLeast(64)
        Bg.run {
            // Arka plan ve logo arka planda hazırlanır; arayüz iş parçacığı sadece hazır bitmap'leri çizer.
            val baked = HeroBackdrop.bake(e.accent, dm.density, dm.widthPixels)
            val logo = AppRepository.heroLogo(this, e, size)
            Bg.ui {
                if (current?.pkg != e.pkg) return@ui
                backdropPkg = e.pkg
                backdrop.set(baked, logo)
            }
        }
    }

    /**
     * Odak her değiştiğinde çalışır: burada bellek ayırma ve yeniden yerleşim (requestLayout) yok.
     * Başlık ve alt başlığın genişliği sabit olduğu için metin değişimi sadece o satırı yeniden çizer.
     */
    private fun updateHero(e: AppEntry) {
        setIfChanged(greeting, greetingText())
        accentBar.setColor(e.accent)
        if (heroTitle.text != e.label) {
            heroTitle.text = e.label
            heroTitle.alpha = 0.4f
            heroTitle.translationX = dp(-6f)
            // Gölgeli metnin saydamlık animasyonu donanım katmanında: her karede yeniden çizim yok.
            heroTitle.animate().alpha(1f).translationX(0f).setDuration(200).withLayer().start()
        }
        val mostRecent = mostRecent
        setIfChanged(heroSubtitle, when {
            e.pkg == packageName -> "Güncel siteler · kumandayla fare imleci"
            e == mostRecent -> "Kaldığın yerden devam et"
            e.lastUsed > 0 -> "Son açılış: ${relative(e.lastUsed)}"
            else -> AppRepository.categoryLabel(e.category) ?: if (e.isLeanback) "TV uygulaması" else "Uygulama"
        })
    }

    /** Aynı metni yeniden atamak bile TextView'i yeniden ölçtürür; sadece değişince ata. */
    private fun setIfChanged(v: TextView, value: CharSequence) {
        if (!android.text.TextUtils.equals(v.text, value)) v.text = value
    }

    private fun greetingText(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Günaydın"
        in 12..17 -> "İyi günler"
        in 18..22 -> "İyi akşamlar"
        else -> "İyi geceler"
    }

    private fun relative(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 2 -> "az önce"
            m < 60 -> "$m dakika önce"
            m < 24 * 60 -> "${m / 60} saat önce"
            m < 48 * 60 -> "dün"
            else -> "${m / (24 * 60)} gün önce"
        }
    }

    private fun launch(e: AppEntry, from: View) {
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            startActivity(AppRepository.launchIntent(this, e), opts)
        } catch (t: Exception) {
            toast("${e.label} açılamadı")
            return
        }
        scheduleSmoothTrim(e.pkg)
    }

    /**
     * Akıcı mod: açılan uygulama ayağa kalktıktan sonra (açılışı yavaşlatmamak için 4 sn bekleyip)
     * arka planda kalan diğer uygulamaları kapatır. RAM ve CPU'nun tamamı öndeki uygulamaya kalır;
     * YouTube, TV+, Apple TV gibi uygulamalarda bellek baskısından doğan takılmalar azalır.
     */
    private fun scheduleSmoothTrim(pkg: String) {
        if (!prefs.smoothMode || !Adb.isUsable(this)) return
        val apps = all
        Bg.main.postDelayed({ Cleaner.run(applicationContext, apps, pkg, done = {}, failed = {}) }, 4000)
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Exception) {
            toast("Bu ayar bu cihazda açılamıyor")
        }
    }

    private fun openAppInfo(e: AppEntry) =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- üst çubuk durumu

    private fun updateClock() {
        val now = Date()
        clockText.text = SimpleDateFormat("HH:mm", tr).format(now)
        dateText.text = SimpleDateFormat("EEE d MMM", tr).format(now)
        greeting.text = greetingText()
    }

    private fun updateRam() {
        val p = Memory.usedPercent(this)
        if (p == lastRam) return
        lastRam = p
        ramText.text = "%$p"
        ramDot.setColor(
            when {
                p < 70 -> Palette.GOOD
                p < 85 -> Palette.WARN
                else -> Palette.BAD
            }
        )
    }

    private fun updateAirPlayChip() {
        val st = AirPlayState
        airPlayPill.setText(st.client?.let { "AirPlay · $it" } ?: "AirPlay")
        airPlayPill.setDot(
            when {
                !st.running -> 0
                st.mirroring || st.audio -> Palette.ROSE
                else -> Palette.GOOD
            }
        )
    }

    private fun refreshWeather() {
        Bg.run {
            val w = runCatching { Weather.refresh(this) }.getOrNull() ?: return@run
            Bg.ui {
                weatherGroup.visibility = View.VISIBLE
                weatherIcon = GlyphDrawable(w.glyph, Palette.TEXT)
                (weatherGroup.getChildAt(1) as ImageView).setImageDrawable(weatherIcon)
                weatherText.text = "${Weather.format(w)} · ${w.city}"
            }
        }
    }

    // ---------------------------------------------------------------- paneller

    private fun showAppMenu(e: AppEntry) {
        val custom = prefs.sort == Prefs.Sort.CUSTOM
        panel.show("app:${e.pkg}") {
            title(e.label, e.pkg)
            row("Aç", glyph = Glyph.OPEN) { panel.close(); launch(e, appsRow) }
            if (custom) {
                row("Başa taşı", glyph = Glyph.ARROW_FIRST) { move(e, Int.MIN_VALUE) }
                row("Sola taşı", glyph = Glyph.ARROW_LEFT) { move(e, -1) }
                row("Sağa taşı", glyph = Glyph.ARROW_RIGHT) { move(e, +1) }
            }
            row("Gizle", "Ayarlar → Gizli uygulamalar'dan geri getirilir", Glyph.HIDE) {
                panel.close()
                prefs.hidden = prefs.hidden + e.pkg
                val idx = apps.indexOf(e)
                val next = apps.getOrNull(idx + 1) ?: apps.getOrNull(idx - 1)
                showApps(all, next?.pkg)
            }
            if (e.pkg != packageName) {
                row("Zorla durdur", "Belleği hemen geri verir (ADB)", Glyph.STOP) {
                    if (!Adb.isUsable(this@LauncherActivity)) {
                        showAdbSetup(null)
                    } else {
                        Cleaner.forceStop(this@LauncherActivity, e.pkg) { ok ->
                            toast(if (ok) "${e.label} durduruldu" else "Durdurulamadı")
                            updateRam()
                        }
                        panel.close()
                    }
                }
            }
            row("Uygulama bilgisi", glyph = Glyph.INFO) { panel.close(); openAppInfo(e) }
            if (!e.isSystem && e.pkg != packageName) {
                row("Kaldır", glyph = Glyph.TRASH, danger = true) {
                    panel.close()
                    start(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun move(e: AppEntry, delta: Int) {
        val list = apps.map { it.pkg }.toMutableList()
        val i = list.indexOf(e.pkg)
        if (i < 0) return
        val j = if (delta == Int.MIN_VALUE) 0 else (i + delta).coerceIn(0, list.size - 1)
        list.removeAt(i)
        list.add(j, e.pkg)
        // Gizli uygulamaların eski sıradaki yeri korunur.
        val hidden = prefs.order.filter { it in prefs.hidden }
        prefs.order = list + hidden
        panel.close()
        showApps(all, e.pkg)
    }

    private fun showSettings(focusIndex: Int = 0) {
        val ctx = this
        val watcherOn = HomeControl.isWatcherEnabled(ctx)
        val newMode = prefs.mode == Prefs.Mode.NEW
        panel.show("settings", focusIndex = focusIndex) {
            title("Ayarlar", "Sade Ana Ekran ${appVersion()}")
            header("Arayüz")
            if (newMode) {
                row("Bu ana ekran", "HOME tuşu buraya gelir", Glyph.HOME, checked = true) {}
                row("Google TV arayüzüne dön", "Geri gelmek için: Uygulamalar → Sade Ana Ekran → ⚙", Glyph.ARROW_LEFT) {
                    prefs.mode = Prefs.Mode.STOCK
                    panel.close()
                    HomeControl.stockHomeIntent(ctx)?.let { start(it) }
                }
            } else {
                row("Bu ana ekranı kullan", "Şu an Google TV arayüzü kullanılıyor", Glyph.HOME) {
                    prefs.mode = Prefs.Mode.NEW
                    if (!HomeControl.isWatcherEnabled(ctx)) HomeControl.enableWatcher(ctx)
                    showSettings(1)
                }
            }
            row("Kart görünümü", value = if (prefs.cardStyle == Prefs.CardStyle.COLOR) "Renkli" else "Banner", glyph = Glyph.GRID) {
                prefs.cardStyle = if (prefs.cardStyle == Prefs.CardStyle.COLOR) Prefs.CardStyle.BANNER else Prefs.CardStyle.COLOR
                AppRepository.invalidate()
                loadApps(current?.pkg)
                showSettings(if (newMode) 2 else 1)
            }
            row("Sıralama", value = if (prefs.sort == Prefs.Sort.CUSTOM) "Özel" else "Son kullanılan", glyph = Glyph.CLOCK) {
                if (prefs.sort == Prefs.Sort.CUSTOM && !AppRepository.hasUsageAccess(ctx)) {
                    toast("Son kullanılana göre sıralama için önce ADB kurulumu gerekli")
                    showAdbSetup(null)
                    return@row
                }
                prefs.sort = if (prefs.sort == Prefs.Sort.CUSTOM) Prefs.Sort.RECENT else Prefs.Sort.CUSTOM
                showApps(all, current?.pkg)
                showSettings(if (newMode) 3 else 2)
            }

            header("Sistem erişimi")
            row(
                "HOME tuşu yönlendirmesi",
                if (watcherOn) "Google TV'nin HOME kilidi aşılıyor" else "Kapalı: HOME, Google TV'ye gider",
                Glyph.HOME, value = if (watcherOn) "Açık" else "Kapalı",
            ) {
                if (!watcherOn && HomeControl.enableWatcher(ctx)) {
                    toast("HOME yönlendirmesi açıldı"); showSettings(focusIndex)
                } else {
                    start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            row(
                "ADB (Boşalt, zorla durdur)",
                when {
                    Adb.status == Adb.Status.READY -> "Bağlı"
                    prefs.adbPaired -> "Kurulu, gerektiğinde bağlanır"
                    else -> "Kurulum gerekli (bir kez)"
                },
                Glyph.CODE,
                checked = Adb.isUsable(ctx),
            ) { showAdbSetup(null) }
            row("TV'yi hızlandır", "Gereksiz ön yüklü uygulamaları kapat", Glyph.BOLT) { showDebloat() }

            header("AirPlay ve akıcılık")
            row("AirPlay alıcısı", "iPhone, iPad ve Mac'ten ekran yansıtma", Glyph.CAST,
                value = if (prefs.airPlayEnabled) "Açık" else "Kapalı") { showAirPlay() }
            row("Akıcılık ayarları", "Akıcı mod, uygulama optimizasyonu, kare göstergesi", Glyph.GAUGE) { showSmoothness() }

            header("Hava durumu")
            row("Konum", value = prefs.weatherCity ?: "Otomatik", glyph = Glyph.SUN) { showWeather() }

            header("Uygulamalar")
            val hidden = prefs.hidden
            row("Gizli uygulamalar", value = hidden.size.toString(), glyph = Glyph.HIDE) { showHidden() }
            row("Sıralamayı sıfırla", "Alfabetik (A–Z)", Glyph.REFRESH) {
                prefs.order = emptyList()
                prefs.sort = Prefs.Sort.CUSTOM
                showApps(all, current?.pkg)
                toast("Sıralama sıfırlandı")
            }

            header("Sistem")
            row("Android ayarları", glyph = Glyph.GEAR) {
                start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            row("Tarayıcı", glyph = Glyph.GLOBE) {
                panel.close()
                start(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun showAirPlay(focusIndex: Int = 0) {
        val ctx = this
        val st = AirPlayState
        val on = prefs.airPlayEnabled
        panel.show("airplay", focusIndex = focusIndex) {
            title(
                "AirPlay",
                when {
                    !on -> "Kapalı"
                    st.mirroring -> "${st.client ?: "Bir cihaz"} ekranını yansıtıyor"
                    st.audio -> "${st.client ?: "Bir cihaz"} ses çalıyor"
                    st.running -> "Hazır · \"${AirPlayService.displayName(ctx)}\" olarak görünüyor"
                    else -> "Başlatılıyor…"
                },
            )
            row("AirPlay alıcısı", if (on) "Açık: TV, Apple cihazlarında görünüyor" else "Kapalı", Glyph.CAST,
                value = if (on) "Açık" else "Kapalı") {
                prefs.airPlayEnabled = !on
                if (prefs.airPlayEnabled) AirPlayService.start(ctx) else AirPlayService.stop(ctx)
                showAirPlay(0)
            }
            if (st.mirroring || st.audio) {
                row("Bağlantıyı kes", st.client, Glyph.STOP, danger = true) { AirPlayService.instance?.disconnect() }
            }
            row("Görünen ad", value = AirPlayService.displayName(ctx), glyph = Glyph.DISPLAY) { askAirPlayName() }
            paragraph(
                "iPhone / iPad: Denetim Merkezi → Ekran Yansıtma → \"${AirPlayService.displayName(ctx)}\".\n" +
                    "Sadece ses: müzik çalarken AirPlay simgesi → TV'yi seç.\n" +
                    "Mac: menü çubuğundaki Denetim Merkezi → Ekran Yansıtma.\n\n" +
                    "TV ve Apple cihazı aynı ağda olmalı. Geri tuşu yansıtmayı bitirir."
            )
        }
    }

    private fun askAirPlayName() {
        val input = EditText(this).apply {
            setText(AirPlayService.displayName(this@LauncherActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        val save = {
            val name = input.text.toString().trim().take(40)
            prefs.airPlayName = name.ifEmpty { null }
            if (prefs.airPlayEnabled) AirPlayService.restart(this)
            showAirPlay(2)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("AirPlay'de görünen ad")
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setPositiveButton("Kaydet") { _, _ -> save() }
            .setNegativeButton("Vazgeç", null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> dialog.dismiss(); save(); true }
        dialog.show()
        input.requestFocus()
    }

    private fun showSmoothness(focusIndex: Int = 0, progress: String? = null) {
        val ctx = this
        val adb = Adb.isUsable(ctx)
        panel.show("smooth", focusIndex = focusIndex) {
            title("Akıcılık", "YouTube, TV+, Apple TV ve diğer uygulamaların takılmadan çalışması için")
            row(
                "Akıcı mod",
                "Açtığın uygulamaya tüm RAM'i bırakır: açıldıktan birkaç saniye sonra arka plandakiler kapanır",
                Glyph.BOLT, value = if (prefs.smoothMode) "Açık" else "Kapalı",
            ) {
                if (!adb) { showAdbSetup { showSmoothness(0) }; return@row }
                prefs.smoothMode = !prefs.smoothMode
                showSmoothness(0)
            }
            row(
                "Uygulamaları optimize et",
                progress ?: "Uygulamalar önceden derlenir: daha hızlı açılış, kaydırmada daha az takılma (birkaç dakika)",
                Glyph.GAUGE,
            ) {
                if (!adb) { showAdbSetup { showSmoothness(1) }; return@row }
                if (optimizing) return@row
                optimizing = true
                val pkgs = all.filter { it.pkg != packageName }.map { it.pkg }
                toast("Optimizasyon başladı; bu sırada TV'yi kullanabilirsin")
                Debloat.optimize(ctx, pkgs, progress = { i, n ->
                    if (panel.tag == "smooth") showSmoothness(1, "Optimize ediliyor: $i / $n")
                }, done = { ok ->
                    optimizing = false
                    toast(if (ok == null) "ADB hatası" else "$ok uygulama optimize edildi")
                    if (panel.tag == "smooth") showSmoothness(1)
                })
            }
            row("TV'yi hızlandır", "Gereksiz ön yüklü uygulamaları kapat, animasyonları hızlandır", Glyph.CHIP) { showDebloat() }
            row(
                "Performans göstergesi",
                "Köşede kare sayısı ve takılma oranı (akıcılığı ölçmek için)",
                Glyph.DISPLAY, value = if (prefs.perfOverlay) "Açık" else "Kapalı",
            ) {
                prefs.perfOverlay = !prefs.perfOverlay
                frameStats?.detach(); frameStats = null
                if (prefs.perfOverlay) frameStats = FrameStats.attach(ctx, root)
                showSmoothness(3)
            }
            paragraph(
                "İpucu: Ayarlar → Ekran ve Ses → Gelişmiş görüntü → \"İçerik kare hızını eşleştir\" açıkken " +
                    "TV+ ve Apple TV'deki filmler (24 kare/sn) sarsıntısız oynar."
            )
        }
    }

    private var optimizing = false

    /** Başka bir uygulamadayken ana ekranın tuttuğu önbellekler bırakılır; RAM öndeki uygulamaya kalır. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            HeroBackdrop.trim()
            AppRepository.trimHeroCache()
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun showAdbSetup(after: (() -> Unit)?) {
        val ctx = this
        val debugOn = Adb.isDebuggingEnabled(ctx)
        val status = Adb.status
        panel.show("adb", onClose = null) {
            title("ADB kurulumu", "Bellek boşaltma ve zorla durdurma için bir kez yapılır")
            when {
                !debugOn -> paragraph(
                    "1. Ayarlar → Sistem → Hakkında → \"Android TV OS derlemesi\" satırına 7 kez bas.\n" +
                        "2. Ayarlar → Sistem → Geliştirici seçenekleri → \"USB hata ayıklama\"yı aç.\n" +
                        "3. Buraya dönüp \"Bağlan\"a bas."
                )
                status == Adb.Status.PROMPTING -> paragraph(
                    "TV'de \"Hata ayıklamaya izin verilsin mi?\" penceresi açıldı.\n\n" +
                        "\"Bu bilgisayardan her zaman izin ver\" kutusunu işaretleyip \"İzin ver\"e bas."
                )
                status == Adb.Status.NOT_LISTENING -> paragraph(
                    "ADB ağ portu (5555) kapalı görünüyor. Kutu yeniden başlatılınca kapanmış olabilir.\n\n" +
                        "Bilgisayardan bir kez şunu çalıştır:\n  adb connect <TV-IP>:5555\n  adb tcpip 5555\n\n" +
                        "Ya da Geliştirici seçeneklerinde USB/ağ hata ayıklamayı kapatıp yeniden aç."
                )
                status == Adb.Status.READY -> paragraph("Hazır. Launcher artık uygulamaları zorla durdurabilir.")
                else -> paragraph(
                    "\"Bağlan\"a bastığında TV'de bir izin penceresi çıkacak. \"Bu bilgisayardan her zaman izin ver\"i " +
                        "işaretleyip onayla. Launcher bu cihazı bağlı bir bilgisayar gibi kullanır; " +
                        "hiçbir şey silinmez, sadece çalışan uygulamalar kapatılır." +
                        (Adb.lastError?.let { "\n\nSon hata: $it" } ?: "")
                )
            }
            if (status == Adb.Status.READY) {
                row("Devam", glyph = Glyph.CHECK) { panel.close(); after?.invoke() }
            } else if (debugOn) {
                row(if (status == Adb.Status.PROMPTING) "Bekleniyor…" else "Bağlan", glyph = Glyph.LINK) {
                    if (Adb.status == Adb.Status.PROMPTING) return@row
                    Adb.connect(ctx, allowPrompt = true) { ok ->
                        if (ok) {
                            toast("ADB hazır")
                            refreshUsage()
                            if (panel.tag == "adb") {
                                panel.close()
                                after?.invoke()
                            }
                        } else if (panel.tag == "adb") {
                            showAdbSetup(after)
                        }
                    }
                }
            }
            row("Geliştirici seçenekleri", glyph = Glyph.CODE) {
                start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun showSpeedTest() {
        speedTest?.cancel()
        lateinit var ping: TextView
        lateinit var down: TextView
        lateinit var up: TextView
        lateinit var state: TextView
        panel.show("speed", onClose = { speedTest?.cancel(); speedTest = null }) {
            title("Hız testi", "Cloudflare sunucularıyla ölçülür")
            ping = stat("Gecikme", "–", "ms")
            down = stat("İndirme", "–", "Mbps")
            up = stat("Yükleme", "–", "Mbps")
            state = paragraph("Ölçülüyor…")
            row("Yeniden test et", glyph = Glyph.REFRESH) { showSpeedTest() }
        }
        fun fmt(v: Double) = if (v >= 100) "%.0f".format(tr, v) else "%.1f".format(tr, v)
        val test = SpeedTest(object : SpeedTest.Listener {
            override fun onPing(ms: Int) { ping.text = ms.toString(); state.text = "İndirme ölçülüyor…" }
            override fun onDownload(mbps: Double, done: Boolean) {
                down.text = fmt(mbps); if (done) state.text = "Yükleme ölçülüyor…"
            }
            override fun onUpload(mbps: Double, done: Boolean) { up.text = fmt(mbps) }
            override fun onError(message: String) { state.text = "Hata: $message" }
            override fun onFinished() {
                val d = down.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                state.text = when {
                    d >= 25 -> "4K yayın için yeterli."
                    d >= 15 -> "Full HD için iyi, 4K'da zaman zaman düşebilir."
                    d >= 5 -> "HD yayın için yeterli."
                    else -> "Yayınlar takılabilir."
                }
            }
        })
        speedTest = test
        test.start()
    }

    private fun showWeather(results: List<Weather.Place>? = null) {
        panel.show("weather") {
            title("Hava durumu konumu")
            row("Otomatik (IP konumu)", glyph = Glyph.GLOBE, checked = prefs.weatherCity == null) {
                prefs.weatherCity = null
                Weather.invalidate(); refreshWeather()
                showWeather()
            }
            prefs.weatherCity?.let { row(it, "Seçili şehir", Glyph.SUN, checked = true) {} }
            row("Şehir ara…", glyph = Glyph.SEARCH) { askCity() }
            if (results != null) {
                header(if (results.isEmpty()) "Sonuç bulunamadı" else "Sonuçlar")
                for (p in results) {
                    row(p.name, p.detail) {
                        prefs.weatherCity = p.name
                        prefs.weatherLat = p.lat
                        prefs.weatherLon = p.lon
                        Weather.invalidate(); refreshWeather()
                        showWeather()
                    }
                }
            }
        }
    }

    private fun askCity() {
        val input = EditText(this).apply {
            hint = "Ör. İstanbul"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Şehir ara")
            .setView(FrameLayout(this).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(input)
            })
            .setPositiveButton("Ara") { _, _ -> searchCity(input.text.toString()) }
            .setNegativeButton("Vazgeç", null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> dialog.dismiss(); searchCity(input.text.toString()); true }
        dialog.show()
        input.requestFocus()
    }

    private fun searchCity(q: String) {
        if (q.isBlank()) return
        Bg.run {
            val res = runCatching { Weather.search(q.trim()) }.getOrNull()
            Bg.ui {
                if (res == null) toast("Arama yapılamadı (internet?)") else showWeather(res)
            }
        }
    }

    private fun showHidden() {
        val hidden = prefs.hidden
        val entries = all.filter { it.pkg in hidden }.sortedBy { it.label.lowercase() }
        panel.show("hidden") {
            title("Gizli uygulamalar", if (entries.isEmpty()) "Gizlenmiş uygulama yok" else "Seçtiğin uygulama geri gelir")
            for (e in entries) {
                row(e.label, e.pkg, Glyph.HIDE) {
                    prefs.hidden = prefs.hidden - e.pkg
                    showApps(all, current?.pkg)
                    showHidden()
                }
            }
        }
    }

    private fun showDebloat(focusIndex: Int = 0) {
        val ctx = this
        if (!Adb.isUsable(ctx)) {
            showAdbSetup { showDebloat() }
            return
        }
        val items = Debloat.installed(ctx)
        val anim = Debloat.animationScale(ctx)
        panel.show("debloat", focusIndex = focusIndex) {
            title(
                "TV'yi hızlandır",
                "Uygulamalar silinmez, sadece kapatılır (pm disable-user). Her biri tek tıkla geri açılır.",
            )
            var index = 0
            row("Önerilenlerin hepsini kapat", "RAM'i ve arka plan yükünü azaltır", Glyph.BOLT) {
                Debloat.disableRecommended(ctx) { n ->
                    toast(if (n == null) "ADB hatası" else if (n == 0) "Zaten hepsi kapalı" else "$n uygulama kapatıldı")
                    AppRepository.invalidate(); loadApps(current?.pkg)
                    showDebloat(0)
                }
            }
            index++
            val animIndex = index
            row(
                "Animasyonlar",
                "Menü ve açılış geçişleri; 0.5x belirgin şekilde daha seri hissettirir",
                Glyph.GAUGE,
                value = if (anim == 1f) "Normal" else "${anim}x",
            ) {
                val next = if (anim == 1f) 0.5f else 1f
                Debloat.setAnimationScale(ctx, next) { ok ->
                    if (!ok) toast("Değiştirilemedi")
                    showDebloat(animIndex)
                }
            }
            index++
            for ((group, rec) in listOf("Önerilen" to true, "Kullanmıyorsan" to false)) {
                val list = items.filter { it.first.recommended == rec }
                if (list.isEmpty()) continue
                header(group)
                for ((item, enabled) in list) {
                    val myIndex = index++
                    row(item.title, item.note, if (enabled) Glyph.STOP else Glyph.CHECK, value = if (enabled) "Açık" else "Kapatıldı") {
                        Debloat.setEnabled(ctx, item.pkg, !enabled) { ok ->
                            if (!ok) toast("İşlem yapılamadı")
                            AppRepository.invalidate(); loadApps(current?.pkg)
                            showDebloat(myIndex)
                        }
                    }
                }
            }
            if (items.any { !it.second }) {
                row("Hepsini geri aç", "Kapatılan her şeyi eski haline getirir", Glyph.REFRESH) {
                    Debloat.restoreAll(ctx) { ok ->
                        toast(if (ok) "Hepsi geri açıldı" else "ADB hatası")
                        AppRepository.invalidate(); loadApps(current?.pkg)
                        showDebloat(0)
                    }
                }
            }
            if (items.isEmpty()) paragraph("Bu cihazda kapatılacak bilinen gereksiz uygulama bulunamadı.")
        }
    }

    // ---------------------------------------------------------------- tuşlar

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (panel.handleKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Ana ekranda Geri bir şey yapmaz; Google TV modunda normal uygulama gibi kapanır.
            if (prefs.mode == Prefs.Mode.STOCK && event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val SIDE = 48
    }
}

