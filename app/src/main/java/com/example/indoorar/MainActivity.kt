package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.indoorar.BaseActivity


class MainActivity : BaseActivity() {

    private val baseDelay = 150L
    private val animationDuration = 400L
    private val offsetY = -40f

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_IndoorAR_Splash)
        super.onCreate(savedInstanceState)

        setTheme(R.style.Theme_IndoorAR)
        setContentView(R.layout.main_activity)

        // Pegando elementos
        val rootView = findViewById<View>(R.id.mainRoot)
        val logo = findViewById<View>(R.id.logo)
        val slogan = findViewById<View>(R.id.txtSlogan)
        val btnCadastrar = findViewById<Button>(R.id.btnCadastro)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        // Pequena sombra no slogan
        slogan.elevation = 8f

        // Inicializa invisível e mais pro lado
        listOf(rootView, logo, slogan, btnCadastrar, btnLogin).forEach { view ->
            view.alpha = 0f
            view.translationY = offsetY
        }

        // Animações
        animateView(rootView, 0L)
        animateView(logo, baseDelay)
        animateView(slogan, baseDelay * 2)
        animateViewWithBounce(btnCadastrar, baseDelay * 3)
        animateViewWithBounce(btnLogin, baseDelay * 4)

        // Botões
        btnCadastrar.setOnClickListener {
            startActivity(Intent(this, ActivityConta::class.java))
        }

        btnLogin.setOnClickListener {
            startActivity(Intent(this, ActivityLogin::class.java))
        }
    }

    // Fade + slide padrão
    private fun animateView(view: View, delay: Long) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(animationDuration)
            .start()
    }

    // Fade + slide + bounce suave
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