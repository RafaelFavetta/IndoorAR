package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.bumptech.glide.Glide
import com.google.firebase.storage.FirebaseStorage
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt

class ActivityMeusMapas : BaseActivity() {

    private val adapter = MapasAdapter { mapa -> onMapaClicked(mapa) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meus_mapas)

        val btnVoltar = findViewById<android.widget.ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMapas)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "Usuário não logado", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseFirestore.getInstance().collection("mapas")
            .whereEqualTo("criadorUid", userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Toast.makeText(this, "Erro ao buscar mapas", Toast.LENGTH_SHORT).show()
                    adapter.submit(emptyList())
                    return@addSnapshotListener
                }
                val listaMapas = snapshots?.documents?.map { doc ->
                    docParaMapaResumoSeguro(doc)
                } ?: emptyList()
                val ordenada = listaMapas.sortedByDescending { it.dataCriacao?.seconds ?: 0 }
                adapter.submit(ordenada)
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
        val editBtnId = resources.getIdentifier("btnEditarMapa", "id", packageName)
        val btnEditar = if (editBtnId != 0) dialog.findViewById<Button>(editBtnId) else null
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

        // Mostrar botão de edição apenas para o criador (maker)
        btnEditar?.let { b ->
            // Por padrão escondido no layout; checamos se o usuário atual é o autor e se é maker
            val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (!currentUid.isNullOrBlank() && currentUid == m.autorUid) {
                // Valida tipo da conta (maker)
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("usuarios").document(currentUid)
                    .get()
                    .addOnSuccessListener { doc ->
                        val tipo = doc.getString("tipoConta")
                        if (tipo == "maker") {
                            b.visibility = View.VISIBLE
                            b.setOnClickListener {
                                dialog.dismiss()
                                // Abre o editor passando MAP_ID
                                val intent = Intent(this, com.example.indoorar.ui.ActivityEditor::class.java)
                                intent.putExtra("MAP_ID", m.id)
                                startActivity(intent)
                            }
                        } else {
                            b.visibility = View.GONE
                        }
                    }
                    .addOnFailureListener { _ -> b.visibility = View.GONE }
            } else {
                b.visibility = View.GONE
            }
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

    private fun composeQrWithText(qr: android.graphics.Bitmap, mapName: String): android.graphics.Bitmap {
        val prompt = getString(R.string.qr_scan_prompt)
        val width = qr.width
        val padding = (width * 0.06f).toInt()
        val spacing = (width * 0.04f).toInt()

        val namePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = "#32357A".toColorInt() // azul
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = width * 0.08f
        }
        val promptPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = width * 0.06f
        }
        val maxTextWidth = width * 0.9f
        fun fitText(p: android.graphics.Paint, text: String, desired: Float, min: Float = width * 0.04f): Float {
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
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
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
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val filename = "QR_${mapId}_${timestamp}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Wander")
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
        finally { if (!bmp.isRecycled) bmp.recycle() }
    }

    private fun saveQrAsPdf(mapId: String, mapName: String): Boolean {
        return try {
            val qr = generateQrBitmap(mapId, 1024) ?: return false
            val bmp = composeQrWithText(qr, mapName)
            if (!qr.isRecycled) qr.recycle()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val filename = "QR_${mapId}_${timestamp}.pdf"
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
        val pageWidth = 595
        val pageHeight = 842
        val scale = kotlin.math.min(pageWidth.toFloat() / bmp.width, pageHeight.toFloat() / bmp.height)
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
        if (!bmp.isRecycled) bmp.recycle()
    }
}
