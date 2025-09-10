package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : BaseActivity() {

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
    }
}