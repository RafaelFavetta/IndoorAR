package com.example.indoorar

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ActivityEditarTelefone : BaseActivity() {

    private lateinit var editNovoTelefone: EditText
    private lateinit var btnSalvarTelefone: Button
    private var telefoneBruto: String = ""
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editar_telefone)

        db = FirebaseFirestore.getInstance()

        val btnVoltar = findViewById<ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }

        editNovoTelefone = findViewById(R.id.editNovoTelefone)
        btnSalvarTelefone = findViewById(R.id.btnSalvarTelefone)
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progress_bar_telefone)
        fun setLoading(loading: Boolean) {
            progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
            btnSalvarTelefone.isEnabled = !loading
        }
        // start loading while we fetch
        setLoading(true)

        // Preencher com o telefone atual (se existir) para melhor UX
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val uid = currentUser.uid
            db.collection("usuarios").document(uid).get()
                .addOnSuccessListener { doc ->
                    val raw = doc?.getString("telefone") ?: ""
                    val digits = raw.replace(Regex("\\D"), "")
                    telefoneBruto = digits
                    if (digits.isNotEmpty()) {
                        val masked = formatPhone(digits)
                        editNovoTelefone.setText(masked)
                        editNovoTelefone.setSelection(masked.length)
                    }
                    setLoading(false)
                }
                .addOnFailureListener {
                    setLoading(false)
                    // falha ao buscar telefone: não bloqueia UI
                }
        } else {
            setLoading(false)
        }

        // Máscara semelhante ao cadastro
        editNovoTelefone.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            private val mask = "(##) #####-####"
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                val digits = s.toString().replace(Regex("\\D"), "")
                telefoneBruto = digits
                var masked = ""
                var i = 0
                for (m in mask) {
                    if (m == '#') {
                        if (i < digits.length) masked += digits[i++] else break
                    } else {
                        if (i < digits.length) masked += m
                    }
                }
                s?.replace(0, s.length, masked)
                isUpdating = false
            }
        })

        btnSalvarTelefone.setOnClickListener {
            setLoading(true)
            val novoTel = telefoneBruto
            if (novoTel.isEmpty() || novoTel.length < 11) {
                setLoading(false)
                Toast.makeText(this, "Informe um telefone válido com DDD", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                setLoading(false)
                Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uid = user.uid
            val dados = mapOf("telefone" to novoTel)
            db.collection("usuarios").document(uid)
                .update(dados)
                .addOnSuccessListener {
                    setLoading(false)
                    Toast.makeText(this, "Telefone atualizado com sucesso", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener {
                    setLoading(false)
                    Toast.makeText(this, "Erro ao atualizar telefone: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // Utility to format a digits-only phone into the mask (keeps first digits only)
    private fun formatPhone(digitsOnly: String): String {
        val mask = "(##) #####-####"
        var masked = ""
        var i = 0
        for (m in mask) {
            if (m == '#') {
                if (i < digitsOnly.length) masked += digitsOnly[i++] else break
            } else {
                if (i < digitsOnly.length) masked += m
            }
        }
        return masked
    }
}
