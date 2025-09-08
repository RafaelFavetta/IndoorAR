package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    private val baseDelay = 150L
    private val animationDuration = 400L
    private val offsetY = -40f

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_IndoorAR_Splash)
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_IndoorAR)
        setContentView(R.layout.main_activity)

        val rootView = findViewById<View>(R.id.mainRoot)
        val logo = findViewById<View>(R.id.logo)
        val slogan = findViewById<View>(R.id.txtSlogan)
        val btnCadastrar = findViewById<Button>(R.id.btnCadastro)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        slogan.elevation = 8f

        // Inicializa invisível e fora da tela
        listOf(rootView, logo, slogan, btnCadastrar, btnLogin).forEach { view ->
            view.alpha = 0f
            view.translationY = offsetY
        }

        // Checa usuário logado
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser != null) {
            // Usuário logado → pega tipo de conta e manda pra home certa após animação
            animateView(rootView, 0L)
            animateView(logo, baseDelay)
            animateView(slogan, baseDelay * 2) {
                // Depois da animação do slogan, busca tipo de conta
                val db = FirebaseFirestore.getInstance()
                db.collection("usuarios").document(currentUser.uid).get()
                    .addOnSuccessListener { doc ->
                        val tipoConta = doc.getString("tipoConta")
                        when (tipoConta) {
                            "comum" -> startActivity(Intent(this, ActivityHomeComum::class.java))
                            "maker" -> startActivity(Intent(this, ActivityHomeMaker::class.java))
                            else -> startActivity(Intent(this, ActivityLogin::class.java))
                        }
                        finish()
                    }
                    .addOnFailureListener {
                        startActivity(Intent(this, ActivityLogin::class.java))
                        finish()
                    }
            }
        } else {
            // Não logado → animação normal, depois deixa botões visíveis
            animateView(rootView, 0L)
            animateView(logo, baseDelay)
            animateView(slogan, baseDelay * 2)
            animateViewWithBounce(btnCadastrar, baseDelay * 3)
            animateViewWithBounce(btnLogin, baseDelay * 4)

            btnCadastrar.setOnClickListener {
                startActivity(Intent(this, ActivityConta::class.java))
            }

            btnLogin.setOnClickListener {
                startActivity(Intent(this, ActivityLogin::class.java))
            }
        }
    }

    private fun animateView(view: View, delay: Long, endAction: (() -> Unit)? = null) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(animationDuration)
            .withEndAction { endAction?.invoke() }
            .start()
    }

    private fun animateViewWithBounce(view: View, delay: Long) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(animationDuration)
            .withEndAction {
                view.animate()
                    .translationY(-10f)
                    .setDuration(150)
                    .withEndAction {
                        view.animate()
                            .translationY(0f)
                            .setDuration(150)
                            .start()
                    }.start()
            }.start()
    }
}