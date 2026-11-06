#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import re
import shutil
import sys

FILE = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not FILE.is_file():
    sys.exit("ERROR: MainActivity.kt not found.")

src = FILE.read_text(encoding="utf-8")
original = src

def require(condition, message):
    if not condition:
        sys.exit("PREFLIGHT FAILED: " + message)

def replace_once(old, new, label):
    global src
    count = src.count(old)
    require(count == 1, f"{label}: expected 1 match, found {count}")
    src = src.replace(old, new, 1)

require("private fun getAlbumArt(song: Song)" in src,
        "getAlbumArt function not found")

require("private fun updateAllPlayerArtwork(song: Song)" in src,
        "updateAllPlayerArtwork function not found")

require("private val albumArtCache" in src,
        "albumArtCache not found")

require("private val backgroundExecutor" in src,
        "backgroundExecutor not found")

require("Artwork is loaded separately by the player UI." in src,
        "Your local MediaItem artwork change is missing; refusing to overwrite.")

require("loadAlbumArtAsync" not in src,
        "An async artwork patch already exists.")

# ---------------------------------------------------------
# 1. Add dedicated artwork executor and cache synchronization
# ---------------------------------------------------------

replace_once(
"""    private val backgroundExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()
""",
"""    private val backgroundExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val albumArtExecutor: ExecutorService =
        Executors.newFixedThreadPool(2)

    private val albumArtCacheLock = Any()
""",
"artwork executor"
)

replace_once(
"""    private val albumArtMissCache =
        mutableSetOf<Long>()
""",
"""    private val albumArtMissCache =
        java.util.Collections.synchronizedSet(
            mutableSetOf<Long>()
        )
""",
"miss cache synchronization"
)

# ---------------------------------------------------------
# 2. Synchronize cache access inside getAlbumArt
# ---------------------------------------------------------

replace_once(
"""        albumArtCache.get(song.id)?.let {
            return it
        }
""",
"""        synchronized(albumArtCacheLock) {
            albumArtCache.get(song.id)?.let {
                return it
            }
        }
""",
"album cache read"
)

replace_once(
"""                        albumArtCache.put(
                            song.id,
                            bitmap
                        )
""",
"""                        synchronized(albumArtCacheLock) {
                            albumArtCache.put(
                                song.id,
                                bitmap
                            )
                        }
""",
"album cache write"
)

# ---------------------------------------------------------
# 3. Add asynchronous loader and reusable ImageView binder
# ---------------------------------------------------------

anchor = """    private fun getAlbumArt(song: Song): android.graphics.Bitmap? {
"""

helper = """    private fun loadAlbumArtAsync(
        song: Song,
        callback: (android.graphics.Bitmap?) -> Unit
    ) {
        synchronized(albumArtCacheLock) {
            albumArtCache.get(song.id)?.let { cached ->
                mainHandler.post {
                    callback(cached)
                }
                return
            }
        }

        albumArtExecutor.execute {
            val bitmap = getAlbumArt(song)

            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    callback(bitmap)
                }
            }
        }
    }

    private fun bindAlbumArt(
        image: ImageView,
        song: Song
    ) {
        image.tag = song.id
        image.setImageResource(R.drawable.icon)

        loadAlbumArtAsync(song) { bitmap ->
            if (image.tag == song.id && !isFinishing && !isDestroyed) {
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                } else {
                    image.setImageResource(R.drawable.icon)
                }
            }
        }
    }

"""

replace_once(anchor, helper + anchor, "async artwork helper insertion")

# ---------------------------------------------------------
# 4. Convert central player artwork updater
# ---------------------------------------------------------

old = """    private fun updateAllPlayerArtwork(song: Song) {
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
    }
"""

new = """    private fun updateAllPlayerArtwork(song: Song) {
        val songId = song.id

        if (::miniCover.isInitialized) {
            miniCover.tag = songId
            miniCover.setImageResource(R.drawable.icon)
        }

        miniExpansionCover?.let {
            it.tag = songId
            it.setImageResource(R.drawable.icon)
        }

        fullPlayerCover?.let {
            it.tag = songId
            it.setImageResource(R.drawable.icon)
        }

        (miniTransitionFullCover as? ImageView)?.let {
            it.tag = songId
            it.setImageResource(R.drawable.icon)
        }

        loadAlbumArtAsync(song) { artwork ->
            if (currentSong?.id != songId) return@loadAlbumArtAsync

            if (::miniCover.isInitialized && miniCover.tag == songId) {
                if (artwork != null) miniCover.setImageBitmap(artwork)
                else miniCover.setImageResource(R.drawable.icon)
            }

            miniExpansionCover?.takeIf { it.tag == songId }?.let {
                if (artwork != null) it.setImageBitmap(artwork)
                else it.setImageResource(R.drawable.icon)
            }

            fullPlayerCover?.takeIf { it.tag == songId }?.let {
                if (artwork != null) it.setImageBitmap(artwork)
                else it.setImageResource(R.drawable.icon)

                if (fullPlayerQueueExpanded) {
                    it.animate().cancel()
                    it.clearAnimation()
                    it.alpha = 0f
                    it.scaleX = 0.94f
                    it.scaleY = 0.94f
                }
            }

            (miniTransitionFullCover as? ImageView)
                ?.takeIf { it.tag == songId }
                ?.let {
                    if (artwork != null) it.setImageBitmap(artwork)
                    else it.setImageResource(R.drawable.icon)
                }
        }
    }
"""

replace_once(old, new, "central player artwork updater")

# ---------------------------------------------------------
# 5. Convert library and queue ImageView call sites
# ---------------------------------------------------------

old1 = """                    getAlbumArt(song)?.let {
                        setImageBitmap(it)
                    } ?: run {
                        setImageResource(R.drawable.icon)
                    }
"""
new1 = """                    setImageResource(R.drawable.icon)
                    tag = song.id
"""
replace_once(old1, new1, "library artwork placeholder")

old2 = """                getAlbumArt(song)?.let {
                    cover.setImageBitmap(it)
                } ?: run {
                    cover.setImageResource(R.drawable.icon)
                }
"""
new2 = """                bindAlbumArt(cover, song)
"""
# This pattern appears in two separate library/card areas.
count = src.count(old2)
require(count == 2, f"library/card artwork blocks: expected 2, found {count}")
src = src.replace(old2, new2)

old3 = """        getAlbumArt(song)?.let {
            cover.setImageBitmap(it)
        } ?: run {
            cover.setImageResource(R.drawable.icon)
        }
"""
replace_once(old3, """        bindAlbumArt(cover, song)
""", "song row artwork")

old4 = """                val bitmap = getAlbumArt(song)

                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                } else {
                    image.setImageResource(R.drawable.icon)
                }
"""
replace_once(old4, """                bindAlbumArt(image, song)
""", "artwork preview")

old5 = """        getAlbumArt(song)?.let {
            queueCover.setImageBitmap(it)
        } ?: run {
            queueCover.setImageResource(R.drawable.icon)
        }
"""
replace_once(old5, """        bindAlbumArt(queueCover, song)
""", "queue header artwork")

old6 = """            getAlbumArt(queueSong)?.let {
                cover.setImageBitmap(it)
            } ?: run {
                cover.setImageResource(R.drawable.icon)
            }
"""
replace_once(old6, """            bindAlbumArt(cover, queueSong)
""", "queue popup artwork")

# ---------------------------------------------------------
# 6. Convert createRealSongCover
# ---------------------------------------------------------

old7 = """    private fun createRealSongCover(song: Song, sizeDp: Int): ImageView {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(sizeDp),
                dp(sizeDp)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP

            val art = getAlbumArt(song)

            if (art != null) {
                setImageBitmap(art)
            } else {
                setImageResource(R.drawable.icon)
                clearColorFilter()
                background = null
            }
        }
    }
"""

new7 = """    private fun createRealSongCover(song: Song, sizeDp: Int): ImageView {
        val cover = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(sizeDp),
                dp(sizeDp)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.icon)
            clearColorFilter()
            background = null
        }

        bindAlbumArt(cover, song)
        return cover
    }
"""
replace_once(old7, new7, "createRealSongCover")

# ---------------------------------------------------------
# 7. Replace direct playSong artwork reads
# ---------------------------------------------------------

old8 = """            getAlbumArt(song)?.let {
                miniCover.setImageBitmap(it)
            } ?: run {
                miniCover.setImageResource(
                    R.drawable.icon
                )
            }
"""
require(src.count(old8) == 2,
        "playSong artwork blocks: expected 2, found " + str(src.count(old8)))
src = src.replace(old8, """            updateAllPlayerArtwork(song)
""")

# ---------------------------------------------------------
# 8. Replace restore-state artwork reads
# ---------------------------------------------------------

old9 = """                    getAlbumArt(song)?.let {
                        miniCover.setImageBitmap(it)
                    } ?: run {
                        miniCover.setImageResource(
                            R.drawable.icon
                        )
                    }
"""
replace_once(old9, """                    updateAllPlayerArtwork(song)
""", "restore current media artwork")

old10 = """        getAlbumArt(song)?.let {
            miniCover.setImageBitmap(it)
        } ?: run {
            miniCover.setImageResource(
                R.drawable.icon
            )
        }
"""
replace_once(old10, """        updateAllPlayerArtwork(song)
""", "restore saved media artwork")

# ---------------------------------------------------------
# 9. Full Player: no synchronous initial extraction
# ---------------------------------------------------------

old11 = """            } else {
                getAlbumArt(song)
            }
"""
replace_once(old11, """            } else {
                null
            }
""", "Full Player initial artwork")

# ---------------------------------------------------------
# 10. Shutdown executor safely
# ---------------------------------------------------------

replace_once(
"""        backgroundExecutor.shutdownNow()

        super.onDestroy()
""",
"""        backgroundExecutor.shutdownNow()
        albumArtExecutor.shutdownNow()

        super.onDestroy()
""",
"executor shutdown"
)

# ---------------------------------------------------------
# 11. Validate result before writing
# ---------------------------------------------------------

require(src != original, "No source changes were produced.")

remaining = [
    (i + 1, line.strip())
    for i, line in enumerate(src.splitlines())
    if "getAlbumArt(" in line
]

# getAlbumArt definition and artworkBytes helper are allowed to remain.
unexpected = [
    item for item in remaining
    if "private fun getAlbumArt(" not in item[1]
    and "getAlbumArt(song)" not in item[1]
]

# Direct UI call sites should have been removed. The remaining
# artworkBytes helper is retained for compatibility but is no longer
# used by the local MediaItem builder.
require(
    len(remaining) <= 3,
    "Unexpected direct artwork calls remain: " + repr(remaining)
)

# Ensure our helper exists exactly once and the local MediaItem edit survives.
require(src.count("private fun loadAlbumArtAsync(") == 1,
        "async helper validation failed")

require("Artwork is loaded separately by the player UI." in src,
        "local MediaItem edit was not preserved")

# Kotlin brace sanity check (coarse; compiler remains authoritative).
require(src.count("{") == src.count("}"),
        "brace count mismatch")

# ---------------------------------------------------------
# 12. Create unique backup and write atomically
# ---------------------------------------------------------

stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
backup = FILE.with_name(FILE.name + ".bak_async_v3_" + stamp)

require(not backup.exists(), "backup already exists: " + str(backup))

shutil.copy2(FILE, backup)

tmp = FILE.with_suffix(FILE.suffix + ".async_v3.tmp")
tmp.write_text(src, encoding="utf-8")
tmp.replace(FILE)

print("SUCCESS: Async artwork V3 patch applied.")
print("Backup:", backup)
print("Changed file:", FILE)
print("Remaining getAlbumArt references:")
for line_no, text in remaining:
    print(f"  {line_no}: {text}")
print()
print("IMPORTANT: Run git diff --check and compile before committing.")
