package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.ui.ActivityEditor
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.bumptech.glide.Glide
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.toColorInt
import com.google.firebase.storage.FirebaseStorage

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
                adapter.submit(listaMapas)
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
            imagemUrl = doc.getString("imagemUrl")
        )
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_mapa_preview, null)
        val txtTitulo = view.findViewById<TextView>(R.id.txtTituloMapa)
        val txtDesc = view.findViewById<TextView>(R.id.txtDescricaoMapa)
        val ivPreview = view.findViewById<ImageView>(R.id.ivPreview)
        val btnIniciar = view.findViewById<Button>(R.id.btnIniciarNavegacao)

        txtTitulo.text = m.nome
        txtDesc.text = m.descricao.ifBlank { "Sem descrição" }

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
            // Gera um minimapa simples a partir de formas e pois
            val mapaRef = FirebaseFirestore.getInstance().collection("mapas").document(m.id)
            ivPreview.post {
                val w = ivPreview.width
                val h = ivPreview.height
                mapaRef.collection("formas").get().addOnSuccessListener { formasSnap ->
                    mapaRef.collection("pois").get().addOnSuccessListener { poisSnap ->
                        ivPreview.setImageBitmap(
                            gerarMinimapaBitmap(formasSnap.documents, poisSnap.documents, w, h)
                        )
                    }
                }
            }
        }

        btnIniciar.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ActivityMap::class.java).apply {
                putExtra("MAP_ID", m.id)
            })
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
}
