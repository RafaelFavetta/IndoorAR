package com.example.indoorar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.redmadrobot.inputmask.MaskedTextChangedListener
import androidx.core.graphics.toColorInt

class ActivityLogin : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var telefoneField: EditText
    private var telefoneBruto: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val editEmail = findViewById<EditText>(R.id.editEmail)
        val editSenha = findViewById<EditText>(R.id.editSenha)
        telefoneField = findViewById(R.id.editTelefone) // se tiver campo de telefone
        val btnEntrar = findViewById<Button>(R.id.btnEntrar)

        // Aplica a máscara no telefone (igual ActivityCriar)
        MaskedTextChangedListener.installOn(
            editText = telefoneField,
            primaryFormat = "+55 ([00]) [00000]-[0000]",
            valueListener = object : MaskedTextChangedListener.ValueListener {
                override fun onTextChanged(
                    maskFilled: Boolean,
                    extractedValue: String,
                    formattedValue: String
                ) {
                    telefoneBruto = extractedValue
                }
            }
        )

        btnEntrar.setOnClickListener {
            val email = editEmail.text.toString().trim()
            val senha = editSenha.text.toString().trim()
            val telefone = telefoneBruto // pegando valor já formatado

            if (email.isEmpty() || senha.isEmpty() || telefone.isEmpty()) {
                snackbar("Preencha todos os campos!")
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, senha)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uid = auth.currentUser!!.uid
                        db.collection("usuarios").document(uid).get()
                            .addOnSuccessListener { document ->
                                if (document.exists()) {
                                    val tipoConta = document.getString("tipoConta")
                                    if (tipoConta == "maker") {
                                        startActivity(Intent(this, HomeCriadorActivity::class.java))
                                    } else {
                                        startActivity(Intent(this, HomeComumActivity::class.java))
                                    }
                                    finish()
                                } else {
                                    snackbar("Usuário não encontrado no banco.")
                                }
                            }
                            .addOnFailureListener { e ->
                                snackbar("Erro ao buscar usuário: ${e.message}")
                            }
                    } else {
                        snackbar("Erro no login: ${task.exception?.message}")
                    }
                }
        }
    }

    private fun snackbar(msg: String) {
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_SHORT).apply {
            setTextColor(Color.WHITE)
            setBackgroundTint("#3F60CD".toColorInt())
        }.show()
    }
}