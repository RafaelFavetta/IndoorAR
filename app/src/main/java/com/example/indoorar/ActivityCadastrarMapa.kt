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
import com.google.firebase.storage.StorageReference
import android.os.Handler
import android.os.Looper
import com.google.firebase.storage.StorageException
import com.google.firebase.FirebaseApp

class ActivityCadastrarMapa : BaseActivity() {

    private lateinit var imgPreview: ImageView
    private lateinit var txtHintImagem: TextView
    private lateinit var editNomeLocal: EditText
    private lateinit var editDescricaoLocal: EditText
    private lateinit var editAndarLocal: EditText
    private lateinit var btnSalvarLocal: MaterialButton
    private lateinit var progressSalvar: ProgressBar

    private var selectedImageUri: Uri? = null // manterá a URI local copiada
    private var originalPickedUri: Uri? = null // URI original do provedor (content://)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selected ->
            originalPickedUri = selected
            val copied = copyUriToCache(selected)
            if (copied == null) {
                showSnackbar("Falha ao acessar a imagem selecionada")
                return@let
            }
            selectedImageUri = copied
            txtHintImagem.visibility = View.GONE
            // Remover qualquer tint e garantir crop
            ImageViewCompat.setImageTintList(imgPreview, null)
            imgPreview.scaleType = ImageView.ScaleType.CENTER_CROP

            Glide.with(this)
                .load(copied)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        // Fallback
                        imgPreview.setImageURI(copied)
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable?,
                        model: Any?,
                        target: Target<Drawable>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                    ): Boolean { return false }
                })
                .into(imgPreview)
        }
    }

    // Copia a URI para um arquivo local no cache e retorna sua URI
    private fun copyUriToCache(src: Uri): Uri? {
        return try {
            val mime = contentResolver.getType(src) ?: "image/jpeg"
            val ext = when {
                mime.endsWith("png") -> ".png"
                mime.endsWith("webp") -> ".webp"
                mime.endsWith("heic") -> ".heic"
                else -> ".jpg"
            }
            val outFile = java.io.File(cacheDir, "upload_${System.currentTimeMillis()}$ext")
            contentResolver.openInputStream(src)?.use { input ->
                java.io.FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Uri.fromFile(outFile)
        } catch (_: Exception) { null }
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

        // Helpers
        fun extFromMime(mime: String): String = when {
            mime.endsWith("png") -> "png"
            mime.endsWith("webp") -> "webp"
            mime.endsWith("heic") -> "heic"
            else -> "jpg"
        }

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

        // Helpers
        fun makeGsUrl(ref: StorageReference): String = "gs://${ref.bucket}${ref.path}"

        fun fetchDownloadUrlWithRetry(
            ref: StorageReference,
            retries: Int = 3,
            initialDelayMs: Long = 300,
            onDone: (Uri?) -> Unit
        ) {
            ref.downloadUrl
                .addOnSuccessListener { onDone(it) }
                .addOnFailureListener { err ->
                    if (retries <= 0) {
                        onDone(null)
                    } else {
                        val nextDelay = (initialDelayMs * 2).coerceAtMost(2000)
                        mainHandler.postDelayed({
                            fetchDownloadUrlWithRetry(ref, retries - 1, nextDelay, onDone)
                        }, initialDelayMs)
                    }
                }
        }

        // Upload da imagem e depois salvar metadados
        val contentType = (originalPickedUri?.let { contentResolver.getType(it) }
            ?: contentResolver.getType(uri)) ?: "image/jpeg"
        val fileExt = extFromMime(contentType)
        val metadata = StorageMetadata.Builder()
            .setContentType(contentType)
            .build()
        val path = "map_images/${user.uid}/${System.currentTimeMillis()}.$fileExt"

        val errorLog = mutableListOf<String>()

        fun handleUploadSuccess(ref: StorageReference) {
            fetchDownloadUrlWithRetry(ref, retries = 3, initialDelayMs = 300) { dlUri ->
                val imageUrl = dlUri?.toString() ?: makeGsUrl(ref)
                saveToFirestore(imageUrl)
            }
        }
        fun handleUploadFailureFinal() {
            val details = if (errorLog.isEmpty()) "Sem detalhes" else errorLog.joinToString(" | ")
            showSnackbar("Não foi possível enviar a imagem: $details")
            setLoading(false)
        }

        fun attemptUpload(candidates: List<com.google.firebase.storage.FirebaseStorage>, idx: Int = 0) {
            if (idx >= candidates.size) { handleUploadFailureFinal(); return }
            val storage = candidates[idx]
            val ref = storage.reference.child(path)
            ref.putFile(uri, metadata)
                .addOnSuccessListener { handleUploadSuccess(ref) }
                .addOnFailureListener { e ->
                    val bucketInfo = try { storage.app.options.storageBucket ?: "<sem bucket>" } catch (_: Exception) { "<erro bucket>" }
                    val msg = when (e) {
                        is com.google.firebase.storage.StorageException -> "bucket=$bucketInfo code=${e.errorCode} msg=${e.message}"
                        else -> "bucket=$bucketInfo msg=${e.message}"
                    }
                    errorLog += msg
                    // tenta próximo candidato
                    attemptUpload(candidates, idx + 1)
                }
        }

        try {
            val storages = getStorageCandidates()
            attemptUpload(storages)
        } catch (t: Throwable) {
            showSnackbar("Falha ao iniciar upload: ${t.message}")
            setLoading(false)
        }
    }

    // Lista candidatos de Storage (corrigido, default, e derivado do projectId) - prioriza projectId.appspot.com
    private fun getStorageCandidates(): List<com.google.firebase.storage.FirebaseStorage> {
        val out = mutableListOf<com.google.firebase.storage.FirebaseStorage>()
        val app = try { FirebaseApp.getInstance() } catch (_: Exception) { null }
        val options = app?.options
        val projectId = options?.projectId
        val bucket = options?.storageBucket
        val corrected = bucket?.let {
            if (it.endsWith(".firebasestorage.app")) it.replace(".firebasestorage.app", ".appspot.com") else it
        }
        val derived = if (!projectId.isNullOrBlank()) "$projectId.appspot.com" else null
        // 1) Derivado do projectId (se existir)
        if (derived != null) out += com.google.firebase.storage.FirebaseStorage.getInstance("gs://$derived")
        // 2) options.storageBucket corrigido, mas apenas se contiver o projectId (para evitar buckets de outro projeto)
        if (!corrected.isNullOrBlank() && !projectId.isNullOrBlank() && corrected.contains(projectId)) {
            out += com.google.firebase.storage.FirebaseStorage.getInstance("gs://$corrected")
        }
        // 3) default
        out += com.google.firebase.storage.FirebaseStorage.getInstance()
        return out.distinctBy { it.toString() }
    }

    // Obtém instância do FirebaseStorage com bucket corrigido, se necessário
    private fun getStorage(): com.google.firebase.storage.FirebaseStorage {
        val bucket = try { FirebaseApp.getInstance().options.storageBucket } catch (_: Exception) { null }
        val corrected = bucket?.let {
            if (it.endsWith(".firebasestorage.app")) it.replace(".firebasestorage.app", ".appspot.com") else it
        }
        return if (!corrected.isNullOrBlank()) com.google.firebase.storage.FirebaseStorage.getInstance("gs://$corrected")
        else com.google.firebase.storage.FirebaseStorage.getInstance()
    }
}
