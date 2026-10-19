package `in`.ankitsaroj.mirage.receiver

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import `in`.ankitsaroj.mirage.R
import `in`.ankitsaroj.mirage.core.KeyMap
import kotlinx.coroutines.launch

/**
 * The Mac's extended display: a full-screen, immersive SurfaceView sized exactly to the panel,
 * fed by [VideoDecoder] (HWC overlay, never a TextureView). Handles rotation itself (configChanges)
 * and asks the host to rebuild the virtual display for the new orientation.
 * Back gesture or a 3-finger hold opens a small menu (disconnect / stats).
 */
class ReceiverActivity : ComponentActivity(), SurfaceHolder.Callback {

    private var session: ReceiverSession? = null
    private lateinit var settings: ReceiverSettings
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TextView
    private lateinit var engine: GestureEngine
    private lateinit var input: InputCapture
    private lateinit var panel: Panel
    private val main = Handler(Looper.getMainLooper())
    private var menu: AlertDialog? = null

    private val overlayTick = object : Runnable {
        override fun run() {
            val s = session ?: return
            engine.pointsPerPixel = s.pointsPerPixel
            if (overlay.visibility == TextView.VISIBLE) overlay.text = s.stats.overlayText()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = ReceiverHub.session
        if (s == null || s.isEnded) { finish(); return }
        session = s
        settings = ReceiverSettings(this)
        panel = Panel.probe(this, display)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            preferredDisplayModeId = panel.bestModeId // 90 Hz at native resolution on the Tab S9 FE
        }

        surfaceView = SurfaceView(this)
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(16, 12, 16, 12)
            visibility = if (settings.showStats) TextView.VISIBLE else TextView.GONE
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(24, 24, 0, 0) })
        setContentView(root)
        hideSystemBars()

        surfaceView.holder.addCallback(this)

        val ctrlAsCmd = settings.ctrlAsCommand
        engine = GestureEngine(
            sink = { e -> session?.sendInput(e) },
            touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            keyMapper = KeyMap::androidToMac,
            metaMapper = { meta -> KeyMap.metaToModifiers(meta, ctrlAsCmd) },
        )
        engine.pointsPerPixel = s.pointsPerPixel
        engine.onMenuGesture = { showMenu() }
        input = InputCapture(surfaceView, engine).also { it.attach() }

        onBackPressedDispatcher.addCallback(this) { showMenu() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                ReceiverHub.status.collect { st ->
                    val live = ReceiverHub.session
                    if (st !is ReceiverStatus.Connected && st !is ReceiverStatus.Connecting || live == null || live !== session) finish()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        main.post(overlayTick)
    }

    override fun onStop() {
        main.removeCallbacks(overlayTick)
        super.onStop()
    }

    override fun onDestroy() {
        if (::input.isInitialized) input.detach()
        menu?.dismiss()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else if (::engine.isInitialized) engine.releaseAll()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::input.isInitialized && input.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        val s = session ?: return
        val p = Panel.probe(this, display)
        if (p.pixelWidth != panel.pixelWidth || p.pixelHeight != panel.pixelHeight) {
            Log.i(TAG, "rotation → ${p.pixelWidth}x${p.pixelHeight}, asking the host to reconfigure")
            panel = p
            s.sendReconfigure(p)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Surface
    // ---------------------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            holder.surface.setFrameRate(panel.refreshHz.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ALWAYS)
        } catch (e: Exception) {
            Log.w(TAG, "setFrameRate failed", e)
        }
        session?.decoder?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        session?.decoder?.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        session?.decoder?.setSurface(null)
    }

    // ---------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showMenu() {
        if (menu?.isShowing == true || isFinishing) return
        engine.releaseAll()
        val statsLabel = getString(if (overlay.visibility == TextView.VISIBLE) R.string.menu_hide_stats else R.string.menu_show_stats)
        menu = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.receiver_title)
            .setItems(arrayOf(getString(R.string.menu_disconnect), statsLabel, getString(R.string.menu_resume))) { _, which ->
                when (which) {
                    0 -> disconnect()
                    1 -> toggleStats()
                }
            }
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    private fun toggleStats() {
        val show = overlay.visibility != TextView.VISIBLE
        overlay.visibility = if (show) TextView.VISIBLE else TextView.GONE
        settings.showStats = show
    }

    private fun disconnect() {
        val s = session ?: return finish()
        // Teardown joins the decode/audio threads — keep it off the main thread.
        Thread({ s.end(sendBye = true) }, "mirage-disconnect").start()
        finish()
    }

    private companion object { const val TAG = "Mirage" }
}
