package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ActivityHomeComum : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_comum)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Botão Escanear QR
        val btnEscanear = findViewById<ImageView>(R.id.btnEscanear)
        btnEscanear.setOnClickListener {
            startActivity(Intent(this, ActivityScanQR::class.java))
        }

        // Botão Perfil
        val btnPerfil = findViewById<ImageView>(R.id.btnPerfil)
        btnPerfil.setOnClickListener {
            startActivity(Intent(this, ActivityPerfil::class.java))
        }

        // Botão Mapas Existentes
        val btnMapasExistentes = findViewById<ImageView>(R.id.btnMapasExistentes)
        btnMapasExistentes.setOnClickListener {
            startActivity(Intent(this, ActivityMapasExistentes::class.java))
        }
    }
}