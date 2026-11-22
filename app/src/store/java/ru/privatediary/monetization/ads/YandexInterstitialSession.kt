package ru.privatediary.monetization.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import ru.privatediary.BuildConfig

/** Only instantiated in :ads after a separate, explicit network disclosure is accepted. */
internal class YandexInterstitialSession(
    private val activity: Activity,
    private val onFailure: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var loader: InterstitialAdLoader? = null
    private var ad: InterstitialAd? = null
    private var closed = false
    private val timeout = Runnable { fail() }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        handler.postDelayed(timeout, 45_000)
        try {
            // These options precede manual initialization and every ad request, in that order.
            YandexAds.setUserConsent(false)
            YandexAds.setLocationTracking(false)
            YandexAds.setAppAdAnalyticsReporting(false)
            YandexAds.initialize(activity.applicationContext) {
                activity.runOnUiThread {
                    if (!closed && !activity.isFinishing && !activity.isDestroyed) load()
                }
            }
        } catch (_: Exception) { fail() }
    }

    private fun load() {
        try {
            loader = InterstitialAdLoader(activity)
            // Placement ID only: no targeting, stable user ID, diary content, tags, mood or query.
            val request = AdRequest.Builder(BuildConfig.YAN_AD_UNIT_ID).build()
            loader?.loadAd(request, object : InterstitialAdLoadListener {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    if (closed || activity.isFinishing || activity.isDestroyed) return
                    ad = interstitialAd
                    interstitialAd.setAdEventListener(object : InterstitialAdEventListener {
                        override fun onAdShown() { handler.removeCallbacks(timeout) }
                        override fun onAdFailedToShow(adError: AdError) { fail() }
                        override fun onAdDismissed() { close(); onDismiss() }
                        override fun onAdClicked() = Unit
                        override fun onAdImpression(impressionData: ImpressionData?) = Unit
                    })
                    try { interstitialAd.show(activity) } catch (_: Exception) { fail() }
                }
                override fun onAdFailedToLoad(error: AdRequestError) { fail() }
            })
        } catch (_: Exception) { fail() }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        loader?.cancelLoading()
        loader = null
        ad?.setAdEventListener(null)
        ad = null
    }

    private fun fail() {
        if (closed) return
        close()
        onFailure()
    }
}
