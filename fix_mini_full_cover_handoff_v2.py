from pathlib import Path
from datetime import datetime
import shutil
import sys

path = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not path.exists():
    sys.exit("ERROR: MainActivity.kt not found.")

source = path.read_text(encoding="utf-8")
updated = source

def replace_once(old, new, label):
    global updated
    count = updated.count(old)
    if count != 1:
        sys.exit(
            f"ERROR: {label}: expected 1 match, found {count}. "
            "No changes written."
        )
    updated = updated.replace(old, new, 1)

# 1. Capture the actual visible center and size of the Full Player cover.
old_capture = '''                    val fullLocation =
                        IntArray(2)

                    cover.getLocationOnScreen(
                        fullLocation
                    )

                    miniFullTargetCenterX =
                        fullLocation[0].toFloat() +
                            cover.width / 2f

                    miniFullTargetCenterY =
                        fullLocation[1].toFloat() +
                            cover.height / 2f

                    miniFullTargetSize =
                        cover.width

                    /*
                     * Put the hidden real artwork exactly
                     * under the visible Expansion artwork.
                     */
                    miniExpansionCover?.let { expansionCover ->
                        if (
                            expansionCover.width > 0 &&
                            expansionCover.height > 0
                        ) {
                            val expansionLocation =
                                IntArray(2)

                            expansionCover.getLocationOnScreen(
                                expansionLocation
                            )

                            val expansionCenterX =
                                expansionLocation[0].toFloat() +
                                    expansionCover.width / 2f

                            val expansionCenterY =
                                expansionLocation[1].toFloat() +
                                    expansionCover.height / 2f

                            val fullCenterX =
                                fullLocation[0].toFloat() +
                                    cover.width / 2f

                            val fullCenterY =
                                fullLocation[1].toFloat() +
                                    cover.height / 2f

                            cover.translationX =
                                expansionCenterX -
                                    fullCenterX

                            cover.translationY =
                                expansionCenterY -
                                    fullCenterY

                            val handoffScale =
                                (
                                    expansionCover.width.toFloat() /
                                        cover.width.toFloat()
                                ).coerceIn(
                                    0.05f,
                                    1f
                                )

                            cover.scaleX =
                                handoffScale

                            cover.scaleY =
                                handoffScale
                        }
                    }

'''

new_capture = '''                    /*
                     * Capture the real, visible Full Player cover
                     * destination from its centered container.
                     */
                    val containerLocation =
                        IntArray(2)

                    coverContainer.getLocationOnScreen(
                        containerLocation
                    )

                    miniFullTargetCenterX =
                        containerLocation[0].toFloat() +
                            coverContainer.width / 2f +
                            cover.translationX

                    miniFullTargetCenterY =
                        containerLocation[1].toFloat() +
                            coverContainer.height / 2f +
                            cover.translationY

                    miniFullTargetSize =
                        (
                            cover.width.toFloat() *
                                cover.scaleX
                        ).toInt().coerceAtLeast(1)

'''

replace_once(
    old_capture,
    new_capture,
    "Full Player target geometry"
)

# 2. Make the real Full Player cover follow the animated cover
#    on every frame, not only at the 50% handoff.
old_anchor = '''        cover.translationY =
            centerY - cardCenterY

        cover.alpha = 1f
'''

new_anchor = '''        cover.translationY =
            centerY - cardCenterY

        /*
         * Keep the real Full Player artwork locked to the same
         * screen-space center and visible size on every frame.
         * This prevents a jump when the temporary cover is removed.
         */
        miniTransitionFullCover?.let { fullCover ->
            miniTransitionCoverContainer?.let { container ->

                if (
                    fullCover.width > 0 &&
                    container.width > 0 &&
                    container.height > 0
                ) {
                    val containerLocation =
                        IntArray(2)

                    container.getLocationOnScreen(
                        containerLocation
                    )

                    val fullBaseCenterX =
                        containerLocation[0].toFloat() +
                            container.width / 2f

                    val fullBaseCenterY =
                        containerLocation[1].toFloat() +
                            container.height / 2f

                    val synchronizedScale =
                        coverSize.toFloat() /
                            fullCover.width.toFloat()

                    fullCover.translationX =
                        centerX - fullBaseCenterX

                    fullCover.translationY =
                        centerY - fullBaseCenterY

                    fullCover.scaleX =
                        synchronizedScale

                    fullCover.scaleY =
                        synchronizedScale
                }
            }
        }

        cover.alpha = 1f
'''

replace_once(
    old_anchor,
    new_anchor,
    "Per-frame Full Player cover synchronization"
)

# 3. Increase the gap between the cover and song information.
replace_once(
    "        titleRow.translationY = -dp(72).toFloat()",
    "        titleRow.translationY = -dp(48).toFloat()",
    "Title vertical position"
)

replace_once(
    "        artist.translationY = -dp(72).toFloat()",
    "        artist.translationY = -dp(48).toFloat()",
    "Artist vertical position"
)

# Create backup only after every expected code section is verified.
backup = path.with_name(
    path.name + ".bak_mini_handoff_v2_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

shutil.copy2(path, backup)
path.write_text(updated, encoding="utf-8")

print("SUCCESS: Mini -> Full Player handoff updated.")
print(f"Backup: {backup}")
print("- Actual Full Player cover follows every animation frame.")
print("- Final target uses the visible cover center and size.")
print("- Temporary cover is handed off without a position jump.")
print("- Song title and artist moved down by 24dp.")
