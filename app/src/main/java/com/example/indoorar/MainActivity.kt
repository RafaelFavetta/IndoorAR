package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        val btnCadastrar = findViewById<Button>(R.id.btnLogin7)
        btnCadastrar.setOnClickListener {
            val intent = Intent(this, ActivityConta::class.java)
            startActivity(intent)
        }

        val btnContaComum = findViewById<Button>(R.id.btnLogin7)
        val btnContaMaker = findViewById<Button>(R.id.btnLogin8)

        btnContaComum.setOnClickListener {
            val intent = Intent(this, ActivityCriar::class.java)
            startActivity(intent)
            }

            btnContaMaker.setOnClickListener {
                val intent = Intent(this, ActivityCriar2::class.java)
                startActivity(intent)
            }


    }
}

