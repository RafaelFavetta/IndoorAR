package com.example.indoorar

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.firebase.storage.FirebaseStorage
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.view.isVisible
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

class ActivityMapasExistentes : BaseActivity() {
    private lateinit var recycler: RecyclerView
    private val adapter = MapasAdapter { mapa -> onMapaClicked(mapa) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mapas_existentes)
        val btnVoltar = findViewById<android.widget.ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }
        recycler = findViewById(R.id.recyclerMapas)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        observarMapasExistentesTempoReal()
    }

    private fun observarMapasExistentesTempoReal() {
        FirebaseFirestore.getInstance()
            .collection("mapas")
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Toast.makeText(this, "Erro ao carregar mapas", Toast.LENGTH_SHORT).show()
                    adapter.submit(emptyList())
                    return@addSnapshotListener
                }
                val itens = snap?.documents?.map { docParaMapaResumoSeguro(it) } ?: emptyList()
                adapter.submit(itens)
            }
    }

    private fun docParaMapaResumoSeguro(doc: DocumentSnapshot): MapaResumo {
        return MapaResumo(
            id = doc.id,
            nome = doc.getString("nome") ?: "Mapa sem nome",
            descricao = doc.getString("descricao") ?: "Sem descrição",
            autorUid = doc.getString("criadorUid") ?: "Desconhecido",
            autorNome = doc.getString("nomeAutor") ?: "Desconhecido",
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
            btnIniciar?.visibility = View.GONE
            btnBaixar.visibility = View.GONE
            cardDownload?.visibility = View.VISIBLE
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
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "QR_${mapId}_${timestamp}.png"
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
        val pdfBmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
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