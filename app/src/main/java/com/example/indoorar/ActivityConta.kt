package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ActivityConta : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_conta)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.constraintLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnLogin4 = findViewById<Button>(R.id.btnLogin4)
        btnLogin4.setOnClickListener {
            val intent = Intent(this, ActivityCriar2::class.java)
            startActivity(intent)
        }

        val btnLogin3 = findViewById<Button>(R.id.btnLogin3)
        btnLogin3.setOnClickListener {
            val intent = Intent(this, ActivityCriar::class.java)
            startActivity(intent)
        }
    }
}