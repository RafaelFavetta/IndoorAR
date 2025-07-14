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
    }
}
