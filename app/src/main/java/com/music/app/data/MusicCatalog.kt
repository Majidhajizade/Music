package com.music.app.data

import com.music.app.model.Album
import com.music.app.model.Artist
import com.music.app.model.Song

class MusicCatalog {

    private val artists = LinkedHashMap<Long, Artist>()
    private val albums = LinkedHashMap<Long, Album>()
    private val songs = LinkedHashMap<Long, Song>()

    fun clear() {
        artists.clear()
        albums.clear()
        songs.clear()
    }

    fun setSongs(items: List<Song>) {
        clear()

        items.forEach { song ->
            songs[song.id] = song

            val artistId = song.artistId ?: stableId("artist:${song.artist}")

            if (!artists.containsKey(artistId)) {
                artists[artistId] = Artist(
                    id = artistId,
                    name = song.artist.ifBlank { "Unknown Artist" },
                    slug = slugify(song.artist),
                    imageUrl = song.coverUrl
                )
            }

            if (song.album.isNotBlank() && song.album != "Unknown Album") {
                val albumId = song.albumId
                    ?: stableId("album:${artistId}:${song.album}")

                if (!albums.containsKey(albumId)) {
                    albums[albumId] = Album(
                        id = albumId,
                        title = song.album,
                        artistId = artistId,
                        artistName = song.artist,
                        slug = slugify(song.album),
                        coverUrl = song.coverUrl
                    )
                }
            }
        }
    }

    fun getArtists(): List<Artist> =
        artists.values.sortedBy { it.name.lowercase() }

    fun getAlbums(): List<Album> =
        albums.values.sortedBy { it.title.lowercase() }

    fun getSongs(): List<Song> =
        songs.values.toList()

    fun getArtist(id: Long): Artist? =
        artists[id]

    fun getAlbum(id: Long): Album? =
        albums[id]

    fun getArtistSongs(artistId: Long): List<Song> =
        songs.values.filter {
            it.artistId == artistId ||
                stableId("artist:${it.artist}") == artistId
        }

    fun getArtistAlbums(artistId: Long): List<Album> =
        albums.values.filter {
            it.artistId == artistId
        }

    fun getAlbumSongs(albumId: Long): List<Song> =
        songs.values.filter {
            val resolvedAlbumId =
                it.albumId
                    ?: stableId(
                        "album:${it.artistId ?: stableId("artist:${it.artist}")}:${it.album}"
                    )

            resolvedAlbumId == albumId
        }

    fun search(query: String): SearchResults {
        val q = query.trim()

        if (q.isBlank()) {
            return SearchResults()
        }

        val normalized = normalize(q)

        val artistResults = artists.values
            .filter {
                normalize(it.name).contains(normalized)
            }
            .sortedBy {
                matchRank(normalize(it.name), normalized)
            }

        val albumResults = albums.values
            .filter {
                normalize(it.title).contains(normalized) ||
                    normalize(it.artistName).contains(normalized)
            }
            .sortedBy {
                matchRank(
                    normalize(it.title),
                    normalized
                )
            }

        val songResults = songs.values
            .filter {
                normalize(it.title).contains(normalized) ||
                    normalize(it.artist).contains(normalized) ||
                    normalize(it.album).contains(normalized)
            }
            .sortedBy {
                matchRank(
                    normalize(it.title),
                    normalized
                )
            }

        return SearchResults(
            artists = artistResults,
            albums = albumResults,
            songs = songResults
        )
    }

    private fun matchRank(
        value: String,
        query: String
    ): Int {
        return when {
            value == query -> 0
            value.startsWith(query) -> 1
            value.contains(query) -> 2
            else -> 3
        }
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace(Regex("\\s+"), " ")
    }

    private fun slugify(value: String): String {
        return normalize(value)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
    }

    private fun stableId(value: String): Long {
        return value.hashCode().toLong() and 0x7fffffffL
    }
}

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList()
)
