package com.example.indoorar

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
        val btnVoltar = findViewById<android.widget.ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener {
            val intent = android.content.Intent(this, ActivityPerfil::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            finish()
        }
    }
}