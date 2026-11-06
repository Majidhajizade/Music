from pathlib import Path
from datetime import datetime
import sys

path = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not path.exists():
    sys.exit("ERROR: MainActivity.kt not found.")

source = path.read_text(encoding="utf-8")
updated = source

# 1. Preserve the last real finger position on ACTION_CANCEL.
old_touch = """                        val dy =
                            event.rawY - miniGestureDownY

                        if (miniGestureVertical) {"""

new_touch = """                        val dy =
                            if (
                                event.actionMasked ==
                                android.view.MotionEvent.ACTION_CANCEL
                            ) {
                                miniGestureLastY - miniGestureDownY
                            } else {
                                event.rawY - miniGestureDownY
                            }

                        if (miniGestureVertical) {"""

# 2. Make the transition Dialog non-touchable before showing it.
old_dialog = """        dialog.show()

        if (miniExpansionOpening) {"""

new_dialog = """        if (miniExpansionOpening) {
            /*
             * Keep the Activity as the touch owner while the
             * Mini -> Full gesture is still being dragged.
             */
            dialog.window?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        }

        dialog.show()

        if (miniExpansionOpening) {"""

# 3. Restore touchability only after the expansion animation completes.
old_complete = """            updateMiniFullTransition(1f)

            removeMiniExpansionCard()

            layout.translationX = 0f"""

new_complete = """            updateMiniFullTransition(1f)

            miniTransitionDialog?.window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )

            removeMiniExpansionCard()

            layout.translationX = 0f"""

checks = [
    ("ACTION_CANCEL position preservation", old_touch, new_touch),
    ("Dialog touch protection", old_dialog, new_dialog),
    ("Touch restoration after expansion", old_complete, new_complete),
]

# Fail-safe: verify every exact block before changing anything.
for name, old, new in checks:
    count = updated.count(old)
    if count != 1:
        sys.exit(
            f"ERROR: {name}: expected exactly 1 match, found {count}. "
            "No changes were written."
        )

# Apply all changes in memory first.
for name, old, new in checks:
    updated = updated.replace(old, new, 1)

if updated == source:
    sys.exit("ERROR: No changes generated.")

backup = path.with_name(
    path.name + ".bak_gesture_cancel_v3_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

backup.write_text(source, encoding="utf-8")
path.write_text(updated, encoding="utf-8")

print("SUCCESS: Mini -> Full gesture protection applied.")
print(f"Backup: {backup}")
print("- Dialog stays non-touchable during the drag.")
print("- Activity retains touch ownership.")
print("- ACTION_CANCEL uses the last valid finger position.")
print("- Dialog becomes touchable after expansion completes.")
