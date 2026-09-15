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
    private var activeNavIndex = 0
    private val navItems = mutableListOf<LinearLayout>()
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

    private val recentSongIds =
        mutableListOf<Long>()


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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(
            "music_prefs",
            MODE_PRIVATE
        )

        val onboardingComplete =
            prefs.getBoolean("onboarding_complete", false)

        if (!onboardingComplete) {
            onboardingVisible = true
            showOnboardingWelcome()
        } else {
            if (hasAudioPermission()) {
                loadMusic()
            } else {
                requestAudioPermission()
            }
        }
    }

    // ============================================================
    // FIRST RUN ONBOARDING
    // ============================================================

    // ============================================================
    // FIRST RUN ONBOARDING
    // ============================================================

    private fun onboardingText(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(size)
            setTextColor(color)
            gravity = Gravity.START
            if (bold) {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
        }
    }

    private fun onboardingButton(
        text: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            isClickable = true
            isFocusable = true

            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(40).toFloat()
            }

            setOnClickListener {
                action()
            }
        }
    }

    private fun onboardingIcon(
        type: String
    ): ImageView {
        return ImageView(this).apply {
            setColorFilter(Color.BLACK)

            val drawable = when (type) {
                "music" -> android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.BLACK)
                }

                "notification" -> android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.BLACK)
                }

                else -> null
            }

            if (drawable != null) {
                background = drawable
            }

            setPadding(dp(9), dp(9), dp(9), dp(9))

            // Simple monochrome vector-like glyph drawn directly on the ImageView.
            setImageDrawable(object : android.graphics.drawable.Drawable() {

                private val paint = android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG
                ).apply {
                    color = Color.WHITE
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = dp(2).toFloat()
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }

                override fun draw(canvas: android.graphics.Canvas) {
                    val w = bounds.width().toFloat()
                    val h = bounds.height().toFloat()

                    if (type == "music") {
                        val noteX = w * 0.62f
                        val top = h * 0.22f
                        val bottom = h * 0.68f

                        canvas.drawLine(
                            noteX,
                            top,
                            noteX,
                            bottom,
                            paint
                        )

                        canvas.drawLine(
                            noteX,
                            top,
                            w * 0.80f,
                            top,
                            paint
                        )

                        canvas.drawLine(
                            w * 0.80f,
                            top,
                            w * 0.80f,
                            h * 0.57f,
                            paint
                        )

                        canvas.drawCircle(
                            w * 0.48f,
                            h * 0.72f,
                            w * 0.13f,
                            paint
                        )

                        canvas.drawCircle(
                            w * 0.68f,
                            h * 0.61f,
                            w * 0.13f,
                            paint
                        )
                    } else {
                        val cx = w / 2f
                        val top = h * 0.22f
                        val bottom = h * 0.70f

                        val path = android.graphics.Path()

                        path.moveTo(w * 0.28f, bottom)
                        path.lineTo(w * 0.72f, bottom)
                        path.moveTo(w * 0.34f, bottom)
                        path.quadTo(cx, h * 0.88f, w * 0.66f, bottom)
                        path.moveTo(w * 0.30f, bottom)
                        path.quadTo(cx, h * 0.15f, w * 0.70f, bottom)

                        canvas.drawPath(path, paint)

                        canvas.drawCircle(
                            cx,
                            top,
                            w * 0.055f,
                            paint
                        )
                    }
                }

                override fun setAlpha(alpha: Int) {
                    paint.alpha = alpha
                }

                override fun setColorFilter(
                    colorFilter: android.graphics.ColorFilter?
                ) {
                    paint.colorFilter = colorFilter
                }

                override fun getOpacity(): Int =
                    android.graphics.PixelFormat.TRANSLUCENT
            })
        }
    }

    private fun showOnboardingWelcome() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(
                dp(28),
                dp(38),
                dp(28),
                dp(28)
            )
        }

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                0,
                0.08f
            )
        )

        root.addView(
            onboardingText(
                "Music",
                36f,
                Color.BLACK,
                true
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                dp(46)
            )
        )

        // ---------- FIRST SECTION ----------

        root.addView(
            onboardingText(
                "Play your own tracks",
                25f,
                Color.BLACK,
                true
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            onboardingText(
                "Listen to FLACs and other audio files on your phone",
                15f,
                Color.rgb(105, 105, 105)
            ).apply {
                setPadding(0, dp(8), 0, 0)
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                dp(42)
            )
        )

        // ---------- SECOND SECTION ----------

        root.addView(
            onboardingText(
                "Easily browse through your music",
                25f,
                Color.BLACK,
                true
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            onboardingText(
                "Sort by album, artist, genre, and more",
                15f,
                Color.rgb(105, 105, 105)
            ).apply {
                setPadding(0, dp(8), 0, 0)
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        root.addView(
            onboardingText(
                "By continuing, you agree to the Terms and conditions",
                12f,
                Color.rgb(120, 120, 120)
            ).apply {
                gravity = Gravity.START
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(18)
            }
        )

        root.addView(
            onboardingButton(
                "Continue"
            ) {
                showOnboardingPermissions()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        )

        setContentView(root)
    }

    private fun showOnboardingPermissions() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(
                dp(28),
                dp(38),
                dp(28),
                dp(28)
            )
        }

        root.addView(
            onboardingText(
                "Music player uses these permissions",
                28f,
                Color.BLACK,
                true
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        )

        // ---------- REQUIRED PERMISSIONS ----------

        root.addView(
            onboardingText(
                "Required permissions",
                16f,
                Color.rgb(85, 85, 85),
                true
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                dp(22)
            )
        )

        val musicRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        musicRow.addView(
            onboardingIcon("music"),
            LinearLayout.LayoutParams(
                dp(40),
                dp(40)
            ).apply {
                rightMargin = dp(12)
            }
        )

        musicRow.addView(
            onboardingText(
                "Music and audio",
                20f,
                Color.BLACK,
                true
            ),
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        root.addView(
            musicRow,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            onboardingText(
                "Used to play audio files stored on your phone",
                14f,
                Color.rgb(105, 105, 105)
            ).apply {
                setPadding(dp(52), dp(7), 0, 0)
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                dp(42)
            )
        )

        // ---------- OPTIONAL PERMISSIONS ----------

        root.addView(
            onboardingText(
                "Optional permissions",
                16f,
                Color.rgb(85, 85, 85),
                true
            ),
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                dp(22)
            )
        )

        val notificationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        notificationRow.addView(
            onboardingIcon("notification"),
            LinearLayout.LayoutParams(
                dp(40),
                dp(40)
            ).apply {
                rightMargin = dp(12)
            }
        )

        notificationRow.addView(
            onboardingText(
                "Notifications",
                20f,
                Color.BLACK,
                true
            ),
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        root.addView(
            notificationRow,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            onboardingText(
                "Used to continue and control playback when app is in background and to show notifications about track downloads",
                14f,
                Color.rgb(105, 105, 105)
            ).apply {
                setPadding(dp(52), dp(7), 0, 0)
                setLineSpacing(0f, 1.15f)
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            Space(this),
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        root.addView(
            onboardingButton(
                "Continue"
            ) {
                requestOnboardingPermissions()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        )

        setContentView(root)
    }

    private fun requestOnboardingPermissions() {

        if (!hasAudioPermission()) {
            requestAudioPermission()
        } else {
            requestOptionalPermission()
        }
    }

    private fun requestOptionalPermission() {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {

        getSharedPreferences(
            "music_prefs",
            MODE_PRIVATE
        ).edit()
            .putBoolean("onboarding_complete", true)
            .apply()

        onboardingVisible = false

        loadMusic()
    }

    private fun hasAudioPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= 33)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE

        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val permission =
            if (Build.VERSION.SDK_INT >= 33)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE

        permissionLauncher.launch(permission)
    }

    private fun loadMusic() {
        songs.clear()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->

            val idColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)

            val titleColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            val artistColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (cursor.moveToNext()) {
                songs.add(
                    Song(
                        cursor.getLong(idColumn),
                        cursor.getString(titleColumn) ?: "Unknown",
                        cursor.getString(artistColumn) ?: "Unknown Artist"
                    )
                )
            }
        }

        showMain()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showMain() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, 0, 0, dp(4))
        }

        // Main content host.
        // Home/Search/Library own their scrolling.
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            content,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // ---------- MINI PLAYER ----------

        miniPlayer = createMiniPlayer()

        root.addView(
            miniPlayer,
            LinearLayout.LayoutParams(
                -1,
                dp(60)
            ).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                topMargin = dp(2)
                bottomMargin = dp(3)
            }
        )

        // ---------- BOTTOM NAVIGATION ----------

        root.addView(
            createBottomNavigation(),
            LinearLayout.LayoutParams(
                -1,
                dp(66)
            ).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
                topMargin = 0
                bottomMargin = dp(2)
            }
        )

        setContentView(root)

        showHome()


        loadRecentlyPlayed()

        /*
         * Restore the previous song only after the Mini Player
         * has been created and attached to the hierarchy.
         */
        restorePlayerState()

        playerSaveHandler.removeCallbacks(
            playerSaveRunnable
        )

        playerSaveHandler.post(
            playerSaveRunnable
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

            setBackgroundColor(Color.WHITE)

            elevation = dp(2).toFloat()
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

            updateNavItem(
                navItemView,
                index == activeNavIndex
            )
        }

        miniBottomNavigation = nav

        return nav
    }

    private fun navItem(
        label: String,
        iconRes: Int,
        action: () -> Unit
    ): LinearLayout {

        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true

            setPadding(
                dp(2),
                dp(1),
                dp(2),
                dp(1)
            )

            setOnClickListener {
                action()
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        item.addView(
            iconView,
            LinearLayout.LayoutParams(
                -1,
                dp(28)
            )
        )

        val labelView = TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            includeFontPadding = false
        }

        item.addView(
            labelView,
            LinearLayout.LayoutParams(
                -1,
                dp(18)
            )
        )

        return item
    }

    private fun updateNavItem(
        item: LinearLayout,
        active: Boolean
    ) {

        val iconView =
            item.getChildAt(0) as ImageView

        val labelView =
            item.getChildAt(1) as TextView

        // Clean Spotify-style navigation:
        // no active container/background.
        item.background = null

        if (active) {

            iconView.setColorFilter(
                Color.BLACK,
                android.graphics.PorterDuff.Mode.SRC_IN
            )

            labelView.setTextColor(
                Color.BLACK
            )

            iconView.scaleX = 1.08f
            iconView.scaleY = 1.08f

            labelView.scaleX = 1.02f
            labelView.scaleY = 1.02f

        } else {

            iconView.setColorFilter(
                Color.rgb(
                    125,
                    125,
                    125
                ),
                android.graphics.PorterDuff.Mode.SRC_IN
            )

            labelView.setTextColor(
                Color.rgb(
                    105,
                    105,
                    105
                )
            )

            iconView.scaleX = 1f
            iconView.scaleY = 1f

            labelView.scaleX = 1f
            labelView.scaleY = 1f
        }
    }

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

    private fun getAlbumArt(song: Song): android.graphics.Bitmap? {

        return try {
            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                song.id
            )

            val retriever = MediaMetadataRetriever()

            retriever.setDataSource(
                this,
                uri
            )

            val data = retriever.embeddedPicture

            retriever.release()

            if (data != null) {
                android.graphics.BitmapFactory.decodeByteArray(
                    data,
                    0,
                    data.size
                )
            } else {
                null
            }

        } catch (e: Exception) {
            null
        }
    }



    private fun createRealSongCover(song: Song, sizeDp: Int): ImageView {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(sizeDp),
                dp(sizeDp)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP

            val art = getAlbumArt(song)

            if (art != null) {
                setImageBitmap(art)
            } else {
                setImageResource(android.R.drawable.ic_media_play)
                setColorFilter(Color.DKGRAY)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(235, 235, 235))
                    cornerRadius = dp(10).toFloat()
                }
            }
        }
    }

    private fun createRealSongCard(
        song: Song,
        widthDp: Int,
        imageDp: Int,
        onClick: (() -> Unit)? = null
    ): LinearLayout {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0,
                0,
                0,
                dp(5)
            )

            isClickable = true
            isFocusable = true

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }

            elevation = dp(2).toFloat()

            if (onClick != null) {
                setOnClickListener {
                    onClick.invoke()
                }
            }
        }

        // -------------------------------------------------
        // Artwork
        // -------------------------------------------------

        val coverFrame = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true

            background = GradientDrawable().apply {
                setColor(
                    Color.rgb(
                        235,
                        235,
                        235
                    )
                )
                cornerRadius = dp(16).toFloat()
            }
        }

        val cover = createRealSongCover(
            song,
            imageDp
        ).apply {
            scaleType =
                ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            background =
                GradientDrawable().apply {
                    setColor(
                        Color.rgb(
                            235,
                            235,
                            235
                        )
                    )
                    cornerRadius =
                        dp(16).toFloat()
                }
        }

        coverFrame.addView(
            cover,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )

        // Soft bottom gradient over artwork.
        val artworkShade =
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(
                        75,
                        0,
                        0,
                        0
                    )
                )
            )

        val shade = View(this).apply {
            background = artworkShade
        }

        coverFrame.addView(
            shade,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )

        // Small music badge.
        val badge = TextView(this).apply {
            text = "♫"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = false

            background = GradientDrawable().apply {
                setColor(
                    Color.argb(
                        175,
                        0,
                        0,
                        0
                    )
                )
                shape = GradientDrawable.OVAL
            }
        }

        coverFrame.addView(
            badge,
            FrameLayout.LayoutParams(
                dp(28),
                dp(28),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(9)
                bottomMargin = dp(9)
            }
        )

        card.addView(
            coverFrame,
            LinearLayout.LayoutParams(
                dp(imageDp),
                dp(imageDp)
            )
        )

        // -------------------------------------------------
        // Title
        // -------------------------------------------------

        val title = TextView(this).apply {
            text = song.title
            textSize = 14.5f
            setTextColor(Color.BLACK)

            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END

            includeFontPadding = false

            setPadding(
                dp(4),
                dp(10),
                dp(4),
                0
            )
        }

        card.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                dp(25)
            )
        )

        // -------------------------------------------------
        // Artist
        // -------------------------------------------------

        val artist = TextView(this).apply {
            text = song.artist
            textSize = 12f

            setTextColor(
                Color.rgb(
                    105,
                    105,
                    105
                )
            )

            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END

            includeFontPadding = false

            setPadding(
                dp(4),
                dp(2),
                dp(4),
                0
            )
        }

        card.addView(
            artist,
            LinearLayout.LayoutParams(
                -1,
                dp(20)
            )
        )

        return card
    }

    private fun createRealSongRow(
        song: Song,
        onClick: (() -> Unit)? = null
    ): LinearLayout {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(6),
                dp(6),
                dp(6),
                dp(6)
            )
            isClickable = true
            isFocusable = true

            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(14).toFloat()
            }

            if (onClick != null) {
                setOnClickListener {
                    onClick.invoke()
                }
            }
        }

        val cover = createRealSongCover(
            song,
            58
        ).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(Color.rgb(235, 235, 235))
                cornerRadius = dp(10).toFloat()
            }
        }

        row.addView(
            cover,
            LinearLayout.LayoutParams(
                dp(58),
                dp(58)
            )
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(12),
                0,
                dp(8),
                0
            )
        }

        val title = TextView(this).apply {
            text = song.title
            textSize = 15f
            setTextColor(Color.BLACK)
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        val artist = TextView(this).apply {
            text = song.artist
            textSize = 12.5f
            setTextColor(
                Color.rgb(
                    105,
                    105,
                    105
                )
            )
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(
                0,
                dp(4),
                0,
                0
            )
        }

        info.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                dp(24)
            )
        )

        info.addView(
            artist,
            LinearLayout.LayoutParams(
                -1,
                dp(22)
            )
        )

        row.addView(
            info,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        val more = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(
                Color.rgb(
                    100,
                    100,
                    100
                )
            )
            includeFontPadding = false
        }

        row.addView(
            more,
            LinearLayout.LayoutParams(
                dp(30),
                dp(58)
            )
        )

        return row
    }

    private fun addRealSectionTitle(
        parent: LinearLayout,
        titleText: String
    ) {

        val title = TextView(this).apply {
            text = titleText
            textSize = 21f
            setTextColor(Color.BLACK)

            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            includeFontPadding = false

            setPadding(
                dp(2),
                dp(24),
                dp(2),
                dp(11)
            )
        }

        parent.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )
    }

    private fun showHome() {

        setActiveNavigation(0)

        content.removeAllViews()

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(10),
                dp(18),
                dp(28)
            )
            clipToPadding = false
        }

        // -------------------------------------------------
        // Header
        // -------------------------------------------------

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val greetingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val greeting = TextView(this).apply {
            text = "Good evening"
            textSize = 27f
            setTextColor(Color.BLACK)
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            includeFontPadding = false
        }

        val subtitle = TextView(this).apply {
            text = "Your music, your mood"
            textSize = 13f
            setTextColor(
                Color.rgb(
                    105,
                    105,
                    105
                )
            )
            includeFontPadding = false
            setPadding(
                0,
                dp(5),
                0,
                0
            )
        }

        greetingBox.addView(greeting)
        greetingBox.addView(subtitle)

        header.addView(
            greetingBox,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        val headerAvatar = ImageView(this).apply {
            setImageResource(
                android.R.drawable.ic_menu_myplaces
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                dp(9),
                dp(9),
                dp(9),
                dp(9)
            )
            background = GradientDrawable().apply {
                setColor(
                    Color.rgb(
                        238,
                        238,
                        238
                    )
                )
                shape = GradientDrawable.OVAL
            }
        }

        header.addView(
            headerAvatar,
            LinearLayout.LayoutParams(
                dp(44),
                dp(44)
            )
        )

        page.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        // -------------------------------------------------
        // Quick filter chips
        // -------------------------------------------------

        val chipsScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(
                    0,
                    dp(18),
                    0,
                    dp(2)
                )
            }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addHomeChip(
            textValue: String,
            selected: Boolean = false
        ) {

            val chip = TextView(this).apply {
                text = textValue
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                setPadding(
                    dp(17),
                    dp(9),
                    dp(17),
                    dp(9)
                )

                setTextColor(
                    if (selected) {
                        Color.WHITE
                    } else {
                        Color.BLACK
                    }
                )

                background = GradientDrawable().apply {
                    setColor(
                        if (selected) {
                            Color.BLACK
                        } else {
                            Color.rgb(
                                242,
                                242,
                                242
                            )
                        }
                    )
                    cornerRadius =
                        dp(20).toFloat()
                }
            }

            chips.addView(
                chip,
                LinearLayout.LayoutParams(
                    -2,
                    dp(38)
                ).apply {
                    rightMargin = dp(8)
                }
            )
        }

        addHomeChip(
            "All",
            true
        )
        addHomeChip("Music")
        addHomeChip("Recently played")
        addHomeChip("Made for you")

        chipsScroll.addView(chips)

        page.addView(
            chipsScroll,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            )
        )

        // -------------------------------------------------
        // Recently played
        // -------------------------------------------------

        val recent =
            getRecentlyPlayedSongs()

        if (recent.isNotEmpty()) {

            addRealSectionTitle(
                page,
                "Recently played"
            )

            val recentScroll =
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                }

            val recentRow =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                }

            recent.take(10).forEach { song ->

                val card =
                    createRealSongCard(
                        song,
                        widthDp = 148,
                        imageDp = 148
                    ) {
                        playSong(song)
                    }

                recentRow.addView(
                    card,
                    LinearLayout.LayoutParams(
                        dp(148),
                        -2
                    ).apply {
                        rightMargin = dp(12)
                    }
                )
            }

            recentScroll.addView(recentRow)

            page.addView(
                recentScroll,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )
        }

        // -------------------------------------------------
        // Made for you
        // -------------------------------------------------

        if (songs.isNotEmpty()) {

            addRealSectionTitle(
                page,
                "Made for you"
            )

            val madeForYou =
                songs
                    .drop(10)
                    .take(10)
                    .ifEmpty {
                        songs.take(10)
                    }

            val madeScroll =
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                }

            val madeRow =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                }

            madeForYou.forEach { song ->

                val card =
                    createRealSongCard(
                        song,
                        widthDp = 148,
                        imageDp = 148
                    ) {
                        playSong(song)
                    }

                madeRow.addView(
                    card,
                    LinearLayout.LayoutParams(
                        dp(148),
                        -2
                    ).apply {
                        rightMargin = dp(12)
                    }
                )
            }

            madeScroll.addView(madeRow)

            page.addView(
                madeScroll,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )
        }

        // -------------------------------------------------
        // Your music
        // -------------------------------------------------

        if (songs.isNotEmpty()) {

            addRealSectionTitle(
                page,
                "Your music"
            )

            val musicContainer =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL
                }

            songs.take(12).forEach { song ->

                musicContainer.addView(
                    createRealSongRow(
                        song
                    ) {
                        playSong(song)
                    },
                    LinearLayout.LayoutParams(
                        -1,
                        -2
                    )
                )
            }

            page.addView(
                musicContainer,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )
        }

        if (songs.isEmpty()) {

            val empty = LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    dp(24),
                    dp(80),
                    dp(24),
                    dp(80)
                )
            }

            val emptyTitle = TextView(this).apply {
                text = "No music yet"
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                typeface = Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
            }

            val emptyText = TextView(this).apply {
                text =
                    "Add music to your device to see it here."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(
                    Color.rgb(
                        110,
                        110,
                        110
                    )
                )
                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )
            }

            empty.addView(emptyTitle)
            empty.addView(emptyText)

            page.addView(
                empty,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )
        }

        scroll.addView(page)

        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )
    }

    private fun showLibrary() {

        setActiveNavigation(2)

        content.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        content.addView(root, LinearLayout.LayoutParams(-1, -1))

        // ============================================================
        // HEADER
        // ============================================================

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(18), dp(8))
        }

        header.addView(
            text(
                "Your Library",
                28f,
                Color.rgb(18, 18, 18),
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val add = TextView(this).apply {
            text = "+"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(25, 25, 25))
            setOnClickListener {
                showCreatePlaylistDialog()
            }
        }

        header.addView(
            add,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(-1, dp(70))
        )

        // ============================================================
        // FILTERS
        // ============================================================

        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(18), 0, dp(18), 0)
        }

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOf(
            "Playlists",
            "Artists",
            "Albums",
            "Songs"
        ).forEachIndexed { index, value ->

            val chip = TextView(this).apply {
                text = value
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(17), 0, dp(17), 0)

                setTextColor(
                    if (index == 0) Color.WHITE
                    else Color.rgb(35, 35, 35)
                )

                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(
                        if (index == 0)
                            Color.rgb(25, 25, 25)
                        else
                            Color.rgb(242, 242, 242)
                    )
                }
            }

            filters.addView(
                chip,
                LinearLayout.LayoutParams(-2, dp(38)).apply {
                    rightMargin = dp(8)
                }
            )
        }

        filterScroll.addView(filters)

        root.addView(
            filterScroll,
            LinearLayout.LayoutParams(-1, dp(50))
        )

        // ============================================================
        // SEARCH
        // ============================================================

        val search = EditText(this).apply {
            hint = "Search in your library"
            textSize = 14f
            singleLine = true
            setTextColor(Color.rgb(25, 25, 25))
            setHintTextColor(Color.rgb(105, 105, 105))
            setPadding(dp(17), 0, dp(17), 0)

            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(241, 241, 241))
            }
        }

        root.addView(
            search,
            LinearLayout.LayoutParams(-1, dp(50)).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
        )

        // ============================================================
        // CONTENT
        // ============================================================

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(24))
        }

        scroll.addView(list, ScrollView.LayoutParams(-1, -2))

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // ============================================================
        // PINNED / FAVORITES
        // ============================================================

        val liked = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            setOnClickListener {
                showMultiSelectionFullScreen()
            }
        }

        val likedArt = TextView(this).apply {
            text = "♥"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)

            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(Color.rgb(77, 67, 111))
            }
        }

        liked.addView(
            likedArt,
            LinearLayout.LayoutParams(dp(64), dp(64))
        )

        val likedInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), 0, dp(8), 0)
        }

        likedInfo.addView(
            text(
                "Liked Songs",
                16f,
                Color.rgb(25, 25, 25),
                Typeface.BOLD
            ),
            LinearLayout.LayoutParams(-1, dp(25))
        )

        likedInfo.addView(
            text(
                "Your favorite tracks",
                12f,
                Color.rgb(105, 105, 105),
                Typeface.NORMAL
            ),
            LinearLayout.LayoutParams(-1, dp(21))
        )

        liked.addView(
            likedInfo,
            LinearLayout.LayoutParams(0, dp(64), 1f)
        )

        list.addView(
            liked,
            LinearLayout.LayoutParams(-1, dp(78))
        )

        // ============================================================
        // LIBRARY SECTIONS
        // ============================================================

        val items = listOf(
            Triple("Recently played", "Your latest music", "◷"),
            Triple("My playlists", "Playlists you created", "♫"),
            Triple("Albums", "Saved albums", "▣"),
            Triple("Artists", "Your favorite artists", "●")
        )

        items.forEachIndexed { index, item ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }

            val art = TextView(this).apply {
                text = item.third
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)

                background = GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(
                        when (index) {
                            0 -> Color.rgb(62, 82, 103)
                            1 -> Color.rgb(61, 87, 70)
                            2 -> Color.rgb(103, 77, 63)
                            else -> Color.rgb(76, 76, 76)
                        }
                    )
                }
            }

            row.addView(
                art,
                LinearLayout.LayoutParams(dp(64), dp(64))
            )

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), 0, dp(8), 0)
            }

            info.addView(
                text(
                    item.first,
                    16f,
                    Color.rgb(25, 25, 25),
                    Typeface.BOLD
                ).apply {
                    includeFontPadding = false
                    maxLines = 1
                },
                LinearLayout.LayoutParams(-1, dp(26))
            )

            info.addView(
                text(
                    item.second,
                    12f,
                    Color.rgb(105, 105, 105),
                    Typeface.NORMAL
                ).apply {
                    includeFontPadding = false
                    maxLines = 1
                },
                LinearLayout.LayoutParams(-1, dp(21))
            )

            row.addView(
                info,
                LinearLayout.LayoutParams(0, dp(64), 1f)
            )

            row.addView(
                text(
                    "⋮",
                    24f,
                    Color.rgb(85, 85, 85),
                    Typeface.NORMAL
                ).apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(34), dp(64))
            )

            list.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(78))
            )
        }
    }


    private fun showSearch() {

        setActiveNavigation(1)
        content.removeAllViews()

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(32))
            clipToPadding = false
        }

        // ------------------------------------------------------------
        // Header
        // ------------------------------------------------------------

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "Search"
            textSize = 30f
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }

        val subtitle = TextView(this).apply {
            text = "Find your next favorite song"
            textSize = 13f
            setTextColor(Color.rgb(105, 105, 105))
            includeFontPadding = false
            setPadding(0, dp(5), 0, 0)
        }

        headerText.addView(title)
        headerText.addView(subtitle)

        header.addView(
            headerText,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val searchBadge = TextView(this).apply {
            text = "⌕"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.rgb(244, 244, 244))
                shape = GradientDrawable.OVAL
            }
        }

        header.addView(
            searchBadge,
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        page.addView(
            header,
            LinearLayout.LayoutParams(-1, -2)
        )

        // ------------------------------------------------------------
        // Floating Search Bar
        // ------------------------------------------------------------

        val searchShadow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(7), 0)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), Color.rgb(232, 232, 232))
            }
            elevation = dp(4).toFloat()
        }

        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_search)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(
                Color.rgb(55, 55, 55),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        }

        searchShadow.addView(
            searchIcon,
            LinearLayout.LayoutParams(dp(24), dp(24))
        )

        val searchInput = EditText(this).apply {
            hint = "What do you want to listen to?"
            textSize = 15f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.rgb(115, 115, 115))
            background = null
            singleLine = true
            maxLines = 1
            includeFontPadding = false
            setPadding(dp(10), 0, dp(5), 0)
            imeOptions =
                android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        searchShadow.addView(
            searchInput,
            LinearLayout.LayoutParams(0, -1, 1f)
        )

        val clearButton = TextView(this).apply {
            text = "×"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(90, 90, 90))
            visibility = View.GONE
            includeFontPadding = false

            setOnClickListener {
                searchInput.text.clear()
                searchInput.requestFocus()
            }
        }

        searchShadow.addView(
            clearButton,
            LinearLayout.LayoutParams(dp(32), -1)
        )

        page.addView(
            searchShadow,
            LinearLayout.LayoutParams(-1, dp(56)).apply {
                topMargin = dp(18)
            }
        )

        // ------------------------------------------------------------
        // Results
        // ------------------------------------------------------------

        val resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        page.addView(
            resultsContainer,
            LinearLayout.LayoutParams(-1, -2)
        )

        fun addSearchSectionTitle(textValue: String) {

            val sectionTitle = TextView(this).apply {
                text = textValue
                textSize = 21f
                setTextColor(Color.BLACK)
                typeface =
                    Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )
                includeFontPadding = false
                setPadding(
                    dp(2),
                    dp(25),
                    dp(2),
                    dp(11)
                )
            }

            resultsContainer.addView(sectionTitle)
        }

        // ------------------------------------------------------------
        // Artwork based browse card
        // ------------------------------------------------------------

        fun createBrowseArtwork(
            songsForArtwork: List<Song>,
            fallback: Int
        ): ImageView {

            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(fallback)
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(fallback)
                }
            }

            val song =
                songsForArtwork.firstOrNull()

            if (song != null) {

                val bitmap = getAlbumArt(song)

                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                }
            }

            return image
        }

        fun addBrowseCard(
            parent: LinearLayout,
            label: String,
            songsForArtwork: List<Song>,
            fallback: Int,
            icon: String
        ) {

            val card = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                elevation = dp(2).toFloat()
                clipChildren = true
                clipToPadding = true

                background = GradientDrawable().apply {
                    setColor(fallback)
                    cornerRadius = dp(18).toFloat()
                }
            }

            val artwork = createBrowseArtwork(
                songsForArtwork,
                fallback
            )

            card.addView(
                artwork,
                FrameLayout.LayoutParams(
                    -1,
                    -1
                )
            )

            val shade =
                View(this).apply {
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                Color.argb(15, 0, 0, 0),
                                Color.argb(205, 0, 0, 0)
                            )
                        )
                }

            card.addView(
                shade,
                FrameLayout.LayoutParams(-1, -1)
            )

            val iconBubble = TextView(this).apply {
                text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.argb(115, 0, 0, 0))
                    shape = GradientDrawable.OVAL
                }
            }

            card.addView(
                iconBubble,
                FrameLayout.LayoutParams(
                    dp(44),
                    dp(44)
                ).apply {
                    leftMargin = dp(12)
                    topMargin = dp(12)
                }
            )

            val labelView = TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface =
                    Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )
                includeFontPadding = false
                maxLines = 1
                ellipsize =
                    android.text.TextUtils.TruncateAt.END
                gravity = Gravity.BOTTOM
                setPadding(
                    dp(14),
                    0,
                    dp(14),
                    dp(14)
                )
            }

            card.addView(
                labelView,
                FrameLayout.LayoutParams(
                    -1,
                    dp(54),
                    Gravity.BOTTOM
                )
            )

            parent.addView(
                card,
                LinearLayout.LayoutParams(
                    0,
                    dp(142),
                    1f
                ).apply {
                    rightMargin = dp(8)
                    bottomMargin = dp(9)
                }
            )
        }

        // ------------------------------------------------------------
        // Browse state
        // ------------------------------------------------------------

        fun showBrowseState() {

            resultsContainer.removeAllViews()

            addSearchSectionTitle("Browse all")

            val browseGrid =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL
                }

            val firstRow =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                }

            val artworkA =
                songs.take(1)

            val artworkB =
                if (songs.size > 1)
                    songs.drop(1).take(1)
                else songs.take(1)

            addBrowseCard(
                firstRow,
                "Songs",
                artworkA,
                Color.rgb(215, 229, 246),
                "♫"
            )

            addBrowseCard(
                firstRow,
                "Artists",
                artworkB,
                Color.rgb(230, 215, 239),
                "♪"
            )

            browseGrid.addView(
                firstRow,
                LinearLayout.LayoutParams(
                    -1,
                    dp(151)
                )
            )

            val secondRow =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                }

            val artworkC =
                if (songs.size > 2)
                    songs.drop(2).take(1)
                else songs.take(1)

            val artworkD =
                if (songs.size > 3)
                    songs.drop(3).take(1)
                else songs.take(1)

            addBrowseCard(
                secondRow,
                "Albums",
                artworkC,
                Color.rgb(215, 235, 221),
                "▣"
            )

            addBrowseCard(
                secondRow,
                "Recently played",
                artworkD,
                Color.rgb(244, 225, 204),
                "◷"
            )

            browseGrid.addView(
                secondRow,
                LinearLayout.LayoutParams(
                    -1,
                    dp(151)
                )
            )

            resultsContainer.addView(
                browseGrid
            )

            val recent =
                getRecentlyPlayedSongs()

            if (recent.isNotEmpty()) {

                addSearchSectionTitle(
                    "Recently played"
                )

                recent.take(5).forEach { song ->

                    val row =
                        createRealSongRow(song) {
                            playSong(song)
                        }

                    resultsContainer.addView(
                        row,
                        LinearLayout.LayoutParams(
                            -1,
                            -2
                        )
                    )
                }
            } else if (songs.isNotEmpty()) {

                addSearchSectionTitle(
                    "Your music"
                )

                songs.take(5).forEach { song ->

                    resultsContainer.addView(
                        createRealSongRow(song) {
                            playSong(song)
                        },
                        LinearLayout.LayoutParams(
                            -1,
                            -2
                        )
                    )
                }
            }
        }

        // ------------------------------------------------------------
        // Search results
        // ------------------------------------------------------------

        fun updateResults(query: String) {

            resultsContainer.removeAllViews()

            val normalized =
                query.trim().lowercase()

            if (normalized.isBlank()) {

                clearButton.visibility =
                    View.GONE

                showBrowseState()

                return
            }

            clearButton.visibility =
                View.VISIBLE

            val matches =
                songs.filter { song ->

                    song.title
                        .lowercase()
                        .contains(normalized) ||
                    song.artist
                        .lowercase()
                        .contains(normalized)
                }

            addSearchSectionTitle(
                if (matches.isEmpty())
                    "No results"
                else
                    "${matches.size.coerceAtMost(50)} results"
            )

            if (matches.isEmpty()) {

                val empty =
                    LinearLayout(this).apply {
                        orientation =
                            LinearLayout.VERTICAL
                        gravity =
                            Gravity.CENTER_HORIZONTAL
                        setPadding(
                            dp(20),
                            dp(50),
                            dp(20),
                            dp(60)
                        )
                    }

                val icon =
                    TextView(this).apply {
                        text = "⌕"
                        textSize = 42f
                        gravity = Gravity.CENTER
                        setTextColor(
                            Color.rgb(80, 80, 80)
                        )
                    }

                val emptyTitle =
                    TextView(this).apply {
                        text = "Nothing found"
                        textSize = 19f
                        setTextColor(Color.BLACK)
                        gravity = Gravity.CENTER
                        typeface =
                            Typeface.create(
                                Typeface.DEFAULT,
                                Typeface.BOLD
                            )
                        setPadding(
                            0,
                            dp(9),
                            0,
                            0
                        )
                    }

                val emptyText =
                    TextView(this).apply {
                        text =
                            "Try another song or artist."
                        textSize = 13f
                        setTextColor(
                            Color.rgb(
                                110,
                                110,
                                110
                            )
                        )
                        gravity = Gravity.CENTER
                        setPadding(
                            0,
                            dp(7),
                            0,
                            0
                        )
                    }

                empty.addView(icon)
                empty.addView(emptyTitle)
                empty.addView(emptyText)

                resultsContainer.addView(empty)

            } else {

                matches.take(50).forEach { song ->

                    val row =
                        createRealSongRow(song) {
                            playSong(song)
                        }

                    resultsContainer.addView(
                        row,
                        LinearLayout.LayoutParams(
                            -1,
                            -2
                        )
                    )

                    row.alpha = 0f
                    row.translationY = dp(8).toFloat()

                    row.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(180)
                        .setStartDelay(
                            (
                                resultsContainer.childCount
                                    .coerceAtMost(8) * 18
                            ).toLong()
                        )
                        .start()
                }
            }
        }

        // ------------------------------------------------------------
        // Input
        // ------------------------------------------------------------

        searchInput.addTextChangedListener(
            object : android.text.TextWatcher {

                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    updateResults(
                        text?.toString() ?: ""
                    )
                }

                override fun afterTextChanged(
                    text: android.text.Editable?
                ) {}
            }
        )

        searchInput.setOnEditorActionListener {
            _, actionId, _ ->

            if (
                actionId ==
                android.view.inputmethod.EditorInfo
                    .IME_ACTION_SEARCH
            ) {

                val imm =
                    getSystemService(
                        android.content.Context
                            .INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod
                        .InputMethodManager

                imm.hideSoftInputFromWindow(
                    searchInput.windowToken,
                    0
                )

                true
            } else {
                false
            }
        }

        // Initial state
        showBrowseState()

        scroll.addView(page)

        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )
    }

    private fun createMiniPlayer(): LinearLayout {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(8), dp(6))

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    shape =
                        android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.rgb(248, 248, 248))
                }

            elevation = dp(2).toFloat()
        }

        // ---------- ALBUM ART ----------
        miniCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: android.view.View,
                        outline: android.graphics.Outline
                    ) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height,
                            dp(8).toFloat()
                        )
                    }
                }

            background =
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.rgb(95, 95, 95),
                        Color.rgb(28, 28, 28)
                    )
                ).apply {
                    cornerRadius = dp(8).toFloat()
                }
        }

        layout.addView(
            miniCover,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            )
        )

        // ---------- SONG INFO ----------
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(4), 0)
        }

        miniTitle = text(
            "Nothing Playing",
            13f,
            Color.rgb(18, 18, 18),
            Typeface.BOLD
        ).apply {
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        miniArtist = text(
            "Choose a song",
            11f,
            Color.rgb(105, 105, 105),
            Typeface.NORMAL
        ).apply {
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        info.addView(
            miniTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(20)
            )
        )

        info.addView(
            miniArtist,
            LinearLayout.LayoutParams(
                -1,
                dp(18)
            )
        )

        layout.addView(
            info,
            LinearLayout.LayoutParams(
                0,
                -1,
                1f
            )
        )

        // ---------- PLAY / PAUSE ----------
        playButton = TextView(this).apply {
            text = "▶"
            textSize = 21f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = null

            setOnClickListener {
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) {
                            it.pause()
                            playButton.text = "▶"
                        } else {
                            it.start()
                            playButton.text = "Ⅱ"
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        layout.addView(
            playButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            )
        )

        // ---------- OPEN NOW PLAYING ----------
        miniCover.setOnClickListener {
            if (currentSong != null) {
                showNowPlaying()
            }
        }

        miniTitle.setOnClickListener {
            if (currentSong != null) {
                showNowPlaying()
            }
        }

        miniArtist.setOnClickListener {
            if (currentSong != null) {
                showNowPlaying()
            }
        }

        setupMiniPlayerGestures(layout)

        return layout
    }

    private fun findAdjacentSong(
        currentId: Long,
        next: Boolean
    ): Song? {

        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )

        try {

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->

                val idColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media._ID
                    )

                val titleColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.TITLE
                    )

                val artistColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.ARTIST
                    )

                while (cursor.moveToNext()) {

                    songs.add(
                        Song(
                            cursor.getLong(idColumn),
                            cursor.getString(titleColumn) ?: "Unknown",
                            cursor.getString(artistColumn) ?: "Unknown Artist"
                        )
                    )
                }
            }

        } catch (_: Exception) {
            return null
        }

        val index =
            songs.indexOfFirst {
                it.id == currentId
            }

        if (index < 0) {
            return null
        }

        val target =
            if (next) index + 1 else index - 1

        if (target !in songs.indices) {
            return null
        }

        return songs[target]
    }

    private fun fullMiniExpansionProgress(): Float {
        val card = miniExpansionCard
            ?: return 0f

        val screenHeight =
            resources.displayMetrics.heightPixels
                .toFloat()

        val miniHeight =
            miniPlayer.height.coerceAtLeast(dp(60))

        val maxUp =
            (
                screenHeight -
                    miniHeight
            ).coerceAtLeast(1f)

        val top =
            (
                card.layoutParams
                    as? FrameLayout.LayoutParams
            )?.topMargin
                ?: miniPlayerTopInDecor().toInt()

        val upward =
            (
                miniPlayerTopInDecor() -
                    top
            ).coerceAtLeast(0f)

        return (
            upward /
                maxUp
        )
            .coerceIn(0f, 1f)
    }

    private fun getCrossFadeValue(): Int {
        return getSettingsPrefs()
            .getInt("cross_fade_seconds", 0)
            .coerceIn(0, 12)
    }

    private fun getPlaybackSpeedLabel(): String {

        val speed =
            getSettingsPrefs()
                .getFloat(
                    "playback_speed",
                    1.0f
                )

        return "${speed}x"
    }

    private fun getPlaybackSpeedValue(): Float {
        return getSettingsPrefs()
            .getFloat("playback_speed", 1.0f)
            .coerceIn(0.5f, 2.0f)
    }

    private fun getSettingsPrefs() =
        getSharedPreferences(
            "music_settings",
            MODE_PRIVATE
        )

    private fun getSleepTimerLabel(): String {

        val minutes =
            getSettingsPrefs()
                .getInt("sleep_timer_minutes", 0)

        return if (minutes <= 0)
            "Off"
        else
            "$minutes min"
    }

    private fun miniPlayerTopInDecor(): Float {

        val location =
            IntArray(2)

        val decorLocation =
            IntArray(2)

        miniPlayer.getLocationOnScreen(
            location
        )

        window.decorView.getLocationOnScreen(
            decorLocation
        )

        return (
            location[1] -
            decorLocation[1]
        ).toFloat()
    }

    private fun playMiniNext() {

        val current = currentSong ?: return

        val index =
            playbackQueue.indexOfFirst {
                it.id == current.id
            }

        if (index >= 0 && index < playbackQueue.lastIndex) {
            playSong(playbackQueue[index + 1])
            return
        }

        /*
         * If queue only contains current song, try the
         * currently loaded Library list through MediaStore.
         */
        val next = findAdjacentSong(current.id, true)

        if (next != null) {
            playSong(next, smoothMiniChange = true, miniTextDirection = 1)
        }
    }

    private fun playMiniNextAnimated() {

        val current =
            currentSong
                ?: return

        val index =
            playbackQueue.indexOfFirst {
                it.id == current.id
            }

        val next =
            if (
                index >= 0 &&
                index < playbackQueue.lastIndex
            ) {
                playbackQueue[index + 1]
            } else {
                findAdjacentSong(
                    current.id,
                    true
                )
            }

        if (next == null) {
            return
        }

        playSong(
            next,
            smoothMiniChange = true,
            miniTextDirection = 1
        )
    }

    private fun playMiniPrevious() {

        val current = currentSong ?: return

        val index =
            playbackQueue.indexOfFirst {
                it.id == current.id
            }

        if (index > 0) {
            playSong(playbackQueue[index - 1])
            return
        }

        val previous = findAdjacentSong(current.id, false)

        if (previous != null) {
            playSong(previous, smoothMiniChange = true, miniTextDirection = -1)
        }
    }

    private fun playMiniPreviousAnimated() {

        val current =
            currentSong
                ?: return

        val index =
            playbackQueue.indexOfFirst {
                it.id == current.id
            }

        val previous =
            if (index > 0) {
                playbackQueue[index - 1]
            } else {
                findAdjacentSong(
                    current.id,
                    false
                )
            }

        if (previous == null) {
            return
        }

        playSong(
            previous,
            smoothMiniChange = true,
            miniTextDirection = -1
        )
    }

    private fun removeMiniExpansionCard() {

        miniExpansionCard?.let { card ->

            (card.parent as? ViewGroup)
                ?.removeView(card)
        }

        miniExpansionCard = null
        miniExpansionCover = null
        miniExpansionTitle = null
        miniExpansionArtist = null

        if (::miniPlayer.isInitialized) {
            miniPlayer.alpha = 1f
        }

        miniBottomNavigation?.let { nav ->
            nav.translationY = 0f
            nav.alpha = 1f
        }
        miniFullTargetCenterX = Float.NaN
        miniFullTargetCenterY = Float.NaN
        miniFullTargetSize = 0

    }

    private fun restorePlayerState() {

        val songId =
            playerPrefs.getLong("song_id", -1L)

        if (songId <= 0L) {
            return
        }

        val savedPosition =
            playerPrefs.getInt("position", 0)

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )

        try {

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(songId.toString()),
                null
            )?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val song =
                        Song(
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(
                                    MediaStore.Audio.Media._ID
                                )
                            ),
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    MediaStore.Audio.Media.TITLE
                                )
                            ) ?: "Unknown",
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    MediaStore.Audio.Media.ARTIST
                                )
                            ) ?: "Unknown Artist"
                        )

                    playSong(
                        song,
                        startPosition = savedPosition
                    )
                }
            }

        } catch (_: Exception) {
        }
    }

    private fun loadRecentlyPlayed() {

        recentSongIds.clear()

        val stored =
            playerPrefs.getString(
                "recent_song_ids",
                ""
            ) ?: ""

        if (stored.isNotBlank()) {

            stored
                .split(",")
                .forEach { value ->

                    value
                        .toLongOrNull()
                        ?.let { id ->
                            recentSongIds.add(id)
                        }
                }
        }
    }

    private fun recordRecentlyPlayed(song: Song) {

        recentSongIds.remove(song.id)
        recentSongIds.add(0, song.id)

        while (recentSongIds.size > 20) {
            recentSongIds.removeAt(
                recentSongIds.lastIndex
            )
        }

        playerPrefs.edit()
            .putString(
                "recent_song_ids",
                recentSongIds.joinToString(",")
            )
            .apply()
    }

    private fun getRecentlyPlayedSongs(): List<Song> {

        if (recentSongIds.isEmpty()) {
            loadRecentlyPlayed()
        }

        val byId =
            songs.associateBy { it.id }

        return recentSongIds.mapNotNull { id ->
            byId[id]
        }
    }

    private fun savePlayerState() {

        val song = currentSong ?: return

        val position =
            try {
                mediaPlayer?.currentPosition ?: 0
            } catch (_: Exception) {
                0
            }

        playerPrefs.edit()
            .putLong("song_id", song.id)
            .putInt("position", position)
            .apply()
    }

    private fun scheduleMiniHide(layout: View) {

        cancelMiniHide()

        val runnable = Runnable {

            if (!miniGestureDragging) {
                return@Runnable
            }

            layout.animate()
                .alpha(0f)
                .translationY(dp(18).toFloat())
                .setDuration(420L)
                .setInterpolator(
                    android.view.animation.DecelerateInterpolator()
                )
                .withEndAction {
                    layout.visibility = View.INVISIBLE
                    layout.translationY = 0f
                    layout.alpha = 1f

                    miniCover.rotation = 0f
                    miniCover.scaleX = 1f
                    miniCover.scaleY = 1f
                }
                .start()

            miniGestureDragging = false
        }

        miniHideRunnable = runnable
        layout.postDelayed(runnable, 3000L)
    }

    private fun setupMiniPlayerGestures(
        layout: LinearLayout
    ) {

        val touchSlop =
            android.view.ViewConfiguration
                .get(this)
                .scaledTouchSlop

        val gestureListener =
            View.OnTouchListener { _, event ->

                when (event.actionMasked) {

                    android.view.MotionEvent.ACTION_DOWN -> {

                        miniGestureDownX = event.rawX
                        miniGestureDownY = event.rawY
                        miniGestureLastX = event.rawX
                        miniGestureLastY = event.rawY

                        miniGestureDragging = false
                        miniGestureVertical = false

                        cancelMiniHide()

                        false
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {

                        val dx =
                            event.rawX - miniGestureDownX

                        val dy =
                            event.rawY - miniGestureDownY

                        if (!miniGestureDragging) {

                            if (
                                kotlin.math.abs(dx) > touchSlop ||
                                kotlin.math.abs(dy) > touchSlop
                            ) {

                                miniGestureDragging = true

                                miniGestureVertical =
                                    kotlin.math.abs(dy) >
                                    kotlin.math.abs(dx)

                                if (miniGestureVertical) {
                                    /*
                                     * Mini -> Full transition exists ONLY
                                     * for an upward gesture.
                                     *
                                     * A downward drag while Mini is visible
                                     * must not create the Full Player background
                                     * or change the Mini Player colors.
                                     */
                                    if (dy < 0f) {
                                        createMiniExpansionCard()
                                    }
                                }
                            }
                        }

                        if (!miniGestureDragging) {
                            return@OnTouchListener false
                        }

                        if (miniGestureVertical) {

                            /*
                             * Downward drag from Mini is intentionally inert.
                             * Do not create, colorize, move or fade anything.
                             */
                            if (dy >= 0f) {
                                layout.translationX = 0f
                                layout.translationY = 0f
                                miniPlayer.alpha = 1f

                                miniCover.rotation = 0f
                                miniCover.scaleX = 1f
                                miniCover.scaleY = 1f

                                miniGestureLastY = event.rawY

                                true
                            } else {

                                updateMiniExpansionCard(
                                    dy = dy
                                )

                                val screenHeight =
                                    resources.displayMetrics.heightPixels
                                        .toFloat()

                                val miniHeight =
                                    miniPlayer.height.coerceAtLeast(dp(60))

                                val maxUp =
                                    (
                                        screenHeight -
                                            miniHeight
                                    ).coerceAtLeast(1f)

                                val upward =
                                    (-dy)
                                        .coerceAtLeast(0f)
                                        .coerceAtMost(maxUp)

                                val progress =
                                    (
                                        upward / maxUp
                                    ).coerceIn(0f, 1f)

                                miniFullTransitionProgress = progress

                                updateMiniFullTransition(
                                    progress
                                )

                                miniGestureLastY = event.rawY

                                true
                            }

                        } else {

                            /*
                             * IMPORTANT:
                             * The Mini Player itself does NOT move.
                             */
                            layout.translationX = 0f
                            layout.translationY = 0f

                            miniCover.rotation = 0f
                            miniCover.scaleX = 1f
                            miniCover.scaleY = 1f

                            true
                        }
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {

                        cancelMiniHide()

                        if (!miniGestureDragging) {
                            return@OnTouchListener false
                        }

                        val dx =
                            event.rawX - miniGestureDownX

                        val dy =
                            event.rawY - miniGestureDownY

                        if (miniGestureVertical) {

                            val screenHeight =
                                resources.displayMetrics
                                    .heightPixels
                                    .toFloat()

                            val maxUp =
                                (
                                    screenHeight -
                                    miniPlayer.height
                                ).coerceAtLeast(1f)

                            val upward =
                                (-dy).coerceAtLeast(0f)

                            val progress =
                                (
                                    upward / maxUp
                                ).coerceIn(0f, 1f)

                            if (progress >= 0.5f) {

                                completeMiniExpansion(
                                    layout
                                )

                            } else {

                                cancelMiniExpansion(
                                    layout
                                )
                            }

                            miniGestureDragging = false
                            true

                        } else {

                            /*
                             * Horizontal swipe:
                             * NO Mini Player movement.
                             */
                            layout.translationX = 0f
                            layout.translationY = 0f

                            if (
                                dx <
                                -dp(70)
                            ) {

                                playMiniNextAnimated()

                            } else if (
                                dx >
                                dp(70)
                            ) {

                                playMiniPreviousAnimated()
                            }

                            miniGestureDragging = false
                            true
                        }
                    }

                    else -> false
                }
            }

        layout.setOnTouchListener(
            gestureListener
        )

        miniCover.setOnTouchListener(
            gestureListener
        )

        miniTitle.setOnTouchListener(
            gestureListener
        )

        miniArtist.setOnTouchListener(
            gestureListener
        )

        layout.isClickable = true
        layout.isFocusable = false
    }

    private fun showAboutMusicDialog() {

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(
                    Color.rgb(
                        246,
                        246,
                        246
                    )
                )
                setPadding(
                    dp(20),
                    dp(22),
                    dp(20),
                    dp(24)
                )
            }

        val dialog =
            android.app.Dialog(this)

        val header =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        header.addView(
            text(
                "‹",
                38f,
                Color.BLACK,
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false

                setOnClickListener {
                    dialog.dismiss()
                }
            },
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        header.addView(
            text(
                "About Music",
                27f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            ).apply {
                bottomMargin = dp(20)
            }
        )

        val card =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                gravity =
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    dp(20),
                    dp(36),
                    dp(20),
                    dp(36)
                )

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius =
                            dp(16).toFloat()
                    }
            }

        card.addView(
            text(
                "Music",
                34f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val version =
            try {
                packageManager
                    .getPackageInfo(
                        packageName,
                        0
                    )
                    .versionName
                    ?: "1.0"
            } catch (_: Exception) {
                "1.0"
            }

        card.addView(
            text(
                "Version $version",
                14f,
                Color.BLACK,
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(
                    0,
                    dp(10),
                    0,
                    0
                )
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        card.addView(
            text(
                "You're using the latest version",
                15f,
                Color.BLACK,
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(
                    0,
                    dp(22),
                    0,
                    0
                )
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            card,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        dialog.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        dialog.setContentView(root)
        dialog.show()

        dialog.window?.setLayout(
            -1,
            -1
        )
    }

    private fun showCreatePlaylistDialog() {

        val input = EditText(this).apply {
            hint = "Playlist name"
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Create playlist")
            .setView(input)
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Create"
            ) { _, _ ->

                val name =
                    input.text
                        .toString()
                        .trim()

                if (name.isNotEmpty()) {

                    Toast.makeText(
                        this,
                        "Playlist \"$name\" created",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showManagePlaylistsDialog() {
        showSettingFullScreen(
            "Manage Playlists",
            "Create and manage playlists"
        ) {}
    }

    private fun showManageTabsDialog() {
        showSettingFullScreen(
            "Manage tabs",
            "Home, Library, Settings"
        ) {}
    }

    private fun showMiniPlayer() {

        if (!::miniPlayer.isInitialized) {
            return
        }

        cancelMiniHide()

        miniPlayer.visibility = View.VISIBLE
        miniPlayer.animate()
            .cancel()

        miniPlayer.alpha = 0f
        miniPlayer.translationY = dp(10).toFloat()

        miniCover.rotation = 0f
        miniCover.scaleX = 1f
        miniCover.scaleY = 1f

        miniPlayer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(
                android.view.animation.DecelerateInterpolator()
            )
            .start()
    }

    private fun showMultiSelectionFullScreen(
        title: String,
        options: List<String>,
        selected: MutableList<Boolean>,
        onChanged: (List<Boolean>) -> Unit
    ) {
        val dialog = android.app.Dialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 246, 246))
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(this).apply {
            text = "←"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(12), 0)
            setOnClickListener {
                onChanged(selected.toList())
                dialog.dismiss()
            }
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(52)))
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
        }

        val cardTitle = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
        }

        card.addView(
            cardTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        options.forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
                isClickable = true
                isFocusable = true
            }

            val indicator = TextView(this).apply {
                text = if (selected.getOrNull(index) == true) "●" else "○"
                textSize = 23f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            }

            val label = TextView(this).apply {
                text = option
                textSize = 17f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
            }

            row.addView(indicator, LinearLayout.LayoutParams(dp(38), dp(52)))
            row.addView(
                label,
                LinearLayout.LayoutParams(0, dp(52), 1f)
            )

            row.setOnClickListener {
                selected[index] = !selected[index]
                indicator.text = if (selected[index]) "●" else "○"
            }

            card.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(72)
                )
            )
        }

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun showPermissionsDialog() {

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(
                    Color.rgb(
                        246,
                        246,
                        246
                    )
                )
                setPadding(
                    dp(20),
                    dp(22),
                    dp(20),
                    dp(24)
                )
            }

        val header =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val dialog =
            android.app.Dialog(this)

        header.addView(
            text(
                "‹",
                38f,
                Color.BLACK,
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false

                setOnClickListener {
                    dialog.dismiss()
                }
            },
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        header.addView(
            text(
                "Permissions",
                27f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            ).apply {
                bottomMargin = dp(20)
            }
        )

        val card =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius =
                            dp(16).toFloat()
                    }

                clipToOutline = true
            }

        fun addPermissionRow(
            titleValue: String,
            descriptionValue: String,
            granted: Boolean
        ) {

            val row =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        dp(18),
                        dp(14),
                        dp(18),
                        dp(14)
                    )
                }

            row.addView(
                text(
                    titleValue,
                    16f,
                    Color.BLACK,
                    Typeface.BOLD
                ).apply {
                    includeFontPadding = false
                }
            )

            row.addView(
                text(
                    descriptionValue,
                    13f,
                    Color.BLACK,
                    Typeface.NORMAL
                ).apply {
                    includeFontPadding = false
                    setPadding(
                        0,
                        dp(5),
                        0,
                        0
                    )
                }
            )

            row.addView(
                text(
                    if (granted)
                        "Allowed"
                    else
                        "Not allowed",
                    13f,
                    Color.BLACK,
                    Typeface.BOLD
                ).apply {
                    includeFontPadding = false
                    setPadding(
                        0,
                        dp(8),
                        0,
                        0
                    )
                }
            )

            card.addView(
                row,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )
        }

        val musicGranted =
            if (Build.VERSION.SDK_INT >= 33) {
                checkSelfPermission(
                    android.Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                checkSelfPermission(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }

        val notificationGranted =
            if (Build.VERSION.SDK_INT >= 33) {
                checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        addPermissionRow(
            "Music and audio",
            "Allows Music to read audio files stored on your device.",
            musicGranted
        )

        card.addView(
            View(this).apply {
                setBackgroundColor(
                    Color.rgb(
                        232,
                        232,
                        232
                    )
                )
            },
            LinearLayout.LayoutParams(
                -1,
                1
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            }
        )

        addPermissionRow(
            "Notifications",
            "Allows Music to show playback and music notifications.",
            notificationGranted
        )

        root.addView(
            card,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        val manage =
            text(
                "Open system settings",
                15f,
                Color.WHITE,
                Typeface.BOLD
            ).apply {
                gravity = Gravity.CENTER

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.BLACK)
                        cornerRadius =
                            dp(14).toFloat()
                    }

                setOnClickListener {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings
                                .ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse(
                                "package:$packageName"
                            )
                        )
                    )
                }
            }

        root.addView(
            manage,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            ).apply {
                topMargin = dp(20)
            }
        )

        dialog.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )

        dialog.setContentView(root)
        dialog.show()




        dialog.window?.setLayout(
            -1,
            -1
        )
    }

    private fun showPlaybackSpeedDialog() {
        showSettingFullScreen(
            "Play speed",
            getPlaybackSpeedLabel()
        ) {}
    }

    private fun showQueueSettingsDialog() {
        showSettingFullScreen(
            "Queue settings",
            "Playback queue"
        ) {}
    }

    private fun showSelectionFullScreen(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val dialog = android.app.Dialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 246, 246))
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(this).apply {
            text = "←"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { dialog.dismiss() }
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(52)))
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
        }

        val cardTitle = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
        }

        card.addView(
            cardTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        options.forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
                isClickable = true
                isFocusable = true
            }

            val indicator = TextView(this).apply {
                text = if (index == selectedIndex) "●" else "○"
                textSize = 23f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            }

            val label = TextView(this).apply {
                text = option
                textSize = 17f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
            }

            row.addView(indicator, LinearLayout.LayoutParams(dp(38), dp(52)))
            row.addView(
                label,
                LinearLayout.LayoutParams(0, dp(52), 1f)
            )

            row.setOnClickListener {
                onSelected(index)
                dialog.dismiss()
            }

            card.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(72)
                )
            )
        }

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun showSettingFullScreen(
        titleValue: String,
        value: String,
        action: () -> Unit
    ) {
        when (titleValue) {

            "Sleep timer" -> {
                val options = listOf(
                    "Off",
                    "15 minutes",
                    "30 minutes",
                    "45 minutes",
                    "60 minutes",
                    "90 minutes"
                )

                val current = getSettingsPrefs()
                    .getInt("sleep_timer_minutes", 0)

                val selected = when (current) {
                    15 -> 1
                    30 -> 2
                    45 -> 3
                    60 -> 4
                    90 -> 5
                    else -> 0
                }

                showSelectionFullScreen(
                    "Sleep timer",
                    options,
                    selected
                ) { which ->
                    val minutes = when (which) {
                        1 -> 15
                        2 -> 30
                        3 -> 45
                        4 -> 60
                        5 -> 90
                        else -> 0
                    }

                    getSettingsPrefs()
                        .edit()
                        .putInt("sleep_timer_minutes", minutes)
                        .apply()

                    if (minutes > 0) {
                        android.os.Handler(mainLooper).postDelayed({
                            if (minutes ==
                                getSettingsPrefs()
                                    .getInt("sleep_timer_minutes", 0)
                            ) {
                                mediaPlayer?.pause()
                            }
                        }, minutes * 60_000L)
                    }

                    showSettings()
                }
            }

            "Play speed" -> {
                val options = listOf(
                    "0.5x",
                    "0.75x",
                    "1.0x",
                    "1.25x",
                    "1.5x",
                    "1.75x",
                    "2.0x"
                )

                val speed = getSettingsPrefs()
                    .getFloat("playback_speed", 1.0f)

                val selected = when (speed) {
                    0.5f -> 0
                    0.75f -> 1
                    1.25f -> 3
                    1.5f -> 4
                    1.75f -> 5
                    2.0f -> 6
                    else -> 2
                }

                showSelectionFullScreen(
                    "Play speed",
                    options,
                    selected
                ) { which ->
                    val speeds = floatArrayOf(
                        0.5f,
                        0.75f,
                        1.0f,
                        1.25f,
                        1.5f,
                        1.75f,
                        2.0f
                    )

                    getSettingsPrefs()
                        .edit()
                        .putFloat(
                            "playback_speed",
                            speeds[which]
                        )
                        .apply()

                    applyPlaybackSpeed()
                    showSettings()
                }
            }

            "Queue settings" -> {
                val selected =
                    if (
                        getSettingsPrefs()
                            .getBoolean(
                                "no_duplicate_songs",
                                false
                            )
                    ) 0 else -1

                showSelectionFullScreen(
                    "Queue settings",
                    listOf("Don't allow duplicate songs"),
                    selected
                ) { which ->
                    getSettingsPrefs()
                        .edit()
                        .putBoolean(
                            "no_duplicate_songs",
                            which == 0
                        )
                        .apply()

                    showSettings()
                }
            }

            "Manage tabs" -> {
                val prefs = getSettingsPrefs()

                showMultiSelectionFullScreen(
                    "Manage tabs",
                    listOf(
                        "Home",
                        "Library",
                        "Settings"
                    ),
                    mutableListOf(
                        prefs.getBoolean("tab_home", true),
                        prefs.getBoolean("tab_library", true),
                        prefs.getBoolean("tab_settings", true)
                    )
                ) { checked ->
                    prefs.edit()
                        .putBoolean("tab_home", checked[0])
                        .putBoolean("tab_library", checked[1])
                        .putBoolean("tab_settings", checked[2])
                        .apply()

                    showSettings()
                }
            }

            "Manage Playlists" -> {
                showSelectionFullScreen(
                    "Manage Playlists",
                    listOf(
                        "Create playlist",
                        "My playlists"
                    ),
                    0
                ) { which ->
                    when (which) {
                        0 -> showCreatePlaylistDialog()

                        1 -> Toast.makeText(
                            this,
                            "My playlists",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            else -> {
                action()
            }
        }
    }

    private fun showSleepTimerDialog() {
        showSettingFullScreen(
            "Sleep timer",
            getSleepTimerLabel()
        ) {}
    }

    private fun toggleDarkMode() {

        val prefs = getSettingsPrefs()

        val enabled =
            !prefs.getBoolean(
                "dark_mode",
                false
            )

        prefs.edit()
            .putBoolean(
                "dark_mode",
                enabled
            )
            .apply()

        Toast.makeText(
            this,
            if (enabled)
                "Dark mode enabled"
            else
                "Dark mode disabled",
            Toast.LENGTH_SHORT
        ).show()

        showSettings()
    }

    private fun toggleSetting(
        key: String,
        titleValue: String
    ) {

        val prefs = getSettingsPrefs()

        val newValue =
            !prefs.getBoolean(key, false)

        prefs.edit()
            .putBoolean(key, newValue)
            .apply()

        Toast.makeText(
            this,
            "$titleValue: ${if (newValue) "On" else "Off"}",
            Toast.LENGTH_SHORT
        ).show()

        showSettings()
    }

    private fun updateMiniFullTransition(
        progress: Float
    ) {
        val p =
            progress.coerceIn(0f, 1f)

        miniFullTransitionProgress = p

        /*
         * Navigation leaves together with the Expansion card.
         */
        miniBottomNavigation?.let { nav ->
            nav.translationY =
                dp(82).toFloat() * p

            nav.alpha =
                1f - p
        }

        if (miniExpansionCard != null) {
            miniPlayer.alpha = 0f
        }

        /*
         * During 0 -> 50% ONLY artwork/background is visible.
         */
        miniExpansionCover?.apply {
            alpha = 1f
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
        }

        miniExpansionTitle?.alpha = 0f
        miniExpansionArtist?.alpha = 0f

        /*
         * Create the real Full Player exactly at the 50%
         * handoff. It remains invisible until the second half.
         */
        if (
            p >= 0.5f &&
            miniTransitionDialog == null &&
            currentSong != null
        ) {
            miniExpansionOpening = true
            showNowPlaying()
            miniExpansionOpening = false
        }

        val root =
            miniTransitionRoot
                ?: return

        val secondHalf =
            ((p - 0.5f) / 0.5f)
                .coerceIn(0f, 1f)

        val eased =
            secondHalf *
                secondHalf *
                (3f - 2f * secondHalf)

        root.alpha = eased

        miniTransitionFullCover?.apply {
            alpha = eased
            scaleX = 1f
            scaleY = 1f
            rotation = 0f
        }

        miniTransitionInfo?.apply {
            translationY =
                dp(32).toFloat() *
                    (1f - eased)
            alpha = eased
        }

        miniTransitionSeekBar?.apply {
            translationY =
                dp(20).toFloat() *
                    (1f - eased) -
                    dp(6).toFloat()
            alpha = eased
        }

        miniTransitionTimeRow?.apply {
            translationY =
                dp(20).toFloat() *
                    (1f - eased)
            alpha = eased
        }

        miniTransitionControls?.apply {
            translationY =
                dp(38).toFloat() *
                    (1f - eased) -
                    dp(10).toFloat()
            alpha = eased
        }

        miniTransitionSecondary?.apply {
            translationY =
                dp(44).toFloat() *
                    (1f - eased)
            alpha = eased
        }
    }

    private fun showSettings() {

        content.removeAllViews()
        content.setPadding(
            dp(20),
            dp(18),
            dp(20),
            dp(26)
        )
        content.setBackgroundColor(Color.rgb(246, 246, 246))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.rgb(246, 246, 246))
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
            setBackgroundColor(Color.rgb(246, 246, 246))
        }

        page.addView(
            text(
                "Settings",
                32f,
                Color.rgb(24, 23, 27),
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
                letterSpacing = -0.02f
            },
            LinearLayout.LayoutParams(-1, dp(46)).apply {
                bottomMargin = dp(18)
            }
        )

        // ---------- PLAYBACK ----------

        addModernSettingsSection(
            page,
            "Playback"
        )

        addModernSettingsCard(
            page,
            listOf(
                ModernSetting(
                    "Sleep timer",
                    getSleepTimerLabel()
                ) {
                    showSleepTimerDialog()
                },
                ModernSetting(
                    "Play speed",
                    getPlaybackSpeedLabel()
                ) {
                    showPlaybackSpeedDialog()
                },
                ModernSetting(
                    "Cross fade",
                    if (getSettingsPrefs().getBoolean(
                            "cross_fade",
                            false
                        )
                    ) "On" else "Off"
                ) {
                    toggleSetting(
                        "cross_fade",
                        "Cross fade"
                    )
                },
                ModernSetting(
                    "Skip silence between tracks",
                    if (getSettingsPrefs().getBoolean(
                            "skip_silence",
                            false
                        )
                    ) "On" else "Off"
                ) {
                    toggleSetting(
                        "skip_silence",
                        "Skip silence between tracks"
                    )
                },
                ModernSetting(
                    "Control music from lock screen",
                    if (getSettingsPrefs().getBoolean(
                            "lock_screen_controls",
                            true
                        )
                    ) "On" else "Off"
                ) {
                    toggleSetting(
                        "lock_screen_controls",
                        "Control music from lock screen"
                    )
                }
            )
        )

        // ---------- PLAYLIST ----------

        addModernSettingsSection(
            page,
            "Playlist"
        )

        addModernSettingsCard(
            page,
            listOf(
                ModernSetting(
                    "Queue settings",
                    "Playback queue"
                ) {
                    showQueueSettingsDialog()
                },
                ModernSetting(
                    "Don't allow duplicate songs",
                    if (getSettingsPrefs().getBoolean(
                            "no_duplicate_songs",
                            false
                        )
                    ) "On" else "Off"
                ) {
                    toggleSetting(
                        "no_duplicate_songs",
                        "Don't allow duplicate songs"
                    )
                },
                ModernSetting(
                    "Manage Playlists",
                    "Create and manage playlists"
                ) {
                    showManagePlaylistsDialog()
                }
            )
        )

        // ---------- GENERAL ----------

        addModernSettingsSection(
            page,
            "General"
        )

        addModernSettingsCard(
            page,
            listOf(
                ModernSetting(
                    "Manage tabs",
                    "Home, Library, Settings"
                ) {
                    showManageTabsDialog()
                },
                ModernSetting(
                    "Dark mode",
                    if (getSettingsPrefs().getBoolean(
                            "dark_mode",
                            false
                        )
                    ) "On" else "Off"
                ) {
                    toggleDarkMode()
                },
                ModernSetting(
                    "Allow external device to start playback",
                    if (getSettingsPrefs().getBoolean(
                            "external_playback",
                            true
                        )
                    ) "On" else "Off"
                ) {
                    toggleSetting(
                        "external_playback",
                        "Allow external device to start playback"
                    )
                }
            )
        )

        // ---------- PRIVACY ----------

        addModernSettingsSection(
            page,
            "Privacy"
        )

        addModernSettingsCard(
            page,
            listOf(
                ModernSetting(
                    "Permissions",
                    "Music and notifications"
                ) {
                    showPermissionsDialog()
                }
            )
        )

        // ---------- ABOUT ----------

        addModernSettingsSection(
            page,
            "About Music"
        )

        addModernSettingsCard(
            page,
            listOf(
                ModernSetting(
                    "About Music",
                    "Version 1.0"
                ) {
                    showAboutMusicDialog()
                }
            )
        )

        scroll.addView(
            page,
            ViewGroup.LayoutParams(-1, -2)
        )

        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )
    }


    private fun createMiniExpansionCard() {
        if (miniExpansionCard != null) return
        if (!::miniPlayer.isInitialized) return
        val song = currentSong ?: return

        val decor = window.decorView as? ViewGroup ?: return

        val location = IntArray(2)
        miniPlayer.getLocationOnScreen(location)

        val decorLocation = IntArray(2)
        decor.getLocationOnScreen(decorLocation)

        val top =
            location[1] -
                decorLocation[1]

        val screenWidth =
            resources.displayMetrics.widthPixels

        val miniHeight =
            miniPlayer.height.coerceAtLeast(dp(60))

        val card = FrameLayout(this).apply {
            clipChildren = false
            background = createMiniExpansionBackground(song)
            elevation = dp(12).toFloat()
        }

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline
                    ) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height,
                            dp(18).toFloat()
                        )
                    }
                }

            getAlbumArt(song)?.let {
                setImageBitmap(it)
            } ?: run {
                setImageResource(R.drawable.ic_music)
            }

            alpha = 1f
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
        }

        val title =
            text(
                song.title,
                20f,
                Color.WHITE,
                Typeface.BOLD
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                alpha = 0f
            }

        val artist =
            text(
                song.artist,
                13f,
                Color.WHITE,
                Typeface.NORMAL
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                alpha = 0f
            }

        card.addView(
            cover,
            FrameLayout.LayoutParams(
                dp(46),
                dp(46),
                Gravity.CENTER
            )
        )

        card.addView(
            title,
            FrameLayout.LayoutParams(
                -1,
                dp(28)
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(28)
                rightMargin = dp(28)
                bottomMargin = dp(34)
            }
        )

        card.addView(
            artist,
            FrameLayout.LayoutParams(
                -1,
                dp(22)
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(28)
                rightMargin = dp(28)
                bottomMargin = dp(10)
            }
        )

        decor.addView(
            card,
            FrameLayout.LayoutParams(
                screenWidth,
                miniHeight
            ).apply {
                leftMargin = 0
                topMargin = top
            }
        )

        miniExpansionCard = card
        miniExpansionCover = cover
        miniExpansionTitle = title
        miniExpansionArtist = artist

        miniPlayer.alpha = 0f

        updateMiniExpansionCard(0f)
    }


    private fun updateMiniExpansionCard(
        dy: Float
    ) {
        val card = miniExpansionCard ?: return
        val cover = miniExpansionCover ?: return

        val screenHeight =
            resources.displayMetrics.heightPixels.toFloat()

        val miniHeight =
            miniPlayer.height.coerceAtLeast(dp(60))

        val maxUp =
            (screenHeight - miniHeight).coerceAtLeast(1f)

        val upward =
            (-dy)
                .coerceAtLeast(0f)
                .coerceAtMost(maxUp)

        val progress =
            (upward / maxUp).coerceIn(0f, 1f)

        val lp =
            card.layoutParams as? FrameLayout.LayoutParams
                ?: return

        lp.width =
            resources.displayMetrics.widthPixels

        lp.height =
            (miniHeight + upward).toInt()

        lp.leftMargin = 0

        lp.topMargin =
            (miniPlayerTopInDecor() - upward).toInt()

        card.layoutParams = lp

        /*
         * Continuous Mini -> Full artwork path.
         */
        val miniLocation = IntArray(2)
        miniCover.getLocationOnScreen(miniLocation)

        val miniCenterX =
            miniLocation[0] +
                miniCover.width / 2f

        val miniCenterY =
            miniLocation[1] +
                miniCover.height / 2f

        val targetReady =
            !miniFullTargetCenterX.isNaN() &&
            !miniFullTargetCenterY.isNaN() &&
            miniFullTargetSize > 0

        /*
         * Before the real Full Player is handed off at 50%,
         * use the exact geometry of its current layout.
         *
         * Full Player lower panel:
         *   info      = 55dp
         *   seekbar   = 11dp
         *   time      = 18dp
         *   controls  = 72dp
         *   secondary = 46dp
         *
         * Total = 202dp.
         *
         * coverContainer has 2dp top + 2dp bottom margins.
         */
        val fallbackTargetCenterX =
            resources.displayMetrics.widthPixels / 2f

        val bottomPanelHeight =
            dp(202).toFloat()

        val coverContainerHeight =
            (
                screenHeight -
                    bottomPanelHeight -
                    dp(4).toFloat()
            ).coerceAtLeast(0f)

        val fallbackTargetCenterY =
            coverContainerHeight / 2f

        val fallbackTargetSize =
            (
                resources.displayMetrics.widthPixels -
                    dp(40)
            )
                .coerceAtMost(dp(390))
                .coerceAtLeast(dp(250))

        val targetCenterX =
            if (targetReady)
                miniFullTargetCenterX
            else
                fallbackTargetCenterX

        val targetCenterY =
            if (targetReady)
                miniFullTargetCenterY
            else
                fallbackTargetCenterY

        val targetSize =
            if (targetReady)
                miniFullTargetSize
            else
                fallbackTargetSize

        val centerX =
            miniCenterX +
                (targetCenterX - miniCenterX) * progress

        val centerY =
            miniCenterY +
                (targetCenterY - miniCenterY) * progress

        val coverSize =
            (
                dp(46) +
                    (targetSize - dp(46)) * progress
            )
                .toInt()
                .coerceAtLeast(dp(46))

        val coverLp = cover.layoutParams
        coverLp.width = coverSize
        coverLp.height = coverSize
        cover.layoutParams = coverLp

        val cardLocation = IntArray(2)
        card.getLocationOnScreen(cardLocation)

        val cardCenterX =
            cardLocation[0] +
                card.width / 2f

        val cardCenterY =
            cardLocation[1] +
                card.height / 2f

        cover.translationX =
            centerX - cardCenterX

        cover.translationY =
            centerY - cardCenterY

        cover.alpha = 1f
        cover.rotation = 0f
        cover.scaleX = 1f
        cover.scaleY = 1f

        miniExpansionTitle?.apply {
            alpha = 0f
            translationY = 0f
        }

        miniExpansionArtist?.apply {
            alpha = 0f
            translationY = 0f
        }

        miniFullTransitionProgress = progress

        updateMiniFullTransition(progress)
    }


    private fun animateMiniExpansionTo(
        targetProgress: Float,
        duration: Long,
        onEnd: (() -> Unit)? = null
    ) {
        val card = miniExpansionCard ?: return

        val screenHeight =
            resources.displayMetrics.heightPixels
                .toFloat()

        val miniHeight =
            miniPlayer.height.coerceAtLeast(dp(60))

        val maxUp =
            (
                screenHeight -
                    miniHeight
            ).coerceAtLeast(1f)

        val current =
            fullMiniExpansionProgress()

        android.animation.ValueAnimator
            .ofFloat(
                current,
                targetProgress
            )
            .apply {
                this.duration = duration
                interpolator =
                    DecelerateInterpolator()

                addUpdateListener { animator ->
                    val progress =
                        animator.animatedValue
                            as Float

                    miniFullTransitionProgress = progress

                    updateMiniExpansionCard(
                        -maxUp * progress
                    )

                    updateMiniFullTransition(
                        progress
                    )
                }

                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(
                            animation: android.animation.Animator
                        ) {
                            onEnd?.invoke()
                        }
                    }
                )
            }
            .start()
    }


    private fun completeMiniExpansion(
        layout: LinearLayout
    ) {
        if (miniExpansionCard == null) return
        if (miniFullTransitionAnimating) return

        miniFullTransitionAnimating = true

        animateMiniExpansionTo(
            targetProgress = 1f,
            duration = 360L
        ) {
            updateMiniFullTransition(1f)

            removeMiniExpansionCard()

            layout.translationX = 0f
            layout.translationY = 0f
            layout.alpha = 1f

            miniCover.rotation = 0f
            miniCover.scaleX = 1f
            miniCover.scaleY = 1f

            miniBottomNavigation?.let { nav ->
                nav.translationY = dp(82).toFloat()
                nav.alpha = 0f
            }

            miniFullTransitionProgress = 1f
            miniFullTransitionAnimating = false
        }
    }


    private fun cancelMiniExpansion(
        layout: LinearLayout
    ) {
        if (miniExpansionCard == null) {
            miniPlayer.alpha = 1f
            updateMiniFullTransition(0f)
            return
        }

        if (miniFullTransitionAnimating) return

        miniFullTransitionAnimating = true

        animateMiniExpansionTo(
            targetProgress = 0f,
            duration = 280L
        ) {
            updateMiniFullTransition(0f)

            miniTransitionDialog?.let { dialog ->
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }

            miniTransitionDialog = null
            miniTransitionRoot = null
            miniTransitionFullCover = null
            miniTransitionInfo = null
            miniTransitionSeekBar = null
            miniTransitionTimeRow = null
            miniTransitionControls = null
            miniTransitionSecondary = null
            miniTransitionCoverContainer = null

            removeMiniExpansionCard()

            layout.translationX = 0f
            layout.translationY = 0f
            layout.alpha = 1f

            miniCover.rotation = 0f
            miniCover.scaleX = 1f
            miniCover.scaleY = 1f

            miniBottomNavigation?.let { nav ->
                nav.translationY = 0f
                nav.alpha = 1f
            }

            miniPlayer.alpha = 1f

            miniFullTransitionProgress = 0f
            miniFullTransitionAnimating = false
        }
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


    recordRecentlyPlayed(song)

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
