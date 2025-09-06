package com.example.indoorar

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.*
import com.google.android.material.snackbar.Snackbar

open class BaseActivity : AppCompatActivity() {

    private lateinit var insetsController: WindowInsetsControllerCompat
    private val handler = Handler(Looper.getMainLooper())
    private val hideDelay: Long = 2000 // 2 segundos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUltimateFullScreen()
    }

    private fun setupUltimateFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        hideSystemBars()

        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val compatInsets = WindowInsetsCompat.toWindowInsetsCompat(insets)
            val systemBarsVisible = compatInsets.isVisible(WindowInsetsCompat.Type.systemBars())

            if (systemBarsVisible) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ hideSystemBars() }, hideDelay)
            }
            insets
        }

        window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    fun showSnackbar(message: String) {
        val rootView = findViewById<android.view.View>(android.R.id.content)
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).apply {
            setTextColor(Color.WHITE)
            setBackgroundTint("#32357A".toColorInt())
        }.show()
    }
}