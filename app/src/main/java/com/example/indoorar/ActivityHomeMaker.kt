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
import android.view.ViewTreeObserver
import android.graphics.*
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.abs
import androidx.core.graphics.toColorInt

class ActivityHomeMaker : BaseActivity() {

    private lateinit var recyclerRecentes: RecyclerView
    private lateinit var progressRecentes: ProgressBar
    private lateinit var indicatorsRecentes: LinearLayout
    private var recentesListener: ListenerRegistration? = null

    // Substitui pages por itens individuais
    private val recentAdapter = RecentesAdapter { mapa -> onMapaClicked(mapa) }

    // Auto-scroll config
    private val SCROLL_STEP_PX = 2
    private val FRAME_DELAY_MS = 16L          // ~60fps
    private val INITIAL_DELAY_MS = 1500L      // 1,5s

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
        setContentView(R.layout.activity_home_maker)

        // Aplicar insets apenas no topo e laterais do root
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // Texto de boas-vindas
        findViewById<TextView>(R.id.txtBemVindo).text = getString(R.string.bem_vindo)


        // Navbar
        try {
            findViewById<BottomNavigationView>(R.id.bottomNavMaker)?.apply {
                // Evitar aplicar padding inferior de insets na navbar
                ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                    insets
                }

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

        // Aplicar largura para mostrar ~1,5 cards
        recyclerRecentes.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val w = recyclerRecentes.width
                if (w > 0) {
                    val density = resources.displayMetrics.density
                    val cardWidth = (w * 2f / 3f).toInt()
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

        // "Ver todos" abre MeusMapas
        findViewById<TextView>(R.id.btnVerTodosMaker)?.setOnClickListener {
            startActivity(Intent(this, ActivityMeusMapas::class.java))
        }

        carregarMapasRecentesEmTempoReal()
    }

    override fun onResume() { super.onResume(); startAutoScroll() }
    override fun onPause() { stopAutoScroll(); super.onPause() }
    override fun onDestroy() { super.onDestroy(); recentesListener?.remove(); stopAutoScroll() }

    private fun startAutoScroll() {
        if (recentAdapter.itemCount <= 1) return
        if (autoScrollRunning) return
        autoScrollRunning = true
        autoScrollHandler.postDelayed(autoScrollRunnable, INITIAL_DELAY_MS)
    }
    private fun stopAutoScroll() { autoScrollRunning = false; autoScrollHandler.removeCallbacks(autoScrollRunnable) }

    private fun recycleLoopIfNeeded() {
        // Só faz loop enquanto auto-scroll ativo para não atrapalhar scroll manual
        if (!autoScrollRunning) return
        val lm = recyclerRecentes.layoutManager as? LinearLayoutManager ?: return
        val lastFull = lm.findLastCompletelyVisibleItemPosition()
        val total = recentAdapter.itemCount
        if (total > 0 && lastFull == total - 1) {
            lm.scrollToPosition(0)
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
        if (bestIndex >= 0) setCurrentIndicator(bestIndex)
    }

    private fun imageKeyFor(m: MapaResumo): String {
        val url = m.imagemUrl
        if (!url.isNullOrBlank()) return "url:$url"
        val t = m.imagemBlobThumb?.toBytes(); if (t != null && t.isNotEmpty()) return "thumb:${t.contentHashCode()}:${t.size}"
        val b = m.imagemBlob?.toBytes(); if (b != null && b.isNotEmpty()) return "blob:${b.contentHashCode()}:${b.size}"
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
                val limited = unicos.take(5)
                recentAdapter.submit(limited)
                progressRecentes.visibility = View.GONE
                recyclerRecentes.visibility = if (recentAdapter.itemCount == 0) View.GONE else View.VISIBLE
                buildIndicators(recentAdapter.itemCount)
                updateIndicatorFromLayout()
                startAutoScroll()
            }
    }

    private fun docParaMapaResumoMaker(doc: DocumentSnapshot, uid: String): MapaResumo {
        return MapaResumo(
            id = doc.id,
            nome = doc.getString("nome") ?: "Mapa sem nome",
            descricao = doc.getString("descricao") ?: "",
            autorUid = uid,
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
        if (index < 0 || index >= n) return
        for (i in 0 until n) {
            val iv = indicatorsRecentes.getChildAt(i) as? ImageView ?: continue
            iv.setImageResource(if (i == index) R.drawable.dot_selected else R.drawable.dot_unselected)
        }
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = BottomSheetDialog(this)
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
                    com.bumptech.glide.Glide.with(this@ActivityHomeMaker)
                        .load(medium)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(ivPreview)
                }
                thumb != null && thumb.isNotEmpty() -> {
                    com.bumptech.glide.Glide.with(this@ActivityHomeMaker)
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
                                    com.bumptech.glide.Glide.with(this@ActivityHomeMaker)
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
                            com.bumptech.glide.Glide.with(this@ActivityHomeMaker)
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
            android.widget.Toast.makeText(this, if (ok) "QR salvo em Imagens/Wander" else "Falha ao salvar QR", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnPDF?.setOnClickListener {
            val ok = saveQrAsPdf(m.id, m.nome)
            android.widget.Toast.makeText(this, if (ok) "PDF salvo em Downloads" else "Falha ao salvar PDF", android.widget.Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun generateQrBitmap(content: String, size: Int = 1024): Bitmap? {
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
            val filename = "QR_${mapId}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
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

    private fun writeSimplePdfWithBitmap(bmp: Bitmap, out: java.io.OutputStream) {
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
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, left, top, null)
        document.finishPage(page)
        document.writeTo(out)
        document.close()
        if (!scaled.isRecycled) scaled.recycle()
    }
}