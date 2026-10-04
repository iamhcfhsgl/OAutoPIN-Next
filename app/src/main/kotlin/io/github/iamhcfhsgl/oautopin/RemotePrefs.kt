package io.github.iamhcfhsgl.oautopin

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Cross-process PIN store on top of libxposed framework-owned RemotePreferences.
 *
 * The hooked `com.android.systemui` process reads that store through
 * [XposedModule.getRemotePreferences]; the module's own settings UI writes it by
 * binding to the framework service with [io.github.libxposed.service.XposedServiceHelper]
 * (see [PinStore]). Both sides share the single authoritative snapshot owned by
 * the framework, and no world-readable file is involved.
 *
 * `XSharedPreferences` and `MODE_WORLD_READABLE` are deliberately not used: they
 * are the legacy mechanism LSPosed deprecates and removes in 2.3.0.
 */
internal object RemotePrefs {
    /**
     * RemotePreferences group.
     *
     * Named after this fork rather than the upstream `"pin"` group: the package
     * rename means the new build is a fresh install, so there is no stored state to
     * carry over, and a legacy fallback would keep resurfacing the old value after
     * the user saves a new PIN.
     */
    const val GROUP = "oautopin"

    /** Key holding the 4-8 digit SIM PIN. */
    const val KEY_PIN = "pin"

    private val PIN_PATTERN = Regex("^\\d{4,8}$")

    @Volatile
    private var cached: SharedPreferences? = null

    private fun open(module: XposedModule): SharedPreferences? {
        cached?.let { return it }
        return try {
            if (module.frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) {
                moduleLog(module, Log.WARN, "framework does not advertise remote preferences")
                return null
            }
            module.getRemotePreferences(GROUP).also { cached = it }
        } catch (t: Throwable) {
            moduleLog(
                module,
                Log.ERROR,
                "getRemotePreferences($GROUP) failed: ${t.javaClass.name}: ${t.message}"
            )
            null
        }
    }

    /** Forgets the cached handle, so the next read picks up a fresh snapshot. */
    fun invalidate() {
        cached = null
    }

    /**
     * Reads and validates the stored PIN.
     *
     * @return the PIN when it is present and well formed, otherwise `null`.
     */
    fun readPin(module: XposedModule): String? {
        val preferences = open(module) ?: return null
        val pin = try {
            preferences.getString(KEY_PIN, null)
        } catch (t: Throwable) {
            moduleLog(
                module,
                Log.ERROR,
                "reading RemotePreferences failed: ${t.javaClass.name}: ${t.message}"
            )
            return null
        }
        if (pin.isNullOrEmpty() || !PIN_PATTERN.matches(pin)) {
            moduleLog(module, Log.WARN, "PIN invalid or empty, auto verify disabled")
            return null
        }
        return pin
    }
}

/**
 * Writes a diagnostic line to logcat and, when the module is running inside a
 * hooked process, to the framework log as well. Diagnostics must never break the
 * host process, so every sink is guarded.
 */
internal fun moduleLog(module: XposedModule?, priority: Int, message: String) {
    try {
        Log.println(priority, TAG, message)
    } catch (_: Throwable) {
        // logcat is best effort only.
    }
    if (module != null) {
        try {
            module.log(priority, TAG, message)
        } catch (_: Throwable) {
            // The framework sink is best effort only.
        }
    }
}

private const val TAG = "OAutoPIN"
