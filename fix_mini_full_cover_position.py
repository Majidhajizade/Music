from pathlib import Path
from datetime import datetime
import subprocess
import sys

F = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not F.exists():
    sys.exit("ERROR: MainActivity.kt not found.")

s = F.read_text(encoding="utf-8")

old = """        val cardCenterX =
            miniFullCachedCardCenterX

        val cardCenterY =
            miniFullCachedCardBaseCenterY -
                upward / 2f

        cover.translationX =
            centerX - cardCenterX

        cover.translationY =
            centerY - cardCenterY
"""

new = """        /*
         * Convert the Expansion card center to screen coordinates.
         * Artwork source and destination centers are both screen-based.
         */
        val decorLocation =
            IntArray(2)

        window.decorView.getLocationOnScreen(
            decorLocation
        )

        val cardCenterX =
            decorLocation[0].toFloat() +
                miniFullCachedCardCenterX

        val cardCenterY =
            decorLocation[1].toFloat() +
                miniFullCachedCardBaseCenterY -
                upward / 2f

        cover.translationX =
            centerX - cardCenterX

        cover.translationY =
            centerY - cardCenterY
"""

count = s.count(old)

if count != 1:
    sys.exit(
        f"ERROR: Expected exactly 1 target block, found {count}. "
        "No changes applied."
    )

backup = F.with_name(
    F.name + ".bak_cover_position_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

backup.write_text(s, encoding="utf-8")

updated = s.replace(old, new, 1)
F.write_text(updated, encoding="utf-8")

print("SUCCESS: Mini -> Full cover coordinate fix applied.")
print(f"Backup: {backup}")

result = subprocess.run(
    ["git", "diff", "--check"],
    text=True
)

if result.returncode != 0:
    print("WARNING: git diff --check reported an issue.")
    sys.exit(result.returncode)

print("SUCCESS: git diff --check")
