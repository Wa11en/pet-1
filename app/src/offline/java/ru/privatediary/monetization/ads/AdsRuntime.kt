package ru.privatediary.monetization.ads

import android.app.Activity
import android.app.Application
import android.content.Context

/** The offline distribution has no advertising dependency, permission or network entry point. */
object AdsRuntime {
    fun enterProcess(context: Context): Boolean = false
    fun onApplicationCreate(application: Application): Boolean = false
    fun isConfigured(): Boolean = false
    fun show(activity: Activity): Boolean = false
}
