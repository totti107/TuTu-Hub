package com.tutu.prayertv

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.min
import kotlin.random.Random

/**
 * The prayer board: place, countdown to the next prayer, the six times, and a Sahih Muslim hadith that changes
 * on its own. Used by the app screen and by the TV screen saver ([dream] = true moves it slightly every minute
 * so nothing burns into the panel).
 */
class BoardView(context: Context, private val dream: Boolean) : FrameLayout(context) {

    private val prefs = Prefs(context)
    private val repo = PrayerRepo(context, prefs)
    private val hadiths = Hadiths(context, prefs)
    private val main = Handler(Looper.getMainLooper())

    private val metrics = resources.displayMetrics
    private val s: Float = min(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f).coerceAtLeast(0.3f)
    private fun px(v: Number) = (v.toFloat() * s).toInt()

    private val teal = Color.parseColor("#6EE7D6")
    private val ink = Color.parseColor("#0B3B3A")
    private val white = Color.WHITE
    private val soft = Color.parseColor("#C9DCE4")
    private val dim = Color.parseColor("#8FB0BD")

    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val content = LinearLayout(context)
    private val place = text(26f, teal, medium).apply { letterSpacing = 0.2f }
    private val headline = text(76f, white, light)
    private val dateLine = text(30f, soft, regular)
    private val clock = text(96f, white, light).apply { gravity = Gravity.END }
    private val cards = ArrayList<Card>()
    private val hadithBox = LinearLayout(context)
    private val hadithHead = text(22f, teal, medium).apply { letterSpacing = 0.12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val hadithAr = text(42f, white, regular)
    private val hadithEn = text(29f, soft, light)
    private val footer = text(22f, dim, regular)

    private var today: Day? = null
    private var tomorrow: Day? = null
    private var loading = false
    private var lastLoadTry = 0L
    private var lastHadith = 0L
    private var lastShift = 0L
    private var running = false

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#0F5566"), Color.parseColor("#0C2E4A"), Color.parseColor("#0A1630")),
        )
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(110), px(70), px(110), px(44))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Header: place, countdown, dates | clock
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(place)
        left.addView(headline, lp(top = 4))
        left.addView(dateLine, lp(top = 2))
        header.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(clock)
        content.addView(header)

        // Six prayer cards
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf(
            Triple("الفجر", "Fajr", R.drawable.ic_fajr),
            Triple("الشروق", "Sunrise", R.drawable.ic_sunrise),
            Triple("الظهر", "Dhuhr", R.drawable.ic_dhuhr),
            Triple("العصر", "Asr", R.drawable.ic_asr),
            Triple("المغرب", "Maghrib", R.drawable.ic_maghrib),
            Triple("العشاء", "Isha", R.drawable.ic_isha),
        )
        names.forEachIndexed { i, (ar, en, icon) ->
            val c = Card(ar, en, icon)
            cards += c
            row.addView(c.view, LinearLayout.LayoutParams(0, px(310), 1f).apply { if (i > 0) leftMargin = px(24) })
        }
        content.addView(row, lp(top = 40))

        // Hadith
        hadithBox.orientation = LinearLayout.VERTICAL
        hadithBox.setPadding(px(44), px(28), px(44), px(28))
        hadithBox.background = rounded(Color.argb(46, 0, 0, 0), 28)
        hadithAr.apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.15f)
        }
        hadithEn.setLineSpacing(0f, 1.12f)
        hadithEn.gravity = Gravity.CENTER_VERTICAL
        hadithBox.addView(hadithHead)
        hadithBox.addView(hadithAr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f).apply { topMargin = px(6) })
        hadithBox.addView(hadithEn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.3f).apply { topMargin = px(8) })
        autoSize(hadithAr, 26f, 42f)
        autoSize(hadithEn, 20f, 29f)
        content.addView(hadithBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(32) })

        content.addView(footer, lp(top = 16))

        showHadith(hadiths.current(), animate = false)
        render()
    }

    // ---- lifecycle ----

    fun start() {
        if (running) return
        running = true
        lastHadith = System.currentTimeMillis()
        loadIfNeeded(force = true)
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    /** Settings changed: drop what we have and fetch again. */
    fun reload() {
        repo.clearCache()
        today = null; tomorrow = null
        render()
        loadIfNeeded(force = true)
    }

    fun nextHadith() { showHadith(hadiths.next(), animate = true); lastHadith = System.currentTimeMillis() }
    fun previousHadith() { showHadith(hadiths.previous(), animate = true); lastHadith = System.currentTimeMillis() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            loadIfNeeded(force = false)
            render()
            if (now - lastHadith >= prefs.hadithSeconds * 1000L) {
                lastHadith = now
                showHadith(hadiths.next(), animate = true)
            }
            if (dream && now - lastShift >= 60_000L) {
                lastShift = now
                content.animate().translationX(Random.nextInt(-16, 17) * s).translationY(Random.nextInt(-12, 13) * s)
                    .setDuration(4000).start()
            }
            main.postDelayed(this, 1000L - now % 1000L + 5)
        }
    }

    // ---- data ----

    private fun ymd(offsetDays: Int): Triple<Int, Int, Int> {
        val c = Calendar.getInstance(repo.tz())
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun loadIfNeeded(force: Boolean) {
        if (loading) return
        val (y, m, d) = ymd(0)
        val t = today
        val stale = t == null || t.y != y || t.m != m || t.d != d
        // Calculated offline? Try Aladhan again every 15 minutes.
        val retry = t != null && !t.online && System.currentTimeMillis() - lastLoadTry > 15 * 60_000L
        if (!force && !stale && !retry) return
        loading = true
        lastLoadTry = System.currentTimeMillis()
        val next = ymd(1)
        Thread {
            val a = try { repo.load(y, m, d) } catch (_: Throwable) { null }
            val b = try { repo.load(next.first, next.second, next.third) } catch (_: Throwable) { null }
            main.post {
                loading = false
                if (a != null) today = a
                if (b != null) tomorrow = b
                render()
            }
        }.start()
    }

    // ---- drawing ----

    private fun timeFormat(withSeconds: Boolean = false): SimpleDateFormat {
        val pattern = if (prefs.use24h) "HH:mm" else "h:mm"
        return SimpleDateFormat(if (withSeconds) "$pattern:ss" else pattern, PrayerRepo.LOCALE).apply { timeZone = repo.tz() }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val fmt = timeFormat()
        place.text = prefs.placeLabel.uppercase(PrayerRepo.LOCALE)
        clock.text = fmt.format(Date(now))

        val day = today
        val dateFmt = SimpleDateFormat("EEEE d MMMM", PrayerRepo.LOCALE).apply { timeZone = repo.tz() }
        val hijri = day?.hijri.orEmpty()
        dateLine.text = if (hijri.isBlank()) dateFmt.format(Date(now)) else "${dateFmt.format(Date(now))}  ·  $hijri"

        if (day == null) {
            headline.text = "Loading prayer times…"
            cards.forEach { it.set("--:--", false) }
            footer.text = footerText(null)
            return
        }

        // Next time to come: today's, or tomorrow's Fajr after Isha.
        var nextIdx = day.times.indexOfFirst { it > now }
        val nextAt: Long
        if (nextIdx >= 0) nextAt = day.times[nextIdx] else {
            nextIdx = 0
            nextAt = tomorrow?.times?.get(0) ?: (day.times[0] + 86_400_000L)
        }
        cards.forEachIndexed { i, c -> c.set(fmt.format(Date(day.times[i])), i == nextIdx) }

        // Just started (not sunrise)? Say so for 15 minutes.
        val started = day.times.indices.lastOrNull { i -> i != 1 && day.times[i] <= now && now - day.times[i] < 15 * 60_000L }
        headline.text = if (started != null) "It's ${cards[started].en} time" else "${cards[nextIdx].en} in ${countdown(nextAt - now)}"
        footer.text = footerText(day)
    }

    private fun countdown(ms: Long): String {
        val mins = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(0)
        if (mins < 60) return "$mins min"
        val h = mins / 60
        val m = mins % 60
        return if (m == 0) "$h h" else "$h h ${m} min"
    }

    private fun footerText(day: Day?): String {
        val method = methodById(prefs.method).short + if (prefs.school == 1) " · Hanafi" else ""
        val src = when {
            day == null -> "Getting times…"
            day.online -> "Aladhan · $method · updated ${timeFormat().format(Date(day.fetchedAt))}"
            else -> "Calculated on the TV (offline) · $method"
        }
        return if (dream) src else "$src     ◂ ▸ change hadith  ·  OK settings"
    }

    private fun showHadith(h: Hadith?, animate: Boolean) {
        val apply = {
            if (h == null) {
                hadithHead.text = "SAHIH MUSLIM"
                hadithAr.text = ""
                hadithEn.text = ""
            } else {
                hadithHead.text = "SAHIH MUSLIM ${h.number}  ·  ${h.book.uppercase(PrayerRepo.LOCALE)}"
                hadithAr.text = "«${h.arabic}»"
                hadithEn.text = h.english
            }
        }
        if (!animate) { apply(); return }
        hadithBox.animate().alpha(0f).setDuration(700).withEndAction {
            apply()
            hadithBox.animate().alpha(1f).setDuration(900).start()
        }.start()
    }

    // ---- small helpers ----

    private fun text(size: Float, color: Int, face: Typeface) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
        setTextColor(color)
        typeface = face
        includeFontPadding = true
    }

    private fun autoSize(tv: TextView, minSize: Float, maxSize: Float) {
        if (Build.VERSION.SDK_INT >= 26) {
            tv.setAutoSizeTextTypeUniformWithConfiguration(
                (minSize * s).toInt().coerceAtLeast(1), (maxSize * s).toInt().coerceAtLeast(2), 1, TypedValue.COMPLEX_UNIT_PX,
            )
        } else {
            tv.ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px(top) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radius).toFloat()
    }

    private inner class Card(ar: String, val en: String, icon: Int) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(8), px(20), px(8), px(20))
        }
        private val img = ImageView(context).apply { setImageResource(icon) }
        private val arText = text(36f, white, medium).apply { gravity = Gravity.CENTER }
        private val enText = text(27f, soft, regular).apply { gravity = Gravity.CENTER; text = en }
        private val time = text(64f, white, light).apply { gravity = Gravity.CENTER }
        private var hi: Boolean? = null

        init {
            arText.text = ar
            view.addView(img, LinearLayout.LayoutParams(px(66), px(66)))
            view.addView(arText, wrap(16))
            view.addView(enText, wrap(4))
            view.addView(time, wrap(14))
        }

        private fun wrap(top: Int) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(top); gravity = Gravity.CENTER_HORIZONTAL }

        fun set(value: String, highlighted: Boolean) {
            time.text = value
            if (hi == highlighted) return
            hi = highlighted
            view.background = rounded(if (highlighted) teal else Color.argb(36, 255, 255, 255), 30)
            val main = if (highlighted) ink else white
            img.setColorFilter(if (highlighted) ink else teal, PorterDuff.Mode.SRC_IN)
            arText.setTextColor(main)
            enText.setTextColor(if (highlighted) ink else soft)
            time.setTextColor(main)
            view.animate().scaleX(if (highlighted) 1.04f else 1f).scaleY(if (highlighted) 1.04f else 1f).setDuration(400).start()
        }
    }
}
