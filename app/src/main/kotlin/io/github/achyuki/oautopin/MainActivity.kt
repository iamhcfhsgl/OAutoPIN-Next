package io.github.achyuki.oautopin

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/**
 * PIN configuration UI.
 *
 * The PIN is kept in framework-owned RemotePreferences (see [PinStore]) rather
 * than in a world-readable preference file, so the hooked `com.android.systemui`
 * process can read it without `MODE_WORLD_READABLE` and without the legacy
 * `XSharedPreferences` bridge.
 *
 * All [PinStore] calls run on a worker thread: they may block for a few seconds
 * while the framework service binds, which must not happen on the main thread.
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "oautopin-prefs").apply { isDaemon = true }
    }

    private lateinit var rootView: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var pinEditText: EditText
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)

        val scrollRoot = ScrollView(this)
        rootView = LinearLayout(this)
        scrollRoot.addView(rootView)
        rootView.orientation = LinearLayout.VERTICAL
        setContentView(scrollRoot)

        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        buildUi()
        // Register as early as possible: the framework binds the service when
        // this module's own process starts.
        PinStore.bind()
        loadStoredPin()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val hint = TextView(this)
        hint.text = "The PIN is stored in LSPosed RemotePreferences and read by SystemUI " +
            "to unlock the SIM card automatically after boot."
        rootView.addView(hint)

        pinEditText = EditText(this)
        pinEditText.hint = "Input PIN (4-8 NUM)"
        pinEditText.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
        pinEditText.inputType = InputType.TYPE_CLASS_NUMBER
        rootView.addView(pinEditText)

        saveButton = Button(this)
        saveButton.text = "Save"
        saveButton.setOnClickListener { save() }
        rootView.addView(saveButton)

        statusView = TextView(this)
        rootView.addView(statusView)

        setBusy(true)
    }

    private fun loadStoredPin() {
        showStatus("Connecting to the LSPosed preferences service\u2026", false)
        worker.execute {
            val stored = PinStore.readPin()
            val error = PinStore.lastError
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (stored != null) {
                    pinEditText.setText(stored)
                    showStatus("Loaded the PIN saved in LSPosed preferences.", false)
                } else {
                    showStatus(
                        "No PIN stored yet. " + (error
                            ?: "Enter a 4-8 digit PIN and tap Save."),
                        error != null
                    )
                }
            }
        }
    }

    private fun save() {
        val pin = pinEditText.text.toString().trim()
        if (!pin.matches(Regex("^\\d{4,8}$"))) {
            Toast.makeText(this, "Input invalid", Toast.LENGTH_SHORT).show()
            showStatus("PIN must be 4-8 digits.", true)
            return
        }

        setBusy(true)
        showStatus("Saving\u2026", false)
        worker.execute {
            val saved = PinStore.writePin(pin)
            val error = PinStore.lastError
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (saved) {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                    showStatus(
                        "Saved to LSPosed preferences. Hot reload the module (or restart " +
                            "SystemUI) for the change to take effect.",
                        false
                    )
                } else {
                    Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
                    showStatus("Save failed: ${error ?: "unknown error"}", true)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        saveButton.isEnabled = !busy
        pinEditText.isEnabled = !busy
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusView.text = message
        statusView.setTextColor(if (isError) ERROR_COLOR else NORMAL_COLOR)
        statusView.visibility = View.VISIBLE
    }

    private companion object {
        const val ERROR_COLOR = 0xFFD32F2F.toInt()
        const val NORMAL_COLOR = 0xFF388E3C.toInt()
    }
}
