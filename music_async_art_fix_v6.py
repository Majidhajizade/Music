from pathlib import Path
from datetime import datetime
import shutil
import sys

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
            f"ABORT: {label}: expected {expected} matches, found {count}"
        )
    s = s.replace(old, new)
    changes.append(f"{label} ({count})")

# Library row
replace(
'''                    getAlbumArt(song)?.let {
                        setImageBitmap(it)
                    } ?: run {
                        setImageResource(R.drawable.icon)
                    }''',
'''                    loadAlbumArtAsync(song, this)''',
"Library row artwork"
)

# Library cover
replace(
'''                getAlbumArt(song)?.let {
                    cover.setImageBitmap(it)
                } ?: run {
                    cover.setImageResource(R.drawable.icon)
                }''',
'''                loadAlbumArtAsync(song, cover)''',
"Library cover artwork"
)

# Real song cover
replace(
'''            val art = getAlbumArt(song)

            if (art != null) {
                setImageBitmap(art)
            } else {
                setImageResource(R.drawable.icon)
                clearColorFilter()
                background = null
            }''',
'''            loadAlbumArtAsync(song, this)''',
"Real song cover"
)

# Song row cover
replace(
'''        getAlbumArt(song)?.let {
            cover.setImageBitmap(it)
        } ?: run {
            cover.setImageResource(R.drawable.icon)
        }''',
'''        loadAlbumArtAsync(song, cover)''',
"Song row cover"
)

# Browse artwork
replace(
'''                val bitmap = getAlbumArt(song)

                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                } else {
                    image.setImageResource(R.drawable.icon)
                }''',
'''                loadAlbumArtAsync(song, image)''',
"Browse artwork"
)

# Restored mini player - first occurrence
replace(
'''                    getAlbumArt(song)?.let {
                        miniCover.setImageBitmap(it)
                    } ?: run {
                        miniCover.setImageResource(
                            R.drawable.icon
                        )
                    }''',
'''                    loadAlbumArtAsync(song, miniCover)''',
"Restored mini player artwork"
)

# Restored mini player - second occurrence
replace(
'''        getAlbumArt(song)?.let {
            miniCover.setImageBitmap(it)
        } ?: run {
            miniCover.setImageResource(
                R.drawable.icon
            )
        }''',
'''        loadAlbumArtAsync(song, miniCover)''',
"Restored mini player artwork (second path)"
)

# Queue cover
replace(
'''        getAlbumArt(song)?.let {
            queueCover.setImageBitmap(it)
        } ?: run {
            queueCover.setImageResource(R.drawable.icon)
        }''',
'''        loadAlbumArtAsync(song, queueCover)''',
"Queue cover"
)

# Queue song cover
replace(
'''            getAlbumArt(queueSong)?.let {
                cover.setImageBitmap(it)
            } ?: run {
                cover.setImageResource(R.drawable.icon)
            }''',
'''            loadAlbumArtAsync(queueSong, cover)''',
"Queue song artwork"
)

# Mini player song changes: smooth and non-smooth
replace(
'''            getAlbumArt(song)?.let {
                miniCover.setImageBitmap(it)
            } ?: run {
                miniCover.setImageResource(
                    R.drawable.icon
                )
            }''',
'''            loadAlbumArtAsync(song, miniCover)''',
"Mini player artwork",
expected=2
)

# Animate song: cache-only synchronous lookup
replace(
'''        val fullPlayerArtwork =
            getAlbumArt(song)''',
'''        val fullPlayerArtwork =
            getAlbumArtCached(song)''',
"Animate song cache-only lookup"
)

# Add async background/cover refresh at the end of animateSongChange
anchor = '''              root.background = newBackground
        }
    }'''

replacement = '''              root.background = newBackground
        }

        // Load missing artwork without blocking the transition.
        loadAlbumArtAsync(song, cover) { bitmap ->
            if (bitmap != null &&
                currentSong?.id == song.id &&
                !isFinishing &&
                !isDestroyed
            ) {
                if (!fullPlayerQueueExpanded) {
                    cover.alpha = 1f
                    cover.setImageBitmap(bitmap)
                }

                val w = bitmap.width.coerceAtLeast(1)
                val h = bitmap.height.coerceAtLeast(1)

                val colors = intArrayOf(
                    bitmap.getPixel(w / 2, h / 4),
                    bitmap.getPixel(w / 2, h / 2),
                    bitmap.getPixel(w / 2, (h * 3) / 4)
                )

                root.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    colors
                )
            }
        }
    }'''

replace(
    anchor,
    replacement,
    "Async artwork refresh in animateSongChange"
)

if s == original:
    sys.exit("ABORT: no changes produced")

# Verify MediaItem protection
if "// Artwork is loaded separately by the player UI." not in s:
    sys.exit("ABORT: MediaItem protection comment missing")

if "albumArtExecutor.shutdownNow()" not in s:
    sys.exit("ABORT: artwork executor shutdown missing")

# Backup and write
backup = FILE.with_name(
    FILE.name + ".bak_async_v6_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

shutil.copy2(FILE, backup)
tmp = FILE.with_suffix(".kt.tmp")
tmp.write_text(s)
tmp.replace(FILE)

print("\nSUCCESS: V6 applied")
print("Backup:", backup)
print("\nChanges:")
for item in changes:
    print(" -", item)
