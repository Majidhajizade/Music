package com.music.app.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "Unknown Album",
    val dateAdded: Long = 0L,
    val artistId: Long? = null,
    val albumId: Long? = null,
    val coverUrl: String? = null,
    val duration: Long = 0L,
    val releaseDate: Long = 0L
)
