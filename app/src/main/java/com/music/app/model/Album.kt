package com.music.app.model

data class Album(
    val id: Long,
    val title: String,
    val artistId: Long? = null,
    val artistName: String = "Unknown Artist",
    val slug: String = "",
    val coverUrl: String? = null,
    val releaseDate: Long = 0L,
    val albumType: String = "album"
)
