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
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import com.google.firebase.firestore.Blob

class ActivityCadastrarMapa : BaseActivity() {

    private lateinit var imgPreview: ImageView
    private lateinit var txtHintImagem: TextView
    private lateinit var editNomeLocal: EditText
    private lateinit var editDescricaoLocal: EditText
    private lateinit var editAndarLocal: EditText
    private lateinit var btnSalvarLocal: MaterialButton
    private lateinit var progressSalvar: ProgressBar

    private var selectedImageUri: Uri? = null // manterá a URI local copiada

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selected ->
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

        fun returnToCaller(mapId: String) {
            val data = Intent().apply { putExtra("MAP_ID", mapId) }
            setResult(RESULT_OK, data)
            finish()
        }

        // Comprimir duas versões: medium e thumbnail
        try {
            val medium = compressUri(uri, targetMaxBytes = 720_000, maxDim = 1280)
            val thumb = compressUri(uri, targetMaxBytes = 150_000, maxDim = 600)
            val mediumBytes = medium.first
            val thumbBytes = thumb.first
            val mediumMime = medium.second
            val thumbMime = thumb.second
            if (mediumBytes == null && thumbBytes == null) {
                showSnackbar("Não foi possível processar a imagem selecionada")
                setLoading(false)
                return
            }
            // Garantir que o total do documento não ultrapasse ~1MB: se necessário, priorizar thumb
            val total = (mediumBytes?.size ?: 0) + (thumbBytes?.size ?: 0)
            val (finalMedium, finalThumb) = if (total > 950_000) {
                // Se excedeu, descarta medium e mantém só o thumb
                Pair(null, thumbBytes)
            } else Pair(mediumBytes, thumbBytes)

            val dados = hashMapOf(
                "nome" to nome,
                "descricao" to descricao,
                "quantidadeAndares" to quantidadeAndares,
                // blobs
                "imagemBlob" to (finalMedium?.let { Blob.fromBytes(it) }),
                "imagemMime" to (finalMedium?.let { mediumMime } ?: thumbMime), // mime principal
                "imagemBlobThumb" to (finalThumb?.let { Blob.fromBytes(it) }),
                "imagemMimeThumb" to (finalThumb?.let { thumbMime }),
                // compatibilidade
                "imagemUrl" to "",
                // metadados
                "criadorUid" to user.uid,
                "nomeAutor" to (user.displayName ?: ""),
                "dataCriacao" to Timestamp.now()
            )
            // Remove nulls para não gravar campos vazios
            val sanitized = dados.filterValues { it != null }
            FirebaseFirestore.getInstance().collection("mapas")
                .add(sanitized)
                .addOnSuccessListener { docRef ->
                    showSnackbar("Mapa cadastrado com sucesso.")
                    setLoading(false)
                    returnToCaller(docRef.id)
                }
                .addOnFailureListener { e ->
                    showSnackbar("Erro ao salvar: ${e.message}")
                    setLoading(false)
                }
        } catch (t: Throwable) {
            showSnackbar("Falha ao processar imagem: ${t.message}")
            setLoading(false)
        }
    }

    // Compacta a imagem da URI com limites de tamanho e dimensão
    private fun compressUri(uri: Uri, targetMaxBytes: Int, maxDim: Int): Pair<ByteArray?, String> {
        // 1) Ler dimensões
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val origW = bounds.outWidth
        val origH = bounds.outHeight
        if (origW <= 0 || origH <= 0) return Pair(null, "image/jpeg")

        // 2) Calcular inSampleSize
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(origW, origH, maxDim, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: return Pair(null, "image/jpeg")

        // 3) Comprimir JPEG com qualidade decrescente
        val baos = ByteArrayOutputStream()
        var quality = 90
        var outBytes: ByteArray
        do {
            baos.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            outBytes = baos.toByteArray()
            quality -= 10
        } while (outBytes.size > targetMaxBytes && quality >= 30)

        // Se ainda grande, downscale adicional e qualidade moderada
        if (outBytes.size > targetMaxBytes) {
            val scale = 0.75f
            val newW = (bmp.width * scale).toInt().coerceAtLeast(300)
            val newH = (bmp.height * scale).toInt().coerceAtLeast(300)
            val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
            baos.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            outBytes = baos.toByteArray()
            if (!scaled.isRecycled) scaled.recycle()
        }
        if (!bmp.isRecycled) bmp.recycle()
        return Pair(outBytes, "image/jpeg")
    }

    private fun calculateInSampleSize(origW: Int, origH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (origH > reqH || origW > reqW) {
            val halfH = origH / 2
            val halfW = origW / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
