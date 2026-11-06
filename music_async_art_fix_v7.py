from pathlib import Path
from datetime import datetime
import sys
import shutil

FILE = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not FILE.is_file():
    sys.exit("ERROR: MainActivity.kt not found")

s = FILE.read_text()
original = s
changes = []

def replace(old, new, label, expected=1):
    global s
    count = s.count(old)
    if count != expected:
        sys.exit(
            f"ABORT: {label}: expected {expected}, found {count}"
        )
    s = s.replace(old, new)
    changes.append(f"{label} ({count})")

# 1. Make album color cache thread-safe.
replace(
'''            albumColorCache.get(song.id)?.let {
                return it
            }

            val colors = extractColors(bitmap)

            albumColorCache.put(
                song.id,
                colors
            )

            return colors''',
'''            synchronized(albumArtLock) {
                albumColorCache.get(song.id)?.let {
                    return it
                }

                val colors = extractColors(bitmap)

                albumColorCache.put(
                    song.id,
                    colors
                )

                return colors
            }''',
"Thread-safe album color cache"
)

# 2. Remove synchronous artwork extraction from Full Player.
replace(
'''        val initialArtwork =
            if (miniExpansionOpening) {
                (
                    miniExpansionCover?.drawable
                        as? android.graphics.drawable.BitmapDrawable
                )?.bitmap
            } else {
                getAlbumArt(song)
            }''',
'''        val initialArtwork =
            if (miniExpansionOpening) {
                (
                    miniExpansionCover?.drawable
                        as? android.graphics.drawable.BitmapDrawable
                )?.bitmap
            } else {
                null
            }''',
"Full Player initial artwork async"
)

# 3. Remove recurring color updater and its scheduling.
start = "        // ---------- DYNAMIC COLOR ANIMATION ----------"
end = "        // ---------- ATTACH LOWER PLAYER PANEL ----------"

if s.count(start) != 1 or s.count(end) != 1:
    sys.exit("ABORT: Dynamic color updater boundaries are not unique")

a = s.index(start)
b = s.index(end, a)

s = s[:a] + s[b:]

s = s.replace(
    " * the animation frame. The background will be refreshed\\n"
    "         * asynchronously after the player is shown.",
    " * the animation frame. Artwork and colors will be refreshed\\n"
    "         * asynchronously after the player is shown."
)

changes.append("Remove recurring color updater and polling")

# 4. Start one asynchronous artwork request after Full Player cover creation.
anchor = "        fullPlayerCover = cover"

callback = '''        fullPlayerCover = cover

        // Artwork loading and color animation are performed asynchronously.
        var fullPlayerArtworkActive = true

        val artworkLoadDelay =
            if (miniExpansionOpening) 400L else 0L

        handler.postDelayed({

            if (!fullPlayerArtworkActive ||
                isFinishing ||
                isDestroyed
            ) {
                return@postDelayed
            }

            loadAlbumArtAsync(song, cover) { bitmap ->

                if (bitmap == null ||
                    !fullPlayerArtworkActive ||
                    currentSong?.id != song.id ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return@loadAlbumArtAsync
                }

                val targetColors = getAlbumColors(
                    song,
                    bitmap
                )

                val startColors = currentColors.copyOf()

                val animator =
                    android.animation.ValueAnimator.ofFloat(
                        0f,
                        1f
                    ).apply {

                        duration = 1800L

                        addUpdateListener {
                            if (!fullPlayerArtworkActive ||
                                currentSong?.id != song.id
                            ) {
                                cancel()
                                return@addUpdateListener
                            }

                            val f =
                                it.animatedValue as Float

                            val colors = IntArray(3)

                            for (i in 0..2) {
                                colors[i] = mixColor(
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
                                    if (fullPlayerArtworkActive &&
                                        currentSong?.id == song.id
                                    ) {
                                        currentColors = targetColors
                                    }
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

        }, artworkLoadDelay)
'''

replace(
    anchor,
    callback,
    "Full Player asynchronous artwork and color animation"
)

# 5. Disable pending artwork callbacks when Full Player is dismissed.
replace(
'''            handler.removeCallbacks(
                colorUpdater
            )''',
'''            fullPlayerArtworkActive = false''',
"Disable Full Player artwork callback on dismiss"
)

if s == original:
    sys.exit("ABORT: No changes generated")

# Verify executable polling code is completely gone.
if "val colorUpdater" in s:
    sys.exit("ABORT: colorUpdater declaration remains")
if "post(colorUpdater)" in s or "colorUpdater," in s:
    sys.exit("ABORT: colorUpdater scheduling remains")
if "handler.postDelayed(\n                        this,\n                        2000L" in s:
    sys.exit("ABORT: recurring color polling remains")

# Verify the MediaItem artwork comment is preserved.
required_comment = (
    "// Artwork is loaded separately by the player UI.\n"
    "        // Avoid synchronous artwork extraction while creating MediaItems."
)
if required_comment not in s:
    sys.exit("ABORT: MediaItem artwork protection comment missing")

backup = FILE.with_name(
    FILE.name + ".bak_async_v7_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

shutil.copy2(FILE, backup)
FILE.write_text(s)

print("SUCCESS: V7 applied")
print("Backup:", backup)
print("\nChanges:")
for item in changes:
    print(" -", item)
