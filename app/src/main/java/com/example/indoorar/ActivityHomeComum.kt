package com.example.indoorar

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import android.widget.LinearLayout
import androidx.core.graphics.scale

class ActivityHomeComum : BaseActivity() {

    private lateinit var recyclerRecentes: RecyclerView
    private lateinit var progressRecentes: android.widget.ProgressBar
    private lateinit var txtEmptyRecentes: TextView
    private lateinit var indicatorsRecentes: LinearLayout
    private lateinit var snapHelper: PagerSnapHelper
    private val recentAdapter = RecentPagesAdapter { mapa -> onMapaClicked(mapa) }
    private var recentesListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_comum)

        // System insets
        val mainView = findViewById<View>(R.id.main)
        if (mainView == null) {
            Toast.makeText(this, "Erro: View 'main' não encontrada no layout.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<ImageView>(R.id.btnEscanear)?.setOnClickListener {
            startActivity(Intent(this, ActivityScanQR::class.java))
        }
        findViewById<ImageView>(R.id.btnPerfil)?.setOnClickListener {
            startActivity(Intent(this, ActivityPerfil::class.java))
        }
        findViewById<ImageView>(R.id.btnMapasExistentes)?.setOnClickListener {
            startActivity(Intent(this, ActivityMapasExistentes::class.java))
        }
        findViewById<androidx.cardview.widget.CardView>(R.id.cardFavoritos)?.setOnClickListener {
            startActivity(Intent(this, ActivityFavoritos::class.java))
        }

        // Lista de recentes (carrossel horizontal paginado)
        recyclerRecentes = findViewById(R.id.recyclerRecentes)
        progressRecentes = findViewById(R.id.progressRecentes)
        txtEmptyRecentes = findViewById(R.id.txtEmptyRecentes)
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

    private fun imageKeyFor(m: MapaResumo): String {
        val url = m.imagemUrl
        if (!url.isNullOrBlank()) return "url:$url"
        val t = m.imagemBlobThumb?.toBytes()
        if (t != null && t.isNotEmpty()) return "thumb:${java.util.Arrays.hashCode(t)}:${t.size}"
        val b = m.imagemBlob?.toBytes()
        if (b != null && b.isNotEmpty()) return "blob:${java.util.Arrays.hashCode(b)}:${b.size}"
        return "id:${m.id}"
    }

    private fun carregarMapasRecentesEmTempoReal() {
        progressRecentes.visibility = View.VISIBLE
        txtEmptyRecentes.visibility = View.GONE

        recentesListener?.remove()
        recentesListener = FirebaseFirestore.getInstance().collection("mapas")
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .limit(20) // busca um pouco mais para garantir imagens únicas suficientes
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    progressRecentes.visibility = View.GONE
                    txtEmptyRecentes.visibility = View.VISIBLE
                    txtEmptyRecentes.text = "Não foi possível carregar os mapas."
                    buildIndicators(0)
                    return@addSnapshotListener
                }
                val lista = snap?.documents?.map { docParaMapaResumoSeguro(it) } ?: emptyList()
                val unicos = lista.distinctBy { imageKeyFor(it) }
                val limited = unicos.take(10) // 5 páginas x 2 itens por página
                recentAdapter.submit(limited)
                progressRecentes.visibility = View.GONE
                txtEmptyRecentes.visibility = if (limited.isEmpty()) View.VISIBLE else View.GONE

                // Atualiza indicadores (recentAdapter trabalha em páginas)
                buildIndicators(recentAdapter.itemCount)

                // Seleciona o indicador inicial baseado na página atual (snapped)
                val lm = recyclerRecentes.layoutManager
                val snapView = if (lm != null) snapHelper.findSnapView(lm) else null
                val pos = if (snapView != null) recyclerRecentes.getChildAdapterPosition(snapView) else 0
                if (pos >= 0) setCurrentIndicator(pos)
            }
    }

    private fun docParaMapaResumoSeguro(doc: DocumentSnapshot): MapaResumo {
        return MapaResumo(
            id = doc.id,
            nome = doc.getString("nome") ?: "Mapa sem nome",
            descricao = doc.getString("descricao") ?: "",
            autorUid = doc.getString("criadorUid") ?: "",
            autorNome = doc.getString("nomeAutor") ?: (doc.getString("criadorUid") ?: ""),
            dataCriacao = doc.getTimestamp("dataCriacao"),
            imagemUrl = doc.getString("imagemUrl"),
            imagemBlob = doc.getBlob("imagemBlob"),
            imagemMime = doc.getString("imagemMime"),
            imagemBlobThumb = doc.getBlob("imagemBlobThumb"),
            imagemMimeThumb = doc.getString("imagemMimeThumb")
        )
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.bottomsheet_mapa_preview)

        val txtTitulo = dialog.findViewById<TextView>(R.id.txtTituloMapa)
        val txtDesc = dialog.findViewById<TextView>(R.id.txtDescricaoMapa)
        val ivPreview = dialog.findViewById<ImageView>(R.id.ivPreview)
        val btnIniciar = dialog.findViewById<Button>(R.id.btnIniciarNavegacao)
        val btnBaixar = dialog.findViewById<Button>(R.id.btnBaixarQRCode)
        val cardDownload = dialog.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDownloadQRCode)
        val btnPDF = dialog.findViewById<Button>(R.id.btnDownloadPDF)
        val btnPNG = dialog.findViewById<Button>(R.id.btnDownloadPNG)

        txtTitulo?.text = m.nome
        txtDesc?.text = m.descricao.ifBlank { "Sem descrição" }

        if (ivPreview != null) {
            val medium = m.imagemBlob?.toBytes()
            val thumb = m.imagemBlobThumb?.toBytes()
            when {
                medium != null && medium.isNotEmpty() -> {
                    Glide.with(ivPreview.context)
                        .load(medium)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(ivPreview)
                }
                thumb != null && thumb.isNotEmpty() -> {
                    Glide.with(ivPreview.context)
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
                            val ref = FirebaseStorage.getInstance().getReferenceFromUrl(url)
                            ref.downloadUrl
                                .addOnSuccessListener { httpsUri ->
                                    Glide.with(ivPreview.context)
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
                            Glide.with(ivPreview.context)
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
            startActivity(Intent(this, ActivityMap::class.java).apply {
                putExtra("MAP_ID", m.id)
            })
        }

        btnBaixar?.setOnClickListener {
            val visible = cardDownload?.isVisible == true
            cardDownload?.visibility = if (visible) View.GONE else View.VISIBLE
        }

        btnPNG?.setOnClickListener {
            val ok = saveQrAsPng(m.id)
            Toast.makeText(this, if (ok) "QR salvo em Imagens/IndoorAR" else "Falha ao salvar QR", Toast.LENGTH_SHORT).show()
        }
        btnPDF?.setOnClickListener {
            val ok = saveQrAsPdf(m.id)
            Toast.makeText(this, if (ok) "PDF salvo em Downloads" else "Falha ao salvar PDF", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun generateQrBitmap(content: String, size: Int = 1024): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = createBitmap(width, height)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp[x, y] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            bmp
        } catch (_: Exception) { null }
    }

    private fun saveQrAsPng(mapId: String): Boolean {
        val bmp = generateQrBitmap(mapId) ?: return false
        return try {
            val filename = "QR_${mapId}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IndoorAR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) { false }
    }

    private fun saveQrAsPdf(mapId: String): Boolean {
        return try {
            val bmp = generateQrBitmap(mapId, 1024) ?: return false
            val filename = "QR_${mapId}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.pdf"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri: Uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                writeSimplePdfWithBitmap(bmp, out)
            } ?: return false
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) { false }
    }

    private fun writeSimplePdfWithBitmap(bmp: Bitmap, out: OutputStream) {
        val pageWidth = 595 // A4 width at 72dpi (~8.27in * 72)
        val pageHeight = 842 // A4 height at 72dpi (~11.69in * 72)
        val scale = minOf(pageWidth.toFloat() / bmp.width, pageHeight.toFloat() / bmp.height)
        val pdfBmp = bmp.scale((bmp.width * scale).toInt(), (bmp.height * scale).toInt())
        val document = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val left = (pageWidth - pdfBmp.width) / 2f
        val top = (pageHeight - pdfBmp.height) / 2f
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(pdfBmp, left, top, null)
        document.finishPage(page)
        document.writeTo(out)
        document.close()
        if (!pdfBmp.isRecycled) pdfBmp.recycle()
    }
}
