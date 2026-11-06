from pathlib import Path
from datetime import datetime
import shutil
import sys

path = Path("app/src/main/java/com/music/app/MainActivity.kt")
src = path.read_text(encoding="utf-8")
original = src

def replace_exact(old, new, expected=1, label=""):
    global src
    count = src.count(old)
    if count != expected:
        print(f"ERROR: {label}: expected {expected}, found {count}")
        sys.exit(1)
    src = src.replace(old, new, expected)

# 1. Independent completion flag
replace_exact(
    "    private var fullPlayerCloseProgress = 0f\n",
    "    private var fullPlayerCloseProgress = 0f\n"
    "    private var fullPlayerCloseAnimationCompleted = false\n",
    label="completion flag declaration"
)

# 2. Reset flag when a new close animation starts
replace_exact(
    """    private fun finishFullPlayerClose(
        dialog: android.app.Dialog,
        root: View,
        cover: ImageView,
        coverContainer: View
    ) {

        val miniLocation =""",
    """    private fun finishFullPlayerClose(
        dialog: android.app.Dialog,
        root: View,
        cover: ImageView,
        coverContainer: View
    ) {

        fullPlayerCloseAnimationCompleted = false

        val miniLocation =""",
    label="close animation start"
)

# 3. Mark completion only after the animation reaches its end
replace_exact(
    """                fullPlayerCloseProgress = 0f

                dialog.dismiss()""",
    """                fullPlayerCloseProgress = 0f

                fullPlayerCloseAnimationCompleted = true
                dialog.dismiss()""",
    label="close animation completion"
)

# 4. Restore UI only for direct dismiss paths.
replace_exact(
    """                /*
                 * Restore the Mini Player UI when the Full Player
                 * is dismissed through Android Back or another
                 * direct dismiss path instead of the close gesture.
                 */
                if (
                    ::miniPlayer.isInitialized &&
                    currentSong != null
                ) {
                    miniPlayer.animate().cancel()
                    miniPlayer.visibility = View.VISIBLE
                    miniPlayer.alpha = 1f
                }

                miniBottomNavigation?.let { nav ->
                    nav.animate().cancel()
                    nav.visibility = View.VISIBLE
                    nav.translationY = 0f
                    nav.alpha = 1f
                }

                fullPlayerDialog = null""",
    """                /*
                 * A completed close animation has already restored
                 * the Mini Player and navigation. Do not cancel it.
                 *
                 * For Android Back or another direct dismiss path,
                 * restore the hidden Mini Player UI immediately.
                 */
                val closeAnimationCompleted =
                    fullPlayerCloseAnimationCompleted

                fullPlayerCloseAnimationCompleted = false

                if (!closeAnimationCompleted) {

                    if (
                        ::miniPlayer.isInitialized &&
                        currentSong != null
                    ) {
                        miniPlayer.animate().cancel()
                        miniPlayer.visibility = View.VISIBLE
                        miniPlayer.alpha = 1f
                    }

                    miniBottomNavigation?.let { nav ->
                        nav.animate().cancel()
                        nav.visibility = View.VISIBLE
                        nav.translationY = 0f
                        nav.alpha = 1f
                    }
                }

                fullPlayerDialog = null""",
    label="dismiss listener restoration"
)

if src == original:
    print("ERROR: No changes generated.")
    sys.exit(1)

backup = path.with_name(
    path.name + ".bak_back_restore_v2_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

shutil.copy2(path, backup)
path.write_text(src, encoding="utf-8")

print("SUCCESS: Full Player Back restoration v2 applied.")
print(f"Backup: {backup}")
print("- Direct dismiss restores Mini Player and navigation.")
print("- Completed close animation is not interrupted by dismiss listener.")
print("- Active navigation selection and colors remain unchanged.")
