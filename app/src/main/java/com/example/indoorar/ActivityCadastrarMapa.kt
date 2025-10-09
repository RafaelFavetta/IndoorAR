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
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageException

class ActivityCadastrarMapa : BaseActivity() {

    private lateinit var imgPreview: ImageView
    private lateinit var txtHintImagem: TextView
    private lateinit var editNome: TextInputEditText
    private lateinit var editDescricao: TextInputEditText
    private lateinit var dropTipoLocal: AutoCompleteTextView
    private lateinit var editAndar: TextInputEditText
    private lateinit var editHorario: TextInputEditText
    private lateinit var btnSalvar: MaterialButton
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
        editNome = findViewById(R.id.editNome)
        editDescricao = findViewById(R.id.editDescricao)
        dropTipoLocal = findViewById(R.id.dropTipoLocal)
        editAndar = findViewById(R.id.editAndar)
        editHorario = findViewById(R.id.editHorario)
        btnSalvar = findViewById(R.id.btnSalvar)
        progressSalvar = findViewById(R.id.progressSalvar)

        // Voltar
        findViewById<ImageView>(R.id.btnVoltar).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Dropdown tipos
        val tipos = resources.getStringArray(R.array.tipos_locais)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, tipos)
        dropTipoLocal.setAdapter(adapter)

        // Escolher imagem
        findViewById<View>(R.id.cardImagem).setOnClickListener { pickImage.launch("image/*") }
        imgPreview.setOnClickListener { pickImage.launch("image/*") }

        // Salvar
        btnSalvar.setOnClickListener { salvarMapa() }
    }

    private fun setLoading(loading: Boolean) {
        progressSalvar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSalvar.isEnabled = !loading
    }

    private fun salvarMapa() {
        val nome = editNome.text?.toString()?.trim().orEmpty()
        val descricao = editDescricao.text?.toString()?.trim().orEmpty()
        val tipo = dropTipoLocal.text?.toString()?.trim().orEmpty()
        val andar = editAndar.text?.toString()?.trim().orEmpty()
        val horario = editHorario.text?.toString()?.trim().orEmpty()

        // Chips
        val recursos = mutableListOf<String>()
        if (findViewById<CompoundButton>(R.id.chipAcessivel).isChecked) recursos += "Acessível"
        if (findViewById<CompoundButton>(R.id.chipElevador).isChecked) recursos += "Elevador"
        if (findViewById<CompoundButton>(R.id.chipEscadas).isChecked) recursos += "Escadas"
        if (findViewById<CompoundButton>(R.id.chipBanheiros).isChecked) recursos += "Banheiros"
        if (findViewById<CompoundButton>(R.id.chipWifi).isChecked) recursos += "Wi-Fi"
        if (findViewById<CompoundButton>(R.id.chipEstacionamento).isChecked) recursos += "Estacionamento"

        if (nome.isEmpty()) { showSnackbar("Informe o nome do mapa"); return }
        if (descricao.isEmpty()) { showSnackbar("Informe a descrição"); return }
        if (tipo.isEmpty()) { showSnackbar("Selecione o tipo do local"); return }

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

        fun saveToFirestore(imageUrl: String?) {
            val dados = hashMapOf(
                "nome" to nome,
                "descricao" to descricao,
                "tipo" to tipo,
                "andar" to andar,
                "horario" to horario,
                "recursos" to recursos,
                "imagemUrl" to (imageUrl ?: ""),
                // campos compatíveis com fluxo maker
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

        val uri = selectedImageUri
        if (uri == null) {
            saveToFirestore(null)
        } else {
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
                    saveToFirestore(null)
                    return
                }
                val uploadTask = storageRef.putStream(input, metadata)
                uploadTask
                    .addOnSuccessListener {
                        // Upload OK -> tentar obter URL pública
                        storageRef.downloadUrl
                            .addOnSuccessListener { httpsUri ->
                                saveToFirestore(httpsUri.toString())
                            }
                            .addOnFailureListener { _ ->
                                // Upload ok mas não deu para obter downloadUrl -> salvar gs:// como fallback
                                val gsUrl = storageRef.toString()
                                saveToFirestore(gsUrl)
                            }
                    }
                    .addOnFailureListener { e ->
                        val msg = when (e) {
                            is StorageException -> when (e.errorCode) {
                                StorageException.ERROR_NOT_AUTHORIZED -> "Sem permissão para enviar imagem (verifique as regras do Storage)"
                                StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> "Falha de rede ao enviar imagem"
                                else -> e.message
                            }
                            else -> e.message
                        }
                        showSnackbar("Não foi possível enviar a imagem: ${msg ?: "erro desconhecido"}")
                        saveToFirestore(null)
                    }
                    .addOnCompleteListener { try { input.close() } catch (_: Throwable) {} }
            } catch (t: Throwable) {
                showSnackbar("Falha ao ler imagem: ${t.message}")
                saveToFirestore(null)
            }
        }
    }
}
