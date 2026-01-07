package com.kevinluo.autoglm.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.kevinluo.autoglm.util.Logger

/**
 * Transparent activity to toggle floating window and collapse notification panel.
 * This activity finishes immediately after toggling the floating window.
 * 
 * Uses Activity instead of AppCompatActivity to avoid bringing the app task to foreground.
 */
class FloatingWindowToggleActivity : Activity() {

    /**
     * Called when the activity is created.
     * Handles the toggle action and finishes immediately.
     *
     * @param savedInstanceState The saved instance state bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.d(TAG, "Toggle activity started, action: ${intent.action}")

        // Check overlay permission
        if (!FloatingWindowService.canDrawOverlays(this)) {
            Logger.w(TAG, "No overlay permission")
            val mainIntent = Intent(this, com.kevinluo.autoglm.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(mainIntent)
            finish()
            return
        }

        when (intent.action) {
            Companion.ACTION_SHOW -> showFloatingWindow()
            Companion.ACTION_HIDE -> hideFloatingWindow()
            Companion.ACTION_TOGGLE -> toggleFloatingWindow()
            else -> toggleFloatingWindow()
        }

        // Finish immediately
        finish()
    }

    private fun toggleFloatingWindow() {
        val service = FloatingWindowService.getInstance()
        if (service != null && service.isVisible()) {
            hideFloatingWindow()
        } else {
            showFloatingWindow()
        }
    }

    private fun showFloatingWindow() {
        val serviceIntent = Intent(this, FloatingWindowService::class.java)
        startService(serviceIntent)
        Handler(Looper.getMainLooper()).postDelayed({
            FloatingWindowService.getInstance()?.show()
        }, 100)
    }

    private fun hideFloatingWindow() {
        FloatingWindowService.getInstance()?.hide()
    }

    companion object {
        private const val TAG = "FloatingWindowToggle"
        const val ACTION_TOGGLE = "com.kevinluo.autoglm.ACTION_TOGGLE_FLOATING"
        const val ACTION_SHOW = "com.kevinluo.autoglm.ACTION_SHOW_FLOATING"
        const val ACTION_HIDE = "com.kevinluo.autoglm.ACTION_HIDE_FLOATING"
    }
}
