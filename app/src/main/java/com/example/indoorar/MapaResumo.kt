package com.example.indoorar

import com.google.firebase.Timestamp

data class MapaResumo(
    val id: String,
    val nome: String,
    val descricao: String,
    val autorUid: String,
    val autorNome: String,
    val dataCriacao: Timestamp?,
    val imagemUrl: String?
)
