package com.example.indoorar

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
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

    fun hideKeyboard() {
        val view = currentFocus
        if (view != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    fun showSnackbar(msg: String, anchorView: View? = null) {
        hideKeyboard() // fecha teclado antes de mostrar uma mensagem

        val rootView = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT)
        snackbar.setTextColor(Color.WHITE)
        snackbar.setBackgroundTint("#32357A".toColorInt())

        // Se passar um anchorView, a snackbar vai aparecer acima dela
        anchorView?.let { snackbar.setAnchorView(it) }

        snackbar.show()
    }
}