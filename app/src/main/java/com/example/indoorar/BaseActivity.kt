package com.example.indoorar

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.*
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import android.util.Log
import kotlin.math.min

open class BaseActivity : AppCompatActivity() {

    private lateinit var insetsController: WindowInsetsControllerCompat
    private val handler = Handler(Looper.getMainLooper())
    private val hideDelay: Long = 2000 // 2 segundos

    // --- Uniform density support (Toutiao-like adaptation) ---
    companion object {
        // Keep original scaledDensity so we can respect user font scale moderately
        private var nonCompatScaledDensity: Float = 0f
        private var densityApplied = false
    }

    // Allow child activities to customize their design width if necessary
    protected open fun designWidthDp(): Float = 360f

    override fun attachBaseContext(newBase: android.content.Context) {
        // Clamp extreme font scales to avoid layout breakage while still respecting accessibility
        val cfg = newBase.resources.configuration
        val clamped = cfg.fontScale.coerceIn(0.85f, 1.15f)
        if (clamped != cfg.fontScale) {
            val newCfg = Configuration(cfg)
            newCfg.fontScale = clamped
            val wrapped = newBase.createConfigurationContext(newCfg)
            super.attachBaseContext(wrapped)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply uniform density before view inflation/theme adjustments that depend on metrics
        applyUniformDensity(designWidthDp = designWidthDp())
        super.onCreate(savedInstanceState)
        setupUltimateFullScreen()
    }

    private fun setupUltimateFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        insetsController = controller
        controller.systemBarsBehavior =
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

    // Apply a process/activity density based on a reference design width to normalize UI across devices
    private fun applyUniformDensity(designWidthDp: Float) {
        val app = application ?: return
        val appDm = app.resources.displayMetrics

        if (nonCompatScaledDensity == 0f) {
            nonCompatScaledDensity = appDm.scaledDensity
            // Keep scaledDensity updated if user changes font size at runtime
            app.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    if (newConfig.fontScale > 0) {
                        nonCompatScaledDensity = app.resources.displayMetrics.scaledDensity
                    }
                }
                override fun onLowMemory() { /* no-op */ }
            })
        }

        val shortestSidePx = min(appDm.widthPixels, appDm.heightPixels)
        if (shortestSidePx == 0) return

        val targetDensity = shortestSidePx / designWidthDp
        val targetScaledDensity = targetDensity * (nonCompatScaledDensity / appDm.density)
        val targetDensityDpi = (160 * targetDensity).toInt()

        // Apply to app metrics (affects most inflations, including third-party libs)
        if (!densityApplied || appDm.density != targetDensity) {
            appDm.density = targetDensity
            appDm.scaledDensity = targetScaledDensity
            appDm.densityDpi = targetDensityDpi
            densityApplied = true
        }

        // Apply to this activity's metrics as well
        val actDm = this.resources.displayMetrics
        actDm.density = targetDensity
        actDm.scaledDensity = targetScaledDensity
        actDm.densityDpi = targetDensityDpi
    }

    fun hideKeyboard() {
        val view = currentFocus
        val token = view?.windowToken
        if (token != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(token, 0)
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

    // Sincroniza o e-mail do usuário do Auth com o documento do Firestore após alterações (como verificação de novo e-mail)
    fun syncUserEmailToFirestore(onDone: ((Boolean) -> Unit)? = null) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onDone?.invoke(false)
            return
        }
        user.reload()
            .addOnSuccessListener {
                val emailAtual = user.email
                if (emailAtual.isNullOrEmpty()) {
                    onDone?.invoke(false)
                    return@addOnSuccessListener
                }
                val db = FirebaseFirestore.getInstance()
                val docRef = db.collection("usuarios").document(user.uid)
                docRef.get()
                    .addOnSuccessListener { doc ->
                        val precisaAtualizar = !doc.exists() || doc.getString("email") != emailAtual
                        if (!precisaAtualizar) {
                            onDone?.invoke(true)
                            return@addOnSuccessListener
                        }
                        docRef.set(mapOf("email" to emailAtual), SetOptions.merge())
                            .addOnSuccessListener {
                                Log.d("BaseActivity", "Email sincronizado no Firestore: $emailAtual")
                                onDone?.invoke(true)
                            }
                            .addOnFailureListener { ex ->
                                Log.w("BaseActivity", "Falha ao sincronizar email no Firestore", ex)
                                onDone?.invoke(false)
                            }
                    }
                    .addOnFailureListener { ex ->
                        Log.w("BaseActivity", "Falha ao buscar documento do usuário para sincronizar", ex)
                        onDone?.invoke(false)
                    }
            }
            .addOnFailureListener { ex ->
                Log.w("BaseActivity", "Falha ao recarregar usuário antes de sincronizar email", ex)
                onDone?.invoke(false)
            }
    }
}