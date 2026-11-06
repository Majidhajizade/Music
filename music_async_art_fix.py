from pathlib import Path
import re
import shutil

p = Path("app/src/main/java/com/music/app/MainActivity.kt")
s = p.read_text()
original = s

def replace_once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    s = s.replace(old, new, 1)
    print("OK:", label)

replace_once(
'''    private val backgroundExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()
''',
'''    private val backgroundExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val albumArtExecutor: ExecutorService =
        Executors.newFixedThreadPool(2)

    private var albumArtRequestId = 0L
''',
"Artwork executor"
)

replace_once(
'''    private val albumArtMissCache =
        mutableSetOf<Long>()
''',
'''    private val albumArtMissCache =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
''',
"Thread-safe cache"
)

replace_once(
'''    private fun updateAllPlayerArtwork(song: Song) {
''',
'''    private fun loadAlbumArtAsync(
        song: Song,
        callback: (android.graphics.Bitmap?) -> Unit
    ) {
        albumArtExecutor.execute {
            val bitmap = try {
                getAlbumArt(song)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                callback(bitmap)
            }
        }
    }

    private fun updateAllPlayerArtwork(song: Song) {
''',
"Async loader"
)

start = s.index("    private fun updateAllPlayerArtwork(song: Song) {")
end = s.index("    private fun updatePlaybackUiFromController()", start)

s = s[:start] + '''    private fun updateAllPlayerArtwork(song: Song) {
        val requestId = ++albumArtRequestId

        if (::miniCover.isInitialized) {
            miniCover.setImageResource(R.drawable.icon)
        }
        miniExpansionCover?.setImageResource(R.drawable.icon)
        fullPlayerCover?.setImageResource(R.drawable.icon)
        (miniTransitionFullCover as? ImageView)
            ?.setImageResource(R.drawable.icon)

        loadAlbumArtAsync(song) { artwork ->
            if (requestId != albumArtRequestId ||
                currentSong?.id != song.id
            ) return@loadAlbumArtAsync

            if (::miniCover.isInitialized) {
                if (artwork != null) miniCover.setImageBitmap(artwork)
                else miniCover.setImageResource(R.drawable.icon)
            }

            miniExpansionCover?.let {
                if (artwork != null) it.setImageBitmap(artwork)
                else it.setImageResource(R.drawable.icon)
            }

            fullPlayerCover?.let {
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

            (miniTransitionFullCover as? ImageView)?.let {
                if (artwork != null) it.setImageBitmap(artwork)
                else it.setImageResource(R.drawable.icon)
            }
        }
    }

''' + s[end:]
print("OK: Async artwork UI")

pattern = re.compile(
    r'        artworkBytes\\(song\\)\\?\\.let \\{\\s*'
    r'metadataBuilder\\.setArtworkData\\(\\s*'
    r'it,\\s*MediaMetadata\\.PICTURE_TYPE_FRONT_COVER\\s*'
    r'\\)\\s*\\}\\s*'
)
matches = len(pattern.findall(s))
if matches == 1:
    s = pattern.sub(
        '        // Artwork is loaded asynchronously by the player UI.\\n',
        s, count=1
    )
    print("OK: MediaItem artwork extraction removed")
elif matches == 0 and "Artwork is loaded asynchronously by the player UI." in s:
    print("OK: MediaItem artwork already removed")
else:
    raise RuntimeError(f"MediaItem artwork block matches: {matches}")

old = '''            } else {
                getAlbumArt(song)
            }
'''
new = '''            } else {
                null
            }
'''
replace_once(old, new, "Full Player initial artwork")

replace_once(
'''                    val bitmap =
                        getAlbumArt(song)

                    if (bitmap != null) {
''',
'''                    loadAlbumArtAsync(song) { bitmap ->
                    if (bitmap != null) {
''',
"Async Full Player colors"
)

replace_once(
'''                        animator.start()
                    }

                    handler.postDelayed(
                        this,
                        2000L
                    )
''',
'''                        animator.start()
                    }
                    }

                    handler.postDelayed(
                        this,
                        2000L
                    )
''',
"Color callback closure"
)

start = s.index("        if (smoothMiniChange) {", s.index("    private fun playSong("))
end = s.index("        playButton.setImageResource(", start)
s = s[:start] + '''        updateAllPlayerArtwork(song)

        if (smoothMiniChange) {
            animateMiniSongTextChange(
                song,
                miniTextDirection
            )
        } else {
            miniTitle.text = song.title
            miniArtist.text = song.artist
        }

''' + s[end:]
print("OK: playSong artwork made asynchronous")

replace_once(
'''        backgroundExecutor.shutdownNow()

        super.onDestroy()
''',
'''        backgroundExecutor.shutdownNow()
        albumArtExecutor.shutdownNow()

        super.onDestroy()
''',
"Executor cleanup"
)

if s == original:
    raise RuntimeError("No changes made")

backup = p.with_name(p.name + ".bak_async_art")
if not backup.exists():
    shutil.copy2(p, backup)

p.write_text(s)
print("\\nSUCCESS: Asynchronous artwork patch applied.")
print("Backup:", backup)
