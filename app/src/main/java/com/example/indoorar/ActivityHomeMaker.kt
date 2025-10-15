package com.example.indoorar

import android.content.Intent
import android.os.Bundle
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
import com.example.indoorar.ui.ActivityEditor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import android.widget.LinearLayout

class ActivityHomeMaker : BaseActivity() {

    private lateinit var recyclerRecentes: RecyclerView
    private lateinit var progressRecentes: ProgressBar
    private lateinit var indicatorsRecentes: LinearLayout
    private lateinit var snapHelper: PagerSnapHelper
    private var recentesListener: ListenerRegistration? = null

    private val recentAdapter = RecentPagesAdapter { mapa ->
        // Abrir no editor
        startActivity(Intent(this, ActivityEditor::class.java).apply {
            putExtra("MAP_ID", mapa.id)
        })
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
        findViewById<TextView>(R.id.txtBemVindo).text = "BEM-VINDO!"

        // Botões principais
        findViewById<ImageView>(R.id.btnCriarMapa).setOnClickListener {
            startActivity(Intent(this, ActivityEditor::class.java))
        }
        findViewById<ImageView>(R.id.btnMeusMapas).setOnClickListener {
            startActivity(Intent(this, ActivityMeusMapas::class.java))
        }
        findViewById<ImageView>(R.id.btnPerfil).setOnClickListener {
            startActivity(Intent(this, ActivityPerfil::class.java))
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
}