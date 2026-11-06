from pathlib import Path
from datetime import datetime
import sys

path = Path("app/src/main/java/com/music/app/MainActivity.kt")
source = path.read_text(encoding="utf-8")

old = """                        miniGestureDragging = false
                        miniGestureVertical = false

                        cancelMiniHide()

                        false"""

new = """                        miniGestureDragging = false
                        miniGestureVertical = false
                        miniFullTransitionProgress = 0f

                        cancelMiniHide()

                        false"""

count = source.count(old)
if count != 1:
    sys.exit(
        f"ERROR: Expected exactly 1 ACTION_DOWN block, found {count}. "
        "No changes written."
    )

updated = source.replace(old, new, 1)

backup = path.with_name(
    path.name + ".bak_progress_reset_v5_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

backup.write_text(source, encoding="utf-8")
path.write_text(updated, encoding="utf-8")

print("SUCCESS: Gesture progress reset added.")
print(f"Backup: {backup}")
