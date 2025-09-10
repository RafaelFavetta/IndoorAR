package com.example.indoorar.model

data class MapObject(
    val id: String,
    val type: String, // "rect" ou "poi"
    val name: String? = null,
    val x: Float,
    val y: Float,
    val width: Float? = null,
    val height: Float? = null,
    val color: Int? = null
)
