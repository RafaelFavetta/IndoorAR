package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_IndoorAR_Splash)
        super.onCreate(savedInstanceState)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user != null) {
            // Pula login: busca tipoConta
            FirebaseFirestore.getInstance().collection("usuarios").document(user.uid).get()
                .addOnSuccessListener { doc ->
                    val tipo = (doc.getString("tipoConta") ?: "comum").lowercase()
                    val dest = if (tipo == "maker") ActivityHomeMaker::class.java else ActivityHomeComum::class.java
                    startActivity(Intent(this, dest))
                    finish()
                }
                .addOnFailureListener {
                    // fallback
                    startActivity(Intent(this, ActivityHomeComum::class.java))
                    finish()
                }
            return
        }
        setTheme(R.style.Theme_IndoorAR)
        setContentView(R.layout.main_activity)

        val rootView = findViewById<View>(R.id.mainRoot)
        rootView.alpha = 1f


        val btnCadastrar = findViewById<Button>(R.id.btnCadastro)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        btnCadastrar.elevation = 8f
        btnLogin.elevation = 8f
        rootView.elevation = 8f
        btnCadastrar.setOnClickListener {
            val intent = Intent(this, ActivityConta::class.java)
            startActivity(intent)
        }
        btnLogin.setOnClickListener {
            val intent = Intent(this, ActivityLogin::class.java)
            startActivity(intent)
        }

    }
}