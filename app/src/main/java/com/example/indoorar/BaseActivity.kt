package com.example.indoorar

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