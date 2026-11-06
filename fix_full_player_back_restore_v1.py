from pathlib import Path
from datetime import datetime
import sys

path = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not path.exists():
    sys.exit("ERROR: MainActivity.kt not found. No changes made.")

source = path.read_text(encoding="utf-8")

old = """        dialog.setOnDismissListener {

            if (fullPlayerDialog === dialog) {
                fullPlayerDialog = null
                fullPlayerQueueExpanded = false
            }

            handler.removeCallbacks(
                updater
            )

            fullPlayerArtworkActive = false
"""

new = """        dialog.setOnDismissListener {

            if (fullPlayerDialog === dialog) {

                /*
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

                fullPlayerDialog = null
                fullPlayerQueueExpanded = false
            }

            handler.removeCallbacks(
                updater
            )

            fullPlayerArtworkActive = false
"""

count = source.count(old)
if count != 1:
    sys.exit(
        "ERROR: Expected exactly one matching dismiss-listener block; "
        f"found {count}. No changes were written."
    )

updated = source.replace(old, new, 1)

if updated == source:
    sys.exit("ERROR: No changes generated.")

backup = path.with_name(
    path.name + ".bak_back_restore_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

backup.write_text(source, encoding="utf-8")
path.write_text(updated, encoding="utf-8")

print("SUCCESS: Full Player dismiss restoration added.")
print(f"Backup: {backup}")
print("- Mini Player visibility and alpha restored when a song exists.")
print("- Home/Search/Library navigation visibility and position restored.")
print("- Other Dialog dismiss cleanup remains unchanged.")
