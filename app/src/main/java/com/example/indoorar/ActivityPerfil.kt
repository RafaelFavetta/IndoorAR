package com.example.indoorar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.request.RequestOptions
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import androidx.core.net.toUri
import androidx.core.content.edit
import com.yalantis.ucrop.UCrop

class ActivityPerfil : BaseActivity() {
    private lateinit var userImage: ImageView
    private val PREFS_NAME = "perfil_prefs"
    private val KEY_IMAGE_URI = "image_uri"
    private val KEY_IMAGE_PATH = "image_path"
    private fun saveImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val file = File(filesDir, "profile_image.jpg")
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e("Perfil", "Erro ao salvar imagem", e)
            null
        }
    }
    private val cropImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val resultUri = UCrop.getOutput(data)
            if (resultUri != null) {
                val imagePath = saveImageToInternalStorage(resultUri)
                if (imagePath != null) {
                    com.bumptech.glide.Glide.with(this)
                        .load(imagePath)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.account_circle)
                        .error(R.drawable.account_circle)
                        .into(userImage)
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    prefs.edit { putString(KEY_IMAGE_PATH, imagePath).remove(KEY_IMAGE_URI) }
                } else {
                    userImage.setImageResource(R.drawable.account_circle)
                }
            } else {
                userImage.setImageResource(R.drawable.account_circle)
            }
        }
    }
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val destinationFile = File(getExternalFilesDir(null), "cropped_profile_image.jpg")
                val destinationUri = Uri.fromFile(destinationFile)
                val uCrop = UCrop.of(it, destinationUri)
                    .withAspectRatio(1f, 1f)
                    .withOptions(UCrop.Options().apply {
                        setCircleDimmedLayer(true)
                        setShowCropFrame(false)
                        setShowCropGrid(false)
                    })
                cropImageLauncher.launch(uCrop.getIntent(this))
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Erro ao abrir editor de corte", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private val requestMediaImagesPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            pickImageLauncher.launch("image/*")
        } else {
            android.widget.Toast.makeText(this, "Permissão para acessar imagens negada", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_perfil)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val txtNomeUsuario = findViewById<android.widget.TextView>(R.id.txtNomeUsuario)
        val txtEmailUsuario = findViewById<android.widget.TextView>(R.id.txtEmailUsuario)
        userImage = findViewById<ImageView>(R.id.userImage)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_IMAGE_PATH, null)
        val savedUri = prefs.getString(KEY_IMAGE_URI, null)
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            txtEmailUsuario.text = user.email ?: "Email não disponível"
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            db.collection("usuarios").document(user.uid).get().addOnSuccessListener { doc ->
                val nome = doc.getString("nome") ?: "Nome não disponível"
                txtNomeUsuario.text = nome
            }.addOnFailureListener {
                txtNomeUsuario.text = "Nome não disponível"
            }
        } else {
            txtNomeUsuario.text = "Usuário não logado"
            txtEmailUsuario.text = "Usuário não logado"
        }

        // Foto de perfil
        if (savedPath != null) {
            Log.d("Perfil", "Carregando imagem do caminho: $savedPath")
            com.bumptech.glide.Glide.with(this)
                .load(savedPath)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.account_circle)
                .error(R.drawable.account_circle)
                .into(userImage)
        } else if (savedUri != null) {
            try {
                Log.d("Perfil", "Tentando carregar imagem URI: $savedUri")
                com.bumptech.glide.Glide.with(this)
                    .load(savedUri.toUri())
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.account_circle)
                    .error(R.drawable.account_circle)
                    .into(userImage)
            } catch (e: Exception) {
                userImage.setImageResource(R.drawable.account_circle)
            }
        } else {
            // Nenhuma foto salva, mostra ícone padrão
            userImage.setImageResource(R.drawable.account_circle)
        }

        // Clique para selecionar/remover foto de perfil
        userImage.setOnClickListener {
            val options = arrayOf("Remover foto de perfil", "Escolher da galeria")
            AlertDialog.Builder(this)
                .setTitle("Foto de perfil")
                .setItems(options) { dialog, which ->
                    when (which) {
                        0 -> { // Remover foto de perfil
                            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            prefs.edit { remove(KEY_IMAGE_PATH).remove(KEY_IMAGE_URI) }
                            userImage.setImageResource(R.drawable.account_circle)
                        }
                        1 -> { // Escolher da galeria
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                requestMediaImagesPermissionLauncher.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
                            } else {
                                pickImageLauncher.launch("image/*")
                            }
                        }
                    }
                }
                .show()
        }

        // Botão Sair
        val itemSair = findViewById<LinearLayout>(R.id.itemSair)
        itemSair.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(this, ActivityLogin::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        val btnVoltar = findViewById<ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener {
            val user = FirebaseAuth.getInstance().currentUser
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            if (user != null) {
                db.collection("usuarios").document(user.uid).get().addOnSuccessListener { doc ->
                    val tipo = doc.getString("tipo") ?: "comum"
                    if (tipo == "maker") {
                        startActivity(Intent(this, ActivityHomeMaker::class.java))
                    } else {
                        startActivity(Intent(this, ActivityHomeComum::class.java))
                    }
                    finish()
                }.addOnFailureListener {
                    startActivity(Intent(this, ActivityHomeComum::class.java))
                    finish()
                }
            } else {
                startActivity(Intent(this, ActivityHomeComum::class.java))
                finish()
            }
        }

        // Configurações
        val itemConfiguracoes = findViewById<LinearLayout>(R.id.itemConfiguracoes)
        itemConfiguracoes.setOnClickListener {
            startActivity(Intent(this, ActivityConfigConta::class.java))
        }
    }
}