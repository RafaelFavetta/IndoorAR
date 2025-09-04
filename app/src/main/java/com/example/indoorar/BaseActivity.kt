package com.example.indoorar

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.*

open class BaseActivity : AppCompatActivity() {

    private lateinit var insetsController: WindowInsetsControllerCompat
    private val handler = Handler(Looper.getMainLooper())
    private val hideDelay: Long = 2000 // 2 segundos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUltimateFullScreen()
    }

    private fun setupUltimateFullScreen() {
        // Fullscreen compatível
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        hideSystemBars()

        // Listener para barras reaparecerem e sumirem automaticamente
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            // Usa o compat só para ler
            val compatInsets = WindowInsetsCompat.toWindowInsetsCompat(insets)
            val systemBarsVisible = compatInsets.isVisible(WindowInsetsCompat.Type.systemBars())

            if (systemBarsVisible) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ hideSystemBars() }, hideDelay)
            }
            insets
        }

        // Layout change listener (compatível)
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
}