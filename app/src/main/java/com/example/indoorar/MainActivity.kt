package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        val btnCadastrar = findViewById<Button>(R.id.btnLogin7)  // Vai pra ActivityConta
        val btnLogin = findViewById<Button>(R.id.btnLogin8)     // Vai pra tela de login

        btnCadastrar.setOnClickListener {
            val intent = Intent(this, ActivityConta::class.java)
            startActivity(intent)
        }

        btnLogin.setOnClickListener {
            // Tela de login ainda vai criar
        }
    }
}
