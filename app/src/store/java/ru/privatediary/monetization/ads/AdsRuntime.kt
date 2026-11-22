package ru.privatediary.monetization.ads

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import ru.privatediary.BuildConfig
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import kotlin.system.exitProcess

/** SDK-free entry point. Do not add ad SDK classes to this object. */
object AdsRuntime {
    internal const val EXTRA_ENTRY_TOKEN = "ru.privatediary.ads.ENTRY_TOKEN"
    private var adProcess = false
    private var authorizedEntry: String? = null
    private var lifecycle: AdProcessLifetime? = null
    private var processTickets: AdEntryTicketStore? = null

    /** Call immediately after super.attachBaseContext, before Android starts any providers. */
    fun enterProcess(context: Context): Boolean {
        val name = processName(context) ?: terminateProcess()
        adProcess = name == "${context.packageName}:ads"
        if (!adProcess) return false
        processTickets = tickets(context)
        authorizedEntry = runCatching {
            processTickets?.consume(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }.getOrNull()
        if (authorizedEntry == null) terminateProcess()
        return true
    }

    /** Return true means Application must skip the diary graph, reminders and other initializers. */
    fun onApplicationCreate(application: Application): Boolean {
        if (!adProcess) return false
        if (authorizedEntry == null) terminateProcess()
        lifecycle = AdProcessLifetime(application).also { it.install() }
        return true
    }

    fun isConfigured(): Boolean = BuildConfig.YAN_AD_UNIT_ID.matches(Regex("R-M-[0-9]+-[0-9]+"))

    /** Only an explicit user action should invoke this. No diary data is accepted or attached. */
    fun show(activity: Activity): Boolean {
        if (!isConfigured() || activity.isFinishing || activity.isDestroyed || adProcess) return false
        return runCatching {
            val token = tickets(activity).issue(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            try {
                activity.startActivity(Intent(activity, StoreAdActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_ENTRY_TOKEN, token)
                })
            } catch (failure: Exception) {
                tickets(activity).revoke()
                throw failure
            }
            true
        }.getOrDefault(false)
    }

    internal fun claimActivity(intent: Intent): Boolean {
        val token = intent.getStringExtra(EXTRA_ENTRY_TOKEN)
        intent.removeExtra(EXTRA_ENTRY_TOKEN)
        if (token == null || token != authorizedEntry) return false
        authorizedEntry = null
        return true
    }

    internal fun finishSession() { lifecycle?.finish() ?: terminateProcess() }

    private fun tickets(context: Context) = AdEntryTicketStore(File(context.noBackupFilesDir, "ad_entry"))

    private fun processName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        return runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrNull()
            ?: (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }

    internal fun terminateProcess(): Nothing {
        // Also revoke a second rapid tap that reached an already-running singleTask host.
        runCatching { processTickets?.revoke() }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}

/** Tracks our host AND the SDK's fullscreen Activity: the host stopping alone must not end an ad. */
private class AdProcessLifetime(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val started = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val stopWhenHidden = Runnable { if (started.isEmpty()) AdsRuntime.terminateProcess() }
    private val deadline = Runnable { finish() }

    fun install() {
        application.registerActivityLifecycleCallbacks(this)
        // A grant cannot keep a service-only process alive, nor survive indefinitely in the foreground.
        handler.postDelayed(stopWhenHidden, 5_000)
        handler.postDelayed(deadline, 180_000)
    }

    fun finish() {
        handler.removeCallbacks(deadline)
        activities.toList().forEach { if (!it.isFinishing) it.finish() }
        if (started.isEmpty()) AdsRuntime.terminateProcess()
        // onActivityStopped will terminate the process once every Activity is hidden.
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activities.add(activity)
    }
    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        handler.removeCallbacks(stopWhenHidden)
    }
    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        if (started.isEmpty()) {
            // Allow the SDK Activity/configuration replacement to reach onStart during a handoff.
            handler.postDelayed(stopWhenHidden, 250)
        }
    }
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
