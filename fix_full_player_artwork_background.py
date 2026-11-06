
from pathlib import Path
from datetime import datetime
import shutil
import sys

FILE = Path(
    "app/src/main/java/com/music/app/MainActivity.kt"
)

if not FILE.exists():
    sys.exit("ERROR: MainActivity.kt not found. Run inside ~/Music")

src = FILE.read_text(encoding="utf-8")

if "applyFullPlayerArtworkBackground" in src:
    sys.exit("ERROR: Background patch already exists. No changes made.")

backup = FILE.with_name(
    FILE.name + ".bak_artwork_background_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

shutil.copy2(FILE, backup)

def replace_once(old, new, label):
    global src
    count = src.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly 1 match, found {count}"
        )
    src = src.replace(old, new, 1)

try:

    # -------------------------------------------------
    # 1. Keep a reference to the Full Player background root
    # -------------------------------------------------

    replace_once(
        "private var fullPlayerCover: ImageView? = null",
        """private var fullPlayerCover: ImageView? = null
    private var fullPlayerBackgroundRoot: View? = null""",
        "Background root property"
    )

    # -------------------------------------------------
    # 2. Add blurred artwork background renderer
    # -------------------------------------------------

    marker = "    private fun updateAllPlayerArtwork(song: Song) {"

    helper = r'''
    /*
     * Full Player artwork background.
     *
     * A separate low-resolution copy of the current album
     * artwork creates a soft blurred background.
     * A vertical dark gradient fades it into pure black.
     * The foreground album cover remains untouched.
     */
    private fun applyFullPlayerArtworkBackground(
        target: View,
        artwork: Bitmap
    ) {
        if (
            artwork.isRecycled ||
            artwork.width <= 0 ||
            artwork.height <= 0
        ) return

        try {
            val blurWidth =
                (artwork.width / 14).coerceAtLeast(1)

            val blurHeight =
                (artwork.height / 14).coerceAtLeast(1)

            val smallBitmap =
                Bitmap.createScaledBitmap(
                    artwork,
                    blurWidth,
                    blurHeight,
                    true
                )

            val blurredBitmap =
                Bitmap.createScaledBitmap(
                    smallBitmap,
                    artwork.width,
                    artwork.height,
                    true
                )

            if (smallBitmap !== artwork &&
                !smallBitmap.isRecycled
            ) {
                smallBitmap.recycle()
            }

            val artworkLayer =
                android.graphics.drawable.BitmapDrawable(
                    resources,
                    blurredBitmap
                ).apply {
                    gravity = Gravity.FILL
                    alpha = 255
                }

            val fadeLayer =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.argb(35, 0, 0, 0),
                        Color.argb(75, 0, 0, 0),
                        Color.argb(155, 0, 0, 0),
                        Color.argb(225, 0, 0, 0),
                        Color.BLACK
                    )
                )

            val newBackground =
                android.graphics.drawable.LayerDrawable(
                    arrayOf(
                        artworkLayer,
                        fadeLayer
                    )
                )

            val previousBackground =
                target.background

            if (previousBackground != null) {

                val transition =
                    android.graphics.drawable.TransitionDrawable(
                        arrayOf(
                            previousBackground,
                            newBackground
                        )
                    ).apply {
                        isCrossFadeEnabled = true
                    }

                target.background = transition
                transition.startTransition(650)

            } else {
                target.background = newBackground
            }

        } catch (error: Exception) {
            android.util.Log.e(
                "MusicArtworkBackground",
                "Unable to update artwork background",
                error
            )
        }
    }

'''

    if src.count(marker) != 1:
        raise RuntimeError("Cannot locate updateAllPlayerArtwork")

    src = src.replace(marker, helper + marker, 1)

    # -------------------------------------------------
    # 3. Refresh background when the song changes
    # -------------------------------------------------

    replace_once(
        """        fullPlayerCover?.let { cover ->
            loadAlbumArtAsync(song, cover)

            if (fullPlayerQueueExpanded) {""",
        """        fullPlayerCover?.let { cover ->
            loadAlbumArtAsync(song, cover) { bitmap ->

                if (
                    bitmap != null &&
                    currentSong?.id == song.id
                ) {
                    fullPlayerBackgroundRoot?.let { backgroundRoot ->
                        applyFullPlayerArtworkBackground(
                            backgroundRoot,
                            bitmap
                        )
                    }
                }
            }

            if (fullPlayerQueueExpanded) {""",
        "Track change background update"
    )

    # -------------------------------------------------
    # 4. Replace old three-color dynamic background
    # -------------------------------------------------

    start_marker = "        // ---------- DYNAMIC BACKGROUND ----------"
    end_marker = "        // ---------- ARTWORK ----------"

    if src.count(start_marker) != 1:
        raise RuntimeError("Dynamic background start not found")

    if src.count(end_marker) != 1:
        raise RuntimeError("Artwork section marker not found")

    start = src.index(start_marker)
    end = src.index(end_marker, start)

    if end <= start:
        raise RuntimeError("Invalid background section boundaries")

    new_background_section = r'''        // ---------- ALBUM ART BACKGROUND ----------

        val initialArtwork =
            if (miniExpansionOpening) {
                (
                    miniExpansionCover?.drawable
                        as? android.graphics.drawable.BitmapDrawable
                )?.bitmap
            } else {
                null
            }

        fullPlayerBackgroundRoot = root

        root.setBackgroundColor(Color.BLACK)

        initialArtwork?.let { artwork ->
            applyFullPlayerArtworkBackground(
                root,
                artwork
            )
        }

'''

    src = (
        src[:start] +
        new_background_section +
        src[end:]
    )

    # -------------------------------------------------
    # 5. Replace old color animation with artwork loading
    # -------------------------------------------------

    old_start = (
        "        // Artwork loading and color animation "
        "are performed asynchronously."
    )

    old_end = "        val coverContainer = FrameLayout(this).apply {"

    if src.count(old_start) != 1:
        raise RuntimeError("Artwork loading section not found")

    if src.count(old_end) != 1:
        raise RuntimeError("Cover container section not found")

    start = src.index(old_start)
    end = src.index(old_end, start)

    new_loader = r'''        // Background and foreground artwork are loaded
        // independently of the Mini -> Full animation.

        var fullPlayerArtworkActive = true

        val artworkLoadDelay =
            if (miniExpansionOpening) 400L else 0L

        mainHandler.postDelayed({

            if (
                !fullPlayerArtworkActive ||
                isFinishing ||
                isDestroyed
            ) {
                return@postDelayed
            }

            loadAlbumArtAsync(song, cover) { bitmap ->

                if (
                    bitmap == null ||
                    !fullPlayerArtworkActive ||
                    currentSong?.id != song.id ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return@loadAlbumArtAsync
                }

                applyFullPlayerArtworkBackground(
                    root,
                    bitmap
                )
            }

        }, artworkLoadDelay)


'''

    src = src[:start] + new_loader + src[end:]

    # -------------------------------------------------
    # 6. Release background reference on dismiss
    # -------------------------------------------------

    replace_once(
        """                fullPlayerDialog = null
                fullPlayerQueueExpanded = false""",
        """                fullPlayerDialog = null

                if (fullPlayerBackgroundRoot === root) {
                    fullPlayerBackgroundRoot = null
                }

                fullPlayerQueueExpanded = false""",
        "Dismiss cleanup"
    )

    # -------------------------------------------------
    # 7. Save patched source
    # -------------------------------------------------

    FILE.write_text(src, encoding="utf-8")

    print("SUCCESS: Full Player artwork background applied.")
    print(f"Backup: {backup}")
    print()
    print("Applied changes:")
    print("- Current album cover used as background.")
    print("- Soft blurred background image.")
    print("- Background extends behind status bar.")
    print("- Smooth fade from artwork to pure black.")
    print("- Smooth 650ms background transition.")
    print("- Background updates when the song changes.")
    print("- Main album cover remains sharp.")
    print("- Existing Mini/Full transition structure preserved.")

except Exception as error:

    shutil.copy2(backup, FILE)

    print("ERROR:", error)
    print("Original source restored from backup.")
    sys.exit(1)

