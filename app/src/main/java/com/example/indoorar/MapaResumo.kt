package com.example.indoorar

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob

data class MapaResumo(
    val id: String,
    val nome: String,
    val descricao: String,
    val autorUid: String,
    val autorNome: String,
    val dataCriacao: Timestamp?,
    val imagemUrl: String?,
    val imagemBlob: Blob? = null,
    val imagemMime: String? = null,
    val imagemBlobThumb: Blob? = null,
    val imagemMimeThumb: String? = null
)
