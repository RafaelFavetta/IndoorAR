package com.example.indoorar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

class ActivityMeusMapas : BaseActivity() {

    private lateinit var recycler: RecyclerView
    private val adapter = MapasAdapter { mapa -> onMapaClicked(mapa) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meus_mapas)

        recycler = findViewById(R.id.recyclerMapas)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        carregarMapas()
    }

    private fun carregarMapas() {
        FirebaseFirestore.getInstance()
            .collection("mapas")
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val itens = snap.documents.map { doc ->
                    MapaResumo(
                        id = doc.id,
                        nome = doc.getString("nome") ?: "FATEC Araras Antonio Brambilla",
                        descricao = doc.getString("descricao") ?: "Mapa da FATEC Araras 2025",
                        autor = doc.getString("criadorUid") ?: "number",
                        dataCriacao = doc.getTimestamp("dataCriacao")
                    )
                }
                adapter.submit(itens)
            }
            .addOnFailureListener {
            }
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottomsheet_mapa_preview, null)

        view.findViewById<TextView>(R.id.txtTituloMapa).text = m.nome
        view.findViewById<TextView>(R.id.txtDescricaoMapa).text = m.descricao
        val ivPreview = view.findViewById<ImageView>(R.id.ivPreview)
        ivPreview.setImageResource(R.drawable.ic_minimap_placeholder)

        // Carregar formas e pois do Firestore
        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document(m.id)
        mapaRef.collection("formas").get().addOnSuccessListener { formasSnap ->
            mapaRef.collection("pois").get().addOnSuccessListener { poisSnap ->
                val formas = formasSnap.documents
                val pois = poisSnap.documents
                ivPreview.setImageBitmap(gerarMinimapaBitmap(formas, pois, ivPreview.width, ivPreview.height))
            }
        }

        view.findViewById<Button>(R.id.btnIniciarNavegacao).setOnClickListener {
            dialog.dismiss()
            val itn = android.content.Intent(this, ActivityMap::class.java)
            itn.putExtra("MAP_ID", m.id)
            startActivity(itn)
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
}

private data class MapaResumo(
    val id: String,
    val nome: String,
    val descricao: String,
    val autor: String,
    val dataCriacao: Timestamp?
)

private class MapasAdapter(
    private val onClick: (MapaResumo) -> Unit
) : RecyclerView.Adapter<MapasAdapter.VH>() {

    private val itens = mutableListOf<MapaResumo>()

    fun submit(novos: List<MapaResumo>) {
        itens.clear()
        itens.addAll(novos)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mapa, parent, false)
        return VH(v)
    }

    override fun getItemCount() = itens.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(itens[position], onClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.cardMapa)
        private val txtNome = itemView.findViewById<TextView>(R.id.txtNome)
        private val txtDescricao = itemView.findViewById<TextView>(R.id.txtDescricao)
        private val txtAutorData = itemView.findViewById<TextView>(R.id.txtAutorData)

        fun bind(m: MapaResumo, onClick: (MapaResumo) -> Unit) {
            txtNome.text = m.nome
            txtDescricao.text = m.descricao
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            val dataFmt = m.dataCriacao?.let { sdf.format(it.toDate()) } ?: "data desconhecida"
            txtAutorData.text = "Autor: ${m.autor} • Criado em: $dataFmt"
            card.setOnClickListener { onClick(m) }
        }
    }
}
