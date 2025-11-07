package com.example.indoorar

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomnavigation.BottomNavigationView
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import android.widget.LinearLayout
import androidx.core.graphics.scale
import kotlin.math.abs
import androidx.core.graphics.toColorInt

class ActivityHomeComum : BaseActivity() {

    // Substitui Page adapter por adapter de itens individuais
    private val recentAdapter = RecentesAdapter { mapa -> onMapaClicked(mapa) }
    private lateinit var recyclerRecentes: RecyclerView
    private lateinit var progressRecentes: android.widget.ProgressBar
    private lateinit var indicatorsRecentes: LinearLayout
    // Removido snapHelper
    private var recentesListener: ListenerRegistration? = null

    // Contagem original (sem duplicação) usada para indicadores e wrapping
    private var baseRecentCount: Int = 0
    // Protege contra reposicionamentos repetidos
    private var isWrappingRepositioning: Boolean = false

    // Auto-scroll config
    private val SCROLL_STEP_PX = 2
    private val FRAME_DELAY_MS = 16L          // ~60fps
    private val INITIAL_DELAY_MS = 1500L      // 1,5s

    // Auto-scroll
    private val autoScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoScrollRunning = false
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!autoScrollRunning) return
            recyclerRecentes.scrollBy(SCROLL_STEP_PX, 0)
            recycleLoopIfNeeded()
            updateIndicatorFromLayout()
            autoScrollHandler.postDelayed(this, FRAME_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_comum)

        // System insets: aplicar somente topo/laterais no root, sem padding inferior
        val mainView = findViewById<View>(R.id.main)
        if (mainView == null) {
            Toast.makeText(this, "Erro: View 'main' não encontrada no layout.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // Navbar
        try {
            findViewById<BottomNavigationView>(R.id.bottomNavComum)?.apply {
                // Evita que a própria BottomNavigationView receba padding inferior de insets
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                    insets
                }

                try { selectedItemId = R.id.action_home } catch (_: Exception) {}

                setOnItemSelectedListener { item ->
                    try {
                        if (this.selectedItemId == item.itemId) return@setOnItemSelectedListener true

                        when (item.itemId) {
                            R.id.action_home -> { startActivity(Intent(this@ActivityHomeComum, ActivityHomeComum::class.java)); true }
                            R.id.action_scan -> { startActivity(Intent(this@ActivityHomeComum, ActivityScanQR::class.java)); true }
                            R.id.action_favoritos -> { startActivity(Intent(this@ActivityHomeComum, ActivityFavoritos::class.java)); true }
                            R.id.action_config -> { startActivity(Intent(this@ActivityHomeComum, ActivityPerfilComum::class.java)); true }
                            else -> false
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("HomeComum", "Erro no bottomNavComum listener", e)
                        false
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeComum", "Falha ao configurar bottomNavComum", e)
        }


        // Lista de recentes (carrossel horizontal paginado)
        recyclerRecentes = findViewById(R.id.recyclerRecentes)
        progressRecentes = findViewById(R.id.progressRecentes)
        indicatorsRecentes = findViewById(R.id.indicatorsRecentes)

        recyclerRecentes.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        recyclerRecentes.adapter = recentAdapter

        // Aplicar largura para mostrar ~1,5 cards
        recyclerRecentes.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val w = recyclerRecentes.width
                if (w > 0) {
                    val density = resources.displayMetrics.density
                    val cardWidth = (w * 2f / 3f).toInt() // ~66% da largura do recycler
                    val sideMargin = (8 * density).toInt()
                    recentAdapter.setItemSizing(cardWidth, sideMargin)
                    recyclerRecentes.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    recentAdapter.notifyDataSetChanged()
                }
            }
        })

        recyclerRecentes.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updateIndicatorFromLayout()
            }
        })

        recyclerRecentes.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> stopAutoScroll()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    autoScrollHandler.postDelayed({ startAutoScroll() }, 2000)
                }
            }
            false
        }

        // "Ver todos" abre MapasExistentes
        findViewById<TextView>(R.id.btnVerTodosComum)?.setOnClickListener {
            startActivity(Intent(this, ActivityMapasExistentes::class.java))
        }

        carregarMapasRecentesEmTempoReal()
    }

    override fun onResume() {
        super.onResume()
        startAutoScroll()
    }

    override fun onPause() {
        stopAutoScroll()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        recentesListener?.remove()
        stopAutoScroll()
    }

    private fun startAutoScroll() {
        if (recentAdapter.itemCount <= 1) return
        if (autoScrollRunning) return
        autoScrollRunning = true
        autoScrollHandler.postDelayed(autoScrollRunnable, INITIAL_DELAY_MS)
    }

    private fun stopAutoScroll() {
        autoScrollRunning = false
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
    }

    private fun recycleLoopIfNeeded() {
        // Só faz loop enquanto auto-scroll ativo para não atrapalhar scroll manual
        if (!autoScrollRunning) return
        val lm = recyclerRecentes.layoutManager as? LinearLayoutManager ?: return
        val total = recentAdapter.itemCount
        if (baseRecentCount > 1 && total >= baseRecentCount * 3) {
            val firstVisible = lm.findFirstVisibleItemPosition()
            if (firstVisible == RecyclerView.NO_POSITION) return
            // Se estivermos na cópia final, reposiciona para a cópia do meio preservando offset
            if (firstVisible >= baseRecentCount * 2) {
                if (!isWrappingRepositioning) {
                    isWrappingRepositioning = true
                    val offsetView = recyclerRecentes.getChildAt(0)
                    val offset = offsetView?.left ?: 0
                    val newPos = (firstVisible % baseRecentCount) + baseRecentCount
                    recyclerRecentes.post {
                        lm.scrollToPositionWithOffset(newPos, offset)
                        // limpar flag no próximo ciclo de mensagem
                        recyclerRecentes.post { isWrappingRepositioning = false }
                    }
                }
            }
            // Se estivermos na cópia inicial (rolagem para trás extrema), também reposiciona para o meio
            else if (firstVisible < baseRecentCount) {
                if (!isWrappingRepositioning) {
                    isWrappingRepositioning = true
                    val offsetView = recyclerRecentes.getChildAt(0)
                    val offset = offsetView?.left ?: 0
                    val newPos = (firstVisible % baseRecentCount) + baseRecentCount
                    recyclerRecentes.post {
                        lm.scrollToPositionWithOffset(newPos, offset)
                        recyclerRecentes.post { isWrappingRepositioning = false }
                    }
                }
            }
        } else {
            val lastFull = lm.findLastCompletelyVisibleItemPosition()
            if (total > 0 && lastFull == total - 1) {
                // fallback simples
                lm.scrollToPosition(0)
            }
        }
    }

    private fun updateIndicatorFromLayout() {
        val childCount = recyclerRecentes.childCount
        if (childCount == 0) return
        val centerX = recyclerRecentes.width / 2
        var bestIndex = -1
        var bestDist = Int.MAX_VALUE
        for (i in 0 until childCount) {
            val child = recyclerRecentes.getChildAt(i) ?: continue
            val childCenter = (child.left + child.right) / 2
            val dist = abs(childCenter - centerX)
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = recyclerRecentes.getChildAdapterPosition(child)
            }
        }
        if (bestIndex >= 0) {
            val idx = if (baseRecentCount > 0) bestIndex % baseRecentCount else bestIndex
            setCurrentIndicator(idx)
        }
    }

    private fun buildIndicators(count: Int) {
        indicatorsRecentes.removeAllViews()
        if (count <= 1) { indicatorsRecentes.visibility = View.GONE; return }
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
        if (index < 0 || index >= n) return
        for (i in 0 until n) {
            val iv = indicatorsRecentes.getChildAt(i) as? ImageView ?: continue
            iv.setImageResource(if (i == index) R.drawable.dot_selected else R.drawable.dot_unselected)
        }
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
        progressRecentes.visibility = View.VISIBLE
        recyclerRecentes.visibility = View.GONE
        recentesListener?.remove()
        recentesListener = FirebaseFirestore.getInstance().collection("mapas")
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    progressRecentes.visibility = View.GONE
                    recyclerRecentes.visibility = View.GONE
                    buildIndicators(0)
                    return@addSnapshotListener
                }
                val lista = snap?.documents?.map { docParaMapaResumoSeguro(it) } ?: emptyList()
                val unicos = lista.distinctBy { imageKeyFor(it) }
                val limited = unicos.take(5)

                // prepara wrapping triplo para loop suave quando houver 2+ itens
                baseRecentCount = limited.size
                if (baseRecentCount > 1) {
                    val wrapped = ArrayList<MapaResumo>(baseRecentCount * 3)
                    wrapped.addAll(limited)
                    wrapped.addAll(limited)
                    wrapped.addAll(limited)
                    recentAdapter.submit(wrapped)
                    // posiciona na cópia do meio (mantém continuidade)
                    recyclerRecentes.post { recyclerRecentes.scrollToPosition(baseRecentCount) }
                } else {
                    recentAdapter.submit(limited)
                }

                progressRecentes.visibility = View.GONE
                recyclerRecentes.visibility = if (recentAdapter.itemCount == 0) View.GONE else View.VISIBLE
                buildIndicators(baseRecentCount)
                updateIndicatorFromLayout()
                startAutoScroll()
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
            val ok = saveQrAsPng(m.id, m.nome)
            Toast.makeText(this, if (ok) "QR salvo em Imagens/Wander" else "Falha ao salvar QR", Toast.LENGTH_SHORT).show()
        }
        btnPDF?.setOnClickListener {
            val ok = saveQrAsPdf(m.id, m.nome)
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

    private fun composeQrWithText(qr: Bitmap, mapName: String): Bitmap {
        val prompt = getString(R.string.qr_scan_prompt)
        val width = qr.width
        val padding = (width * 0.06f).toInt()
        val spacing = (width * 0.04f).toInt()
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#1976D2".toColorInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = width * 0.08f
        }
        val promptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textAlign = Paint.Align.CENTER
            textSize = width * 0.06f
        }
        val maxTextWidth = width * 0.9f
        fun fitText(p: Paint, text: String, desired: Float, min: Float = width * 0.04f): Float {
            var size = desired
            p.textSize = size
            var w = p.measureText(text)
            while (w > maxTextWidth && size > min) {
                size *= 0.9f
                p.textSize = size
                w = p.measureText(text)
            }
            return size
        }
        namePaint.textSize = fitText(namePaint, mapName, namePaint.textSize)
        promptPaint.textSize = fitText(promptPaint, prompt, promptPaint.textSize)
        val nameFM = namePaint.fontMetrics
        val promptFM = promptPaint.fontMetrics
        val nameH = (nameFM.bottom - nameFM.top).toInt()
        val promptH = (promptFM.bottom - promptFM.top).toInt()
        val finalHeight = qr.height + padding + nameH + spacing / 2 + promptH + padding
        val out = createBitmap(width, finalHeight)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(qr, 0f, 0f, null)
        val cx = width / 2f
        var y = qr.height + padding.toFloat()
        y += -nameFM.top
        canvas.drawText(mapName, cx, y, namePaint)
        y += spacing / 2f
        y += -promptFM.top
        canvas.drawText(prompt, cx, y, promptPaint)
        return out
    }

    private fun saveQrAsPng(mapId: String, mapName: String): Boolean {
        val qr = generateQrBitmap(mapId) ?: return false
        val bmp = composeQrWithText(qr, mapName)
        if (!qr.isRecycled) qr.recycle()
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "QR_${mapId}_${timestamp}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Wander")
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
        finally { if (!bmp.isRecycled) bmp.recycle() }
    }

    private fun saveQrAsPdf(mapId: String, mapName: String): Boolean {
        return try {
            val qr = generateQrBitmap(mapId, 1024) ?: return false
            val bmp = composeQrWithText(qr, mapName)
            if (!qr.isRecycled) qr.recycle()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "QR_${mapId}_${timestamp}.pdf"
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
