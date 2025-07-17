package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val splashTimeOut: Long = 1500 // 1 segundo e meio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            // Abre a MainActivity após o delay
            startActivity(Intent(this, MainActivity::class.java))
            finish() // Fecha a splash pra não voltar aqui ao apertar "voltar"
        }, splashTimeOut)
    }
}
