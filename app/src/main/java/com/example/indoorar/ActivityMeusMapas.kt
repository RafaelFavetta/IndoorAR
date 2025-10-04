package com.example.indoorar

import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.ViewGroup
import android.view.LayoutInflater
import com.google.firebase.firestore.FieldValue

class ActivityMeusMapas : BaseActivity() {

    private val adapter = MapasAdapter { mapa -> onMapaClicked(mapa) }
    private var mapaSelecionado: MapaResumo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meus_mapas)

        val btnVoltar = findViewById<android.widget.ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener {
            startActivity(Intent(this, ActivityHomeMaker::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
            finish()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMapas)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val db = FirebaseFirestore.getInstance()
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "Usuário não logado", Toast.LENGTH_SHORT).show()
            return
        }

        // Busca ao vivo dos mapas do usuário logado
        db.collection("mapas")
            .whereEqualTo("criadorUid", userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Toast.makeText(this, "Erro ao buscar mapas", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                val listaMapas = snapshots?.documents?.mapNotNull { doc ->
                    doc.toObject(MapaResumo::class.java)
                } ?: emptyList()
                adapter.submit(listaMapas)
            }
    }

    private fun migrarNomeAutorDoUsuarioAtual(onDone: () -> Unit) {
        val uid = getUidMaker()
        if (uid.isBlank()) { onDone(); return }
        val db = FirebaseFirestore.getInstance()
        db.collection("usuarios").document(uid).get()
            .addOnSuccessListener { userDoc ->
                val nomeAutor = userDoc.getString("nome") ?: uid
                db.collection("mapas").whereEqualTo("criadorUid", uid).get()
                    .addOnSuccessListener { snap ->
                        if (snap.isEmpty) { onDone(); return@addOnSuccessListener }
                        val batch = db.batch()
                        snap.documents.forEach { doc ->
                            val hasNomeAutor = doc.contains("nomeAutor") && !doc.getString("nomeAutor").isNullOrBlank()
                            val hadAutorNome = doc.contains("autorNome")
                            if (!hasNomeAutor || hadAutorNome) {
                                val ref = doc.reference
                                val updates = mutableMapOf<String, Any>("nomeAutor" to nomeAutor)
                                if (hadAutorNome) updates["autorNome"] = FieldValue.delete()
                                batch.update(ref, updates)
                            }
                        }
                        batch.commit().addOnCompleteListener { onDone() }
                    }
                    .addOnFailureListener { onDone() }
            }
            .addOnFailureListener { onDone() }
    }

    private fun carregarMapas() {
        val uid = getUidMaker()
        if (uid.isBlank()) {
            Toast.makeText(this, "Usuário não logado", Toast.LENGTH_SHORT).show()
            adapter.submit(emptyList())
            return
        }
        val db = FirebaseFirestore.getInstance()
        db.collection("mapas")
            .whereEqualTo("criadorUid", uid)
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val mapas = snap.documents
                val listaResumo = mutableListOf<MapaResumo>()
                if (mapas.isEmpty()) {
                    adapter.submit(emptyList())
                    return@addOnSuccessListener
                }
                var count = 0
                mapas.forEach { doc ->
                    val autorUid = doc.getString("criadorUid") ?: ""
                    db.collection("usuarios").document(autorUid).get().addOnSuccessListener { userDoc ->
                        val nomeAutor = userDoc.getString("nome") ?: autorUid
                        val id = doc.id
                        if (!id.isNullOrBlank()) {
                            listaResumo.add(
                                MapaResumo(
                                    id = id,
                                    nome = doc.getString("nome") ?: "FATEC Araras Antonio Brambilla",
                                    descricao = doc.getString("descricao") ?: "Mapa da FATEC Araras 2025",
                                    autorUid = autorUid,
                                    autorNome = nomeAutor,
                                    dataCriacao = doc.getTimestamp("dataCriacao")
                                )
                            )
                        }
                        count++
                        if (count == mapas.size) {
                            adapter.submit(listaResumo)
                        }
                    }
                }
            }
            .addOnFailureListener {
                adapter.submit(emptyList())
            }
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_mapa_preview, null)
        val txtTitulo = view.findViewById<TextView>(R.id.txtTituloMapa)
        val txtDesc = view.findViewById<TextView>(R.id.txtDescricaoMapa)
        val ivPreview = view.findViewById<ImageView>(R.id.ivPreview)
        val btnIniciar = view.findViewById<Button>(R.id.btnIniciarNavegacao)
        val btnBaixarQRCode = view.findViewById<Button>(R.id.btnBaixarQRCode)
        val cardDownloadQRCode = view.findViewById<MaterialCardView>(R.id.cardDownloadQRCode)
        val btnDownloadPDF = view.findViewById<Button>(R.id.btnDownloadPDF)
        val btnDownloadPNG = view.findViewById<Button>(R.id.btnDownloadPNG)
        txtTitulo.text = m.nome
        txtDesc.text = m.descricao
        ivPreview.setImageResource(R.drawable.ic_minimap_placeholder)
        cardDownloadQRCode.visibility = View.GONE

        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document(m.id ?: "")
        ivPreview.post {
            val w = ivPreview.width
            val h = ivPreview.height
            mapaRef.collection("formas").get().addOnSuccessListener { formasSnap ->
                mapaRef.collection("pois").get().addOnSuccessListener { poisSnap ->
                    ivPreview.setImageBitmap(gerarMinimapaBitmap(formasSnap.documents, poisSnap.documents, w, h))
                }
            }
        }

        var launching = false
        btnIniciar.setOnClickListener {
            if (launching) return@setOnClickListener
            launching = true
            btnIniciar.isEnabled = false
            dialog.dismiss()
            val id = m.id ?: ""
            if (id.isBlank()) {
                Toast.makeText(this, "ID de mapa inválido", Toast.LENGTH_SHORT).show()
                launching = false
                btnIniciar.isEnabled = true
                return@setOnClickListener
            }
            startActivity(Intent(this, ActivityMap::class.java).putExtra("MAP_ID", id))
        }
        btnBaixarQRCode.setOnClickListener { cardDownloadQRCode.visibility = View.VISIBLE }
        btnDownloadPDF.setOnClickListener {
            gerarQRCode(m, true)
            cardDownloadQRCode.visibility = View.GONE
        }
        btnDownloadPNG.setOnClickListener {
            gerarQRCode(m, false)
            cardDownloadQRCode.visibility = View.GONE
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun gerarMinimapaBitmap(
        formas: List<DocumentSnapshot>,
        pois: List<DocumentSnapshot>,
        width: Int,
        height: Int
    ): Bitmap {
        val bmpW = if (width > 0) width else 400
        val bmpH = if (height > 0) height else 200
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFF5F5F5.toInt())
        val paint = Paint()

        // Calcular bounding box
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        formas.forEach { f ->
            val pos = f.get("posicao") as? List<*> ?: return@forEach
            val tam = f.get("tamanho") as? List<*> ?: return@forEach
            val x = (pos.getOrNull(0) as? Number)?.toFloat() ?: 0f
            val y = (pos.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val h = (tam.getOrNull(0) as? Number)?.toFloat() ?: 0f
            val w = (tam.getOrNull(1) as? Number)?.toFloat() ?: 0f
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x + w)
            maxY = maxOf(maxY, y + h)
        }
        pois.forEach { p ->
            val x = (p.get("x") as? Number)?.toFloat() ?: 0f
            val y = (p.get("y") as? Number)?.toFloat() ?: 0f
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) {
            minX = 0f; minY = 0f; maxX = bmpW.toFloat(); maxY = bmpH.toFloat()
        }
        val scaleX = if (maxX - minX > 0) bmpW / (maxX - minX) else 1f
        val scaleY = if (maxY - minY > 0) bmpH / (maxY - minY) else 1f

        // Desenhar formas
        formas.forEach { f ->
            val pos = f.get("posicao") as? List<*> ?: return@forEach
            val tam = f.get("tamanho") as? List<*> ?: return@forEach
            val x = (pos.getOrNull(0) as? Number)?.toFloat() ?: 0f
            val y = (pos.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val h = (tam.getOrNull(0) as? Number)?.toFloat() ?: 0f
            val w = (tam.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val cor = (f.getString("cor") ?: "#D9D9D9").toColorInt()
            paint.color = cor
            paint.style = Paint.Style.FILL
            val left = (x - minX) * scaleX
            val top = (y - minY) * scaleY
            val right = left + w * scaleX
            val bottom = top + h * scaleY
            canvas.drawRect(RectF(left, top, right, bottom), paint)
        }
        // Desenhar POIs
        paint.style = Paint.Style.FILL
        paint.color = 0xFF32357A.toInt()
        pois.forEach { p ->
            val x = (p.get("x") as? Number)?.toFloat() ?: 0f
            val y = (p.get("y") as? Number)?.toFloat() ?: 0f
            val cx = (x - minX) * scaleX
            val cy = (y - minY) * scaleY
            canvas.drawCircle(cx, cy, 10f, paint)
        }
        return bmp
    }

    private fun gerarQRCode(mapa: MapaResumo, pdf: Boolean) {
        val mapId = mapa.id
        val nomeMapa = mapa.nome
        val frase = "Me escaneie para ter o seu próprio guia!"
        val writer = QRCodeWriter()
        val qrSize = 512
        val bitMatrix = writer.encode(mapId, BarcodeFormat.QR_CODE, qrSize, qrSize)
        val qrBmp = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.RGB_565)
        for (x in 0 until qrSize) {
            for (y in 0 until qrSize) {
                qrBmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        if (pdf) {
            try {
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "qrcode_${mapId}.pdf")
                val document = com.itextpdf.text.Document()
                val writerPdf = com.itextpdf.text.pdf.PdfWriter.getInstance(document, FileOutputStream(file))
                document.open()
                val azul = com.itextpdf.text.BaseColor(0x32,0x35,0x7A)
                val fonteTitulo = com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 16f, com.itextpdf.text.Font.BOLD, azul)
                val fonteNormal = com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 12f, com.itextpdf.text.Font.NORMAL)
                // Título antes do QR
                document.add(com.itextpdf.text.Paragraph(nomeMapa ?: "Mapa sem nome", fonteTitulo))
                // QR
                val stream = java.io.ByteArrayOutputStream()
                qrBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val img = com.itextpdf.text.Image.getInstance(stream.toByteArray())
                img.alignment = com.itextpdf.text.Element.ALIGN_CENTER
                document.add(img)
                // Frase após o QR
                document.add(com.itextpdf.text.Paragraph("\n$frase", fonteNormal))
                document.close(); writerPdf.close()
                abrirShareSheet(file, "application/pdf")
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao salvar PDF", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val padding = 24
                val lineSpacing = 16
                val tituloSize = 42f
                val fraseSize = 30f
                val topSectionHeight = (padding + tituloSize + lineSpacing)
                val phraseBlockHeight = (lineSpacing + fraseSize + padding)
                val totalHeight = (topSectionHeight + qrSize + phraseBlockHeight).toInt()
                val outBmp = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outBmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                val centerX = qrSize / 2f
                val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
                // Título
                paintText.textSize = tituloSize
                paintText.color = 0xFF32357A.toInt()
                val tituloBaseline = padding + tituloSize
                canvas.drawText(nomeMapa ?: "Mapa sem nome", centerX, tituloBaseline, paintText)
                // QR
                val qrTop = (topSectionHeight).toInt()
                canvas.drawBitmap(qrBmp, 0f, qrTop.toFloat(), null)
                // Frase
                paintText.textSize = fraseSize
                paintText.color = android.graphics.Color.BLACK
                val fraseBaseline = qrTop + qrSize + lineSpacing + fraseSize
                canvas.drawText(frase, centerX, fraseBaseline, paintText)
                val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "qrcode_${mapId}.png")
                val stream = FileOutputStream(file)
                outBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.close()
                abrirShareSheet(file, "image/png")
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao salvar PNG", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun salvarQRCodeNoDispositivo(mapa: MapaResumo, pdf: Boolean) {
        val mapId = mapa.id
        val nomeMapa = mapa.nome
        val frase = "Me escaneie para ter o seu próprio guia!"
        val writer = QRCodeWriter()
        val qrSize = 512
        val bitMatrix = writer.encode(mapId, BarcodeFormat.QR_CODE, qrSize, qrSize)
        val qrBmp = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.RGB_565)
        for (x in 0 until qrSize) {
            for (y in 0 until qrSize) {
                qrBmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        if (pdf) {
            try {
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "qrcode_${mapId}_download.pdf")
                val document = com.itextpdf.text.Document()
                val writerPdf = com.itextpdf.text.pdf.PdfWriter.getInstance(document, FileOutputStream(file))
                document.open()
                val azul = com.itextpdf.text.BaseColor(0x32,0x35,0x7A)
                val fonteTitulo = com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 16f, com.itextpdf.text.Font.BOLD, azul)
                val fonteNormal = com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 12f, com.itextpdf.text.Font.NORMAL)
                document.add(com.itextpdf.text.Paragraph(nomeMapa ?: "Mapa sem nome", fonteTitulo))
                val stream = java.io.ByteArrayOutputStream()
                qrBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val img = com.itextpdf.text.Image.getInstance(stream.toByteArray())
                img.alignment = com.itextpdf.text.Element.ALIGN_CENTER
                document.add(img)
                document.add(com.itextpdf.text.Paragraph("\n$frase", fonteNormal))
                document.close(); writerPdf.close()
                Toast.makeText(this, "PDF salvo em: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Erro ao salvar PDF", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val padding = 24
                val lineSpacing = 16
                val tituloSize = 42f
                val fraseSize = 30f
                val topSectionHeight = (padding + tituloSize + lineSpacing)
                val phraseBlockHeight = (lineSpacing + fraseSize + padding)
                val totalHeight = (topSectionHeight + qrSize + phraseBlockHeight).toInt()
                val outBmp = Bitmap.createBitmap(qrSize, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outBmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                val centerX = qrSize / 2f
                val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
                paintText.textSize = tituloSize
                paintText.color = 0xFF32357A.toInt()
                val tituloBaseline = padding + tituloSize
                canvas.drawText(nomeMapa ?: "Mapa sem nome", centerX, tituloBaseline, paintText)
                val qrTop = (topSectionHeight).toInt()
                canvas.drawBitmap(qrBmp, 0f, qrTop.toFloat(), null)
                paintText.textSize = fraseSize
                paintText.color = android.graphics.Color.BLACK
                val fraseBaseline = qrTop + qrSize + lineSpacing + fraseSize
                canvas.drawText(frase, centerX, fraseBaseline, paintText)
                val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "qrcode_${mapId}_download.png")
                val stream = FileOutputStream(file)
                outBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.close()
                Toast.makeText(this, "PNG salvo em: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Erro ao salvar PNG", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun abrirShareSheet(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = mimeType
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(shareIntent, "Compartilhar QR Code"))
    }

    private fun getUidMaker(): String {
        return FirebaseAuth.getInstance().currentUser?.uid ?: ""
    }

    private fun mostrarDialogAcaoQRCode(mapa: MapaResumo, pdf: Boolean) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Escolha a ação")
        builder.setMessage("O que deseja fazer com o QR Code?")
        builder.setPositiveButton("Compartilhar") { _, _ ->
            gerarQRCode(mapa, pdf)
        }
        builder.setNegativeButton("Salvar no dispositivo") { _, _ ->
            salvarQRCodeNoDispositivo(mapa, pdf)
        }
        builder.show()
    }
}
