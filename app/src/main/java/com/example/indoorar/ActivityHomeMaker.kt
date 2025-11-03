package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import android.widget.LinearLayout
import androidx.core.graphics.set
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import com.google.android.material.bottomnavigation.BottomNavigationView

class ActivityHomeMaker : BaseActivity() {

    private lateinit var recyclerRecentes: RecyclerView
    private lateinit var progressRecentes: ProgressBar
    private lateinit var indicatorsRecentes: LinearLayout
    private lateinit var snapHelper: PagerSnapHelper
    private var recentesListener: ListenerRegistration? = null

    private val recentAdapter = RecentPagesAdapter { mapa ->
        // Abrir bottom sheet de preview (mesmo comportamento da HomeComum)
        onMapaClicked(mapa)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_maker)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Texto de boas-vindas
        findViewById<TextView>(R.id.txtBemVindo).text = getString(R.string.bem_vindo)


        // Navbar
        try {
            findViewById<BottomNavigationView>(R.id.bottomNavMaker)?.apply {
                try {
                    selectedItemId = R.id.action_home
                } catch (_: Exception) {}

                setOnItemSelectedListener { item ->
                    try {
                        if (this.selectedItemId == item.itemId) return@setOnItemSelectedListener true

                        when (item.itemId) {
                            R.id.action_home -> { startActivity(Intent(this@ActivityHomeMaker, ActivityHomeMaker::class.java)); true }
                            R.id.action_criar -> { startActivity(Intent(this@ActivityHomeMaker, com.example.indoorar.ui.ActivityEditor::class.java)); true }
                            R.id.action_estatisticas -> { startActivity(Intent(this@ActivityHomeMaker, ActivityEstatisticas::class.java)); true }
                            R.id.action_config -> { startActivity(Intent(this@ActivityHomeMaker, ActivityPerfilMaker::class.java)); true }
                            else -> false
                        }
                    } catch (e: Exception) {
                        Log.e("HomeMaker", "Erro no bottomNavMaker listener", e)
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomeMaker", "Falha ao configurar bottomNavMaker", e)
        }

        // Views de recentes (carrossel horizontal paginado)
        recyclerRecentes = findViewById(R.id.recyclerRecentes)
        progressRecentes = findViewById(R.id.progressRecentes)
        indicatorsRecentes = findViewById(R.id.indicatorsRecentes)

        recyclerRecentes.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        recyclerRecentes.adapter = recentAdapter

        snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerRecentes)

        recyclerRecentes.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = recyclerView.layoutManager ?: return
                    val snapView = snapHelper.findSnapView(lm) ?: return
                    val pos = recyclerView.getChildAdapterPosition(snapView)
                    if (pos != RecyclerView.NO_POSITION) setCurrentIndicator(pos)
                }
            }
        })

        carregarMapasRecentesEmTempoReal()
    }

    override fun onDestroy() {
        super.onDestroy()
        recentesListener?.remove()
    }

    private fun imageKeyFor(m: MapaResumo): String {
        val url = m.imagemUrl
        if (!url.isNullOrBlank()) return "url:$url"
        val t = m.imagemBlobThumb?.toBytes()
        if (t != null && t.isNotEmpty()) return "thumb:${t.contentHashCode()}:${t.size}"
        val b = m.imagemBlob?.toBytes()
        if (b != null && b.isNotEmpty()) return "blob:${b.contentHashCode()}:${b.size}"
        return "id:${m.id}"
    }

    private fun carregarMapasRecentesEmTempoReal() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            // vazio/sem usuário: esconder lista e indicadores
            progressRecentes.visibility = View.GONE
            recyclerRecentes.visibility = View.GONE
            buildIndicators(0)
            return
        }
        progressRecentes.visibility = View.VISIBLE
        recyclerRecentes.visibility = View.GONE

        recentesListener?.remove()
        recentesListener = FirebaseFirestore.getInstance().collection("mapas")
            .whereEqualTo("criadorUid", uid)
            .limit(200)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    progressRecentes.visibility = View.GONE
                    recyclerRecentes.visibility = View.GONE
                    buildIndicators(0)
                    return@addSnapshotListener
                }
                val lista = snap?.documents?.map { docParaMapaResumoMaker(it, uid) } ?: emptyList()
                val ordenada = lista.sortedByDescending { it.dataCriacao?.seconds ?: 0 }
                val unicos = ordenada.distinctBy { imageKeyFor(it) }
                val limited = unicos.take(10) // 5 páginas x 2 itens por página
                recentAdapter.submit(limited)
                progressRecentes.visibility = View.GONE
                if (recentAdapter.itemCount == 0) {
                    recyclerRecentes.visibility = View.GONE
                } else {
                    recyclerRecentes.visibility = View.VISIBLE
                }
                buildIndicators(recentAdapter.itemCount)

                val lm = recyclerRecentes.layoutManager
                val snapView = if (lm != null) snapHelper.findSnapView(lm) else null
                val pos = if (snapView != null) recyclerRecentes.getChildAdapterPosition(snapView) else 0
                if (pos >= 0) setCurrentIndicator(pos)
            }
    }

    private fun docParaMapaResumoMaker(doc: DocumentSnapshot, uid: String): MapaResumo {
        return MapaResumo(
            id = doc.id,
            nome = doc.getString("nome") ?: "Mapa sem nome",
            descricao = doc.getString("descricao") ?: "",
            autorUid = doc.getString("criadorUid") ?: uid,
            autorNome = doc.getString("nomeAutor") ?: uid,
            dataCriacao = doc.getTimestamp("dataCriacao"),
            imagemUrl = doc.getString("imagemUrl"),
            imagemBlob = doc.getBlob("imagemBlob"),
            imagemMime = doc.getString("imagemMime"),
            imagemBlobThumb = doc.getBlob("imagemBlobThumb"),
            imagemMimeThumb = doc.getString("imagemMimeThumb")
        )
    }

    private fun buildIndicators(count: Int) {
        indicatorsRecentes.removeAllViews()
        if (count <= 1) {
            indicatorsRecentes.visibility = View.GONE
            return
        }
        indicatorsRecentes.visibility = View.VISIBLE
        val dm = resources.displayMetrics
        val dotMargin = (4 * dm.density).toInt()
        repeat(count) { idx ->
            val iv = ImageView(this)
            iv.setImageResource(if (idx == 0) R.drawable.dot_selected else R.drawable.dot_unselected)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dotMargin, 0, dotMargin, 0)
            iv.layoutParams = lp
            indicatorsRecentes.addView(iv)
        }
    }

    private fun setCurrentIndicator(index: Int) {
        val n = indicatorsRecentes.childCount
        for (i in 0 until n) {
            val iv = indicatorsRecentes.getChildAt(i) as? ImageView ?: continue
            iv.setImageResource(if (i == index) R.drawable.dot_selected else R.drawable.dot_unselected)
        }
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(R.layout.bottomsheet_mapa_preview)

        val txtTitulo = dialog.findViewById<TextView>(R.id.txtTituloMapa)
        val txtDesc = dialog.findViewById<TextView>(R.id.txtDescricaoMapa)
        val ivPreview = dialog.findViewById<ImageView>(R.id.ivPreview)
        val btnIniciar = dialog.findViewById<android.widget.Button>(R.id.btnIniciarNavegacao)
        val btnBaixar = dialog.findViewById<android.widget.Button>(R.id.btnBaixarQRCode)
        val cardDownload = dialog.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDownloadQRCode)
        val btnPDF = dialog.findViewById<android.widget.Button>(R.id.btnDownloadPDF)
        val btnPNG = dialog.findViewById<android.widget.Button>(R.id.btnDownloadPNG)

        txtTitulo?.text = m.nome
        txtDesc?.text = m.descricao.ifBlank { "Sem descrição" }

        // Carrega preview (thumb/medium/url)
        if (ivPreview != null) {
            val medium = m.imagemBlob?.toBytes()
            val thumb = m.imagemBlobThumb?.toBytes()
            when {
                medium != null && medium.isNotEmpty() -> {
                    com.bumptech.glide.Glide.with(ivPreview.context)
                        .load(medium)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(ivPreview)
                }
                thumb != null && thumb.isNotEmpty() -> {
                    com.bumptech.glide.Glide.with(ivPreview.context)
                        .load(thumb)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(ivPreview)
                }
                else -> {
                    val url = m.imagemUrl
                    if (!url.isNullOrBlank()) {
                        if (url.startsWith("gs://")) {
                            val ref = com.google.firebase.storage.FirebaseStorage.getInstance().getReferenceFromUrl(url)
                            ref.downloadUrl
                                .addOnSuccessListener { httpsUri ->
                                    com.bumptech.glide.Glide.with(ivPreview.context)
                                        .load(httpsUri)
                                        .centerCrop()
                                        .placeholder(R.drawable.ic_minimap_placeholder)
                                        .error(R.drawable.ic_minimap_placeholder)
                                        .into(ivPreview)
                                }
                                .addOnFailureListener {
                                    ivPreview.setImageResource(R.drawable.ic_minimap_placeholder)
                                }
                        } else {
                            com.bumptech.glide.Glide.with(ivPreview.context)
                                .load(url)
                                .centerCrop()
                                .placeholder(R.drawable.ic_minimap_placeholder)
                                .error(R.drawable.ic_minimap_placeholder)
                                .into(ivPreview)
                        }
                    } else {
                        ivPreview.setImageResource(R.drawable.ic_minimap_placeholder)
                    }
                }
            }
        }

        btnIniciar?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ActivityNavHud::class.java).apply {
                putExtra("MAP_ID", m.id)
            })
        }

        btnBaixar?.setOnClickListener {
            // Substituir os botões principais pelos botões de download
            btnIniciar?.visibility = View.GONE
            btnBaixar.visibility = View.GONE
            cardDownload?.visibility = View.VISIBLE
        }

        btnPNG?.setOnClickListener {
            val ok = saveQrAsPng(m.id)
            android.widget.Toast.makeText(this, if (ok) "QR salvo em Imagens/IndoorAR" else "Falha ao salvar QR", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnPDF?.setOnClickListener {
            val ok = saveQrAsPdf(m.id)
            android.widget.Toast.makeText(this, if (ok) "PDF salvo em Downloads" else "Falha ao salvar PDF", android.widget.Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun generateQrBitmap(content: String, size: Int = 1024): android.graphics.Bitmap? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
                com.google.zxing.EncodeHintType.MARGIN to 1
            )
            val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = createBitmap(width, height)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp[x, y] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            bmp
        } catch (_: Exception) { null }
    }

    private fun saveQrAsPng(mapId: String): Boolean {
        val bmp = generateQrBitmap(mapId) ?: return false
        return try {
            val filename = "QR_${mapId}_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IndoorAR")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) { false }
    }

    private fun saveQrAsPdf(mapId: String): Boolean {
        return try {
            val bmp = generateQrBitmap(mapId, 1024) ?: return false
            val filename = "QR_${mapId}_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.pdf"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri: android.net.Uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                writeSimplePdfWithBitmap(bmp, out)
            } ?: return false
            values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) { false }
    }

    private fun writeSimplePdfWithBitmap(bmp: android.graphics.Bitmap, out: java.io.OutputStream) {
        val pageWidth = 595 // A4 width at 72dpi (~8.27in * 72)
        val pageHeight = 842 // A4 height at 72dpi (~11.69in * 72)
        val scale =
            kotlin.math.min(pageWidth.toFloat() / bmp.width, pageHeight.toFloat() / bmp.height)
        val scaled = bmp.scale((bmp.width * scale).toInt(), (bmp.height * scale).toInt())
        val document = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val left = (pageWidth - scaled.width) / 2f
        val top = (pageHeight - scaled.height) / 2f
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(scaled, left, top, null)
        document.finishPage(page)
        document.writeTo(out)
        document.close()
        if (!scaled.isRecycled) scaled.recycle()
    }
}