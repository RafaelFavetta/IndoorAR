package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.example.indoorar.BaseActivity


class ActivityForgotPassword : BaseActivity() {

    private lateinit var etForgotEmail: EditText
    private lateinit var btnResetPassword: Button
    private lateinit var tvBackToLogin: TextView
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        // Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Views
        etForgotEmail = findViewById(R.id.editEmailRecuperar)
        btnResetPassword = findViewById(R.id.btnResetPassword)
        tvBackToLogin = findViewById(R.id.tvBackToLogin)

        // Botão de reset
        btnResetPassword.setOnClickListener {
            val email = etForgotEmail.text.toString().trim()

            if (email.isEmpty()) {
                etForgotEmail.error = "Digite seu e-mail"
                return@setOnClickListener
            }

            // Firebase manda e-mail de reset
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(
                            this,
                            "Link de recuperação enviado para $email",
                            Toast.LENGTH_LONG
                        ).show()
                        finish() // Fecha a tela e volta pro login
                    } else {
                        Toast.makeText(
                            this,
                            "Erro: ${task.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        // Voltar para Login
        tvBackToLogin.setOnClickListener {
            startActivity(Intent(this, Activity_login2::class.java))
            finish()
        }
    }
}


