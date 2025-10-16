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
import androidx.core.content.FileProvider

class ActivityPerfil : BaseActivity() {
    companion object {
        private const val prefsName = "perfil_prefs"
        private const val keyImageUri = "image_uri"
        private const val keyImagePath = "image_path"
    }
    private lateinit var userImage: ImageView
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
                    val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                    prefs.edit { putString(keyImagePath, imagePath).remove(keyImageUri) }
                } else {
                    userImage.setImageResource(R.drawable.account_circle)
                }
            } else {
                // Se UCrop não retornou URI, tenta obter erro para log
                val err = UCrop.getError(data)
                if (err != null) Log.e("Perfil", "UCrop error", err)
                userImage.setImageResource(R.drawable.account_circle)
            }
        } else if (result.data != null) {
            // Resultado de erro específico do UCrop
            val err = UCrop.getError(result.data!!)
            if (err != null) Log.e("Perfil", "UCrop RESULT_ERROR", err)
        }
    }
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                // Usar FileProvider para o destino do UCrop e garantir permissões temporárias
                val destinationFile = File(getExternalFilesDir(null), "cropped_profile_image.jpg")
                val destinationUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    destinationFile
                )
                val uCrop = UCrop.of(it, destinationUri)
                    .withAspectRatio(1f, 1f)
                    .withOptions(UCrop.Options().apply {
                        setCircleDimmedLayer(true)
                        setShowCropFrame(false)
                        setShowCropGrid(false)
                    })
                val intent = uCrop.getIntent(this).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                // Concede permissões explícitas ao Activity do UCrop
                val targetPkg = intent.component?.packageName
                    ?: intent.resolveActivity(packageManager)?.packageName
                if (!targetPkg.isNullOrBlank()) {
                    try {
                        grantUriPermission(targetPkg, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                    try {
                        grantUriPermission(targetPkg, destinationUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (_: Exception) {}
                }
                cropImageLauncher.launch(intent)
            } catch (e: Exception) {
                // Fallback: se não conseguir abrir o crop, salva a imagem direta sem cortar
                Log.e("Perfil", "Falha ao abrir UCrop, aplicando fallback", e)
                try {
                    val imagePath = saveImageToInternalStorage(it)
                    if (imagePath != null) {
                        com.bumptech.glide.Glide.with(this)
                            .load(imagePath)
                            .apply(RequestOptions.circleCropTransform())
                            .placeholder(R.drawable.account_circle)
                            .error(R.drawable.account_circle)
                            .into(userImage)
                        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                        prefs.edit { putString(keyImagePath, imagePath).remove(keyImageUri) }
                    } else {
                        userImage.setImageResource(R.drawable.account_circle)
                    }
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, "Erro ao processar imagem selecionada", android.widget.Toast.LENGTH_LONG).show()
                }
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
        userImage = findViewById(R.id.userImage)
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val savedPath = prefs.getString(keyImagePath, null)
        val savedUri = prefs.getString(keyImageUri, null)
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
            } catch (_: Exception) {
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
                            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                            prefs.edit { remove(keyImagePath).remove(keyImageUri) }
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
                    val tipo = (doc.getString("tipoConta") ?: "comum").lowercase()
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

    override fun onResume() {
        super.onResume()
        // Ao voltar para o perfil, sincroniza Firestore e atualiza
        syncUserEmailToFirestore {
            val user = FirebaseAuth.getInstance().currentUser
            val emailAtual = user?.email ?: "Email não disponível"
            findViewById<android.widget.TextView>(R.id.txtEmailUsuario)?.text = emailAtual
        }
    }
}
