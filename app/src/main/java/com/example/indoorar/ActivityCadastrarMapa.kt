package com.example.indoorar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.ImageViewCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import android.graphics.drawable.Drawable
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata

class ActivityCadastrarMapa : BaseActivity() {

    private lateinit var imgPreview: ImageView
    private lateinit var txtHintImagem: TextView
    private lateinit var editNomeLocal: EditText
    private lateinit var editDescricaoLocal: EditText
    private lateinit var editAndarLocal: EditText
    private lateinit var btnSalvarLocal: MaterialButton
    private lateinit var progressSalvar: ProgressBar

    private var selectedImageUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selected ->
            selectedImageUri = selected
            txtHintImagem.visibility = View.GONE
            // Remover qualquer tint e garantir crop
            ImageViewCompat.setImageTintList(imgPreview, null)
            imgPreview.scaleType = ImageView.ScaleType.CENTER_CROP

            Glide.with(this)
                .load(selected)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        // Fallback para ImageView caso Glide falhe
                        imgPreview.setImageURI(selected)
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable?,
                        model: Any?,
                        target: Target<Drawable>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }
                })
                .into(imgPreview)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastrar_mapa)

        // Views
        imgPreview = findViewById(R.id.imgPreview)
        txtHintImagem = findViewById(R.id.txtHintImagem)
        editNomeLocal = findViewById(R.id.editNomeLocal)
        editDescricaoLocal = findViewById(R.id.editDescricaoLocal)
        editAndarLocal = findViewById(R.id.editAndarLocal)
        btnSalvarLocal = findViewById(R.id.btnSalvarLocal)
        progressSalvar = findViewById(R.id.progressSalvar)

        // Voltar
        findViewById<ImageView>(R.id.btnVoltar).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Escolher imagem
        findViewById<View>(R.id.cardImagem).setOnClickListener { pickImage.launch("image/*") }
        imgPreview.setOnClickListener { pickImage.launch("image/*") }

        // Salvar
        btnSalvarLocal.setOnClickListener { salvarMapa() }
    }

    private fun setLoading(loading: Boolean) {
        progressSalvar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSalvarLocal.isEnabled = !loading
    }

    private fun salvarMapa() {
        val nome = editNomeLocal.text?.toString()?.trim().orEmpty()
        val descricao = editDescricaoLocal.text?.toString()?.trim().orEmpty()
        val quantidadeAndaresStr = editAndarLocal.text?.toString()?.trim().orEmpty()

        if (nome.isEmpty()) { showSnackbar("Informe o nome do mapa"); return }
        if (descricao.isEmpty()) { showSnackbar("Informe a descrição do local"); return }
        val quantidadeAndares = quantidadeAndaresStr.toIntOrNull()
        if (quantidadeAndares == null || quantidadeAndares <= 0) {
            showSnackbar("Informe a quantidade de andares")
            return
        }
        val uri = selectedImageUri
        if (uri == null) {
            showSnackbar("Selecione uma imagem do local")
            return
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showSnackbar("Você precisa estar logado para cadastrar um mapa")
            return
        }

        setLoading(true)

        fun returnToCaller(mapId: String) {
            val data = Intent().apply { putExtra("MAP_ID", mapId) }
            setResult(RESULT_OK, data)
            finish()
        }

        fun saveToFirestore(imageUrl: String) {
            val dados = hashMapOf(
                "nome" to nome,
                "descricao" to descricao,
                "quantidadeAndares" to quantidadeAndares,
                "imagemUrl" to imageUrl,
                // metadados
                "criadorUid" to user.uid,
                "nomeAutor" to (user.displayName ?: ""),
                "dataCriacao" to Timestamp.now()
            )
            FirebaseFirestore.getInstance().collection("mapas")
                .add(dados)
                .addOnSuccessListener { docRef ->
                    showSnackbar("Mapa cadastrado com sucesso.")
                    setLoading(false)
                    returnToCaller(docRef.id)
                }
                .addOnFailureListener { e ->
                    showSnackbar("Erro ao salvar: ${e.message}")
                    setLoading(false)
                }
        }

        // Upload da imagem e depois salvar metadados
        val storageRef = FirebaseStorage.getInstance().reference
            .child("map_images/${user.uid}/${System.currentTimeMillis()}.jpg")
        val contentType = contentResolver.getType(uri) ?: "image/jpeg"
        val metadata = StorageMetadata.Builder()
            .setContentType(contentType)
            .build()

        try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                showSnackbar("Falha ao acessar a imagem selecionada")
                setLoading(false)
                return
            }
            val uploadTask = storageRef.putStream(input, metadata)
            uploadTask
                .addOnSuccessListener {
                    storageRef.downloadUrl
                        .addOnSuccessListener { httpsUri ->
                            saveToFirestore(httpsUri.toString())
                        }
                        .addOnFailureListener { _ ->
                            // Fallback para gs:// se não conseguir URL pública
                            val gsUrl = storageRef.toString()
                            saveToFirestore(gsUrl)
                        }
                }
                .addOnFailureListener { e ->
                    showSnackbar("Não foi possível enviar a imagem: ${e.message}")
                    setLoading(false)
                }
                .addOnCompleteListener { try { input.close() } catch (_: Throwable) {} }
        } catch (t: Throwable) {
            showSnackbar("Falha ao ler imagem: ${t.message}")
            setLoading(false)
        }
    }
}
