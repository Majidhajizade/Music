#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import re
import shutil
import sys

FILE = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not FILE.is_file():
    sys.exit("ERROR: MainActivity.kt not found")

source = FILE.read_text()
original = source
changes = []

def require(condition, message):
    if not condition:
        sys.exit("ABORT: " + message)

def replace_once(old, new, label):
    global source
    count = source.count(old)
    require(count == 1, f"{label}: expected 1 match, found {count}")
    source = source.replace(old, new, 1)
    changes.append(label)

# --------------------------------------------------
# 1. Validate existing source
# --------------------------------------------------

require("private val backgroundExecutor" in source,
        "backgroundExecutor declaration missing")

require("private val albumArtCache" in source,
        "albumArtCache missing")

require("private fun getAlbumArt(song: Song)" in source,
        "getAlbumArt missing")

require("private fun updateAllPlayerArtwork(song: Song)" in source,
        "updateAllPlayerArtwork missing")

require("private fun createMiniExpansionBackground" in source,
        "createMiniExpansionBackground missing")

require("private fun animateSongChange" in source,
        "animateSongChange missing")

require("backgroundExecutor.shutdownNow()" in source,
        "onDestroy executor shutdown anchor missing")

require("// Artwork is loaded separately by the player UI." in source,
        "MediaItem artwork protection comment missing")

require("albumArtExecutor" not in source,
        "Async artwork infrastructure already exists; refusing duplicate patch")

# --------------------------------------------------
# 2. Dedicated executor and synchronization
# --------------------------------------------------

replace_once(
'''    private val backgroundExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()
''',
'''    private val backgroundExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    // Album artwork extraction must never run on the UI thread.
    private val albumArtExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val albumArtLock = Any()

    private val albumArtInFlight =
        mutableMapOf<Long, MutableList<(android.graphics.Bitmap?) -> Unit>>()
''',
"Dedicated artwork executor and synchronization"
)

# --------------------------------------------------
# 3. Replace artwork cache/extraction implementation
# --------------------------------------------------

start = source.find("private val albumArtCache")
end_marker = "private fun getAlbumArt(song: Song): android.graphics.Bitmap?"
start_fn = source.find(end_marker, start)

require(start >= 0 and start_fn > start,
        "Artwork cache section not found")

brace = source.find("{", start_fn)
depth = 0
in_string = False
escaped = False
end_fn = -1

for i in range(brace, len(source)):
    c = source[i]
    if in_string:
        if escaped:
            escaped = False
        elif c == "\\":
            escaped = True
        elif c == '"':
            in_string = False
        continue
    if c == '"':
        in_string = True
    elif c == "{":
        depth += 1
    elif c == "}":
        depth -= 1
        if depth == 0:
            end_fn = i + 1
            break

require(end_fn > 0, "Could not locate end of getAlbumArt")

new_cache = '''private val albumArtCache =
    object : android.util.LruCache<Long, android.graphics.Bitmap>(
        (
            Runtime.getRuntime().maxMemory() / 1024L / 8L
        ).toInt().coerceAtMost(16 * 1024)
    ) {
        override fun sizeOf(
            key: Long,
            value: android.graphics.Bitmap
        ): Int {
            return value.byteCount / 1024
        }
    }

private val albumArtMissCache =
    mutableSetOf<Long>()

private val albumColorCache =
    object : android.util.LruCache<Long, IntArray>(256) {
    }

private fun getAlbumArtCached(
    song: Song
): android.graphics.Bitmap? {
    return synchronized(albumArtLock) {
        albumArtCache.get(song.id)
    }
}

/*
 * Worker-thread only.
 * Never call this method directly from UI code.
 */
private fun getAlbumArt(
    song: Song
): android.graphics.Bitmap? {

    synchronized(albumArtLock) {
        albumArtCache.get(song.id)?.let { return it }
        if (albumArtMissCache.contains(song.id)) return null
    }

    return try {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            song.id
        )

        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(this, uri)
            val data = retriever.embeddedPicture

            val bitmap = if (data != null) {
                android.graphics.BitmapFactory.decodeByteArray(
                    data, 0, data.size
                )
            } else {
                null
            }

            synchronized(albumArtLock) {
                if (bitmap != null) {
                    albumArtCache.put(song.id, bitmap)
                    albumArtMissCache.remove(song.id)
                } else {
                    albumArtMissCache.add(song.id)
                }
            }

            bitmap
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        synchronized(albumArtLock) {
            albumArtMissCache.add(song.id)
        }
        null
    }
}

/*
 * Safe asynchronous artwork loader.
 * ImageView tag prevents stale results from replacing newer songs.
 */
private fun loadAlbumArtAsync(
    song: Song,
    imageView: ImageView,
    fallbackRes: Int = R.drawable.icon,
    onLoaded: ((android.graphics.Bitmap?) -> Unit)? = null
) {
    imageView.tag = song.id

    val cached = getAlbumArtCached(song)
    if (cached != null) {
        imageView.setImageBitmap(cached)
        onLoaded?.invoke(cached)
        return
    }

    imageView.setImageResource(fallbackRes)

    synchronized(albumArtLock) {
        albumArtInFlight[song.id]?.let { callbacks ->
            callbacks.add { bitmap ->
                mainHandler.post {
                    if (imageView.tag == song.id &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        bitmap?.let {
                            imageView.setImageBitmap(it)
                        }
                        onLoaded?.invoke(bitmap)
                    }
                }
            }
            return
        }

        albumArtInFlight[song.id] =
            mutableListOf { bitmap ->
                mainHandler.post {
                    if (imageView.tag == song.id &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        bitmap?.let {
                            imageView.setImageBitmap(it)
                        }
                        onLoaded?.invoke(bitmap)
                    }
                }
            }
    }

    albumArtExecutor.execute {
        val bitmap = getAlbumArt(song)

        val callbacks = synchronized(albumArtLock) {
            albumArtInFlight.remove(song.id)?.toList()
                ?: emptyList()
        }

        callbacks.forEach { callback ->
            callback(bitmap)
        }
    }
}'''

source = source[:start] + new_cache + source[end_fn:]
changes.append("Thread-safe artwork cache and asynchronous loader")

# --------------------------------------------------
# 4. updateAllPlayerArtwork
# --------------------------------------------------

replace_once(
'''    private fun updateAllPlayerArtwork(song: Song) {
        val artwork = getAlbumArt(song)

        if (::miniCover.isInitialized) {
            artwork?.let {
                miniCover.setImageBitmap(it)
            } ?: miniCover.setImageResource(
                R.drawable.icon
            )
        }

        miniExpansionCover?.let { cover ->
            artwork?.let {
                cover.setImageBitmap(it)
            } ?: cover.setImageResource(
                R.drawable.icon
            )
        }

        fullPlayerCover?.let { cover ->
            artwork?.let {
                cover.setImageBitmap(it)
            } ?: cover.setImageResource(
                R.drawable.icon
            )

            if (fullPlayerQueueExpanded) {
                cover.animate().cancel()
                cover.clearAnimation()
                cover.alpha = 0f
                cover.scaleX = 0.94f
                cover.scaleY = 0.94f
            }
        }

        (miniTransitionFullCover as? ImageView)?.let { cover ->
            artwork?.let {
                cover.setImageBitmap(it)
            } ?: cover.setImageResource(
                R.drawable.icon
            )
        }
    }''',
'''    private fun updateAllPlayerArtwork(song: Song) {
        if (::miniCover.isInitialized) {
            loadAlbumArtAsync(song, miniCover)
        }

        miniExpansionCover?.let { cover ->
            loadAlbumArtAsync(song, cover)
        }

        fullPlayerCover?.let { cover ->
            loadAlbumArtAsync(song, cover)

            if (fullPlayerQueueExpanded) {
                cover.animate().cancel()
                cover.clearAnimation()
                cover.alpha = 0f
                cover.scaleX = 0.94f
                cover.scaleY = 0.94f
            }
        }

        (miniTransitionFullCover as? ImageView)?.let { cover ->
            loadAlbumArtAsync(song, cover)
        }
    }''',
"Convert updateAllPlayerArtwork to async"
)

# --------------------------------------------------
# 5. Mini expansion background fallback
# --------------------------------------------------

start = source.find("private fun createMiniExpansionBackground")
require(start >= 0, "Mini background function missing")
brace = source.find("{", start)
depth = 0
in_string = False
escaped = False
end = -1

for i in range(brace, len(source)):
    c = source[i]
    if in_string:
        if escaped:
            escaped = False
        elif c == "\\":
            escaped = True
        elif c == '"':
            in_string = False
        continue
    if c == '"':
        in_string = True
    elif c == "{":
        depth += 1
    elif c == "}":
        depth -= 1
        if depth == 0:
            end = i + 1
            break

require(end > 0, "Mini background function end missing")

new_background = '''private fun createMiniExpansionBackground(
    song: Song
): GradientDrawable {

    return GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.rgb(80, 80, 80),
            Color.rgb(20, 20, 20)
        )
    ).apply {
        cornerRadii = floatArrayOf(
            0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f
        )
    }
}'''

source = source[:start] + new_background + source[end:]
changes.append("Remove synchronous extraction from mini background")

# --------------------------------------------------
# 6. Mini expansion card async gradient
# --------------------------------------------------

replace_once(
'''        val card = FrameLayout(this).apply {
            clipChildren = false
            background = createMiniExpansionBackground(song)
            elevation = dp(12).toFloat()
        }''',
'''        val card = FrameLayout(this).apply {
            clipChildren = false
            tag = song.id
            background = createMiniExpansionBackground(song)
            elevation = dp(12).toFloat()
        }''',
"Tag mini expansion card with song ID"
)

replace_once(
'''        miniExpansionCard = card
        miniExpansionCover = cover
        miniExpansionTitle = title
        miniExpansionArtist = artist

        miniPlayer.alpha = 0f

        updateMiniExpansionCard(0f)''',
'''        miniExpansionCard = card
        miniExpansionCover = cover
        miniExpansionTitle = title
        miniExpansionArtist = artist

        loadAlbumArtAsync(song, cover) { bitmap ->
            if (bitmap != null &&
                miniExpansionCard === card &&
                card.tag == song.id
            ) {
                val w = bitmap.width.coerceAtLeast(1)
                val h = bitmap.height.coerceAtLeast(1)

                val points = intArrayOf(
                    bitmap.getPixel(0, 0),
                    bitmap.getPixel(w / 2, h / 2),
                    bitmap.getPixel(w - 1, h - 1)
                )

                card.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    points
                ).apply {
                    cornerRadii = floatArrayOf(
                        0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f
                    )
                }
            }
        }

        miniPlayer.alpha = 0f

        updateMiniExpansionCard(0f)''',
"Load mini expansion artwork and gradient asynchronously"
)

# --------------------------------------------------
# 7. onDestroy
# --------------------------------------------------

replace_once(
'''        backgroundExecutor.shutdownNow()

        super.onDestroy()''',
'''        backgroundExecutor.shutdownNow()
        albumArtExecutor.shutdownNow()

        synchronized(albumArtLock) {
            albumArtInFlight.clear()
        }

        super.onDestroy()''',
"Shutdown artwork executor safely"
)

# --------------------------------------------------
# 8. Validate no accidental MediaItem regression
# --------------------------------------------------

require(
    "// Artwork is loaded separately by the player UI." in source,
    "MediaItem artwork comment was changed"
)

require(
    "artworkBytes(song)" not in source[source.find("private fun mediaItemForSong"):source.find("private fun mediaItemForSong") + 5000],
    "Synchronous artwork extraction was reintroduced into MediaItem"
)

require(source.count("albumArtExecutor") >= 3,
        "Artwork executor declarations/calls incomplete")

require(source.count("private fun getAlbumArt(") == 1,
        "Unexpected getAlbumArt definitions")

# --------------------------------------------------
# 9. Atomic write with backup
# --------------------------------------------------

require(source != original, "No changes generated")

backup = FILE.with_name(
    FILE.name + ".bak_async_v5_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

shutil.copy2(FILE, backup)

tmp = FILE.with_suffix(FILE.suffix + ".tmp")
tmp.write_text(source)
tmp.replace(FILE)

print("\nSUCCESS: V5 partial async artwork patch applied")
print("Backup:", backup)
print("\nApplied:")
for item in changes:
    print(" -", item)

print("\nNOTE:")
print("Other getAlbumArt call sites are not yet converted.")
print("Do not build until remaining call sites are patched.")
