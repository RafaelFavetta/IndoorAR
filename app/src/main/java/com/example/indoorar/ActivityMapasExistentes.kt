package com.example.indoorar

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

class ActivityMapasExistentes : BaseActivity() {
    private lateinit var recycler: RecyclerView
    private val adapter = MapasAdapter { mapa -> onMapaClicked(mapa) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mapas_existentes)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_voltar_branco)
        toolbar.setNavigationOnClickListener {
            startActivity(Intent(this, ActivityHomeComum::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
            finish()
        }
        recycler = findViewById(R.id.recyclerMapas)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        carregarMapasExistentes()
    }

    private fun carregarMapasExistentes() {
        FirebaseFirestore.getInstance()
            .collection("mapas")
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val itens = snap.documents.map { doc ->
                    MapaResumo(
                        id = doc.id,
                        nome = doc.getString("nome") ?: "Mapa sem nome",
                        descricao = doc.getString("descricao") ?: "Sem descrição",
                        autorUid = doc.getString("criadorUid") ?: "Desconhecido",
                        autorNome = doc.getString("nomeAutor") ?: "Desconhecido",
                        dataCriacao = doc.getTimestamp("dataCriacao")
                    )
                }
                adapter.submit(itens)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Erro ao carregar mapas", Toast.LENGTH_SHORT).show()
            }
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_mapa_preview, null, false)
        val txtTitulo = view.findViewById<TextView>(R.id.txtTituloMapa)
        val txtDesc = view.findViewById<TextView>(R.id.txtDescricaoMapa)
        val ivPreview = view.findViewById<ImageView>(R.id.ivPreview)
        txtTitulo.text = m.nome
        txtDesc.text = m.descricao
        ivPreview.setImageResource(R.drawable.ic_minimap_placeholder)

        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document(m.id)
        ivPreview.post {
            val w = ivPreview.width
            val h = ivPreview.height
            mapaRef.collection("formas").get().addOnSuccessListener { formasSnap ->
                mapaRef.collection("pois").get().addOnSuccessListener { poisSnap ->
                    ivPreview.setImageBitmap(gerarMinimapaBitmap(formasSnap.documents, poisSnap.documents, w, h))
                }
            }
        }

        view.findViewById<Button>(R.id.btnIniciarNavegacao).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ActivityMap::class.java).putExtra("MAP_ID", m.id))
        }

        val btnBaixarQRCode = view.findViewById<Button>(R.id.btnBaixarQRCode)
        val cardDownloadQRCode = view.findViewById<MaterialCardView>(R.id.cardDownloadQRCode)
        val btnDownloadPDF = view.findViewById<Button>(R.id.btnDownloadPDF)
        val btnDownloadPNG = view.findViewById<Button>(R.id.btnDownloadPNG)
        cardDownloadQRCode.visibility = View.GONE
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
        val descricao = mapa.descricao.ifBlank { "Sem descrição" }
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
                val stream = java.io.ByteArrayOutputStream()
                qrBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val img = com.itextpdf.text.Image.getInstance(stream.toByteArray())
                img.alignment = com.itextpdf.text.Element.ALIGN_CENTER
                document.add(img)
                val azul = com.itextpdf.text.BaseColor(0x32,0x35,0x7A)
                val fonteTitulo = com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 16f, com.itextpdf.text.Font.BOLD, azul)
                val fonteNormal = com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 12f, com.itextpdf.text.Font.NORMAL)
                document.add(com.itextpdf.text.Paragraph("\n$nomeMapa", fonteTitulo))
                document.add(com.itextpdf.text.Paragraph(descricao, fonteNormal))
                document.add(com.itextpdf.text.Paragraph(frase, fonteNormal))
                document.close(); writerPdf.close()
                abrirShareSheet(file, "application/pdf")
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao salvar PDF", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val padding = 24
                val lineSpacing = 16
                val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    textAlign = Paint.Align.CENTER
                }
                val tituloSize = 40f
                val descSize = 30f
                val fraseSize = 28f
                val extraHeight = (tituloSize + descSize + fraseSize + lineSpacing * 3 + padding * 2).toInt()
                val outBmp = Bitmap.createBitmap(qrSize, qrSize + extraHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outBmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(qrBmp, 0f, 0f, null)
                val centerX = qrSize / 2f
                var curY = qrSize + padding + tituloSize
                paintText.textSize = tituloSize
                paintText.color = 0xFF32357A.toInt()
                canvas.drawText(nomeMapa, centerX, curY, paintText)
                curY += lineSpacing + descSize
                paintText.textSize = descSize
                paintText.color = android.graphics.Color.BLACK
                canvas.drawText(descricao, centerX, curY, paintText)
                curY += lineSpacing + fraseSize
                paintText.textSize = fraseSize
                canvas.drawText(frase, centerX, curY, paintText)
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

    private fun abrirShareSheet(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = mimeType
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(shareIntent, "Compartilhar QR Code"))
    }
}