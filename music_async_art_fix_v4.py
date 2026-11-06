#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import re
import shutil
import sys

FILE = Path("app/src/main/java/com/music/app/MainActivity.kt")

def fail(message):
    sys.exit("PREFLIGHT FAILED: " + message)

if not FILE.is_file():
    fail(f"File not found: {FILE}")

source = FILE.read_text(encoding="utf-8")

# ---------- PREFLIGHT ----------
required = [
    "private fun getAlbumArt(song: Song): android.graphics.Bitmap?",
    "private fun updateAllPlayerArtwork(song: Song)",
    "private val backgroundExecutor: ExecutorService",
    "override fun onDestroy()",
    "backgroundExecutor.shutdownNow()",
]

for item in required:
    if item not in source:
        fail(f"Missing expected source anchor: {item}")

if "albumArtExecutor" in source:
    fail("albumArtExecutor already exists; refusing duplicate patch")

if source.count("backgroundExecutor.shutdownNow()") != 1:
    fail("Expected exactly one backgroundExecutor shutdown")

if source.count("private fun getAlbumArt(song: Song): android.graphics.Bitmap?") != 1:
    fail("Expected exactly one getAlbumArt definition")

# Extract the original getAlbumArt function using brace matching.
signature = "private fun getAlbumArt(song: Song): android.graphics.Bitmap?"
start = source.index(signature)
brace = source.index("{", start)

depth = 0
end = None
for i in range(brace, len(source)):
    if source[i] == "{":
        depth += 1
    elif source[i] == "}":
        depth -= 1
        if depth == 0:
            end = i + 1
            break

if end is None:
    fail("Could not locate end of getAlbumArt function")

old_function = source[start:end]

# Extract the original function body and move it to a worker-only method.
body_start = old_function.index("{") + 1
body_end = old_function.rfind("}")
original_body = old_function[body_start:body_end]

if "MediaMetadataRetriever" not in original_body:
    fail("Original artwork extraction body does not match expected implementation")

if "albumArtCache" not in original_body:
    fail("Original artwork cache logic not found")

# Build the new cache-only getter and worker loader.
new_function = r'''
private val albumArtLock = Any()

private val albumArtExecutor: ExecutorService =
    Executors.newFixedThreadPool(2)

private val albumArtInFlight =
    mutableSetOf<Long>()

/*
 * UI-thread-safe cache lookup.
 * This method never extracts artwork or waits for a worker.
 */
private fun getAlbumArt(song: Song): android.graphics.Bitmap? {
    synchronized(albumArtLock) {
        albumArtCache.get(song.id)?.let { return it }
        if (albumArtMissCache.contains(song.id)) return null

        if (albumArtInFlight.add(song.id)) {
            try {
                albumArtExecutor.execute {
                    val bitmap = loadAlbumArtBlocking(song)

                    mainHandler.post {
                        synchronized(albumArtLock) {
                            albumArtInFlight.remove(song.id)
                        }

                        if (
                            !isFinishing &&
                            !isDestroyed &&
                            currentSong?.id == song.id
                        ) {
                            updateAllPlayerArtwork(song)
                        }

                        miniExpansionCard?.let { card ->
                            if (currentSong?.id == song.id && bitmap != null) {
                                card.background =
                                    createArtworkGradient(bitmap)
                            }
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                albumArtInFlight.remove(song.id)
            }
        }
    }

    return null
}

/*
 * Must only be called by albumArtExecutor.
 */
private fun loadAlbumArtBlocking(
    song: Song
): android.graphics.Bitmap? {
''' + original_body + r'''
}

/*
 * Build the Mini Expansion gradient after the bitmap is ready.
 */
private fun createArtworkGradient(
    bitmap: android.graphics.Bitmap
): GradientDrawable {
    val points = intArrayOf(
        bitmap.getPixel(0, 0),
        bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
        bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)
    )

    return GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        points
    ).apply {
        cornerRadii = floatArrayOf(
            0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f
        )
    }
}
'''.strip()

# Synchronize cache accesses inside the worker method.
# The original method body is retained to preserve MediaStore behavior.
new_function = new_function.replace(
    "albumArtCache.get(song.id)?.let { return it }",
    "synchronized(albumArtLock) { albumArtCache.get(song.id)?.let { return it } }"
).replace(
    "if (bitmap != null) albumArtCache.put(song.id, bitmap)",
    "if (bitmap != null) synchronized(albumArtLock) { albumArtCache.put(song.id, bitmap) }"
).replace(
    "albumArtMissCache.add(song.id)",
    "synchronized(albumArtLock) { albumArtMissCache.add(song.id) }"
)

# Avoid nested synchronization in the cache-only getter.
new_function = new_function.replace(
    "synchronized(albumArtLock) { albumArtCache.get(song.id)?.let { return it } }",
    "albumArtCache.get(song.id)?.let { return it }"
)

updated = source[:start] + new_function + source[end:]

# Add dedicated executor shutdown without touching playback service.
shutdown_anchor = "backgroundExecutor.shutdownNow()"
if updated.count(shutdown_anchor) != 1:
    fail("Shutdown anchor changed during patch")

updated = updated.replace(
    shutdown_anchor,
    "albumArtExecutor.shutdownNow()\n        synchronized(albumArtLock) {\n            albumArtInFlight.clear()\n        }\n        " + shutdown_anchor,
    1
)

# Preserve the existing MediaItem artwork comments.
if (
    "// Artwork is loaded separately by the player UI." not in updated
    or "// Avoid synchronous artwork extraction while creating MediaItems." not in updated
):
    fail("MediaItem artwork preservation comments were changed")

# Verify all old extraction code now exists only in worker loader.
if updated.count("private fun loadAlbumArtBlocking(") != 1:
    fail("Worker loader insertion failed")

if updated.count("private fun getAlbumArt(song: Song): android.graphics.Bitmap?") != 1:
    fail("Cache-only getter insertion failed")

# Ensure source still has all original getAlbumArt call sites.
if updated.count("getAlbumArt(song)") < 10:
    fail("Unexpected loss of existing artwork call sites")

# Atomic write with timestamped backup.
backup = FILE.with_name(
    FILE.name + ".bak_async_art_v4_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

shutil.copy2(FILE, backup)

tmp = FILE.with_suffix(FILE.suffix + ".tmp")
tmp.write_text(updated, encoding="utf-8")
tmp.replace(FILE)

print("SUCCESS: V4 stage 1 applied")
print(f"Backup: {backup}")
print("Artwork extraction now runs on a dedicated executor.")
print("UI getter now returns cached artwork immediately.")
print("Player artwork refresh is guarded by current song ID.")
print("MediaItem artwork comments preserved.")
print("NOTE: Library/Browse visible-view refresh is not yet implemented.")
