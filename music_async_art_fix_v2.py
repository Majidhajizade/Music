from pathlib import Path
from datetime import datetime
import re
import sys

FILE = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not FILE.exists():
    sys.exit("ERROR: MainActivity.kt not found.")

original = FILE.read_text()
s = original

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected 1 match, found {count}. No file written."
        )
    return text.replace(old, new, 1)

def replace_in_section(text, start_marker, end_marker, old, new, label, expected):
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f"{label}: start marker not found.")

    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise RuntimeError(f"{label}: end marker not found.")

    section = text[start:end]
    count = section.count(old)

    if count != expected:
        raise RuntimeError(
            f"{label}: expected {expected} matches, found {count}. No file written."
        )

    section = section.replace(old, new)
    return text[:start] + section + text[end:]

# 1. Dedicated artwork executor
s = replace_once(
    s,
    """    private val onlineSearchExecutor =
        Executors.newFixedThreadPool(2)
""",
    """    private val onlineSearchExecutor =
        Executors.newFixedThreadPool(2)

    // Album artwork must never be extracted on the UI thread.
    private val albumArtExecutor: ExecutorService =
        Executors.newFixedThreadPool(2)
""",
    "Artwork executor"
)

# 2. Request generation prevents stale artwork updates
s = replace_once(
    s,
    "    private var currentSong: Song? = null\n",
    """    private var currentSong: Song? = null

    private val playerArtworkRequestId =
        java.util.concurrent.atomic.AtomicLong(0L)
""",
    "Artwork request counter"
)

# 3. Make the missing-art cache thread-safe
s = replace_once(
    s,
    """    private val albumArtMissCache =
        mutableSetOf<Long>()
""",
    """    private val albumArtMissCache =
        java.util.Collections.synchronizedSet(
            mutableSetOf<Long>()
        )
""",
    "Thread-safe artwork miss cache"
)

# 4. Add asynchronous artwork loader
helper_marker = "    private fun showHome() {"

helper = """    private fun loadAlbumArtAsync(
        song: Song,
        onLoaded: (android.graphics.Bitmap?) -> Unit
    ) {
        try {
            albumArtExecutor.execute {
                val bitmap = getAlbumArt(song)

                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        onLoaded(bitmap)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Activity is already being destroyed.
        }
    }

"""

s = replace_once(
    s,
    helper_marker,
    helper + helper_marker,
    "Async artwork loader insertion"
)

# 5. Replace synchronous player artwork updater
start_marker = "    private fun updateAllPlayerArtwork(song: Song) {"
end_marker = "    private fun updatePlaybackUiFromController() {"

start = s.find(start_marker)
end = s.find(end_marker, start + len(start_marker))

if start < 0 or end < 0:
    raise RuntimeError("updateAllPlayerArtwork boundaries not found.")

new_function = """    private fun updateAllPlayerArtwork(song: Song) {

        val requestId =
            playerArtworkRequestId.incrementAndGet()

        loadAlbumArtAsync(song) { artwork ->

            if (
                requestId != playerArtworkRequestId.get() ||
                currentSong?.id != song.id
            ) {
                return@loadAlbumArtAsync
            }

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
    }

"""

s = s[:start] + new_function + s[end:]

# 6. Restore player state without synchronous artwork extraction
restore_art = """                    getAlbumArt(song)?.let {
                        miniCover.setImageBitmap(it)
                    } ?: run {
                        miniCover.setImageResource(
                            R.drawable.icon
                        )
                    }
"""

s = replace_in_section(
    s,
    "    private fun restorePlayerState() {",
    "    private fun createBottomNavigation(): LinearLayout {",
    restore_art,
    "                    updateAllPlayerArtwork(song)\n",
    "Restore artwork",
    1
)

restore_art_2 = """        getAlbumArt(song)?.let {
            miniCover.setImageBitmap(it)
        } ?: run {
            miniCover.setImageResource(
                R.drawable.icon
            )
        }
"""

s = replace_in_section(
    s,
    "    private fun restorePlayerState() {",
    "    private fun createBottomNavigation(): LinearLayout {",
    restore_art_2,
    "        updateAllPlayerArtwork(song)\n",
    "Restore saved artwork",
    1
)

# 7. Library rows: placeholder first, then asynchronous artwork
library_sync = """                    getAlbumArt(song)?.let {
                        setImageBitmap(it)
                    } ?: run {
                        setImageResource(R.drawable.icon)
                    }
"""

s = replace_in_section(
    s,
    "    private fun showLibrarySongs() {",
    "    private fun showLibrarySection(",
    library_sync,
    """                    setImageResource(R.drawable.icon)
""",
    "Library synchronous artwork",
    1
)

library_anchor = """                row.addView(
                    cover,
"""

s = replace_in_section(
    s,
    "    private fun showLibrarySongs() {",
    "    private fun showLibrarySection(",
    library_anchor,
    """                cover.tag = song.id

                loadAlbumArtAsync(song) { bitmap ->
                    if (cover.tag == song.id && bitmap != null) {
                        cover.setImageBitmap(bitmap)
                    }
                }

                row.addView(
                    cover,
""",
    "Library asynchronous artwork",
    1
)

# 8. Queue rows: placeholder first, then asynchronous artwork
queue_sync = """            getAlbumArt(queueSong)?.let {
                cover.setImageBitmap(it)
            } ?: run {
                cover.setImageResource(R.drawable.icon)
            }
"""

s = replace_once(
    s,
    queue_sync,
    """            cover.setImageResource(R.drawable.icon)
""",
    "Queue synchronous artwork"
)

queue_anchor = """            val name =
                TextView(this).apply {
"""

s = replace_once(
    s,
    queue_anchor,
    """            cover.tag = queueSong.id

            loadAlbumArtAsync(queueSong) { bitmap ->
                if (cover.tag == queueSong.id && bitmap != null) {
                    cover.setImageBitmap(bitmap)
                }
            }

            val name =
                TextView(this).apply {
""",
    "Queue asynchronous artwork"
)

# 9. Full Player: use cached artwork only during initial construction
s = replace_once(
    s,
    """            } else {
                getAlbumArt(song)
            }
""",
    """            } else {
                albumArtCache.get(song.id)
            }
""",
    "Full Player initial artwork"
)

# 10. Load Full Player cover asynchronously after creating the ImageView
s = replace_once(
    s,
    """        fullPlayerCover = cover

        val coverContainer = FrameLayout(this).apply {
""",
    """        fullPlayerCover = cover

        loadAlbumArtAsync(song) { bitmap ->
            if (
                bitmap != null &&
                currentSong?.id == song.id &&
                fullPlayerCover === cover
            ) {
                cover.setImageBitmap(bitmap)
            }
        }

        val coverContainer = FrameLayout(this).apply {
""",
    "Full Player asynchronous cover"
)

# 11. Replace Full Player's periodic synchronous artwork lookup
color_start = "        val colorUpdater =\n            object : Runnable {"
color_end = "\n\n        handler.post(updater)"

start = s.find(color_start)
end = s.find(color_end, start + len(color_start))

if start < 0 or end < 0:
    raise RuntimeError("Full Player colorUpdater boundaries not found.")

new_color_updater = """        val colorUpdater =
            object : Runnable {

                override fun run() {

                    loadAlbumArtAsync(song) { bitmap ->

                        if (
                            bitmap != null &&
                            dialog.isShowing &&
                            currentSong?.id == song.id &&
                            fullPlayerCover === cover
                        ) {

                            val targetColors =
                                getAlbumColors(
                                    song,
                                    bitmap
                                )

                            val startColors =
                                currentColors.copyOf()

                            val animator =
                                android.animation.ValueAnimator.ofFloat(
                                    0f,
                                    1f
                                ).apply {

                                    duration = 1800L

                                    addUpdateListener {

                                        val f =
                                            it.animatedValue as Float

                                        val colors =
                                            IntArray(3)

                                        for (i in 0..2) {
                                            colors[i] =
                                                mixColor(
                                                    startColors[i],
                                                    targetColors[i],
                                                    f
                                                )
                                        }

                                        applyBackground(colors)
                                    }

                                    addListener(
                                        object :
                                            android.animation.Animator.AnimatorListener {

                                            override fun onAnimationStart(
                                                animation: android.animation.Animator
                                            ) {}

                                            override fun onAnimationEnd(
                                                animation: android.animation.Animator
                                            ) {
                                                currentColors = targetColors
                                            }

                                            override fun onAnimationCancel(
                                                animation: android.animation.Animator
                                            ) {}

                                            override fun onAnimationRepeat(
                                                animation: android.animation.Animator
                                            ) {}
                                        }
                                    )
                                }

                            animator.start()
                        }
                    }

                    handler.postDelayed(
                        this,
                        2000L
                    )
                }
            }
"""

s = s[:start] + new_color_updater + s[end:]

# 12. playSong: preserve text animation, remove synchronous artwork calls
play_start = "    private fun playSong("
play_end = "    override fun onStart() {"

start = s.find(play_start)
end = s.find(play_end, start + len(play_start))

if start < 0 or end < 0:
    raise RuntimeError("playSong boundaries not found.")

section = s[start:end]

old_play_block_start = "        if (smoothMiniChange) {"
old_play_block_end = "        playButton.setImageResource("

a = section.find(old_play_block_start)
b = section.find(old_play_block_end, a + len(old_play_block_start))

if a < 0 or b < 0:
    raise RuntimeError("playSong artwork block boundaries not found.")

new_play_block = """        if (smoothMiniChange) {

            animateMiniSongTextChange(
                song,
                miniTextDirection
            )

        } else {

            miniTitle.text = song.title
            miniArtist.text = song.artist
        }

        updateAllPlayerArtwork(song)

"""

section = section[:a] + new_play_block + section[b:]
s = s[:start] + section + s[end:]

# 13. Shut down artwork executor when Activity is destroyed
s = replace_once(
    s,
    """        backgroundExecutor.shutdownNow()

        super.onDestroy()
""",
    """        backgroundExecutor.shutdownNow()
        albumArtExecutor.shutdownNow()

        super.onDestroy()
""",
    "Artwork executor shutdown"
)

# Final sanity checks before writing
if s == original:
    raise RuntimeError("No changes generated.")

if "artworkBytes(song)" in s:
    print("NOTICE: artworkBytes(song) still exists somewhere in this local file.")

# Backup only after all transformations succeeded
backup = FILE.with_name(
    FILE.name + ".bak_async_art_v2_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

backup.write_text(original)
FILE.write_text(s)

print("SUCCESS: Async artwork patch applied.")
print("Backup:", backup)

print("\nRemaining getAlbumArt references:")
for number, line in enumerate(s.splitlines(), 1):
    if "getAlbumArt(" in line:
        print(f"{number}: {line.strip()}")

print("\nNext checks:")
print("git diff --check")
print("git diff --stat")
