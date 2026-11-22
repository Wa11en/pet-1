package ru.privatediary.monetization.ads

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ru.privatediary.R
import java.util.Locale

/** A separate task/process. Nothing here reads the diary, its key, its graph, or the Pro license. */
class StoreAdActivity : Activity() {
    private var session: YandexInterstitialSession? = null
    private lateinit var content: LinearLayout
    private var foreground = false

    override fun attachBaseContext(newBase: Context) {
        // Read only the UI language, rather than initializing diary settings or the app graph.
        val tag = newBase.getSharedPreferences("diary_settings", Context.MODE_PRIVATE)
            .getString("language", "ru").takeIf { it == "en" } ?: "ru"
        val configuration = Configuration(newBase.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState != null || !AdsRuntime.claimActivity(intent)) {
            AdsRuntime.finishSession()
            return
        }
        showDisclosure()
    }

    override fun onStart() { super.onStart(); foreground = true }
    override fun onStop() { foreground = false; super.onStop() }

    private fun showDisclosure() {
        screen(getString(R.string.ads_privacy_title), getString(R.string.ads_privacy_body))
        button(getString(R.string.ads_continue)) { beginAd() }
        button(getString(R.string.ads_cancel)) { AdsRuntime.finishSession() }
    }

    private fun beginAd() {
        if (!foreground || !AdsRuntime.isConfigured()) {
            showFailure()
            return
        }
        screen(getString(R.string.ads_loading_title), getString(R.string.ads_loading_body))
        content.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        button(getString(R.string.ads_cancel)) { AdsRuntime.finishSession() }
        // This is the first construction/call of any SDK class. Declining the disclosure never gets here.
        session = YandexInterstitialSession(this, ::showFailure) { AdsRuntime.finishSession() }
            .also { it.start() }
    }

    private fun showFailure() {
        if (isFinishing || isDestroyed) return
        session?.close()
        session = null
        screen(getString(R.string.ads_unavailable_title), getString(R.string.ads_unavailable_body))
        button(getString(R.string.ads_close)) { AdsRuntime.finishSession() }
        // End immediately after the message: no SDK process remains alive while a user keeps reading.
        window.decorView.postDelayed({ AdsRuntime.finishSession() }, 3_000)
    }

    private fun screen(title: String, description: String) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = Color.rgb(if (dark) 23 else 248, if (dark) 28 else 249, if (dark) 28 else 245)
        val ink = if (dark) Color.rgb(232, 238, 234) else Color.rgb(35, 48, 43)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(44), dp(28), dp(28))
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.displayMetrics.heightPixels - dp(60)
            setBackgroundColor(background)
        }
        content.addView(TextView(this).apply {
            text = title
            textSize = 26f
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            text = description
            textSize = 17f
            setTextColor(ink)
            setLineSpacing(dp(5).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(20) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
    }

    private fun button(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(58, 103, 83)); cornerRadius = dp(18).toFloat() }
            setOnClickListener { view: View -> view.isEnabled = false; action() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })
    }

    @Deprecated("Ad task handles back by closing its entire session")
    override fun onBackPressed() { AdsRuntime.finishSession() }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
