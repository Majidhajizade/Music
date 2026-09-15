package com.music.app
import kotlin.math.roundToInt

import android.text.TextUtils
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.app.AlertDialog
import android.media.MediaMetadataRetriever

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

data class Song(
    val id: Long,
    val title: String,
    val artist: String
)

class MainActivity : ComponentActivity() {

    private val songs = mutableListOf<Song>()
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var currentSong: Song? = null

    private var playbackQueue = mutableListOf<Song>()
    private var playbackIndex = -1

    private lateinit var content: LinearLayout
    private lateinit var avatar: ImageView
    private lateinit var miniCover: ImageView
    private lateinit var miniPlayer: LinearLayout

    private var miniGestureDownX = 0f
    private var miniGestureDownY = 0f
    private var miniGestureLastX = 0f
    private var miniGestureLastY = 0f
    private var miniGestureDragging = false
    private var miniGestureVertical = false
    private var miniHideRunnable: Runnable? = null

    private var miniExpansionCard: FrameLayout? = null
    private var miniBottomNavigation: View? = null
    private var miniExpansionCover: ImageView? = null
    private var miniExpansionTitle: TextView? = null
    private var miniExpansionArtist: TextView? = null

    // Mini -> Full gesture transition
    private var miniFullTransitionProgress = 0f
    private var miniFullTransitionAnimating = false

    // Active Full Player views used by the Mini -> Full gesture.
    private var miniTransitionDialog: android.app.Dialog? = null
    private var miniTransitionRoot: View? = null
    private var miniTransitionFullCover: View? = null
    private var miniTransitionInfo: View? = null
    private var miniTransitionSeekBar: View? = null
    private var miniTransitionTimeRow: View? = null
    private var miniTransitionControls: View? = null
    private var miniTransitionSecondary: View? = null
    private var miniTransitionCoverContainer: View? = null
    private var miniExpansionOpening = false

    private var miniFullTargetCenterX = Float.NaN
    private var miniFullTargetCenterY = Float.NaN
    private var miniFullTargetSize = 0


    private val playerPrefs by lazy {
        getSharedPreferences("player_state", MODE_PRIVATE)
    }

    private val playerSaveHandler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )

    private val playerSaveRunnable = object : Runnable {
        override fun run() {
            savePlayerState()
            playerSaveHandler.postDelayed(this, 1000L)
        }
    }

    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var playButton: TextView

    private var onboardingVisible = false

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            if (onboardingVisible) {
                requestOptionalPermission()
            } else {
                loadMusic()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            finishOnboarding()
        }

    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                saveAvatar(uri)
                updateAvatar()
            }
        }

    private var activeNavIndex = 0

    private val navItems = mutableListOf<LinearLayout>()

    private fun setActiveNavigation(index: Int) {

        activeNavIndex =
            index.coerceIn(0, 2)

        navItems.forEachIndexed { i, item ->
            updateNavItem(
                item,
                i == activeNavIndex
            )
        }
    }

    private fun updateNavItem(
        item: LinearLayout,
        selected: Boolean
    ) {

        val icon = item.getChildAt(0) as? ImageView
        val label = item.getChildAt(1) as? TextView

        val normalRes = icon?.tag as? Int

        val selectedRes = when (normalRes) {
            R.drawable.ic_nav_home ->
                R.drawable.ic_nav_home_selected

            R.drawable.ic_nav_search ->
                R.drawable.ic_nav_search_selected

            R.drawable.ic_nav_library ->
                R.drawable.ic_nav_library_selected

            else -> normalRes
        }

        if (icon != null && normalRes != null) {
            icon.setImageResource(
                if (selected) selectedRes else normalRes
            )
        }

        label?.setTextColor(
            if (selected) {
                Color.BLACK
            } else {
                Color.rgb(125, 125, 125)
            }
        )
    }


    private fun createBottomNavigation(): LinearLayout {

        navItems.clear()

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                dp(8),
                dp(1),
                dp(8),
                dp(1)
            )
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
        }

        val items = listOf(
            Triple("Home", R.drawable.ic_nav_home) { showHome() },
            Triple("Search", R.drawable.ic_nav_search) { showSearch() },
            Triple("Library", R.drawable.ic_nav_library) { showLibrary() }
        )

        items.forEachIndexed { index, item ->

            val navItemView = navItem(
                item.first,
                item.second,
                index == activeNavIndex,
                item.third
            )

            navItems.add(navItemView)

            nav.addView(
                navItemView,
                LinearLayout.LayoutParams(
                    0,
                    dp(56),
                    1f
                )
            )
        }

        miniBottomNavigation = nav

        return nav
    }

    private fun navItem(
        title: String,
        iconRes: Int,
        selected: Boolean,
        action: () -> Unit
    ): LinearLayout {

        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(5), 0, dp(4))
        }

        val icon = ImageView(this).apply {
            tag = iconRes
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        item.addView(
            icon,
            LinearLayout.LayoutParams(
                dp(25),
                dp(25)
            )
        )

        val label = TextView(this).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(18)
        )

        labelParams.topMargin = dp(2)

        item.addView(label, labelParams)

        updateNavItem(item, selected)

        item.setOnClickListener {

            icon.animate()
                .scaleX(0.84f)
                .scaleY(0.84f)
                .setDuration(70)
                .withEndAction {
                    icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(130)
                        .start()
                }
                .start()

            action()
        }

        return item
    }


    private fun showProfileDialog() {

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(35, 30, 35, 25)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val title = text(
            "Profile",
            24f,
            Color.BLACK,
            Typeface.BOLD
        )

        box.addView(title)

        val profileAvatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }

        updateAvatar(profileAvatar)

        box.addView(
            profileAvatar,
            LinearLayout.LayoutParams(110, 110).apply {
                topMargin = 20
                bottomMargin = 20
            }
        )

        val change = Button(this).apply {
            text = "Upload Photo"
            setOnClickListener {
                imagePicker.launch("image/*")
            }
        }

        val remove = Button(this).apply {
            text = "Remove Photo"
            setOnClickListener {
                deleteAvatar()
                updateAvatar()
            }
        }

        box.addView(change)
        box.addView(remove)

        val dialog = android.app.Dialog(
            this,
            android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
        )

        dialog.setContentView(box)

        dialog.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        dialog.show()
    }

    private fun updateAvatar(target: ImageView? = null) {

        val image = target ?: avatar

        val file = File(
            filesDir,
            "profile_avatar.jpg"
        )

        if (file.exists()) {
            image.setImageURI(Uri.fromFile(file))
        } else {
            image.setImageResource(android.R.drawable.ic_menu_myplaces)
            image.setColorFilter(Color.WHITE)
        }
    }

    private fun saveAvatar(uri: Uri) {

        try {

            contentResolver.openInputStream(uri)?.use { input ->

                FileOutputStream(
                    File(filesDir, "profile_avatar.jpg")
                ).use { output ->

                    input.copyTo(output)
                }
            }

            Toast.makeText(
                this,
                "Profile photo updated",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Could not save photo",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun deleteAvatar() {

        File(
            filesDir,
            "profile_avatar.jpg"
        ).delete()

        updateAvatar()

        Toast.makeText(
            this,
            "Profile photo removed",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showSongMenu(
        anchor: View,
        song: Song
    ) {

        val popup = PopupWindow(
            this
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 12, 22, 12)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val play = text(
            "Play",
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(8, 16, 30, 16)
            setOnClickListener {
                playSong(song)
                popup.dismiss()
            }
        }

        val favorite = text(
            "Add to Favorites",
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(8, 16, 30, 16)
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Added to Favorites",
                    Toast.LENGTH_SHORT
                ).show()
                popup.dismiss()
            }
        }

        val info = text(
            "Song Info",
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(8, 16, 30, 16)
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    song.title,
                    Toast.LENGTH_SHORT
                ).show()
                popup.dismiss()
            }
        }

        box.addView(play)
        box.addView(favorite)
        box.addView(info)

        popup.contentView = box
        popup.width = 230
        popup.height = -2
        popup.isFocusable = true
        popup.elevation = 12f

        popup.showAsDropDown(anchor, -210, -160)
    }

    private fun showSongPopup(
        anchor: View,
        song: Song
    ) {
        val box =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6)
                )

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius =
                            dp(16).toFloat()
                        setColor(Color.WHITE)
                    }

                elevation = dp(18).toFloat()
            }

        fun addPopupItem(
            title: String,
            action: () -> Unit
        ) {
            val item =
                TextView(this).apply {
                    text = title
                    textSize = 15f
                    setTextColor(
                        Color.rgb(
                            25,
                            25,
                            27
                        )
                    )
                    gravity =
                        Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(14),
                        0,
                        dp(14),
                        0
                    )

                    setOnClickListener {
                        action()
                    }
                }

            box.addView(
                item,
                LinearLayout.LayoutParams(
                    dp(190),
                    dp(46)
                )
            )
        }

        addPopupItem("Play Next") {
            playSong(song)
        }

        addPopupItem("Add to Queue") {
            Toast.makeText(
                this,
                "Added to queue",
                Toast.LENGTH_SHORT
            ).show()
        }

        addPopupItem("Add to Favorites") {
            Toast.makeText(
                this,
                "Added to favorites",
                Toast.LENGTH_SHORT
            ).show()
        }

        addPopupItem("Song Info") {
            showChoiceDialog(
                song.title,
                arrayOf(
                    song.artist,
                    "Music"
                )
            )
        }

        showBlurPopup(
            box,
            anchor
        )
    }

    private fun showBlurPopup(
        contentView: View,
        anchor: View
    ) {
        val scrim =
            View(this).apply {
                setBackgroundColor(
                    Color.argb(
                        55,
                        0,
                        0,
                        0
                    )
                )
            }

        val overlay =
            FrameLayout(this).apply {
                addView(
                    scrim,
                    FrameLayout.LayoutParams(
                        -1,
                        -1
                    )
                )

                addView(
                    contentView,
                    FrameLayout.LayoutParams(
                        -2,
                        -2,
                        Gravity.CENTER
                    )
                )
            }

        val dialog =
            android.app.Dialog(this)

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT
            )
        )

        dialog.setContentView(overlay)

        scrim.setOnClickListener {
            dialog.dismiss()
        }

        contentView.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setDimAmount(0.18f)
        dialog.show()

        dialog.window?.setLayout(
            -1,
            -1
        )
    }

    private fun showChoiceDialog(
        titleValue: String,
        options: Array<String>
    ) {

        android.app.AlertDialog.Builder(this)
            .setTitle(titleValue)
            .setItems(options) { _, which ->
                Toast.makeText(
                    this,
                    options[which],
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun animateSongChange(
        song: Song,
        root: ViewGroup,
        cover: ImageView,
        title: TextView,
        artist: TextView,
        seekBar: SeekBar,
        elapsed: TextView,
        remaining: TextView
    ) {

        val decelerate =
            android.view.animation.DecelerateInterpolator()

        // Cancel any previous transition so rapid Next/Previous
        // presses do not stack animations.
        listOf<View>(
            cover,
            title,
            artist,
            seekBar,
            elapsed,
            remaining
        ).forEach {
            it.animate().cancel()
        }

        // -------------------------------------------------
        // TITLE + ARTIST
        // -------------------------------------------------

        title.animate()
            .alpha(0f)
            .translationY(dp(4).toFloat())
            .setDuration(130L)
            .setInterpolator(decelerate)
            .withEndAction {

                title.text = song.title
                title.translationY = -dp(4).toFloat()

                title.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(240L)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()

        artist.animate()
            .alpha(0f)
            .translationY(dp(4).toFloat())
            .setDuration(130L)
            .setInterpolator(decelerate)
            .withEndAction {

                artist.text = song.artist
                artist.translationY = -dp(4).toFloat()

                artist.animate()
                    .alpha(0.68f)
                    .translationY(0f)
                    .setDuration(240L)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()

        // -------------------------------------------------
        // SEEK BAR + TIME
        // -------------------------------------------------

        seekBar.animate()
            .alpha(0.35f)
            .setDuration(110L)
            .setInterpolator(decelerate)
            .withEndAction {

                seekBar.progress = 0

                seekBar.animate()
                    .alpha(1f)
                    .setDuration(300L)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()

        elapsed.animate()
            .alpha(0f)
            .setDuration(110L)
            .setInterpolator(decelerate)
            .withEndAction {

                elapsed.text = "0:00"

                elapsed.animate()
                    .alpha(0.62f)
                    .setDuration(260L)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()

        remaining.animate()
            .alpha(0f)
            .setDuration(110L)
            .setInterpolator(decelerate)
            .withEndAction {

                val duration =
                    mediaPlayer?.duration ?: 0

                val totalSeconds =
                    (duration / 1000).coerceAtLeast(0)

                val minutes =
                    totalSeconds / 60

                val seconds =
                    totalSeconds % 60

                remaining.text =
                    "-${String.format("%d:%02d", minutes, seconds)}"

                remaining.animate()
                    .alpha(0.62f)
                    .setDuration(260L)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()

        // -------------------------------------------------
        // COVER
        // -------------------------------------------------

        cover.animate()
            .alpha(0.18f)
            .scaleX(0.955f)
            .scaleY(0.955f)
            .setDuration(180L)
            .setInterpolator(decelerate)
            .withEndAction {

                getAlbumArt(song)?.let {
                    cover.setImageBitmap(it)
                } ?: run {
                    cover.setImageResource(R.drawable.ic_music)
                }

                cover.animate()
                    .alpha(1f)
                    .scaleX(
                        if (mediaPlayer?.isPlaying == true)
                            1f
                        else
                            0.94f
                    )
                    .scaleY(
                        if (mediaPlayer?.isPlaying == true)
                            1f
                        else
                            0.94f
                    )
                    .setDuration(480L)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()

        // -------------------------------------------------
        // BACKGROUND
        // -------------------------------------------------

        getAlbumArt(song)?.let { bitmap ->

            val colors = run {

                val w = bitmap.width.coerceAtLeast(1)
                val h = bitmap.height.coerceAtLeast(1)

                val points = arrayOf(
                    intArrayOf(w / 2, h / 4),
                    intArrayOf(w / 2, h / 2),
                    intArrayOf(w / 3, (h * 3) / 4),
                    intArrayOf((w * 2) / 3, (h * 3) / 4)
                )

                var r1 = 0
                var g1 = 0
                var b1 = 0

                var r2 = 0
                var g2 = 0
                var b2 = 0

                var r3 = 0
                var g3 = 0
                var b3 = 0

                points.forEachIndexed { index, point ->

                    val x = point[0].coerceIn(0, w - 1)
                    val y = point[1].coerceIn(0, h - 1)

                    val c = bitmap.getPixel(x, y)

                    if (index < 2) {
                        r1 += Color.red(c)
                        g1 += Color.green(c)
                        b1 += Color.blue(c)
                    }

                    if (index >= 1) {
                        r2 += Color.red(c)
                        g2 += Color.green(c)
                        b2 += Color.blue(c)
                    }

                    r3 += Color.red(c)
                    g3 += Color.green(c)
                    b3 += Color.blue(c)
                }

                intArrayOf(
                    Color.rgb(
                        (r1 / 2).coerceIn(0, 255),
                        (g1 / 2).coerceIn(0, 255),
                        (b1 / 2).coerceIn(0, 255)
                    ),
                    Color.rgb(
                        (r2 / 3).coerceIn(0, 255),
                        (g2 / 3).coerceIn(0, 255),
                        (b2 / 3).coerceIn(0, 255)
                    ),
                    Color.rgb(
                        (r3 / 4).coerceIn(0, 255),
                        (g3 / 4).coerceIn(0, 255),
                        (b3 / 4).coerceIn(0, 255)
                    )
                )
            }

            val newBackground =
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    colors
                )

            // Briefly soften the entire surface while swapping
            // the background, then restore it.
            root.animate()
                .alpha(0.88f)
                .setDuration(150L)
                .setInterpolator(decelerate)
                .withEndAction {

                    root.background = newBackground

                    root.animate()
                        .alpha(1f)
                        .setDuration(360L)
                        .setInterpolator(decelerate)
                        .start()
                }
                .start()
        }
    }


    // ============================================================
    // FULL PLAYER -> MINI PLAYER GESTURE
    // ============================================================

    private var fullPlayerCloseDragging = false
    private var fullPlayerCloseDownY = 0f
    private var fullPlayerCloseProgress = 0f
    private var fullPlayerCloseInitialScale = 1f
    private var fullPlayerCloseStartCenterX = 0f
    private var fullPlayerCloseStartCenterY = 0f

    private fun setupFullPlayerCloseGesture(
        dialog: android.app.Dialog,
        root: View,
        cover: ImageView,
        coverContainer: View
    ) {

        coverContainer.setOnTouchListener { _, event ->

            when (event.actionMasked) {

                android.view.MotionEvent.ACTION_DOWN -> {

                    if (fullPlayerCloseDragging) {
                        return@setOnTouchListener true
                    }

                    fullPlayerCloseDownY =
                        event.rawY

                    fullPlayerCloseProgress = 0f

                    fullPlayerCloseInitialScale =
                        cover.scaleX

                    val coverLocation =
                        IntArray(2)

                    cover.getLocationOnScreen(
                        coverLocation
                    )

                    fullPlayerCloseStartCenterX =
                        coverLocation[0] +
                            cover.width / 2f

                    fullPlayerCloseStartCenterY =
                        coverLocation[1] +
                            cover.height / 2f

                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {

                    val dy =
                        event.rawY -
                            fullPlayerCloseDownY

                    if (!fullPlayerCloseDragging) {

                        if (dy < dp(10)) {
                            return@setOnTouchListener true
                        }

                        fullPlayerCloseDragging = true

                        miniPlayer.alpha = 0f
                    }

                    val screenHeight =
                        resources.displayMetrics
                            .heightPixels
                            .toFloat()

                    val progress =
                        (
                            dy /
                                (screenHeight * 0.72f)
                        )
                            .coerceIn(0f, 1f)

                    fullPlayerCloseProgress =
                        progress

                    updateFullPlayerCloseGesture(
                        root,
                        cover,
                        coverContainer,
                        progress
                    )

                    true
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {

                    if (!fullPlayerCloseDragging) {
                        return@setOnTouchListener true
                    }

                    fullPlayerCloseDragging = false

                    if (fullPlayerCloseProgress >= 0.5f) {

                        finishFullPlayerClose(
                            dialog,
                            root,
                            cover,
                            coverContainer
                        )

                    } else {

                        restoreFullPlayerFromGesture(
                            root,
                            cover
                        )
                    }

                    true
                }

                else -> true
            }
        }
    }


    private fun updateFullPlayerCloseGesture(
        root: View,
        cover: ImageView,
        coverContainer: View,
        progress: Float
    ) {

        val miniLocation =
            IntArray(2)

        miniCover.getLocationOnScreen(
            miniLocation
        )

        val fullWidth =
            cover.width
                .coerceAtLeast(1)
                .toFloat()

        val fullCenterX =
            fullPlayerCloseStartCenterX

        val fullCenterY =
            fullPlayerCloseStartCenterY

        val miniSize =
            dp(46).toFloat()

        val miniCenterX =
            miniLocation[0] +
                miniSize / 2f

        val miniCenterY =
            miniLocation[1] +
                miniSize / 2f

        // Interpolate the artwork center directly
        // between Full Player and Mini Player.
        val centerX =
            fullCenterX +
                (
                    miniCenterX -
                        fullCenterX
                ) * progress

        val centerY =
            fullCenterY +
                (
                    miniCenterY -
                        fullCenterY
                ) * progress

        val containerLocation =
            IntArray(2)

        coverContainer.getLocationOnScreen(
            containerLocation
        )

        val containerCenterX =
            containerLocation[0] +
                coverContainer.width / 2f

        val containerCenterY =
            containerLocation[1] +
                coverContainer.height / 2f

        cover.translationX =
            centerX -
                containerCenterX

        cover.translationY =
            centerY -
                containerCenterY

        val targetScale =
            miniSize / fullWidth

        val scale =
            fullPlayerCloseInitialScale +
                (
                    targetScale -
                        fullPlayerCloseInitialScale
                ) * progress

        cover.scaleX = scale
        cover.scaleY = scale

        // Fade the lower Full Player controls.
        root.alpha =
            1f -
                progress * 0.35f

        // Reveal Mini Player underneath.
        miniPlayer.alpha =
            progress

        miniBottomNavigation?.let { nav ->

            nav.translationY =
                dp(82).toFloat() *
                    (1f - progress)

            nav.alpha =
                progress
        }
    }


    private fun restoreFullPlayerFromGesture(
        root: View,
        cover: ImageView
    ) {

        root.animate()
            .alpha(1f)
            .setDuration(300L)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .start()

        cover.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(
                fullPlayerCloseInitialScale
            )
            .scaleY(
                fullPlayerCloseInitialScale
            )
            .setDuration(320L)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .start()

        miniPlayer.animate()
            .alpha(0f)
            .setDuration(220L)
            .start()

        miniBottomNavigation?.animate()
            ?.translationY(dp(82).toFloat())
            ?.alpha(0f)
            ?.setDuration(220L)
            ?.start()

        fullPlayerCloseProgress = 0f
    }


    private fun finishFullPlayerClose(
        dialog: android.app.Dialog,
        root: View,
        cover: ImageView,
        coverContainer: View
    ) {

        val miniLocation =
            IntArray(2)

        miniCover.getLocationOnScreen(
            miniLocation
        )

        val fullWidth =
            cover.width
                .coerceAtLeast(1)
                .toFloat()

        val miniSize =
            dp(46).toFloat()

        val miniCenterX =
            miniLocation[0] +
                miniSize / 2f

        val miniCenterY =
            miniLocation[1] +
                miniSize / 2f

        val containerLocation =
            IntArray(2)

        coverContainer.getLocationOnScreen(
            containerLocation
        )

        val containerCenterX =
            containerLocation[0] +
                coverContainer.width / 2f

        val containerCenterY =
            containerLocation[1] +
                coverContainer.height / 2f

        val finalX =
            miniCenterX -
                containerCenterX

        val finalY =
            miniCenterY -
                containerCenterY

        /*
         * Full -> Mini is a continuous morph.
         *
         * Nothing fades away.
         * The Full Player physically moves toward the Mini
         * while its artwork shrinks into the exact Mini cover.
         */
        miniPlayer.alpha = 0f

        miniBottomNavigation?.apply {
            translationY = dp(82).toFloat()
            alpha = 1f
        }

        val closeDuration = 360L

        /*
         * Move the whole Full Player downward toward Mini.
         *
         * The root itself remains fully visible during the
         * transition. The visual transformation comes from
         * translation/scale rather than alpha.
         */
        root.animate()
            .translationY(
                finalY
            )
            .setDuration(closeDuration)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .start()

        /*
         * Artwork follows the exact same destination as Mini.
         */
        cover.animate()
            .translationX(finalX)
            .translationY(finalY)
            .scaleX(
                miniSize / fullWidth
            )
            .scaleY(
                miniSize / fullWidth
            )
            .setDuration(closeDuration)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .start()

        /*
         * Mini Player and Navigation appear only as the
         * Full Player reaches them.
         */
        miniPlayer.animate()
            .alpha(1f)
            .setDuration(closeDuration)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .start()

        miniBottomNavigation?.animate()
            ?.translationY(0f)
            ?.setDuration(closeDuration)
            ?.setInterpolator(
                DecelerateInterpolator()
            )
            ?.start()

        root.animate()
            .translationY(finalY)
            .setDuration(closeDuration)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .withEndAction {

                /*
                 * Restore the Full Player's internal state
                 * before dismissing the dialog.
                 */
                root.translationY = 0f

                cover.translationX = 0f
                cover.translationY = 0f

                cover.scaleX =
                    if (
                        mediaPlayer?.isPlaying == true
                    ) {
                        1f
                    } else {
                        0.94f
                    }

                cover.scaleY =
                    cover.scaleX

                miniPlayer.alpha = 1f

                miniBottomNavigation?.apply {
                    translationY = 0f
                    alpha = 1f
                }

                fullPlayerCloseProgress = 0f

                dialog.dismiss()
            }
            .start()
    }

    private fun showNowPlaying() {

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }


        val song = currentSong ?: return

        val dialog = android.app.Dialog(
            this,
            android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
            clipToPadding = false
        }

        // ---------- DYNAMIC BACKGROUND ----------

        fun mixColor(a: Int, b: Int, fraction: Float): Int {
            val f = fraction.coerceIn(0f, 1f)

            val ar = Color.red(a)
            val ag = Color.green(a)
            val ab = Color.blue(a)

            val br = Color.red(b)
            val bg = Color.green(b)
            val bb = Color.blue(b)

            return Color.rgb(
                (ar + (br - ar) * f).toInt(),
                (ag + (bg - ag) * f).toInt(),
                (ab + (bb - ab) * f).toInt()
            )
        }

        fun extractColors(bitmap: android.graphics.Bitmap): IntArray {

            val w = bitmap.width
            val h = bitmap.height

            if (w <= 0 || h <= 0) {
                return intArrayOf(
                    Color.rgb(245, 245, 247),
                    Color.rgb(225, 225, 230),
                    Color.rgb(205, 205, 210)
                )
            }

            val points = arrayOf(
                intArrayOf(w / 4, h / 4),
                intArrayOf(3 * w / 4, h / 4),
                intArrayOf(w / 4, 3 * h / 4),
                intArrayOf(3 * w / 4, 3 * h / 4)
            )

            var r1 = 0
            var g1 = 0
            var b1 = 0

            var r2 = 0
            var g2 = 0
            var b2 = 0

            var r3 = 0
            var g3 = 0
            var b3 = 0

            points.forEachIndexed { index, point ->

                val x = point[0].coerceIn(0, w - 1)
                val y = point[1].coerceIn(0, h - 1)

                val c = bitmap.getPixel(x, y)

                if (index < 2) {
                    r1 += Color.red(c)
                    g1 += Color.green(c)
                    b1 += Color.blue(c)
                }

                if (index >= 1) {
                    r2 += Color.red(c)
                    g2 += Color.green(c)
                    b2 += Color.blue(c)
                }

                r3 += Color.red(c)
                g3 += Color.green(c)
                b3 += Color.blue(c)
            }

            val color1 = Color.rgb(
                (r1 / 2).coerceIn(0, 255),
                (g1 / 2).coerceIn(0, 255),
                (b1 / 2).coerceIn(0, 255)
            )

            val color2 = Color.rgb(
                (r2 / 3).coerceIn(0, 255),
                (g2 / 3).coerceIn(0, 255),
                (b2 / 3).coerceIn(0, 255)
            )

            val color3 = Color.rgb(
                (r3 / 4).coerceIn(0, 255),
                (g3 / 4).coerceIn(0, 255),
                (b3 / 4).coerceIn(0, 255)
            )

            return intArrayOf(
                color1,
                color2,
                color3
            )
        }

        var currentColors = intArrayOf(
            Color.rgb(245, 245, 247),
            Color.rgb(225, 225, 230),
            Color.rgb(205, 205, 210)
        )

        fun applyBackground(colors: IntArray) {

            val drawable =
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        colors[0],
                        colors[1],
                        colors[2]
                    )
                ).apply {
                    cornerRadius = 0f
                }

            root.background = drawable
        }

        getAlbumArt(song)?.let {
            currentColors = extractColors(it)
        }

        applyBackground(currentColors)

        // ---------- ARTWORK ----------

        val screenWidth =
            resources.displayMetrics.widthPixels

        val coverSize =
            (screenWidth - dp(40))
                .coerceAtMost(dp(390))
                .coerceAtLeast(dp(250))

        val cover = ImageView(this).apply {

            scaleType =
                ImageView.ScaleType.CENTER_CROP

            clipToOutline = true

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(
                        Color.argb(
                            45,
                            255,
                            255,
                            255
                        )
                    )
                }

            outlineProvider =
                object :
                    android.view.ViewOutlineProvider() {

                    override fun getOutline(
                        view: android.view.View,
                        outline: android.graphics.Outline
                    ) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height,
                            dp(20).toFloat()
                        )
                    }
                }

            getAlbumArt(song)?.let {
                setImageBitmap(it)
            }
        }

        val coverContainer = FrameLayout(this).apply {

            addView(
                cover,
                FrameLayout.LayoutParams(
                    coverSize,
                    coverSize
                ).apply {
                    gravity = Gravity.CENTER
                }
            )
        }

        val coverStoppedScale = 0.94f
        val coverPlayingScale = 1f

        cover.scaleX =
            if (mediaPlayer?.isPlaying == true)
                coverPlayingScale
            else
                coverStoppedScale

        cover.scaleY = cover.scaleX

        // Queue panel is attached after its declaration below.


        setupFullPlayerCloseGesture(
            dialog,
            root,
            cover,
            coverContainer
        )

        root.addView(
            coverContainer,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        )

        // ---------- LOWER PLAYER PANEL ----------

        val bottomPanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

        // ---------- SONG INFO ----------

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(4),
                0,
                dp(4),
                0
            )
        }

        val title = text(
            song.title,
            22f,
            Color.WHITE,
            Typeface.BOLD
        ).apply {
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        val artist = text(
            song.artist,
            14f,
            Color.WHITE,
            Typeface.NORMAL
        ).apply {
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            alpha = 0.68f
            setPadding(
                0,
                dp(3),
                0,
                0
            )
        }

        info.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                dp(28)
            )
        )

        info.addView(
            artist,
            LinearLayout.LayoutParams(
                -1,
                dp(21)
            )
        )

        info.translationY = 0f

        bottomPanel.addView(
            info,
            LinearLayout.LayoutParams(
                -1,
                dp(55)
            )
        )

        // ---------- SEEK BAR ----------
        val seekBar = SeekBar(this).apply {

            max = 1000
            progress = 0

            minHeight = dp(5)
            minimumHeight = dp(5)

            setPadding(
                0,
                dp(1),
                0,
                dp(1)
            )

            thumb = null

            val backgroundTrack =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setColor(
                        Color.argb(
                            70,
                            255,
                            255,
                            255
                        )
                    )
                }

            val progressTrack =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.WHITE)
                }

            val progressClip =
                android.graphics.drawable.ClipDrawable(
                    progressTrack,
                    Gravity.START,
                    1
                )

            progressDrawable =
                android.graphics.drawable.LayerDrawable(
                    arrayOf(
                        backgroundTrack,
                        progressClip
                    )
                ).apply {
                    setId(
                        0,
                        android.R.id.background
                    )
                    setId(
                        1,
                        android.R.id.progress
                    )
                }

            setOnSeekBarChangeListener(
                object :
                    SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (fromUser) {
                            mediaPlayer?.let {
                                try {
                                    val position =
                                        (
                                            it.duration.toLong() *
                                            progress.toLong()
                                        ) / 1000L

                                    it.seekTo(position.toInt())
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    override fun onStartTrackingTouch(
                        seekBar: SeekBar?
                    ) {
                    }

                    override fun onStopTrackingTouch(
                        seekBar: SeekBar?
                    ) {
                    }
                }
            )
        }

        seekBar.translationY = -dp(6).toFloat()

        bottomPanel.addView(
            seekBar,
            LinearLayout.LayoutParams(
                -1,
                dp(10)
            ).apply {
                topMargin = dp(1)
            }
        )

        // ---------- TIME ----------

        val timeRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val elapsed = text(
            "0:00",
            10.5f,
            Color.WHITE,
            Typeface.NORMAL
        ).apply {
            includeFontPadding = false
            alpha = 0.62f
        }

        val remaining = text(
            "-0:00",
            10.5f,
            Color.WHITE,
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.RIGHT
            includeFontPadding = false
            alpha = 0.62f
        }

        timeRow.addView(
            elapsed,
            LinearLayout.LayoutParams(
                0,
                dp(18),
                1f
            )
        )

        timeRow.addView(
            remaining,
            LinearLayout.LayoutParams(
                0,
                dp(18),
                1f
            )
        )

        bottomPanel.addView(
            timeRow,
            LinearLayout.LayoutParams(
                -1,
                dp(18)
            )
        )

        // ---------- MAIN CONTROLS ----------

        val controls =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity = Gravity.CENTER
            }

        val previous =
            ImageView(this).apply {

                setImageResource(
                    com.music.app.R.drawable.ic_player_next
                )

                scaleType =
                    android.widget.ImageView.ScaleType.CENTER_INSIDE

                background = null

                contentDescription =
                    "Previous"

                setPadding(
                    0,
                    0,
                    0,
                    0
                )

                setOnClickListener {

                    val index =
                        songs.indexOfFirst {
                            it.id ==
                                currentSong?.id
                        }

                    if (index > 0) {

                        playSong(
                            songs[index - 1]
                        )

                        animateSongChange(
                            song = songs[index - 1],
                            root = root,
                            cover = cover,
                            title = title,
                            artist = artist,
                            seekBar = seekBar,
                            elapsed = elapsed,
                            remaining = remaining
                        )
                    }
                }
            }

        val play =
            ImageView(this).apply {

                setImageResource(
                    if (mediaPlayer?.isPlaying == true)
                        com.music.app.R.drawable.ic_player_pause
                    else
                        com.music.app.R.drawable.ic_player_play
                )

                scaleType =
                    android.widget.ImageView.ScaleType.CENTER_INSIDE

                background = null

                contentDescription =
                    if (mediaPlayer?.isPlaying == true)
                        "Pause"
                    else
                        "Play"

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
                )

                setColorFilter(
                    android.graphics.PorterDuffColorFilter(
                        Color.WHITE,
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                )

                elevation = 0f

                setOnTouchListener { view, event ->

                    when (event.action) {

                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.animate()
                                .scaleX(1.08f)
                                .scaleY(1.08f)
                                .setDuration(120)
                                .start()

                            view.background =
                                GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(
                                        Color.argb(
                                            77,
                                            255,
                                            255,
                                            255
                                        )
                                    )
                                }
                        }

                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {

                            view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(220)
                                .start()

                            view.animate()
                                .alpha(0.92f)
                                .setDuration(80)
                                .withEndAction {
                                    view.background = null
                                    view.alpha = 1f
                                }
                                .start()
                        }
                    }

                    false
                }

                setOnClickListener {

                    mediaPlayer?.let {

                        try {

                            if (it.isPlaying) {

                                it.pause()

                                cover.animate()
                                    .scaleX(coverStoppedScale)
                                    .scaleY(coverStoppedScale)
                                    .setDuration(380)
                                    .setInterpolator(
                                        android.view.animation.DecelerateInterpolator()
                                    )
                                    .start()

                                setImageResource(
                                    com.music.app.R.drawable.ic_player_play
                                )

                                contentDescription = "Play"

                                playButton.text = "▶"

                            } else {

                                it.start()

                                cover.animate()
                                    .scaleX(coverPlayingScale)
                                    .scaleY(coverPlayingScale)
                                    .setDuration(480)
                                    .setInterpolator(
                                        android.view.animation.DecelerateInterpolator()
                                    )
                                    .start()

                                setImageResource(
                                    com.music.app.R.drawable.ic_player_pause
                                )

                                contentDescription = "Pause"

                                playButton.text = "▮▮"
                            }

                        } catch (_: Exception) {
                        }
                    }
                }
            }

        val next =
            ImageView(this).apply {

                setImageResource(
                    com.music.app.R.drawable.ic_player_previous
                )

                scaleType =
                    android.widget.ImageView.ScaleType.CENTER_INSIDE

                background = null

                contentDescription =
                    "Next"

                setPadding(
                    0,
                    0,
                    0,
                    0
                )

                setOnClickListener {

                    val index =
                        songs.indexOfFirst {
                            it.id ==
                                currentSong?.id
                        }

                    if (
                        index >= 0 &&
                        index < songs.lastIndex
                    ) {

                        playSong(
                            songs[index + 1]
                        )

                        animateSongChange(
                            song = songs[index + 1],
                            root = root,
                            cover = cover,
                            title = title,
                            artist = artist,
                            seekBar = seekBar,
                            elapsed = elapsed,
                            remaining = remaining
                        )
                    }
                }
            }

        controls.addView(
            previous,
            LinearLayout.LayoutParams(
                dp(62),
                dp(62)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(8)
                marginEnd = dp(6)
            }
        )

        controls.addView(
            play,
            LinearLayout.LayoutParams(
                dp(62),
                dp(62)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        )

        controls.addView(
            next,
            LinearLayout.LayoutParams(
                dp(62),
                dp(62)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(6)
                marginEnd = dp(8)
            }
        )

        controls.translationY = -dp(10).toFloat()

        bottomPanel.addView(
            controls,
            LinearLayout.LayoutParams(
                -1,
                dp(72)
            ).apply {
                topMargin = 0
            }
        )

        // ---------- SECONDARY ----------
        val secondary =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(
                    dp(4),
                    0,
                    dp(4),
                    0
                )
            }

        fun secondaryButton(
            icon: Int,
            label: String
        ): LinearLayout {

            val box =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER

                    background = null

                    elevation = 0f

                    isClickable = true
                    isFocusable = true

                    setPadding(
                        dp(6),
                        0,
                        dp(6),
                        0
                    )

                    setOnTouchListener { view, event ->

                        when (event.action) {

                            android.view.MotionEvent.ACTION_DOWN -> {

                                view.animate()
                                    .scaleX(0.94f)
                                    .scaleY(0.94f)
                                    .setDuration(80)
                                    .start()
                            }

                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {

                                view.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(100)
                                    .start()
                            }
                        }

                        false
                    }
                }

            val image =
                ImageView(this).apply {

                    setImageResource(icon)

                    scaleType =
                        ImageView.ScaleType.CENTER_INSIDE

                    alpha = 0.95f
                }

            val text =
                TextView(this).apply {

                    this.text = label

                    setTextColor(
                        Color.WHITE
                    )

                    textSize = 11f

                    setTypeface(
                        null,
                        Typeface.BOLD
                    )

                    gravity =
                        Gravity.CENTER

                    setPadding(
                        dp(5),
                        0,
                        0,
                        0
                    )
                }

            box.addView(
                image,
                LinearLayout.LayoutParams(
                    dp(20),
                    dp(20)
                )
            )

            box.addView(
                text,
                LinearLayout.LayoutParams(
                    -2,
                    -1
                )
            )

            return box
        }

        val lyrics =
            secondaryButton(
                com.music.app.R.drawable.ic_player_lyrics,
                "Lyrics"
            )

        // Local songs do not provide online lyrics.
        lyrics.alpha = 0.35f
        lyrics.isEnabled = false
        lyrics.isClickable = false
        lyrics.contentDescription = "Lyrics unavailable for local music"

        val cast =
            secondaryButton(
                com.music.app.R.drawable.ic_player_cast,
                "Cast"
            )

        cast.setOnClickListener {
            android.widget.Toast
                .makeText(
                    this,
                    "Cast device is not available",
                    android.widget.Toast.LENGTH_SHORT
                )
                .show()
        }

        val queue =
            secondaryButton(
                com.music.app.R.drawable.ic_player_queue,
                "Queue"
            )

        // ---------- ADD SECONDARY PLAYER BUTTONS ----------
        secondary.addView(
            lyrics,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )

        secondary.addView(
            cast,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )

        secondary.addView(
            queue,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )

        // ---------- ATTACH SECONDARY PLAYER ROW ----------

        bottomPanel.addView(
            secondary,
            LinearLayout.LayoutParams(
                -1,
                dp(44)
            ).apply {
                topMargin = dp(2)
            }
        )

        var queueExpanded = false

        val queuePanel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                alpha = 0f
                setPadding(
                    dp(4),
                    0,
                    dp(4),
                    0
                )
            }


        queuePanel.visibility = View.GONE
        queuePanel.alpha = 0f

        coverContainer.addView(
            queuePanel,
            FrameLayout.LayoutParams(
                -1,
                -1
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        queue.setOnClickListener {

            queueExpanded = !queueExpanded

            if (queueExpanded) {

                // Restore Previous / Play / Next.
                controls.visibility = View.VISIBLE
                controls.alpha = 1f
                controls.translationY = -dp(14).toFloat()

                queuePanel.visibility = View.VISIBLE
                queuePanel.alpha = 0f
                queuePanel.translationY = dp(18).toFloat()

                cover.animate()
                    .alpha(0f)
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .setDuration(220L)
                    .start()

                queuePanel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(280L)
                    .setInterpolator(
                        android.view.animation.DecelerateInterpolator()
                    )
                    .start()

            } else {

                queuePanel.animate()
                    .alpha(0f)
                    .translationY(dp(18).toFloat())
                    .setDuration(220L)
                    .setInterpolator(
                        android.view.animation.DecelerateInterpolator()
                    )
                    .withEndAction {

                        queuePanel.visibility = View.GONE

                        cover.animate()
                    .alpha(1f)
                    .scaleX(
                        if (mediaPlayer?.isPlaying == true)
                            coverPlayingScale
                        else
                            coverStoppedScale
                    )
                    .scaleY(
                        if (mediaPlayer?.isPlaying == true)
                            coverPlayingScale
                        else
                            coverStoppedScale
                    )
                            .setDuration(220L)
                            .start()
                    }
                    .start()

                // Always restore Previous / Play / Next.
                controls.visibility = View.VISIBLE
                controls.alpha = 1f
                controls.translationY = -dp(14).toFloat()
            }
        }

        val queueHeader =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val queueCover =
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                    }
                clipToOutline = true
            }

        getAlbumArt(song)?.let {
            queueCover.setImageBitmap(it)
        }

        val queueSongTitle =
            TextView(this).apply {
                text = song.title
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                isSingleLine = true
                ellipsize =
                    android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
                setPadding(dp(10), 0, 0, 0)
            }

        val queueMore =
            TextView(this).apply {
                text = "⋮"
                textSize = 27f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(
                    dp(8),
                    0,
                    dp(4),
                    0
                )
            }

        queueMore.setOnClickListener {

            val popupRoot =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        dp(8),
                        dp(8),
                        dp(8),
                        dp(8)
                    )

                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat()
                            setColor(
                                Color.argb(
                                    248,
                                    35,
                                    35,
                                    38
                                )
                            )
                            setStroke(
                                dp(1),
                                Color.argb(
                                    40,
                                    255,
                                    255,
                                    255
                                )
                            )
                        }

                    elevation = dp(12).toFloat()
                }

            val popupWindow =
                android.widget.PopupWindow(
                    popupRoot,
                    dp(190),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                ).apply {
                    isFocusable = true
                    isOutsideTouchable = true
                    setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(
                            Color.TRANSPARENT
                        )
                    )
                    elevation = dp(12).toFloat()
                }

            fun addPopupItem(
                title: String,
                action: () -> Unit
            ) {

                val item =
                    TextView(this).apply {
                        text = title
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(
                            dp(14),
                            0,
                            dp(14),
                            0
                        )
                        isClickable = true

                        setOnTouchListener { view, event ->

                            when (
                                event.actionMasked
                            ) {

                                android.view.MotionEvent
                                    .ACTION_DOWN -> {
                                    view.alpha = 0.55f
                                }

                                android.view.MotionEvent
                                    .ACTION_UP,
                                android.view.MotionEvent
                                    .ACTION_CANCEL -> {
                                    view.alpha = 1f
                                }
                            }

                            false
                        }

                        setOnClickListener {
                            action()
                            popupWindow.dismiss()
                        }
                    }

                popupRoot.addView(
                    item,
                    LinearLayout.LayoutParams(
                        -1,
                        dp(44)
                    )
                )
            }

            addPopupItem("Share") {

                val share =
                    android.content.Intent(
                        android.content.Intent.ACTION_SEND
                    ).apply {
                        type = "text/plain"
                        putExtra(
                            android.content.Intent.EXTRA_TEXT,
                            "${song.title} — ${song.artist}"
                        )
                    }

                startActivity(
                    android.content.Intent.createChooser(
                        share,
                        "Share"
                    )
                )
            }

            addPopupItem("View Credits") {

                android.app.AlertDialog.Builder(this)
                    .setTitle("View Credits")
                    .setMessage(
                        "${song.title}\n${song.artist}"
                    )
                    .setPositiveButton(
                        "OK",
                        null
                    )
                    .show()
            }

            addPopupItem("Favorite") {

                android.widget.Toast.makeText(
                    this,
                    "Added to Favorites",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            addPopupItem("Suggest Less") {

                android.widget.Toast.makeText(
                    this,
                    "We'll suggest less like this",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            // Open inward, toward the center of the player.
            popupWindow.showAsDropDown(
                queueMore,
                -dp(154),
                dp(2)
            )
        }

        // ---------- QUEUE HEADER COVER ----------
        queueHeader.addView(
            queueCover,
            LinearLayout.LayoutParams(
                dp(52),
                dp(52)
            )
        )

        queueHeader.addView(
            queueSongTitle,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                marginStart = dp(8)
            }
        )

        queueHeader.addView(
            queueMore,
            LinearLayout.LayoutParams(
                dp(40),
                dp(52)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        // ---------- QUEUE MODES ----------

        val queueModes =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(
                    dp(2),
                    dp(2),
                    dp(2),
                    dp(2)
                )
            }

        fun modeButton(
            symbol: String,
            action: () -> Unit
        ): TextView {

            return TextView(this).apply {

                text = symbol
                textSize = 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                alpha = 0.72f
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    action()
                }

                setOnTouchListener { view, event ->

                    when (event.actionMasked) {

                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.animate()
                                .scaleX(0.86f)
                                .scaleY(0.86f)
                                .setDuration(70L)
                                .start()
                        }

                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(110L)
                                .start()
                        }
                    }

                    false
                }
            }
        }

        var shuffleEnabled = false
        var repeatEnabled = false
        var infinityEnabled = true

        lateinit var shuffleButton: TextView
        lateinit var repeatButton: TextView
        lateinit var infinityButton: TextView

        fun modeCard(
            icon: String,
            action: () -> Unit
        ): TextView {

            return TextView(this).apply {

                if (icon == "SHUFFLE_DRAWABLE") {
                    text = ""
                    val shuffleDrawable =
                        androidx.core.content.ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.ic_player_shuffle
                        )?.mutate()

                    shuffleDrawable?.setTint(Color.WHITE)

                    setCompoundDrawablesWithIntrinsicBounds(
                        shuffleDrawable,
                        null,
                        null,
                        null
                    )

                    compoundDrawablePadding = 0
                } else {
                    text = icon
                }

                textSize = 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.RECTANGLE
                        cornerRadius =
                            dp(14).toFloat()
                        setColor(
                            Color.argb(
                                48,
                                255,
                                255,
                                255
                            )
                        )
                        setStroke(
                            dp(1),
                            Color.argb(
                                55,
                                255,
                                255,
                                255
                            )
                        )
                    }

                alpha = 0.78f

                setOnClickListener {
                    action()
                }

                setOnTouchListener { view, event ->

                    when (
                        event.actionMasked
                    ) {

                        android.view.MotionEvent
                            .ACTION_DOWN -> {

                            view.animate()
                                .scaleX(0.91f)
                                .scaleY(0.91f)
                                .setDuration(70L)
                                .start()
                        }

                        android.view.MotionEvent
                            .ACTION_UP,
                        android.view.MotionEvent
                            .ACTION_CANCEL -> {

                            view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(110L)
                                .start()
                        }
                    }

                    false
                }
            }
        }

        shuffleButton =
            modeCard("SHUFFLE_DRAWABLE") {

                shuffleEnabled =
                    !shuffleEnabled

                shuffleButton.alpha =
                    if (shuffleEnabled)
                        1f
                    else
                        0.78f

                if (shuffleEnabled) {
                    playbackQueue.shuffle()
                }
            }

        // Apple Music-style repeat symbol:
        // two curved arrows facing opposite directions.
        repeatButton =
            modeCard("↻") {

                repeatEnabled =
                    !repeatEnabled

                repeatButton.alpha =
                    if (repeatEnabled)
                        1f
                    else
                        0.78f
            }

        infinityButton =
            modeCard("∞") {

                infinityEnabled =
                    !infinityEnabled

                infinityButton.alpha =
                    if (infinityEnabled)
                        1f
                    else
                        0.78f
            }

        fun addModeCard(
            button: TextView
        ) {

            queueModes.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    dp(48),
                    1f
                ).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }

        addModeCard(shuffleButton)
        addModeCard(repeatButton)
        addModeCard(infinityButton)

        // ---------- HISTORY / CLEAR ----------

        val queueActions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val history =
            TextView(this).apply {
                text = "History"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(
                    dp(4),
                    dp(5),
                    dp(8),
                    dp(5)
                )
            }

        val clear =
            TextView(this).apply {
                text = "Clear"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
                setPadding(
                    dp(8),
                    dp(5),
                    dp(4),
                    dp(5)
                )
            }

        history.setOnClickListener {

            val historyText =
                playbackQueue
                    .take(
                        (playbackIndex + 1)
                            .coerceAtLeast(0)
                    )
                    .joinToString("\n") {
                        it.title
                    }

            android.app.AlertDialog.Builder(this)
                .setTitle("History")
                .setMessage(
                    if (historyText.isBlank())
                        "No history"
                    else
                        historyText
                )
                .setPositiveButton("OK", null)
                .show()
        }

        clear.setOnClickListener {

            playbackQueue.clear()
            playbackIndex = -1

            queueExpanded = false

            queuePanel.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    queuePanel.visibility = View.GONE
                }
                .start()

            queue.alpha = 0.95f
        }

        queueActions.addView(
            history,
            LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
            )
        )

        queueActions.addView(
            clear,
            LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
            )
        )

        // ---------- QUEUE LIST ----------

        val queueList =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

        playbackQueue.forEachIndexed { index, queueSong ->

            val row =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        dp(4),
                        dp(4),
                        dp(4),
                        dp(4)
                    )

                    setOnClickListener {

                        if (
                            index in
                            playbackQueue.indices
                        ) {

                            playbackIndex = index

                            playSong(
                                playbackQueue[index]
                            )
                        }
                    }
                }

            val cover =
                ImageView(this).apply {
                    scaleType =
                        ImageView.ScaleType.CENTER_CROP
                }

            getAlbumArt(queueSong)?.let {
                cover.setImageBitmap(it)
            }

            val name =
                TextView(this).apply {
                    text = queueSong.title
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                    setPadding(
                        dp(10),
                        0,
                        0,
                        0
                    )
                }

            row.addView(
                cover,
                LinearLayout.LayoutParams(
                    dp(44),
                    dp(44)
                )
            )

            row.addView(
                name,
                LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f
                )
            )

            queueList.addView(row)
        }

        queuePanel.addView(
            queueHeader,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            )
        )

        queuePanel.addView(
            queueModes,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            ).apply {
                topMargin = dp(7)
            }
        )

        queuePanel.addView(
            queueActions,
            LinearLayout.LayoutParams(
                -1,
                dp(34)
            )
        )

        val queueScroll =
            android.widget.ScrollView(this).apply {

                isFillViewport = true

                overScrollMode =
                    View.OVER_SCROLL_IF_CONTENT_SCROLLS

                clipToPadding = false

                setPadding(
                    0,
                    0,
                    0,
                    dp(4)
                )
            }

        queueScroll.addView(
            queueList,
            android.view.ViewGroup.LayoutParams(
                -1,
                -2
            )
        )

        queuePanel.addView(
            queueScroll,
            android.widget.LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // Queue scrolling is independent from the main playback controls.
        val handler =
            android.os.Handler(
                android.os.Looper.getMainLooper()
            )

        fun formatTime(ms: Int): String {

            val totalSeconds =
                (ms / 1000)
                    .coerceAtLeast(0)

            val minutes =
                totalSeconds / 60

            val seconds =
                totalSeconds % 60

            return String.format(
                "%d:%02d",
                minutes,
                seconds
            )
        }

        // ---------- PLAYER PROGRESS ----------

        val updater =
            object : Runnable {

                override fun run() {

                    mediaPlayer?.let { player ->

                        try {

                            val duration =
                                player.duration

                            val position =
                                player.currentPosition

                            if (duration > 0) {

                                seekBar.progress =
                                    (
                                        position.toLong() *
                                            1000L /
                                            duration.toLong()
                                    ).toInt()

                                elapsed.text =
                                    formatTime(
                                        position
                                    )

                                remaining.text =
                                    "-" +
                                        formatTime(
                                            duration -
                                                position
                                        )

                                play.setImageResource(
                                    if (
                                        player.isPlaying
                                    )
                                        com.music.app.R.drawable.ic_player_pause
                                    else
                                        com.music.app.R.drawable.ic_player_play
                                )

                                playButton.text =
                                    if (
                                        player.isPlaying
                                    )
                                        "Ⅱ"
                                    else
                                        "▶"
                            }

                        } catch (_: Exception) {
                        }
                    }

                    handler.postDelayed(
                        this,
                        500
                    )
                }
            }

        // ---------- DYNAMIC COLOR ANIMATION ----------

        val colorUpdater =
            object : Runnable {

                override fun run() {

                    val bitmap =
                        getAlbumArt(song)

                    if (bitmap != null) {

                        val targetColors =
                            extractColors(bitmap)

                        val startColors =
                            currentColors.copyOf()

                        val animator =
                            android.animation.ValueAnimator.ofFloat(
                                0f,
                                1f
                            ).apply {

                                duration = 1800L

                                addUpdateListener {

                                    val f =
                                        it.animatedValue
                                            as Float

                                    val colors =
                                        IntArray(3)

                                    for (
                                        i in 0..2
                                    ) {
                                        colors[i] =
                                            mixColor(
                                                startColors[i],
                                                targetColors[i],
                                                f
                                            )
                                    }

                                    applyBackground(
                                        colors
                                    )
                                }

                                addListener(
                                    object :
                                        android.animation.Animator.AnimatorListener {

                                        override fun onAnimationStart(
                                            animation:
                                                android.animation.Animator
                                        ) {}

                                        override fun onAnimationEnd(
                                            animation:
                                                android.animation.Animator
                                        ) {
                                            currentColors =
                                                targetColors
                                        }

                                        override fun onAnimationCancel(
                                            animation:
                                                android.animation.Animator
                                        ) {}

                                        override fun onAnimationRepeat(
                                            animation:
                                                android.animation.Animator
                                        ) {}
                                    }
                                )
                            }

                        animator.start()
                    }

                    handler.postDelayed(
                        this,
                        2000L
                    )
                }
            }

        handler.post(updater)
        handler.post(colorUpdater)

        // ---------- ATTACH LOWER PLAYER PANEL ----------

        root.addView(
            bottomPanel,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        dialog.setContentView(root)

        /*
         * Expose the real Full Player views to the Mini -> Full
         * gesture controller.
         */
        if (miniExpansionOpening) {
            miniTransitionDialog = dialog
            miniTransitionRoot = root
            miniTransitionFullCover = cover
            miniTransitionInfo = info
            miniTransitionSeekBar = seekBar
            miniTransitionTimeRow = timeRow
            miniTransitionControls = controls
            miniTransitionSecondary = secondary
            miniTransitionCoverContainer = coverContainer

            root.alpha = 0f

            cover.alpha = 0f
            info.alpha = 0f
            seekBar.alpha = 0f
            timeRow.alpha = 0f
            controls.alpha = 0f
            secondary.alpha = 0f
        }

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT
            )
        )

        root.fitsSystemWindows = false

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT
            )
        )

        dialog.window?.navigationBarColor =
            Color.TRANSPARENT

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            dialog.window?.isNavigationBarContrastEnforced =
                false
            dialog.window?.isStatusBarContrastEnforced =
                false
        }

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            dialog.window?.setDecorFitsSystemWindows(false)
        } else {
            dialog.window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        dialog.setOnDismissListener {

            handler.removeCallbacks(
                updater
            )

            handler.removeCallbacks(
                colorUpdater
            )

            if (miniTransitionDialog === dialog) {
                miniTransitionDialog = null
                miniTransitionRoot = null
                miniTransitionFullCover = null
                miniTransitionInfo = null
                miniTransitionSeekBar = null
                miniTransitionTimeRow = null
                miniTransitionControls = null
                miniTransitionSecondary = null
                miniTransitionCoverContainer = null
            }
        }

        dialog.show()

        if (miniExpansionOpening) {
            /*
             * Capture the real Full Player artwork destination
             * after Android completes the layout pass.
             */
            cover.post {
                if (
                    cover.width > 0 &&
                    cover.height > 0
                ) {
                    val fullLocation =
                        IntArray(2)

                    cover.getLocationOnScreen(
                        fullLocation
                    )

                    miniFullTargetCenterX =
                        fullLocation[0] +
                            cover.width / 2f

                    miniFullTargetCenterY =
                        fullLocation[1] +
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
                                expansionLocation[0] +
                                    expansionCover.width / 2f

                            val expansionCenterY =
                                expansionLocation[1] +
                                    expansionCover.height / 2f

                            val fullCenterX =
                                fullLocation[0] +
                                    cover.width / 2f

                            val fullCenterY =
                                fullLocation[1] +
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

                    updateMiniFullTransition(
                        miniFullTransitionProgress
                    )
                }
            }
        }



        dialog.window?.let { window ->

            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Full Player background extends behind status bar and camera cutout
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(
                    Color.TRANSPARENT
                )
            )

            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }

            // Allow the window/background to occupy the display cutout area
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= 30) {

                window.setDecorFitsSystemWindows(false)

                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars()
                )

                window.insetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController
                        .APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController
                        .APPEARANCE_LIGHT_NAVIGATION_BARS
                )

            }

            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

            // Force the Full Player window/background to occupy the full display.
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
    }

    private fun isDuplicateSongsBlocked(): Boolean {

        return getSettingsPrefs()
            .getBoolean(
                "no_duplicate_songs",
                false
            )
    }

    private fun addSongToPlaybackQueue(
        song: Song
    ): Boolean {

        if (
            isDuplicateSongsBlocked() &&
            playbackQueue.any {
                it.id == song.id
            }
        ) {
            Toast.makeText(
                this,
                "Song is already in the queue",
                Toast.LENGTH_SHORT
            ).show()

            return false
        }

        playbackQueue.add(song)

        return true
    }

    private fun playSong(
        song: Song,
        startPosition: Int = 0,
        smoothMiniChange: Boolean = false,
        miniTextDirection: Int = 1
    ) {

        mediaPlayer?.release()
        mediaPlayer = null

        currentSong = song

        showMiniPlayer()

        if (
            playbackQueue.isEmpty() ||
            playbackQueue.none { it.id == song.id }
        ) {
            playbackQueue = mutableListOf(song)
            playbackIndex = 0
        } else {
            playbackIndex =
                playbackQueue.indexOfFirst {
                    it.id == song.id
                }
        }

        val uri =
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                song.id
            )

        mediaPlayer =
            android.media.MediaPlayer().apply {

                setDataSource(
                    this@MainActivity,
                    uri
                )

                prepare()

                applyPlaybackSpeed()

                if (startPosition > 0) {
                    try {
                        seekTo(
                            startPosition.coerceIn(
                                0,
                                duration.coerceAtLeast(0)
                            )
                        )
                    } catch (_: Exception) {
                    }
                }

                start()

                setOnCompletionListener {

                    savePlayerState()

                    val nextIndex =
                        playbackIndex + 1

                    if (
                        nextIndex >= 0 &&
                        nextIndex < playbackQueue.size
                    ) {

                        playbackIndex = nextIndex

                        playSong(
                            playbackQueue[playbackIndex]
                        )

                    } else {

                        playbackQueue.clear()
                        playbackIndex = -1

                        playButton.text = "▶"

                        savePlayerState()
                    }
                }
            }

        if (smoothMiniChange) {
            animateMiniSongTextChange(song, miniTextDirection)

            getAlbumArt(song)?.let {
                miniCover.setImageBitmap(it)
            } ?: run {
                miniCover.setImageResource(0)
            }
        } else {
            getAlbumArt(song)?.let {
                miniCover.setImageBitmap(it)
            } ?: run {
                miniCover.setImageResource(0)
            }

            miniTitle.text = song.title
            miniArtist.text = song.artist
        }

        playButton.text = "Ⅱ"

        savePlayerState()
    }


    override fun onPause() {

        savePlayerState()

        super.onPause()
    }

    override fun onStop() {

        savePlayerState()

        super.onStop()
    }

    override fun onDestroy() {

        savePlayerState()

        playerSaveHandler.removeCallbacks(
            playerSaveRunnable
        )

        mediaPlayer?.release()
        mediaPlayer = null

        super.onDestroy()
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        typeface: Int = Typeface.NORMAL
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, typeface)
            includeFontPadding = false
        }
    }

}
