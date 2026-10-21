package com.hizir.sadehome.airplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hizir.sadehome.data.Bg
import com.hizir.sadehome.ui.Fonts
import com.hizir.sadehome.ui.Glyph
import com.hizir.sadehome.ui.MATCH
import com.hizir.sadehome.ui.Palette
import com.hizir.sadehome.ui.Pill
import com.hizir.sadehome.ui.WRAP
import com.hizir.sadehome.ui.dp
import com.hizir.sadehome.ui.lp
import com.hizir.sadehome.ui.margins
import com.hizir.sadehome.ui.roundRect
import com.hizir.sadehome.ui.text
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Yeni bir AirPlay gönderenine izin isteği. Ağdaki herhangi bir cihaz TV'ye izinsiz görüntü/ses
 * açamasın diye ilk bağlantıda TV'de sorulur. Ağ iş parçacığı [ask] içinde cevabı bekler.
 * Varsayılan odak "Reddet"; cevap gelmezse süre dolunca reddedilir.
 */
object Consent {
    enum class Answer { DENY, ONCE, ALWAYS }

    private const val TIMEOUT_S = 30L
    private val lock = Any()
    @Volatile private var pending: Request? = null

    class Request(val name: String, val model: String, val canRemember: Boolean) {
        val latch = CountDownLatch(1)
        @Volatile var answer = Answer.DENY
        @Volatile var activity: ConsentActivity? = null

        fun answer(a: Answer) {
            answer = a
            latch.countDown()
        }
    }

    /** Ağ iş parçacığından çağrılır; kullanıcı cevap verene ya da süre dolana kadar bekler. */
    fun ask(ctx: Context, name: String, model: String, canRemember: Boolean): Answer {
        val req = synchronized(lock) {
            if (pending != null) return Answer.DENY // aynı anda tek istek; diğerleri beklemeden reddedilir
            Request(name, model, canRemember).also { pending = it }
        }
        try {
            val started = runCatching {
                ctx.startActivity(
                    Intent(ctx, ConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
                )
            }.isSuccess
            if (!started) return Answer.DENY
            val answered = req.latch.await(TIMEOUT_S, TimeUnit.SECONDS)
            return if (answered) req.answer else Answer.DENY
        } finally {
            synchronized(lock) { pending = null }
            req.activity?.let { a -> Bg.ui { a.finish() } }
        }
    }

    internal fun current(): Request? = pending
}

class ConsentActivity : Activity() {
    private var request: Consent.Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        val req = Consent.current()
        if (req == null) {
            finish()
            return
        }
        request = req
        req.activity = this
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.6f)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(Palette.PANEL, dp(22f), Palette.GLASS_STROKE, dp(1))
            setPadding(dp(28), dp(24), dp(28), dp(22))
        }
        card.addView(text("AirPlay bağlantı isteği", 20f, Palette.TEXT, Fonts.bold))
        val who = req.name.ifBlank { "Bir cihaz" } + if (req.model.isNotBlank()) " (${req.model})" else ""
        // Ad ve model ağdan geliyor: düz metin olarak gösterilir, biçimlendirme yorumlanmaz.
        card.addView(
            text("$who bu TV'ye ekran ya da ses göndermek istiyor.", 14f, Palette.TEXT_DIM, Fonts.medium)
                .apply { maxLines = 3; setLineSpacing(0f, 1.15f) },
            lp(dp(520), WRAP).margins(t = dp(10)),
        )
        card.addView(
            text("Tanımıyorsan reddet. 30 saniye içinde seçilmezse reddedilir.", 12f, Palette.TEXT_FAINT, Fonts.regular)
                .apply { maxLines = 2 },
            lp(dp(520), WRAP).margins(t = dp(6)),
        )

        val buttons = LinearLayout(this).apply { gravity = Gravity.END; clipChildren = false }
        val deny = Pill(this, Glyph.STOP, "Reddet", heightDp = 34).apply { setOnClickListener { reply(Consent.Answer.DENY) } }
        val once = Pill(this, Glyph.CHECK, "Bu sefer izin ver", heightDp = 34).apply { setOnClickListener { reply(Consent.Answer.ONCE) } }
        buttons.addView(deny, lp(WRAP, dp(34)).margins(r = dp(10)))
        buttons.addView(once, lp(WRAP, dp(34)))
        if (req.canRemember) {
            val always = Pill(this, Glyph.CHECK, "Her zaman izin ver", heightDp = 34).apply {
                setOnClickListener { reply(Consent.Answer.ALWAYS) }
            }
            buttons.addView(always, lp(WRAP, dp(34)).margins(l = dp(10)))
        }
        card.addView(buttons, lp(MATCH, WRAP).margins(t = dp(20)))

        val root = FrameLayout(this)
        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        setContentView(root)
        deny.requestFocus()
    }

    private fun reply(a: Consent.Answer) {
        request?.answer(a)
        request = null
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            reply(Consent.Answer.DENY)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // Pencere başka bir şekilde kapandıysa (HOME vb.) cevapsız kalmasın: reddet.
        request?.answer(Consent.Answer.DENY)
        request = null
        super.onDestroy()
    }
}
