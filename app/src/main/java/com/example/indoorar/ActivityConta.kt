package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import com.example.indoorar.BaseActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ActivityConta : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_conta)

        // Ajusta o padding para respeitar as barras do sistema (status, nav bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.constraintLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

// Botão para ir para Cadastro Comum (ActivityCriar)
        val btnComum = findViewById<Button>(R.id.btnComum)
        btnComum.setOnClickListener {
            val intent = Intent(this, ActivityCriar::class.java)
            startActivity(intent)
        }

// Botão para ir para Cadastro Maker (ActivityCriar2)
        val btnMaker = findViewById<Button>(R.id.btnMaker)
        btnMaker.setOnClickListener {
            val intent = Intent(this, ActivityCriar2::class.java)
            startActivity(intent)
        }
    }
}
