from pathlib import Path
from datetime import datetime
import sys

path = Path("app/src/main/java/com/music/app/MainActivity.kt")

if not path.exists():
    sys.exit("ERROR: MainActivity.kt not found.")

source = path.read_text(encoding="utf-8")
updated = source

replacements = [
    (
        "ACTION_UP and ACTION_CANCEL separation",
        """                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {""",
        """                    android.view.MotionEvent.ACTION_UP -> {"""
    ),
    (
        "ACTION_UP coordinate handling",
        """                        val dy =
                            if (
                                event.actionMasked ==
                                android.view.MotionEvent.ACTION_CANCEL
                            ) {
                                miniGestureLastY - miniGestureDownY
                            } else {
                                event.rawY - miniGestureDownY
                            }""",
        """                        val dy =
                            event.rawY - miniGestureDownY"""
    ),
]

for name, old, new in replacements:
    count = updated.count(old)
    if count != 1:
        sys.exit(
            f"ERROR: {name}: expected 1 match, found {count}. "
            "No changes written."
        )
    updated = updated.replace(old, new, 1)

# Insert a dedicated ACTION_CANCEL branch after ACTION_UP.
anchor = """                    }

                    else -> false
                }
            }

        layout.setOnTouchListener(
            gestureListener
        )"""

cancel_branch = """                    }

                    android.view.MotionEvent.ACTION_CANCEL -> {

                        cancelMiniHide()

                        if (!miniGestureDragging) {
                            return@OnTouchListener false
                        }

                        if (miniGestureVertical) {

                            /*
                             * ACTION_CANCEL has no reliable final
                             * finger coordinates. Use the last
                             * progress calculated during ACTION_MOVE.
                             */
                            val progress =
                                miniFullTransitionProgress
                                    .coerceIn(0f, 1f)

                            if (progress >= 0.5f) {
                                completeMiniExpansion(layout)
                            } else {
                                cancelMiniExpansion(layout)
                            }

                        } else {

                            /*
                             * Cancelled horizontal gestures must
                             * never trigger previous/next playback.
                             */
                            layout.translationX = 0f
                            layout.translationY = 0f

                            miniCover.rotation = 0f
                            miniCover.scaleX = 1f
                            miniCover.scaleY = 1f
                        }

                        miniGestureDragging = false
                        true
                    }

                    else -> false
                }
            }

        layout.setOnTouchListener(
            gestureListener
        )"""

count = updated.count(anchor)
if count != 1:
    sys.exit(
        "ERROR: ACTION_CANCEL insertion point: expected 1 match, "
        f"found {count}. No changes written."
    )

updated = updated.replace(anchor, cancel_branch, 1)

if updated == source:
    sys.exit("ERROR: No changes generated.")

backup = path.with_name(
    path.name + ".bak_action_cancel_v4_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

backup.write_text(source, encoding="utf-8")
path.write_text(updated, encoding="utf-8")

print("SUCCESS: ACTION_UP and ACTION_CANCEL separated.")
print(f"Backup: {backup}")
print("- ACTION_UP retains normal release behavior.")
print("- ACTION_CANCEL uses the last valid gesture progress.")
print("- Cancelled horizontal gestures do not change tracks.")
