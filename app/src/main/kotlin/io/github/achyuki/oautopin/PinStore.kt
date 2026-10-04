package io.github.achyuki.oautopin

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Module-process access to the framework-owned RemotePreferences that [Hook]
 * reads inside `com.android.systemui`.
 *
 * Writes go through the libxposed framework service, which owns that store; the
 * module process only borrows it. No world-readable preference file is created,
 * and `MODE_WORLD_READABLE` is never requested, which is what keeps the module
 * out of the LSPosed deprecation list.
 *
 * Every call here can block for up to [SERVICE_TIMEOUT_MS] while the framework
 * service binds, so the UI must call it off the main thread.
 */
internal object PinStore {

    private const val TAG = "OAutoPIN"

    /** How long a caller waits for the framework service to bind. */
    private const val SERVICE_TIMEOUT_MS = 3_000L

    private val PIN_PATTERN = Regex("^\\d{4,8}$")

    private val lock = Any()

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var listenerRegistered = false

    /** Set when the framework service turns out to be unavailable. */
    @Volatile
    var lastError: String? = null
        private set

    // ------------------------------------------------------------------ service binding

    /**
     * Registers the framework service listener once per process.
     *
     * The binding is initiated by the framework when the module's own process
     * starts, so this only has to register early. `XposedServiceHelper` is a
     * static API and binds through the application context it already holds,
     * which is why no `Context` is needed here.
     */
    fun bind() {
        if (listenerRegistered) return
        synchronized(lock) {
            if (listenerRegistered) return
            try {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(bound: XposedService) {
                        synchronized(lock) {
                            service = bound
                            lastError = null
                            (lock as Object).notifyAll()
                        }
                        Log.i(
                            TAG,
                            "framework service bound: api=${bound.apiVersion} " +
                                "framework=${bound.frameworkName}/${bound.frameworkVersion}"
                        )
                    }

                    override fun onServiceDied(dead: XposedService) {
                        synchronized(lock) {
                            if (service === dead) service = null
                            (lock as Object).notifyAll()
                        }
                        Log.w(TAG, "framework service died")
                    }
                })
                listenerRegistered = true
            } catch (t: Throwable) {
                recordError("cannot register framework service listener", t)
            }
        }
    }

    /** @return the bound framework service, or `null` when it is unavailable. */
    private fun awaitService(): XposedService? {
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + SERVICE_TIMEOUT_MS
            var current = service
            while (current == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    (lock as Object).wait(remaining)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                current = service
            }
            if (current == null) {
                lastError = "framework service unavailable; open this module from LSPosed " +
                    "and make sure the module is enabled"
                return null
            }
            return current
        }
    }

    /**
     * Opens the RemotePreferences store after checking the advertised capability.
     *
     * @return the store, or `null` with [lastError] explaining why it is missing.
     */
    private fun remotePreferences(): SharedPreferences? {
        val current = awaitService() ?: return null
        return try {
            if (current.apiVersion < XposedService.API_101 ||
                (current.frameworkProperties and XposedService.PROP_CAP_REMOTE) == 0L
            ) {
                lastError = "framework does not support remote preferences " +
                    "(api=${current.apiVersion})"
                return null
            }
            current.getRemotePreferences(RemotePrefs.GROUP)
        } catch (t: Throwable) {
            recordError("getRemotePreferences(${RemotePrefs.GROUP}) failed", t)
            null
        }
    }

    // ------------------------------------------------------------------ PIN access

    /** @return the stored PIN, or `null` when nothing valid has been saved yet. */
    fun readPin(): String? {
        val preferences = remotePreferences() ?: return null
        return try {
            val pin = preferences.getString(RemotePrefs.KEY_PIN, null)
                ?.takeIf { PIN_PATTERN.matches(it) }
            if (pin != null) lastError = null
            pin
        } catch (t: Throwable) {
            recordError("reading stored PIN failed", t)
            null
        }
    }

    /**
     * Persists the PIN into framework-owned RemotePreferences.
     *
     * `commit()` is used instead of `apply()` so the UI can report the real
     * outcome and so the value is durable before SystemUI is restarted.
     *
     * @return true when the framework accepted and can read back the value.
     */
    fun writePin(pin: String): Boolean {
        if (!PIN_PATTERN.matches(pin)) {
            lastError = "PIN must be 4-8 digits"
            return false
        }
        val preferences = remotePreferences() ?: return false
        return try {
            val committed = preferences.edit().putString(RemotePrefs.KEY_PIN, pin).commit()
            val accepted = committed && pin == preferences.getString(RemotePrefs.KEY_PIN, null)
            lastError = if (accepted) null else "framework rejected the new PIN"
            accepted
        } catch (t: Throwable) {
            recordError("saving PIN failed", t)
            false
        }
    }

    private fun recordError(message: String, t: Throwable) {
        val detail = "$message: ${t.javaClass.name}: ${t.message}"
        lastError = detail
        Log.e(TAG, detail, t)
    }
}
