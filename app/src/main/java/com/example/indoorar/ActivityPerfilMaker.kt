package com.example.indoorar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

class ActivityPerfilMaker : BaseActivity() {
    companion object {
        private const val prefsName = "perfil_prefs"
        private const val keyImageUri = "image_uri"
        private const val keyImagePath = "image_path"
    }

    private lateinit var userImage: ImageView

    // Helper to (re)configure the BottomNavigationView listener consistently
    private fun setupBottomNav(bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView) {
        bottomNav.setOnItemSelectedListener { item ->
            try {
                when (item.itemId) {
                    R.id.action_home -> { startActivity(Intent(this, ActivityHomeComum::class.java)); true }
                    R.id.action_scan -> { startActivity(Intent(this, ActivityScanQR::class.java)); true }
                    R.id.action_favoritos -> { startActivity(Intent(this, ActivityFavoritos::class.java)); true }
                    R.id.action_config -> { /* already here */ true }
                    R.id.action_criar -> { startActivity(Intent(this, com.example.indoorar.ui.ActivityEditor::class.java)); true }
                    R.id.action_estatisticas -> { startActivity(Intent(this, ActivityEstatisticas::class.java)); true }
                    else -> false
                }
            } catch (e: Exception) {
                Log.e("PerfilMaker", "Erro ao tratar seleção do bottom nav", e)
                false
            }
        }
        // Select profile item after listener is set
        bottomNav.post { try { bottomNav.selectedItemId = R.id.action_config } catch (_: Exception) {} }
    }

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
            Log.e("PerfilMaker", "Erro ao salvar imagem", e)
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
                val err = UCrop.getError(data)
                if (err != null) Log.e("PerfilMaker", "UCrop error", err)
                userImage.setImageResource(R.drawable.account_circle)
            }
        } else if (result.data != null) {
            val err = UCrop.getError(result.data!!)
            if (err != null) Log.e("PerfilMaker", "UCrop RESULT_ERROR", err)
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
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
                val targetPkg = intent.component?.packageName
                    ?: intent.resolveActivity(packageManager)?.packageName
                if (!targetPkg.isNullOrBlank()) {
                    try { grantUriPermission(targetPkg, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    try { grantUriPermission(targetPkg, destinationUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                }
                cropImageLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("PerfilMaker", "Falha ao abrir UCrop, aplicando fallback", e)
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
        if (isGranted) pickImageLauncher.launch("image/*")
        else android.widget.Toast.makeText(this, "Permissão para acessar imagens negada", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_perfil_maker)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
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

        if (savedPath != null) {
            Log.d("PerfilMaker", "Carregando imagem do caminho: $savedPath")
            com.bumptech.glide.Glide.with(this)
                .load(savedPath)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.account_circle)
                .error(R.drawable.account_circle)
                .into(userImage)
        } else if (savedUri != null) {
            try {
                Log.d("PerfilMaker", "Tentando carregar imagem URI: $savedUri")
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
            userImage.setImageResource(R.drawable.account_circle)
        }

        userImage.setOnClickListener {
            val options = arrayOf("Remover foto de perfil", "Escolher da galeria")
            AlertDialog.Builder(this)
                .setTitle("Foto de perfil")
                .setItems(options) { dialog, which ->
                    when (which) {
                        0 -> {
                            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                            prefs.edit { remove(keyImagePath).remove(keyImageUri) }
                            userImage.setImageResource(R.drawable.account_circle)
                        }
                        1 -> {
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

        val itemSair = findViewById<LinearLayout>(R.id.itemSair)
        itemSair.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(this, ActivityLogin::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        val itemConfiguracoes = findViewById<LinearLayout>(R.id.itemConfiguracoes)
        itemConfiguracoes?.setOnClickListener {
            startActivity(Intent(this, ActivityConfigConta::class.java))
        }
        val itemHistorico = findViewById<LinearLayout>(R.id.itemHistorico)
        itemHistorico?.setOnClickListener {
            android.widget.Toast.makeText(this, "Histórico - em desenvolvimento", android.widget.Toast.LENGTH_SHORT).show()
        }
        val itemCalendario = findViewById<LinearLayout>(R.id.itemCalendario)
        itemCalendario?.setOnClickListener {
            android.widget.Toast.makeText(this, "Estatísticas - em desenvolvimento", android.widget.Toast.LENGTH_SHORT).show()
        }
        val itemAjuda = findViewById<LinearLayout>(R.id.itemAjuda)
        itemAjuda?.setOnClickListener {
            android.widget.Toast.makeText(this, "Ajuda e Suporte - em desenvolvimento", android.widget.Toast.LENGTH_SHORT).show()
        }

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavPerfil)
        if (bottomNav != null) {
            bottomNav.menu.clear()
            bottomNav.inflateMenu(R.menu.bottom_nav_maker)
            // Ignore bottom system inset on the BottomNavigationView itself
            ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                insets
            }
            setupBottomNav(bottomNav)
        }
    }

    override fun onResume() {
        super.onResume()
        syncUserEmailToFirestore {
            val user = FirebaseAuth.getInstance().currentUser
            val emailAtual = user?.email ?: "Email não disponível"
            findViewById<android.widget.TextView>(R.id.txtEmailUsuario)?.text = emailAtual
        }
    }
}
