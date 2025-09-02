package com.example.indoorar

import com.example.indoorar.ui.ActivityMapCreator
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ImageView

class ActivityHomeMaker : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_maker)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Pegando o botão "Criar mapa"
        val btnCriarMapa = findViewById<ImageView>(R.id.btnCriarMapa)
        btnCriarMapa.setOnClickListener {
            val intent = Intent(this, ActivityMapCreator::class.java)
            startActivity(intent)
        }
    }
}
