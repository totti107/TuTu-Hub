package com.tutu.prayertv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var board: BoardView
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        board = BoardView(this, dream = false)
        board.setOnClickListener { showSettings() }
        setContentView(board)
    }

    override fun onResume() { super.onResume(); board.start() }
    override fun onPause() { board.stop(); super.onPause() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_A -> { showSettings(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { board.nextHadith(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { board.previousHadith(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showSettings() {
        val m = methodById(prefs.method)
        val items = arrayOf(
            "Set as TV screen saver",
            "Location: ${prefs.placeLabel}${if (prefs.country.isNotBlank()) ", " + prefs.country else ""}",
            "Calculation: ${m.name}",
            "Asr: " + if (prefs.school == 1) "Hanafi" else "Standard (Shafi'i, Maliki, Hanbali)",
            "New hadith every: ${intervalLabel(prefs.hadithSeconds)}",
            "Clock: " + if (prefs.use24h) "24-hour" else "12-hour",
        )
        dialog().setTitle("Prayer & Hadith").setItems(items) { _, which ->
            when (which) {
                0 -> openScreenSaverSettings()
                1 -> editLocation()
                2 -> pickMethod()
                3 -> pickSchool()
                4 -> pickInterval()
                5 -> { prefs.use24h = !prefs.use24h; showSettings() }
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun openScreenSaverSettings() {
        val tip = "Choose \"Prayer & Hadith\" under Screen saver"
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "Open Settings → Device Preferences → Screen saver. $tip", Toast.LENGTH_LONG).show()
    }

    private val intervals = intArrayOf(30, 60, 120, 300, 600)
    private fun intervalLabel(sec: Int) = when {
        sec < 60 -> "$sec seconds"
        sec == 60 -> "1 minute"
        else -> "${sec / 60} minutes"
    }

    private fun pickInterval() {
        val labels = intervals.map { intervalLabel(it) }.toTypedArray()
        dialog().setTitle("New hadith every")
            .setSingleChoiceItems(labels, intervals.indexOf(prefs.hadithSeconds)) { d, i ->
                prefs.hadithSeconds = intervals[i]; d.dismiss(); showSettings()
            }.show()
    }

    private fun pickMethod() {
        val labels = METHODS.map { it.name }.toTypedArray()
        dialog().setTitle("Calculation method")
            .setSingleChoiceItems(labels, METHODS.indexOfFirst { it.id == prefs.method }) { d, i ->
                prefs.method = METHODS[i].id; d.dismiss(); board.reload(); showSettings()
            }.show()
    }

    private fun pickSchool() {
        val labels = arrayOf("Standard (Shafi'i, Maliki, Hanbali)", "Hanafi")
        dialog().setTitle("Asr")
            .setSingleChoiceItems(labels, prefs.school) { d, i ->
                prefs.school = i; d.dismiss(); board.reload(); showSettings()
            }.show()
    }

    private fun editLocation() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label })
            val e = EditText(this).apply {
                setText(value); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            box.addView(e)
            return e
        }
        val city = field("City", prefs.city)
        val state = field("Province / state (optional)", prefs.state)
        val country = field("Country", prefs.country)
        dialog().setTitle("Location").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val c = city.text.toString().trim(); val s = state.text.toString().trim(); val k = country.text.toString().trim()
                if (c.isEmpty() || k.isEmpty()) { Toast.makeText(this, "Enter a city and a country", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                Toast.makeText(this, "Looking up $c…", Toast.LENGTH_SHORT).show()
                Thread {
                    val place = try { PrayerRepo(this, prefs).resolve(c, s, k) } catch (_: Exception) { null }
                    main.post {
                        if (place == null) {
                            Toast.makeText(this, "Couldn't find $c, $k. Check the spelling and the internet connection.", Toast.LENGTH_LONG).show()
                        } else {
                            prefs.city = c; prefs.state = s; prefs.country = k
                            prefs.lat = place.lat; prefs.lng = place.lng; prefs.tz = place.tz
                            board.reload()
                            Toast.makeText(this, "Location set: $c (${"%.3f".format(place.lat)}, ${"%.3f".format(place.lng)})", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
