package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.indoorar.BaseActivity
import com.example.indoorar.ui.ActivityEditor
import com.google.firebase.auth.FirebaseAuth

class ActivityHomeMaker : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_maker)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Botão Criar mapa
        val btnCriarMapa = findViewById<ImageView>(R.id.btnCriarMapa)
        btnCriarMapa.setOnClickListener {
            startActivity(Intent(this, ActivityEditor::class.java))
        }

        // Botão Meus Mapas
        val btnMeusMapas = findViewById<ImageView>(R.id.btnMeusMapas)
        btnMeusMapas.setOnClickListener {
            startActivity(Intent(this, ActivityMeusMapas::class.java))
        }

        // Botão Logout
        val btnSignOut = findViewById<ImageView>(R.id.btnSignOut)
        btnSignOut.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, ActivityLogin::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }
}