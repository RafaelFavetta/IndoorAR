package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.cardview.widget.CardView

class ActivityConfigConta : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_config_conta)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val cardEditarNome = findViewById<CardView>(R.id.cardEditarNome)
        cardEditarNome?.setOnClickListener {
            startActivity(Intent(this, ActivityEditarNome::class.java))
        }
        val cardAlterarEmail = findViewById<CardView>(R.id.cardAlterarEmail)
        cardAlterarEmail?.setOnClickListener {
            startActivity(Intent(this, ActivityAlterarEmail::class.java))
        }
        val cardEditarTelefone = findViewById<CardView>(R.id.cardEditarTelefone)
        cardEditarTelefone?.setOnClickListener {
            startActivity(Intent(this, ActivityEditarTelefone::class.java))
        }
        val cardAlterarSenha = findViewById<CardView>(R.id.cardAlterarSenha)
        cardAlterarSenha?.setOnClickListener {
            startActivity(Intent(this, ActivityAlterarSenha::class.java))
        }
    }
}