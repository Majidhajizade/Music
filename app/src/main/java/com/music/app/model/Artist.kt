package com.music.app.model

data class Artist(
    val id: Long,
    val name: String,
    val slug: String = "",
    val imageUrl: String? = null,
    val bio: String? = null,
    val verified: Boolean = false
)
