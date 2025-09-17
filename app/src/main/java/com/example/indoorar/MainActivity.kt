package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.example.indoorar.BaseActivity

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_IndoorAR_Splash)
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_IndoorAR)
        setContentView(R.layout.main_activity)

        val rootView = findViewById<View>(R.id.mainRoot)
        rootView.alpha = 1f


        val btnCadastrar = findViewById<Button>(R.id.btnCadastro)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        btnCadastrar.elevation = 8f
        btnLogin.elevation = 8f
        rootView.elevation = 8f
        btnCadastrar.setOnClickListener {
            val intent = Intent(this, ActivityConta::class.java)
            startActivity(intent)
        }
        btnLogin.setOnClickListener {
            val intent = Intent(this, ActivityLogin::class.java)
            startActivity(intent)
        }

    }
}