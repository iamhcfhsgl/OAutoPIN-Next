package io.github.achyuki.oautopin

import android.app.Application
import android.util.Log
import android.view.View
import android.widget.EditText
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * The module entry point, declared in `META-INF/xposed/java_init.list` and
 * driven by the framework lifecycle.
 *
 * Only `com.android.systemui` is statically scoped, so the package callbacks run
 * there. The PIN comes from framework-owned RemotePreferences (see
 * [RemotePrefs]) and is re-resolved on every load and on every hot reload, so a
 * newly saved PIN applies without restarting SystemUI.
 */
class Hook : XposedModule() {

    companion object {
        private const val TARGET_PACKAGE = "com.android.systemui"
        private const val HOOK_ID = "oautopin_sim_pin_onresume"

        private const val CONTROLLER_CLASS =
            "com.oplus.keyguard.security.view.OplusKeyguardSimInputViewController"

        private const val CONTROLLER_METHOD = "onResume"

        /**
         * Set once the automatic attempt has been dispatched.
         *
         * SystemUI is a long-lived process and the keyguard can be shown more
         * than once (SIM re-insert, airplane mode toggling), so this is reset
         * whenever a controller instance that has not been served yet shows up.
         * See [afterOnResume].
         */
        @Volatile
        private var triedControllers = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<Any, Boolean>()
        )
    }

    private var systemUiClassLoader: ClassLoader? = null

    /** The single installed hook, kept so a hot reload can replace it. */
    private var installed: XposedInterface.HookHandle? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        moduleLog(
            this,
            Log.INFO,
            "module loaded: process=${param.processName} systemServer=${param.isSystemServer} " +
                "api=$apiVersion framework=$frameworkName/$frameworkVersion " +
                "codes=${frameworkVersionCode} props=0x${frameworkProperties.toString(16)}"
        )
    }

    /**
     * Both `onPackageLoaded` and `onPackageReady` fire for the scoped package, so
     * exactly one of them installs the hook. [installHook] is idempotent, which
     * makes the order irrelevant.
     */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        // `PackageLoadedParam` only exposes `defaultClassLoader`; `classLoader`
        // belongs to `PackageReadyParam`. Either one resolves the host classes.
        @Suppress("USELESS_ELVIS")
        systemUiClassLoader = param.defaultClassLoader ?: systemUiClassLoader
        moduleLog(this, Log.INFO, "target package loaded: ${param.packageName}")
        installHook()
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        systemUiClassLoader = param.classLoader
        moduleLog(this, Log.INFO, "target package ready: ${param.packageName}")
        installHook()
    }

    /**
     * Hot reload entry point (API 102).
     *
     * A reload instantiates a **new** module generation, and the framework does
     * not replay `onModuleLoaded` / package callbacks for it. That means a fresh
     * instance has no captured class loader, so the hook has to be replaced
     * through the handles handed over by the framework instead of being
     * re-installed from scratch.
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        if (apiVersion < XposedInterface.API_102) return
        triedControllers.clear()
        RemotePrefs.invalidate()

        val pin = RemotePrefs.readPin(this) ?: run {
            moduleLog(this, Log.WARN, "hot reloaded, but no valid PIN is stored")
            return
        }

        var replaced = 0
        for (handle in param.oldHookHandles) {
            // Match by id when the runtime reports one, otherwise fall back to
            // the executable this module hooks.
            val matches = handle.id == HOOK_ID ||
                handle.executable.name == CONTROLLER_METHOD
            if (!matches) continue
            try {
                handle.replaceHook { chain ->
                    val result = chain.proceed()
                    afterOnResume(chain.thisObject, pin)
                    result
                }
                replaced++
            } catch (t: Throwable) {
                moduleLog(
                    this,
                    Log.ERROR,
                    "replaceHook failed: ${t.javaClass.name}: ${t.message}"
                )
            }
        }
        moduleLog(this, Log.INFO, "hot reloaded: replaced $replaced hook(s)")
    }

    // ------------------------------------------------------------------ hook installation

    private fun installHook() {
        if (installed != null) {
            moduleLog(this, Log.INFO, "hook already installed, skipping")
            return
        }

        val loader = systemUiClassLoader ?: run {
            moduleLog(this, Log.INFO, "no class loader captured yet")
            return
        }

        val pin = RemotePrefs.readPin(this) ?: return

        val method = try {
            val controller = Class.forName(CONTROLLER_CLASS, false, loader)
            controller.getDeclaredMethod(CONTROLLER_METHOD, Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            moduleLog(
                this,
                Log.WARN,
                "cannot resolve $CONTROLLER_CLASS#$CONTROLLER_METHOD: " +
                    "${t.javaClass.name}: ${t.message}"
            )
            return
        }

        try {
            method.isAccessible = true
            val builder = hook(method)
            // Hook ids (atomic replacement on reload) are an API 102 feature.
            val configured = if (apiVersion >= XposedInterface.API_102) {
                builder.setId(HOOK_ID)
            } else {
                builder
            }
            installed = configured.intercept { chain ->
                // The original return value must be passed through unchanged: for
                // a non-void method the framework converts whatever the hooker
                // returns, and returning Unit would break the caller.
                val result = chain.proceed()
                afterOnResume(chain.thisObject, pin)
                result
            }
            moduleLog(this, Log.INFO, "installed $HOOK_ID on ${method.toGenericString()}")
        } catch (t: Throwable) {
            moduleLog(
                this,
                Log.ERROR,
                "failed to install hook on ${method.toGenericString()}: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    // ------------------------------------------------------------------ hook body

    private fun afterOnResume(controller: Any?, pin: String) {
        if (controller == null) return
        if (!controller.javaClass.name.endsWith("SimPinViewController")) return
        // One attempt per controller instance: the keyguard can be shown again
        // later in the same process and must be served again.
        if (!triedControllers.add(controller)) return

        val view = readField(controller, "mView") as? View ?: run {
            moduleLog(this, Log.WARN, "mView unavailable on ${controller.javaClass.name}")
            return
        }
        view.post { tryAutoVerify(controller, view, pin) }
    }

    private fun tryAutoVerify(controller: Any, view: View, pin: String) {
        try {
            if (readField(controller, "isChecking") as? Boolean == true) {
                moduleLog(this, Log.INFO, "isChecking=true, skip")
                return
            }

            val editWidget = invokeMethod(view, "getMEditInputWidget") ?: run {
                moduleLog(this, Log.WARN, "getMEditInputWidget returned null")
                return
            }
            val editText = invokeMethod(editWidget, "getSecurityEditText") as? EditText
                ?: run {
                    moduleLog(this, Log.WARN, "getSecurityEditText returned null")
                    return
                }

            editText.setText(pin)
            invokeMethod(controller, "checkSimPikOrPuk")

            moduleLog(this, Log.INFO, "auto verification dispatched successfully")
        } catch (t: Throwable) {
            moduleLog(this, Log.INFO, "tryAutoVerify error: ${t.javaClass.name}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    /** Walks the superclass chain, because Oplus hides the field in a base class. */
    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (t: Throwable) {
                moduleLog(this, Log.INFO, "readField($name) failed: ${t.javaClass.name}: ${t.message}")
                return null
            }
        }
        return null
    }

    private fun invokeMethod(target: Any, name: String, vararg args: Any?): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            }
            if (method != null) {
                try {
                    method.isAccessible = true
                    return method.invoke(target, *args)
                } catch (t: Throwable) {
                    moduleLog(
                        this,
                        Log.INFO,
                        "invokeMethod($name) failed: ${t.javaClass.name}: ${t.message}"
                    )
                    return null
                }
            }
            type = type.superclass
        }
        moduleLog(this, Log.INFO, "invokeMethod($name) not found on ${target.javaClass.name}")
        return null
    }
}
