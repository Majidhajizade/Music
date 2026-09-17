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
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.SessionToken
import androidx.media3.session.MediaController
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.content.ComponentName
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
    val artist: String,
    val album: String = "Unknown Album",
    val dateAdded: Long = 0L
)

class MainActivity : ComponentActivity() {

    private val songs = mutableListOf<Song>()
    private var mediaPlayer: MediaController? = null
    private var currentSong: Song? = null

    /*
     * Background playback controller.
     *
     * The actual player lives inside MusicPlaybackService.
     * MainActivity only controls it through MediaController.
     */
    private var mediaControllerFuture:
        ListenableFuture<MediaController>? = null

    private var restoredPosition = 0L

    private val mediaControllerListener =
        object : Player.Listener {

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                val id =
                    mediaItem?.mediaId
                        ?.toLongOrNull()
                        ?: return

                val song =
                    songs.firstOrNull {
                        it.id == id
                    }
                        ?: querySongById(id)
                        ?: return

                currentSong = song

                playbackIndex =
                    playbackQueue.indexOfFirst {
                        it.id == song.id
                    }

                showMiniPlayer()

                getAlbumArt(song)?.let {
                    miniCover.setImageBitmap(it)
                } ?: run {
                    miniCover.setImageResource(
                        R.drawable.icon
                    )
                }

                miniTitle.text = song.title
                miniArtist.text = song.artist

                if (
                    ::playButton.isInitialized
                ) {
                    playButton.setImageResource(
                        if (mediaPlayer?.isPlaying == true)
                            com.music.app.R.drawable.ic_player_pause
                        else
                            com.music.app.R.drawable.ic_music_play
                    )
                }

                savePlayerState()
            }

            override fun onIsPlayingChanged(
                isPlaying: Boolean
            ) {
                if (
                    ::playButton.isInitialized
                ) {
                    playButton.setImageResource(
                        if (isPlaying)
                            com.music.app.R.drawable.ic_player_pause
                        else
                            com.music.app.R.drawable.ic_music_play
                    )
                }

                savePlayerState()
            }
        }


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
    private lateinit var playButton: ImageView

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

                Toast.makeText(
                    this@MainActivity,
                    "Profile updated",
                    Toast.LENGTH_SHORT
                ).show()

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

        // Navigation has no selected background.
        item.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.TRANSPARENT)
        }

        if (icon != null) {
            icon.clearColorFilter()
            icon.setColorFilter(Color.BLACK)
        }

        label?.setTextColor(Color.BLACK)
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
            setBackgroundColor(Color.TRANSPARENT)
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
            setBackgroundColor(Color.TRANSPARENT)
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
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATE_ADDED
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

            val albumColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)

            val dateAddedColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                songs.add(
                    Song(
                        id = cursor.getLong(idColumn),
                        title = cursor.getString(titleColumn) ?: "Unknown",
                        artist = cursor.getString(artistColumn) ?: "Unknown Artist",
                        album = cursor.getString(albumColumn) ?: "Unknown Album",
                        dateAdded = cursor.getLong(dateAddedColumn)
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
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, dp(6))
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setPadding(dp(20), dp(10), dp(20), dp(14))
        }

        val homeScroll = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    homeScroll.addView(
        content,
        android.view.ViewGroup.LayoutParams(
            -1,
            -2
        )
    )

    root.addView(
        homeScroll,
        LinearLayout.LayoutParams(-1, 0, 1f)
    )

        // ---------- MINI PLAYER ----------
        miniPlayer = createMiniPlayer().apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(30).toFloat()
                setColor(Color.WHITE)
            }
            clipToOutline = true
            elevation = dp(2).toFloat()
        }

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

        /*
         * The Library/UI is now ready, so restore the
         * previous song only after the Mini Player exists.
         */
        restorePlayerState()

        playerSaveHandler.removeCallbacks(
            playerSaveRunnable
        )
        playerSaveHandler.post(
            playerSaveRunnable
        )
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

    private fun showHome() {

        // Keep the first ~10% of the screen as top breathing room.
        val topSpaceDp = (resources.displayMetrics.heightPixels /
            resources.displayMetrics.density * 0.10f).toInt()

        setActiveNavigation(0)
        content.removeAllViews()

        // Home owns its own canvas. Keep the shared content host
        // transparent so it cannot add an extra white layer.
        content.setPadding(0, 0, 0, 0)
        content.setBackgroundColor(Color.TRANSPARENT)

        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.WHITE
            )
        )

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
            setPadding(0, topSpaceDp, 0, 0)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(
                dp(20),
                dp(28),
                dp(20),
                dp(32)
            )
            clipToPadding = false
        }

        // -------------------------------------------------
        // Apple Music style header
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
            text = "Home"
            textSize = 28f
            setTextColor(Color.BLACK)
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            includeFontPadding = false
        }


        greetingBox.addView(
            greeting,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

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
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(
                0,
                0,
                0,
                0
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(242, 242, 247))
            }

            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: android.graphics.Outline
                ) {
                    outline.setOval(
                        0,
                        0,
                        view.width,
                        view.height
                    )
                }
            }

            isClickable = true
            isFocusable = true
            elevation = dp(4).toFloat()

            setOnClickListener {
                showSettings()
            }
        }

        updateAvatar(headerAvatar)

        header.addView(
            headerAvatar,
            LinearLayout.LayoutParams(
                dp(42),
                dp(42)
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
        // Made For You
        // -------------------------------------------------

        if (songs.isNotEmpty()) {

            val topPicksTitle = TextView(this).apply {
                text = "Top Picks for You"
                textSize = 22f
                setTextColor(Color.BLACK)
                typeface = Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                includeFontPadding = false
                setPadding(
                    0,
                    dp(24),
                    0,
                    dp(14)
                )
            }

            page.addView(
                topPicksTitle,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )

            val suggestionPool =
                songs.filterNot {
                    isSuggestLess(it)
                }

            val madeForYou =
                suggestionPool
                    .drop(10)
                    .take(10)
                    .ifEmpty {
                        suggestionPool.take(10)
                    }

            val madeScroll =
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    clipToPadding = false
                }

            val madeRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(
                        dp(20),
                        0,
                        dp(20),
                        0
                    )
                }

            madeForYou.forEach { song ->

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    isFocusable = true

                    background = null
                    clipChildren = true
                    clipToPadding = true

                    setOnClickListener {
                        playSong(song)
                    }
                }

                val coverFrame = FrameLayout(this).apply {
                    clipChildren = true
                    clipToPadding = true
                    background = null

                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(
                            view: View,
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

                    clipToOutline = true
                }

                val cover = createRealSongCover(
                    song,
                    330
                ).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true

                    background = null
                }

                coverFrame.addView(
                    cover,
                    FrameLayout.LayoutParams(
                        -1,
                        -1
                    )
                )

                val shade = View(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                Color.TRANSPARENT,
                                Color.argb(185, 0, 0, 0)
                            )
                        )
                }

                coverFrame.addView(
                    shade,
                    FrameLayout.LayoutParams(
                        -1,
                        -1
                    )
                )

                val title = TextView(this).apply {
                    text = song.title
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )
                    includeFontPadding = false
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                val artist = TextView(this).apply {
                    text = song.artist
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(
                        0,
                        dp(5),
                        0,
                        0
                    )
                }

                val textBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    setPadding(
                        dp(18),
                        dp(18),
                        dp(18),
                        dp(18)
                    )
                }

                textBox.addView(title)
                textBox.addView(artist)

                coverFrame.addView(
                    textBox,
                    FrameLayout.LayoutParams(
                        -1,
                        -1,
                        Gravity.BOTTOM
                    )
                )

                card.addView(
                    coverFrame,
                    LinearLayout.LayoutParams(
                        dp(230),
                        dp(330)
                    )
                )

                madeRow.addView(
                    card,
                    LinearLayout.LayoutParams(
                        dp(230),
                        dp(330)
                    ).apply {
                        rightMargin = dp(14)
                    }
                )
            }

            madeScroll.addView(madeRow)

            page.addView(
                madeScroll,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                ).apply {
                    topMargin = dp(2)
                    marginStart = -dp(20)
                    marginEnd = -dp(20)
                }
            )
        }


        // -------------------------------------------------
        // Recently Played
        // -------------------------------------------------

        val recent = getRecentlyPlayedSongs()

        if (recent.isNotEmpty()) {

            addRealSectionTitle(
                page,
                "Recently played"
            )

            val recentScroll =
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    clipToPadding = false
                }

            val recentRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(
                        dp(20),
                        0,
                        dp(20),
                        0
                    )
                }

            recent.take(10).forEach { song ->

                val card = createRealSongCard(
                    song,
                    widthDp = 156,
                    imageDp = 156
                ) {
                    playSong(song)
                }

                recentRow.addView(
                    card,
                    LinearLayout.LayoutParams(
                        dp(156),
                        -2
                    ).apply {
                        rightMargin = dp(14)
                    }
                )
            }

            recentScroll.addView(recentRow)

            page.addView(
                recentScroll,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                ).apply {
                    topMargin = dp(2)
                    marginStart = -dp(20)
                    marginEnd = -dp(20)
                }
            )
        }


        // -------------------------------------------------
        // Empty state
        // -------------------------------------------------

        if (songs.isEmpty()) {

            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    dp(24),
                    dp(90),
                    dp(24),
                    dp(90)
                )
            }

            val emptyTitle = TextView(this).apply {
                text = "No music yet"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                typeface = Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
                includeFontPadding = false
            }

            val emptyText = TextView(this).apply {
                text = "Add music to your device to see it here."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(110, 110, 110))
                includeFontPadding = false
                setPadding(
                    0,
                    dp(9),
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

        // Keep the first ~10% of the screen as top breathing room.
        val topSpaceDp = (resources.displayMetrics.heightPixels /
            resources.displayMetrics.density * 0.10f).toInt()

        content.removeAllViews()

        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.WHITE
            )
        )

        // ---------- LIBRARY ROOT ----------
        val root = LinearLayout(this).apply {
            setPadding(0, topSpaceDp, 0, 0)
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            background =
                android.graphics.drawable.ColorDrawable(Color.WHITE)
        }

        content.addView(
            root,
            LinearLayout.LayoutParams(-1, -1)
        )

        // ---------- HEADER ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            setPadding(
                dp(18),
                dp(30),
                dp(18),
                dp(14)
            )
        }

        val title = text(
            "Library",
            32f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                dp(44)
            )
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        // ---------- SCROLL ----------
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.WHITE)
            setPadding(
                0,
                0,
                0,
                dp(8)
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            setPadding(
                dp(8),
                dp(2),
                dp(12),
                dp(28)
            )
        }

        scroll.addView(
            container,
            ViewGroup.LayoutParams(-1, -2)
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // ---------- SECTION ROW ----------
        fun libraryRow(
            icon: String,
            titleText: String,
            subtitle: String,
            click: () -> Unit
        ): LinearLayout {

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    dp(9),
                    dp(2),
                    dp(9)
                )

                isClickable = true
                isFocusable = true

                background =
                    GradientDrawable().apply {
                        cornerRadius =
                            dp(16).toFloat()
                        setColor(Color.WHITE)
                    }

                setOnClickListener {
                    animate()
                        .scaleX(0.985f)
                        .scaleY(0.985f)
                        .setDuration(70)
                        .withEndAction {
                            animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(100)
                                .start()

                            click()
                        }
                        .start()
                }
            }

            // ---------- ICON ----------
            val iconBox = FrameLayout(this).apply {
                background =
                    GradientDrawable().apply {
                        cornerRadius =
                            dp(14).toFloat()
                        setColor(
                            Color.rgb(
                                245,
                                245,
                                247
                            )
                        )
                    }
            }

            val iconView = text(
                icon,
                27f,
                Color.rgb(25, 25, 25),
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }

            iconBox.addView(
                iconView,
                FrameLayout.LayoutParams(
                    -1,
                    -1
                )
            )

            row.addView(
                iconBox,
                LinearLayout.LayoutParams(
                    dp(58),
                    dp(58)
                )
            )

            // ---------- TEXT ----------
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    dp(10),
                    0,
                    dp(6),
                    0
                )
            }

            val titleView = text(
                titleText,
                18f,
                Color.rgb(20, 20, 20),
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
                maxLines = 1
                ellipsize =
                    TextUtils.TruncateAt.END
            }

            val subtitleView = text(
                subtitle,
                13.5f,
                Color.rgb(125, 125, 125),
                Typeface.NORMAL
            ).apply {
                includeFontPadding = false
                maxLines = 1
                ellipsize =
                    TextUtils.TruncateAt.END

                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }

            info.addView(
                titleView,
                LinearLayout.LayoutParams(
                    -1,
                    dp(22)
                )
            )

            info.addView(
                subtitleView,
                LinearLayout.LayoutParams(
                    -1,
                    dp(20)
                )
            )

            row.addView(
                info,
                LinearLayout.LayoutParams(
                    0,
                    dp(54),
                    1f
                )
            )

            // ---------- CHEVRON ----------
            val arrow = text(
                "›",
                31f,
                Color.rgb(150, 150, 150),
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }

            row.addView(
                arrow,
                LinearLayout.LayoutParams(
                    dp(28),
                    dp(54)
                )
            )

            return row
        }

        fun divider() {
            container.addView(
                View(this).apply {
                    setBackgroundColor(
                        Color.rgb(
                            238,
                            238,
                            240
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    -1,
                    dp(1)
                ).apply {
                    leftMargin = dp(64)
                }
            )
        }

        // ---------- SONGS ----------
        container.addView(
            libraryRow(
                "♫",
                "Songs",
                "${songs.size} songs"
            ) {
                showLibrarySongs()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(78)
            )
        )

        divider()

        // ---------- PLAYLISTS ----------
        container.addView(
            libraryRow(
                "☷",
                "Playlists",
                "Your playlists"
            ) {
                showCreatePlaylistDialog()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(78)
            )
        )

        divider()

        // ---------- FAVORITES ----------
        container.addView(
            libraryRow(
                "★",
                "Favorites",
                "Your favorite songs"
            ) {
                showLibraryFavorites()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(78)
            )
        )

        divider()

        // ---------- ARTISTS ----------
        container.addView(
            libraryRow(
                "♟",
                "Artists",
                "Browse by artist"
            ) {
                showLibraryArtists()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(78)
            )
        )

        divider()

        // ---------- ALBUMS ----------
        container.addView(
            libraryRow(
                "◉",
                "Albums",
                "Browse by album"
            ) {
                showLibraryAlbums()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(78)
            )
        )

        divider()

        // ---------- RECENTLY ADDED ----------
        container.addView(
            libraryRow(
                "＋",
                "Recently added",
                "Latest songs"
            ) {
                showLibraryRecentlyAdded()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(78)
            )
        )

        divider()

        // ---------- MOST PLAYED ----------
        container.addView(
            libraryRow(
                "↗",
                "Most played",
                "Your most played songs"
            ) {
                showLibraryMostPlayed()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(78)
            )
        )
    }

    private fun showLibrarySongs() {

        content.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(
            root,
            LinearLayout.LayoutParams(-1, -1)
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(34),
                dp(18),
                dp(10)
            )
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = text(
            "‹",
            38f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            elevation = dp(6).toFloat()
            setOnClickListener {
                showLibrary()
            }
        }

        top.addView(
            back,
            LinearLayout.LayoutParams(
                dp(56),
                dp(56)
            )
        )

        val title = text(
            "Songs",
            30f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        top.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(56),
                1f
            )
        )

        header.addView(
            top,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            )
        )

        // ---------- SEARCH ----------
        val search = EditText(this).apply {
            hint = "Search in songs"
            textSize = 14f
            setTextColor(Color.rgb(35, 35, 35))
            setHintTextColor(Color.rgb(105, 105, 105))
            maxLines = 1
            setPadding(
                dp(16),
                0,
                dp(16),
                0
            )

            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.rgb(232, 232, 232))
            }
        }

        header.addView(
            search,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
        )

        // ---------- PLAY / SHUFFLE ----------
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun actionButton(
            icon: String,
            label: String,
            click: () -> Unit
        ): LinearLayout {

            val button = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0
                )

                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.rgb(232, 232, 232))
                }

                setOnClickListener {
                    click()
                }
            }

            if (icon == "SHUFFLE_ICON") {

                val iconView = ImageView(this).apply {
                    setImageResource(
                        R.drawable.ic_player_shuffle
                    )
                    scaleType = ImageView.ScaleType.CENTER
                    alpha = 0.92f
                }

                button.addView(
                    iconView,
                    LinearLayout.LayoutParams(
                        dp(25),
                        -1
                    )
                )

            } else {

                val iconView = text(
                    icon,
                    19f,
                    Color.rgb(25, 25, 25),
                    Typeface.NORMAL
                ).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }

                button.addView(
                    iconView,
                    LinearLayout.LayoutParams(
                        dp(25),
                        -1
                    )
                )
            }

            val labelView = text(
                label,
                14f,
                Color.rgb(25, 25, 25),
                Typeface.BOLD
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
            }

            button.addView(
                labelView,
                LinearLayout.LayoutParams(
                    -2,
                    -1
                )
            )

            return button
        }

        val playAction = actionButton(
            "▶",
            "Play"
        ) {

            if (songs.isNotEmpty()) {

                playbackQueue =
                    if (isDuplicateSongsBlocked()) {
                        songs
                            .distinctBy { it.id }
                            .toMutableList()
                    } else {
                        songs.toMutableList()
                    }

                playbackIndex = 0

                playSong(
                    playbackQueue[playbackIndex]
                )
            }
        }

        val shuffleAction = actionButton(
            "SHUFFLE_ICON",
            "Shuffle"
        ) {

            if (songs.isNotEmpty()) {

                playbackQueue =
                    if (isDuplicateSongsBlocked()) {
                        songs
                            .distinctBy { it.id }
                            .shuffled()
                            .toMutableList()
                    } else {
                        songs
                            .shuffled()
                            .toMutableList()
                    }

                playbackIndex = 0

                playSong(
                    playbackQueue[playbackIndex]
                )
            }
        }

        actions.addView(
            playAction,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        actions.addView(
            Space(this),
            LinearLayout.LayoutParams(
                dp(10),
                dp(48)
            )
        )

        actions.addView(
            shuffleAction,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        header.addView(
            actions,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        // ---------- SONG LIST ----------
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                0,
                dp(18),
                dp(24)
            )
        }

        fun renderSongs(query: String = "") {

            list.removeAllViews()

            val q = query.trim()

            val filtered =
                if (q.isEmpty()) {
                    songs
                } else {
                    songs.filter {
                        it.title.contains(
                            q,
                            ignoreCase = true
                        ) ||
                        it.artist.contains(
                            q,
                            ignoreCase = true
                        )
                    }
                }

            filtered.forEach { song ->

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        0,
                        dp(6),
                        0,
                        dp(6)
                    )

                    setOnClickListener {
                        playSong(song)
                    }

                    setOnLongClickListener {
                        showSongActionSheet(song)
                        true
                    }
                }

                val cover = ImageView(this).apply {

                    scaleType =
                        ImageView.ScaleType.CENTER_CROP

                    clipToOutline = true

                    outlineProvider =
                        object : ViewOutlineProvider() {

                            override fun getOutline(
                                view: View,
                                outline: android.graphics.Outline
                            ) {
                                outline.setRoundRect(
                                    0,
                                    0,
                                    view.width,
                                    view.height,
                                    dp(10).toFloat()
                                )
                            }
                        }

                    background =
                        GradientDrawable().apply {
                            cornerRadius =
                                dp(10).toFloat()

                            setColor(
                                Color.rgb(
                                    235,
                                    235,
                                    235
                                )
                            )
                        }

                    getAlbumArt(song)?.let {
                        setImageBitmap(it)
                    } ?: run {
                        setImageResource(R.drawable.icon)
                    }
                }

                row.addView(
                    cover,
                    LinearLayout.LayoutParams(
                        dp(48),
                        dp(48)
                    )
                )

                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(12),
                        0,
                        dp(6),
                        0
                    )
                }

                val songTitle = text(
                    song.title,
                    14f,
                    Color.rgb(18, 18, 18),
                    Typeface.BOLD
                ).apply {
                    maxLines = 1
                    ellipsize =
                        TextUtils.TruncateAt.END
                    includeFontPadding = false
                }

                val artist = text(
                    song.artist,
                    11f,
                    Color.rgb(125, 125, 125),
                    Typeface.NORMAL
                ).apply {
                    maxLines = 1
                    ellipsize =
                        TextUtils.TruncateAt.END
                    includeFontPadding = false

                    setPadding(
                        0,
                        dp(4),
                        0,
                        0
                    )
                }

                info.addView(
                    songTitle,
                    LinearLayout.LayoutParams(
                        -1,
                        dp(20)
                    )
                )

                info.addView(
                    artist,
                    LinearLayout.LayoutParams(
                        -1,
                        dp(18)
                    )
                )

                row.addView(
                    info,
                    LinearLayout.LayoutParams(
                        0,
                        dp(56),
                        1f
                    )
                )

                val more = text(
                    "•••",
                    12f,
                    Color.rgb(125, 125, 125),
                    Typeface.BOLD
                ).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false

                    setOnClickListener { view ->
                        showSongMenu(
                            view,
                            song
                        )
                    }
                }

                row.addView(
                    more,
                    LinearLayout.LayoutParams(
                        dp(34),
                        dp(56)
                    )
                )

                list.addView(
                    row,
                    LinearLayout.LayoutParams(
                        -1,
                        dp(68)
                    )
                )

                list.addView(
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
                        dp(1)
                    )
                )
            }
        }

        renderSongs()

        scroll.addView(
            list,
            ViewGroup.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        search.addTextChangedListener(
            object : android.text.TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    renderSongs(
                        s?.toString() ?: ""
                    )
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {}
            }
        )
    }

    // ============================================================
    // LIBRARY PLACEHOLDER SECTIONS
    // ============================================================

    private fun showLibrarySection(
        titleText: String,
        subtitleText: String,
        items: List<Song>
    ) {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            background = android.graphics.drawable.ColorDrawable(Color.WHITE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(16),
                dp(34),
                dp(16),
                dp(10)
            )
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            elevation = dp(6).toFloat()

            setOnClickListener {
                showLibrary()
            }
        }

        header.addView(
            back,
            LinearLayout.LayoutParams(
                dp(56),
                dp(56)
            )
        )

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(8),
                0,
                0,
                0
            )
        }

        titleBox.addView(
            TextView(this).apply {
                text = titleText
                textSize = 22f
                setTextColor(Color.BLACK)
                setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        titleBox.addView(
            TextView(this).apply {
                text = subtitleText
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        header.addView(
            titleBox,
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
                -2
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(
                dp(12),
                dp(4),
                dp(12),
                dp(24)
            )
        }

        if (items.isEmpty()) {

            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    dp(32),
                    dp(80),
                    dp(32),
                    dp(40)
                )
            }

            empty.addView(
                TextView(this).apply {
                    text = "♪"
                    textSize = 42f
                    gravity = Gravity.CENTER
                    setTextColor(Color.LTGRAY)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    -1,
                    dp(60)
                )
            )

            empty.addView(
                TextView(this).apply {
                    text = "No songs here"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.DKGRAY)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(
                        0,
                        dp(8),
                        0,
                        0
                    )
                },
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )

            empty.addView(
                TextView(this).apply {
                    text = when (titleText) {
                        "Favorites" ->
                            "Songs you favorite will appear here"

                        "Artists" ->
                            "Your artists will appear here"

                        "Albums" ->
                            "Your albums will appear here"

                        "Recently added" ->
                            "Recently added songs will appear here"

                        "Most played" ->
                            "Songs you play most will appear here"

                        else ->
                            "Your songs will appear here"
                    }

                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.GRAY)
                    setPadding(
                        0,
                        dp(6),
                        0,
                        0
                    )
                },
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )

            list.addView(
                empty,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )

        } else {

            items.forEachIndexed { index, song ->

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(8),
                        dp(7),
                        dp(8),
                        dp(7)
                    )

                    setOnClickListener {
                        playSong(song)
                    }
                }

                val cover = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(238, 238, 238))
                }

                getAlbumArt(song)?.let {
                    cover.setImageBitmap(it)
                } ?: run {
                    cover.setImageResource(R.drawable.icon)
                }

                row.addView(
                    cover,
                    LinearLayout.LayoutParams(
                        dp(52),
                        dp(52)
                    )
                )

                val textBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        dp(12),
                        0,
                        dp(8),
                        0
                    )
                }

                textBox.addView(
                    TextView(this).apply {
                        text = song.title
                        textSize = 16f
                        setTextColor(Color.BLACK)
                        maxLines = 1
                        ellipsize =
                            android.text.TextUtils.TruncateAt.END
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(
                        -1,
                        -2
                    )
                )

                val secondary = when (titleText) {
                    "Artists" ->
                        song.album

                    "Albums" ->
                        song.artist

                    else ->
                        song.artist
                }

                textBox.addView(
                    TextView(this).apply {
                        text = secondary
                        textSize = 13f
                        setTextColor(Color.GRAY)
                        maxLines = 1
                        ellipsize =
                            android.text.TextUtils.TruncateAt.END
                        setPadding(
                            0,
                            dp(4),
                            0,
                            0
                        )
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(
                        -1,
                        -2
                    )
                )

                row.addView(
                    textBox,
                    LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f
                    )
                )

                val more = TextView(this).apply {
                    text = "•••"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.DKGRAY)
                    setPadding(
                        dp(8),
                        0,
                        dp(4),
                        0
                    )

                    setOnClickListener {
                        showLibrarySongPopup(
                            this,
                            song
                        )
                    }
                }

                row.addView(
                    more,
                    LinearLayout.LayoutParams(
                        dp(42),
                        dp(52)
                    )
                )

                list.addView(
                    row,
                    LinearLayout.LayoutParams(
                        -1,
                        -2
                    )
                )

                if (index < items.lastIndex) {
                    list.addView(
                        View(this).apply {
                            setBackgroundColor(
                                Color.rgb(238, 238, 238)
                            )
                        },
                        LinearLayout.LayoutParams(
                            -1,
                            1
                        ).apply {
                            leftMargin = dp(72)
                        }
                    )
                }
            }
        }

        scroll.addView(
            list,
            ViewGroup.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun showLibraryFavorites() {
        val favorites = songs.filter {
            isFavorite(it)
        }

        showLibrarySection(
            "Favorites",
            "${favorites.size} favorite songs",
            favorites
        )
    }

    private fun showLibraryArtists() {

        val artists = songs
            .groupBy {
                it.artist.ifBlank {
                    "Unknown Artist"
                }
            }
            .toSortedMap(
                String.CASE_INSENSITIVE_ORDER
            )

        val items = artists.values
            .flatten()

        showLibrarySection(
            "Artists",
            "${artists.size} artists",
            items
        )
    }

    private fun showLibraryAlbums() {

        val albums = songs
            .groupBy {
                it.album.ifBlank {
                    "Unknown Album"
                }
            }
            .toSortedMap(
                String.CASE_INSENSITIVE_ORDER
            )

        val items = albums.values
            .flatten()

        showLibrarySection(
            "Albums",
            "${albums.size} albums",
            items
        )
    }

    private fun showLibraryRecentlyAdded() {

        val recent = songs
            .sortedByDescending {
                it.dateAdded
            }

        showLibrarySection(
            "Recently added",
            "${recent.size} songs",
            recent
        )
    }

    private fun showLibraryMostPlayed() {

        val mostPlayed = songs
            .sortedByDescending {
                libraryPrefs.getInt(
                    "play_count_${it.id}",
                    0
                )
            }
            .filter {
                libraryPrefs.getInt(
                    "play_count_${it.id}",
                    0
                ) > 0
            }

        showLibrarySection(
            "Most played",
            "${mostPlayed.size} songs",
            mostPlayed
        )
    }


    private fun showEditInfo() {
        content.removeAllViews()

        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.rgb(246, 246, 246)
            )
        )

        content.setBackgroundColor(
            Color.rgb(246, 246, 246)
        )

        content.setPadding(
            dp(20),
            dp(24),
            dp(20),
            dp(28)
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 246, 246))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true

            setOnClickListener {
                showSettings()
            }
        }

        top.addView(
            back,
            LinearLayout.LayoutParams(
                dp(56),
                dp(56)
            )
        )

        val title = TextView(this).apply {
            text = "Edit Info"
            textSize = 30f
            setTextColor(Color.rgb(20, 20, 22))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

        top.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(56),
                1f
            )
        )

        root.addView(
            top,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            ).apply {
                bottomMargin = dp(30)
            }
        )

        val nameLabel = TextView(this).apply {
            text = "Name"
            textSize = 15f
            setTextColor(Color.rgb(80, 80, 85))
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

        root.addView(
            nameLabel,
            LinearLayout.LayoutParams(
                -1,
                dp(26)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val nameInput = EditText(this).apply {
            setText(getProfileName())
            textSize = 18f
            setTextColor(Color.BLACK)
            setSingleLine(true)
            setPadding(
                dp(16),
                0,
                dp(16),
                0
            )

            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
        }

        root.addView(
            nameInput,
            LinearLayout.LayoutParams(
                -1,
                dp(58)
            ).apply {
                bottomMargin = dp(22)
            }
        )

        val save = TextView(this).apply {
            text = "Save"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true

            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.BLACK)
            }

            setOnClickListener {
                val name = nameInput.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Please enter your name",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                saveProfileName(name)

                Toast.makeText(
                    this@MainActivity,
                    "Profile updated",
                    Toast.LENGTH_SHORT
                ).show()

                showSettings()
            }
        }

        root.addView(
            save,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            )
        )

        content.addView(
            root,
            LinearLayout.LayoutParams(
                -1,
                -1
            )
        )
    }

    private fun showSettings() {

        content.removeAllViews()

        // Reset the Activity Window background so the gray
        // Edit Info background does not leak into the rest
        // of the app.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.WHITE
            )
        )

        // Settings uses the same edge-to-edge window layout as Full Player.
        // The background extends behind the status bar and camera cutout,
        // while system icons (clock, signal, Wi-Fi, battery) remain visible.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

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


        // ---------- PROFILE ----------

        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(16),
                dp(22),
                dp(16),
                dp(22)
            )

            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
        }

        val profileAvatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(242, 242, 247))
            }

            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: android.graphics.Outline
                ) {
                    outline.setOval(
                        0,
                        0,
                        view.width,
                        view.height
                    )
                }
            }
        }

        updateAvatar(profileAvatar)

        profileCard.addView(
            profileAvatar,
            LinearLayout.LayoutParams(
                dp(122),
                dp(122)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        val profileName = TextView(this).apply {
            text = getProfileName()
            textSize = 22f
            setTextColor(Color.rgb(20, 20, 22))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        profileCard.addView(
            profileName,
            LinearLayout.LayoutParams(
                -1,
                dp(32)
            ).apply {
                bottomMargin = dp(18)
            }
        )

        val profileActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun profileButton(
            label: String,
            action: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true

                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.rgb(248, 248, 250))
                }

                setOnClickListener {
                    action()
                }
            }
        }

        val setProfile = profileButton("Set Profile") {
            imagePicker.launch("image/*")
        }

        val editInfo = profileButton("Edit Info") {
            showEditInfo()
        }

        profileActions.addView(
            setProfile,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        profileActions.addView(
            editInfo,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                marginStart = dp(6)
            }
        )

        profileCard.addView(
            profileActions,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            )
        )

        page.addView(
            profileCard,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(24)
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

    private data class ModernSetting(
        val title: String,
        val value: String,
        val action: () -> Unit
    )

    private fun getSettingsPrefs() =
        getSharedPreferences(
            "music_settings",
            MODE_PRIVATE
        )

    private fun addModernSettingsSection(
        parent: LinearLayout,
        titleValue: String
    ) {

        val title = text(
            titleValue,
            13f,
            Color.rgb(95, 95, 95),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
            letterSpacing = 0.015f
        }

        parent.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                dp(32)
            ).apply {
                topMargin = dp(18)
                leftMargin = dp(8)
                rightMargin = dp(4)
            }
        )
    }

    private fun addModernSettingsCard(
        parent: LinearLayout,
        settings: List<ModernSetting>
    ) {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(18).toFloat()
                }

            elevation = 0f
            clipToOutline = true
        }

        settings.forEachIndexed { index, setting ->

            if (setting.title == "Play speed") {
                addPlaybackSliderCard(
                    card,
                    "Play speed",
                    getPlaybackSpeedValue()
                )
                return@forEachIndexed
            }

            if (setting.title == "Cross fade") {
                addPlaybackSliderCard(
                    card,
                    "Cross fade",
                    getCrossFadeValue()
                )
                return@forEachIndexed
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    dp(18),
                    dp(6),
                    dp(14),
                    dp(6)
                )

                isClickable = true
                isFocusable = true
            }

            val names = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val title = text(
                setting.title,
                16f,
                Color.BLACK,
                Typeface.NORMAL
            ).apply {
                includeFontPadding = false
                maxLines = 2
                setSingleLine(false)
            }

            names.addView(
                title,
                LinearLayout.LayoutParams(
                    0,
                    -2,
                    1f
                )
            )

            if (setting.value.isNotBlank()) {
                names.addView(
                    text(
                        setting.value,
                        12.5f,
                        Color.BLACK,
                        Typeface.NORMAL
                    ).apply {
                        includeFontPadding = false
                        maxLines = 2
                        setPadding(
                            0,
                            dp(3),
                            0,
                            0
                        )
                    },
                    LinearLayout.LayoutParams(
                        -1,
                        -2
                    )
                )
            }

            row.addView(
                names,
                LinearLayout.LayoutParams(
                    0,
                    -2,
                    1f
                ).apply {
                    rightMargin = dp(8)
                }
            )

            val isToggle =
                setting.title == "Skip silence between tracks" ||
                setting.title == "Control music from lock screen" ||
                setting.title == "Don't allow duplicate songs" ||
                setting.title == "Dark mode" ||
                setting.title == "Allow external device to start playback"

            if (isToggle) {

                val prefs = getSettingsPrefs()

                val key =
                    when (setting.title) {
                        "Skip silence between tracks" ->
                            "skip_silence"

                        "Control music from lock screen" ->
                            "lock_screen_controls"

                        "Don't allow duplicate songs" ->
                            "no_duplicate_songs"

                        "Dark mode" ->
                            "dark_mode"

                        else ->
                            "external_playback"
                    }

                val defaultValue =
                    when (key) {
                        "lock_screen_controls" -> true
                        "external_playback" -> true
                        else -> false
                    }

                val switchView =
                    android.widget.Switch(this).apply {

                        isChecked =
                            prefs.getBoolean(
                                key,
                                defaultValue
                            )

                        showText = false
                        minWidth = dp(48)

                        scaleX = 0.82f
                        scaleY = 0.82f

                        try {
                            val track = android.content.res.ColorStateList(
                                arrayOf(
                                    intArrayOf(android.R.attr.state_checked),
                                    intArrayOf(-android.R.attr.state_checked)
                                ),
                                intArrayOf(
                                    Color.rgb(42, 118, 224),
                                    Color.rgb(185, 185, 185)
                                )
                            )

                            val thumb = android.content.res.ColorStateList(
                                arrayOf(
                                    intArrayOf(android.R.attr.state_checked),
                                    intArrayOf(-android.R.attr.state_checked)
                                ),
                                intArrayOf(
                                    Color.WHITE,
                                    Color.WHITE
                                )
                            )

                            if (Build.VERSION.SDK_INT >= 21) {
                                trackTintList = track
                                thumbTintList = thumb
                            }
                        } catch (_: Exception) {}

                        setOnCheckedChangeListener { _, checked ->

                            prefs.edit()
                                .putBoolean(
                                    key,
                                    checked
                                )
                                .apply()

                            showSettings()
                        }
                    }

                row.addView(
                    switchView,
                    LinearLayout.LayoutParams(
                        dp(48),
                        dp(40)
                    )
                )

                row.setOnClickListener {
                    switchView.isChecked =
                        !switchView.isChecked
                }

            } else {

                val rightSide =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                rightSide.addView(
                    text(
                        if (
                            setting.title == "Skip silence between tracks" ||
                            setting.title == "Control music from lock screen"
                        ) "" else setting.value,
                        13.5f,
                        Color.BLACK,
                        Typeface.NORMAL
                    ).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize =
                            android.text.TextUtils.TruncateAt.END
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    },
                    LinearLayout.LayoutParams(
                        dp(100),
                        dp(40)
                    )
                )

                rightSide.addView(
                    text(
                        "›",
                        20f,
                        Color.rgb(150, 150, 150),
                        Typeface.NORMAL
                    ).apply {
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(
                        dp(20),
                        dp(40)
                    )
                )

                row.addView(
                    rightSide,
                    LinearLayout.LayoutParams(
                        dp(120),
                        dp(40)
                    )
                )

                row.setOnClickListener {
                    showSettingFullScreen(
                        setting.title,
                        setting.value,
                        setting.action
                    )
                }
            }

            card.addView(
                row,
                LinearLayout.LayoutParams(
                    -1,
                    dp(58)
                )
            )

            if (index < settings.lastIndex) {
                card.addView(
                    View(this).apply {
                        setBackgroundColor(
                            Color.rgb(238, 238, 238)
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
            }
        }

        parent.addView(
            card,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(16)
            }
        )
    }




    private fun showSleepTimerDialog() {
        showSettingFullScreen(
            "Sleep timer",
            getSleepTimerLabel()
        ) {}
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

    private fun showPlaybackSpeedDialog() {
        showSettingFullScreen(
            "Play speed",
            getPlaybackSpeedLabel()
        ) {}
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

    private fun showQueueSettingsDialog() {
        showSettingFullScreen(
            "Queue settings",
            "Playback queue"
        ) {}
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

    private fun applyPlaybackSpeed() {

        if (android.os.Build.VERSION.SDK_INT >= 23) {

            val speed =
                getSettingsPrefs()
                    .getFloat(
                        "playback_speed",
                        1.0f
                    )

            mediaPlayer?.let {
                try {
                    it.setPlaybackSpeed(speed)
                } catch (_: Exception) {
                }
            }
        }
    }


    private fun getSleepTimerLabel(): String {

        val minutes =
            getSettingsPrefs()
                .getInt("sleep_timer_minutes", 0)

        return if (minutes <= 0)
            "Off"
        else
            "$minutes min"
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

    private fun getPlaybackSpeedValue(): Float {
        return getSettingsPrefs()
            .getFloat("playback_speed", 1.0f)
            .coerceIn(0.5f, 2.0f)
    }

    private fun getCrossFadeValue(): Int {
        return getSettingsPrefs()
            .getInt("cross_fade_seconds", 0)
            .coerceIn(0, 12)
    }

    private fun addPlaybackSliderCard(
        parent: LinearLayout,
        title: String,
        currentValue: Number
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(10),
                dp(14),
                dp(10),
                dp(14)
            )
        }

        val titleText = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

        container.addView(
            titleText,
            LinearLayout.LayoutParams(
                -1,
                dp(24)
            )
        )

        val valueText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(70, 70, 70))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

        container.addView(
            valueText,
            LinearLayout.LayoutParams(
                -1,
                dp(22)
            ).apply {
                topMargin = dp(2)
            }
        )

        val blue = Color.rgb(42, 118, 224)
        val lightGray = Color.rgb(232, 232, 232)

        val seekBar = object : SeekBar(this) {

            private val trackPaint =
                android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG
                )

            private val activePaint =
                android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG
                )

            override fun onDraw(
                canvas: android.graphics.Canvas
            ) {
                val trackHeight = dp(8).toFloat()
                val radius = trackHeight / 2f

                val left = paddingLeft.toFloat()
                val right =
                    (width - paddingRight).toFloat()

                val centerY = height / 2f
                val top = centerY - radius
                val bottom = centerY + radius

                trackPaint.color = lightGray

                canvas.drawRoundRect(
                    left,
                    top,
                    right,
                    bottom,
                    radius,
                    radius,
                    trackPaint
                )

                val fraction =
                    if (max > 0) {
                        progress.toFloat() /
                            max.toFloat()
                    } else {
                        0f
                    }

                val activeRight =
                    left
                        ((right - left) * fraction)

                if (activeRight > left) {
                    activePaint.color = blue

                    canvas.drawRoundRect(
                        left,
                        top,
                        activeRight,
                        bottom,
                        radius,
                        radius,
                        activePaint
                    )
                }

                super.onDraw(canvas)
            }
        }.apply {

            if (title == "Play speed") {
                max = 15

                val speed =
                    currentValue
                        .toFloat()
                        .coerceIn(0.5f, 2.0f)

                progress =
                    ((speed - 0.5f) * 10f)
                        .roundToInt()

                valueText.text =
                    String.format(
                        java.util.Locale.US,
                        "%.1fx",
                        speed
                    )
            } else {
                max = 12

                val seconds =
                    currentValue
                        .toInt()
                        .coerceIn(0, 12)

                progress = seconds

                valueText.text =
                    if (seconds == 0) {
                        "Off"
                    } else {
                        "${seconds}s"
                    }
            }

            minHeight = dp(28)
            maxHeight = dp(28)

            setPadding(0, 0, 0, 0)

            progressDrawable =
                android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT
                )

            val thumbDrawable =
                android.graphics.drawable.GradientDrawable()
                    .apply {
                        shape =
                            android.graphics.drawable.GradientDrawable
                                .OVAL

                        setColor(Color.WHITE)

                        setStroke(
                            dp(1),
                            blue
                        )

                        setSize(
                            dp(20),
                            dp(20)
                        )
                    }

            thumb = thumbDrawable
            splitTrack = false

            setOnSeekBarChangeListener(
                object :
                    SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return

                        if (title == "Play speed") {
                            val speed =
                                0.5f
                                    (progress / 10f)

                            valueText.text =
                                String.format(
                                    java.util.Locale.US,
                                    "%.1fx",
                                    speed
                                )

                            getSettingsPrefs()
                                .edit()
                                .putFloat(
                                    "playback_speed",
                                    speed
                                )
                                .apply()

                            applyPlaybackSpeed()
                        } else {
                            valueText.text =
                                if (progress == 0) {
                                    "Off"
                                } else {
                                    "${progress}s"
                                }

                            getSettingsPrefs()
                                .edit()
                                .putInt(
                                    "cross_fade_seconds",
                                    progress
                                )
                                .apply()
                        }

                        invalidate()
                    }

                    override fun onStartTrackingTouch(
                        seekBar: SeekBar?
                    ) {
                        seekBar?.animate()?.cancel()

                        seekBar?.animate()
                            ?.scaleX(1.10f)
                            ?.scaleY(1.10f)
                            ?.setDuration(140)
                            ?.setInterpolator(
                                android.view.animation.DecelerateInterpolator()
                            )
                            ?.start()
                    }

                    override fun onStopTrackingTouch(
                        seekBar: SeekBar?
                    ) {
                        seekBar?.animate()?.cancel()

                        seekBar?.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.setDuration(280)
                            ?.setInterpolator(
                                android.view.animation.DecelerateInterpolator()
                            )
                            ?.start()
                    }
                }
            )
        }

        container.addView(
            seekBar,

            LinearLayout.LayoutParams(
                0,
                dp(20),
                0.82f
            ).apply {
                topMargin = dp(5)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        parent.addView(
            container,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )
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
                cornerRadius = dp(10).toFloat()
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
                cornerRadius = dp(10).toFloat()
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

    private fun addSetting(
        titleValue: String,
        value: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(4),
                0,
                dp(4),
                0
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                action()
            }
        }

        val title = text(
            titleValue,
            16f,
            Color.rgb(25, 25, 25),
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                -1,
                1f
            )
        )

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        right.addView(
            text(
                value,
                15f,
                Color.rgb(120, 120, 125),
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                -2,
                -1
            )
        )

        right.addView(
            text(
                "›",
                25f,
                Color.rgb(170, 170, 175),
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                dp(24),
                -1
            )
        )

        row.addView(
            right,
            LinearLayout.LayoutParams(
                -2,
                -1
            )
        )

        content.addView(
            row,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            )
        )

        val separator = View(this).apply {
            setBackgroundColor(Color.rgb(235, 235, 237))
        }

        content.addView(
            separator,
            LinearLayout.LayoutParams(
                -1,
                dp(1)
            )
        )
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
                setImageResource(R.drawable.icon)
                clearColorFilter()
                background = null
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

            background = null
            elevation = 0f

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
            background = null

            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: android.graphics.Outline
                ) {
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        dp(16).toFloat()
                    )
                }
            }

            clipToOutline = true
        }

        val cover = createRealSongCover(
            song,
            imageDp
        ).apply {
            scaleType =
                ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            background = null
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

    private fun getRecentlyPlayedSongs(): List<Song> {
        return songs.take(10)
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
                    onClick()
                }
            }
        }

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            background = GradientDrawable().apply {
                setColor(
                    Color.rgb(
                        235,
                        235,
                        235
                    )
                )
                cornerRadius = dp(10).toFloat()
            }
        }

        getAlbumArt(song)?.let {
            cover.setImageBitmap(it)
        } ?: run {
            cover.setImageResource(R.drawable.icon)
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

    private fun showSearch() {

        // Keep the first ~10% of the screen as top breathing room.
        val topSpaceDp = (resources.displayMetrics.heightPixels /
            resources.displayMetrics.density * 0.10f).toInt()

        content.removeAllViews()

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
            setPadding(0, topSpaceDp, 0, 0)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val page = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
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
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 225))
            }
            elevation = dp(1).toFloat()
        }

        val searchInput = EditText(this).apply {
            hint = "What do you want to listen to?"
            textSize = 15f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.rgb(115, 115, 115))
            background = null
            setSingleLine(true)
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
                } else {
                    image.setImageResource(R.drawable.icon)
                }
            } else {
                image.setImageResource(R.drawable.icon)
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
            setPadding(dp(14), dp(6), dp(8), dp(6))

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    shape =
                        android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
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
                dp(32),
                dp(32)
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
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isFocusable = true
            isFocusableInTouchMode = true
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
        playButton = ImageView(this).apply {
            setImageResource(
                if (mediaPlayer?.isPlaying == true)
                    com.music.app.R.drawable.ic_player_pause
                else
                    com.music.app.R.drawable.ic_music_play
            )

            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setColorFilter(
                android.graphics.PorterDuffColorFilter(
                    Color.BLACK,
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            )
            background = null
            contentDescription =
                if (mediaPlayer?.isPlaying == true)
                    "Pause"
                else
                    "Play"

            setOnClickListener {
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) {
                            it.pause()
                            setImageResource(
                                com.music.app.R.drawable.ic_music_play
                            )
                            contentDescription = "Play"
                        } else {
                            it.play()
                            setImageResource(
                                com.music.app.R.drawable.ic_player_pause
                            )
                            contentDescription = "Pause"
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        layout.addView(
            playButton,
            LinearLayout.LayoutParams(
                dp(50),
                dp(50)
            ).apply {
                rightMargin = dp(-8)
            }
        )

        // ---------- PLAY NEXT ----------
        val miniPlayNext = ImageView(this).apply {
            setImageResource(
                com.music.app.R.drawable.ic_music_backward
            )
            scaleX = -1f

            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setColorFilter(
                android.graphics.PorterDuffColorFilter(
                    Color.BLACK,
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            )
            background = null
            contentDescription = "Play Next"

            setOnClickListener {
                currentSong?.let { song ->
                    playSongNext(song)
                }
            }
        }

        layout.addView(
            miniPlayNext,
            LinearLayout.LayoutParams(
                dp(50),
                dp(50)
            ).apply {
                leftMargin = dp(-8)
            }
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
                setImageResource(R.drawable.icon)
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
                dp(48),
                dp(48),
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
            miniLocation[0]
                miniCover.width / 2f

        val miniCenterY =
            miniLocation[1]
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
            miniCenterX
                (targetCenterX - miniCenterX) * progress

        val centerY =
            miniCenterY
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
            cardLocation[0].toFloat() +
                card.width / 2f

        val cardCenterY =
            cardLocation[1].toFloat() +
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


    /*
     * Drives the Full Player-like elements during the same
     * Mini -> Full gesture.
     *
     * 0.0  = completely hidden
     * 0.5  = controls begin entering
     * 1.0  = final Full Player position
     */
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

    private fun createMiniExpansionBackground(
        song: Song
    ): GradientDrawable {

        val bitmap =
            getAlbumArt(song)

        if (bitmap == null) {

            return GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(80, 80, 80),
                    Color.rgb(20, 20, 20)
                )
            ).apply {

                cornerRadii =
                    floatArrayOf(
                        dp(18).toFloat(),
                        dp(18).toFloat(),
                        dp(18).toFloat(),
                        dp(18).toFloat(),
                        0f,
                        0f,
                        0f,
                        0f
                    )
            }
        }

        val points =
            intArrayOf(
                bitmap.getPixel(
                    0,
                    0
                ),
                bitmap.getPixel(
                    bitmap.width / 2,
                    bitmap.height / 2
                ),
                bitmap.getPixel(
                    bitmap.width - 1,
                    bitmap.height - 1
                )
            )

        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            points
        ).apply {

            cornerRadii =
                floatArrayOf(
                    dp(18).toFloat(),
                    dp(18).toFloat(),
                    dp(18).toFloat(),
                    dp(18).toFloat(),
                    0f,
                    0f,
                    0f,
                    0f
                )
        }
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

    private fun animateMiniSongTextChange(
        song: Song,
        direction: Int
    ) {
        val distance =
            dp(22).toFloat()

        val outX =
            if (direction > 0) {
                -distance
            } else {
                distance
            }

        val inX =
            -outX

        miniTitle.animate().cancel()
        miniArtist.animate().cancel()

        miniTitle.animate()
            .alpha(0f)
            .translationX(outX)
            .setDuration(150L)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .withEndAction {

                miniTitle.text =
                    song.title

                miniTitle.translationX =
                    inX

                miniTitle.alpha = 0f

                miniTitle.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(240L)
                    .setInterpolator(
                        DecelerateInterpolator()
                    )
                    .start()
            }
            .start()

        miniArtist.animate()
            .alpha(0f)
            .translationX(outX)
            .setDuration(150L)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .withEndAction {

                miniArtist.text =
                    song.artist

                miniArtist.translationX =
                    inX

                miniArtist.alpha = 0f

                miniArtist.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(240L)
                    .setInterpolator(
                        DecelerateInterpolator()
                    )
                    .start()
            }
            .start()
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

    private fun cancelMiniHide() {
        miniHideRunnable?.let {
            miniPlayer.removeCallbacks(it)
        }

        miniHideRunnable = null
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


    private fun connectToPlaybackService() {

        if (mediaPlayer != null) {
            return
        }

        if (mediaControllerFuture != null) {
            return
        }

        val sessionToken =
            SessionToken(
                this,
                ComponentName(
                    this,
                    MusicPlaybackService::class.java
                )
            )

        val future =
            MediaController.Builder(
                this,
                sessionToken
            ).buildAsync()

        mediaControllerFuture = future

        future.addListener(
            {
                try {

                    val controller =
                        future.get()

                    mediaPlayer = controller
                    mediaControllerFuture = null

                    controller.addListener(
                        mediaControllerListener
                    )

                    /*
                     * The Activity can now restore the visible
                     * song state without starting playback.
                     */
                    restorePlayerState()

                    updatePlaybackUiFromController()

                } catch (_: Exception) {
                    mediaPlayer = null
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun updatePlaybackUiFromController() {

        val controller =
            mediaPlayer
                ?: return

        val item =
            controller.currentMediaItem
                ?: return

        val id =
            item.mediaId.toLongOrNull()
                ?: return

        val song =
            songs.firstOrNull {
                it.id == id
            }
                ?: querySongById(id)
                ?: return

        currentSong = song

        showMiniPlayer()

        getAlbumArt(song)?.let {
            miniCover.setImageBitmap(it)
        } ?: run {
            miniCover.setImageResource(
                R.drawable.icon
            )
        }

        miniTitle.text = song.title
        miniArtist.text = song.artist

        if (
            ::playButton.isInitialized
        ) {
            playButton.setImageResource(
                if (controller.isPlaying)
                    com.music.app.R.drawable.ic_player_pause
                else
                    com.music.app.R.drawable.ic_music_play
            )
        }
    }

    private fun querySongById(
        id: Long
    ): Song? {

        val projection =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST
            )

        return try {

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->

                if (!cursor.moveToFirst()) {
                    return@use null
                }

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
            }

        } catch (_: Exception) {
            null
        }
    }

    private fun artworkBytes(
        song: Song
    ): ByteArray? {

        val bitmap =
            getAlbumArt(song)
                ?: return null

        return try {

            val maxSide = 512

            val scale =
                minOf(
                    1f,
                    maxSide.toFloat() /
                        maxOf(
                            bitmap.width,
                            bitmap.height
                        ).toFloat()
                )

            val outputBitmap =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale)
                            .roundToInt()
                            .coerceAtLeast(1),
                        (bitmap.height * scale)
                            .roundToInt()
                            .coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }

            ByteArrayOutputStream().use { stream ->

                outputBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    85,
                    stream
                )

                if (outputBitmap !== bitmap) {
                    outputBitmap.recycle()
                }

                stream.toByteArray()
            }

        } catch (_: Exception) {
            null
        }
    }

    private fun mediaItemForSong(
        song: Song
    ): MediaItem {

        val uri =
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                song.id
            )

        val metadataBuilder =
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)

        artworkBytes(song)?.let {
            metadataBuilder.setArtworkData(
                it,
                MediaMetadata.PICTURE_TYPE_FRONT_COVER
            )
        }

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                metadataBuilder.build()
            )
            .build()
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
            .putLong("position", position)
            .apply()
    }

    private fun restorePlayerState() {

        val controller =
            mediaPlayer
                ?: return

        /*
         * First restore from the Media3 controller.
         * This covers the case where the playback service
         * is still alive after the Activity was closed.
         */
        val controllerItem =
            controller.currentMediaItem

        if (controllerItem != null) {

            val id =
                controllerItem.mediaId
                    .toLongOrNull()

            if (id != null) {

                val song =
                    songs.firstOrNull {
                        it.id == id
                    }
                        ?: querySongById(id)

                if (song != null) {

                    currentSong = song

                    if (
                        playbackQueue.none {
                            it.id == song.id
                        }
                    ) {
                        playbackQueue =
                            mutableListOf(song)

                        playbackIndex = 0
                    } else {
                        playbackIndex =
                            playbackQueue.indexOfFirst {
                                it.id == song.id
                            }
                    }

                    showMiniPlayer()

                    getAlbumArt(song)?.let {
                        miniCover.setImageBitmap(it)
                    } ?: run {
                        miniCover.setImageResource(
                            R.drawable.icon
                        )
                    }

                    miniTitle.text = song.title
                    miniArtist.text = song.artist

                    if (
                        ::playButton.isInitialized
                    ) {
                        playButton.setImageResource(
                            if (controller.isPlaying)
                                com.music.app.R.drawable.ic_player_pause
                            else
                                com.music.app.R.drawable.ic_music_play
                        )
                    }

                    return
                }
            }
        }

        /*
         * The playback service no longer has a MediaItem.
         * Restore the last song and position saved by the Activity,
         * but do NOT start playback automatically.
         */
        val songId =
            playerPrefs.getLong(
                "song_id",
                -1L
            )

        if (songId <= 0L) {
            return
        }

        val position =
            playerPrefs.getLong(
                "position",
                0L
            )

        val song =
            songs.firstOrNull {
                it.id == songId
            }
                ?: querySongById(songId)
                ?: return

        currentSong = song

        playbackQueue =
            mutableListOf(song)

        playbackIndex = 0

        restoredPosition =
            position.coerceAtLeast(0L)

        showMiniPlayer()

        getAlbumArt(song)?.let {
            miniCover.setImageBitmap(it)
        } ?: run {
            miniCover.setImageResource(
                R.drawable.icon
            )
        }

        miniTitle.text = song.title
        miniArtist.text = song.artist

        /*
         * Put the restored song into Media3 so that pressing
         * Play later continues from the saved position.
         */
        try {
            controller.setMediaItem(
                mediaItemForSong(song),
                position.coerceAtLeast(0L)
            )
            controller.prepare()
        } catch (_: Exception) {
        }

        if (
            ::playButton.isInitialized
        ) {
            playButton.setImageResource(
                com.music.app.R.drawable.ic_music_play
            )
        }
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

            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)

            clipToOutline = false
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
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        val iconSize = if (title == "Home") dp(22) else dp(25)

        item.addView(
            icon,
            LinearLayout.LayoutParams(
                iconSize,
                iconSize
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

    private fun getProfileName(): String {
        return getSharedPreferences("profile", MODE_PRIVATE)
            .getString("name", "Your Name")
            ?: "Your Name"
    }

    private fun saveProfileName(name: String) {
        getSharedPreferences("profile", MODE_PRIVATE)
            .edit()
            .putString("name", name.trim())
            .apply()
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

            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
            )

            background =
                GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.WHITE)
                }

            elevation = dp(18).toFloat()
        }

        val play = text(
            "Play",
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(
                dp(12),
                dp(16),
                dp(28),
                dp(16)
            )
            setOnClickListener {
                playSong(song)
                popup.dismiss()
            }
        }

        val favorite = text(
            if (isFavorite(song)) {
                "Remove from Favorites"
            } else {
                "Add to Favorites"
            },
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(
                dp(12),
                dp(16),
                dp(28),
                dp(16)
            )
            setOnClickListener {
                val newState = !isFavorite(song)

                setFavorite(song, newState)

                Toast.makeText(
                    this@MainActivity,
                    if (newState) {
                        "Added to Favorites"
                    } else {
                        "Removed from Favorites"
                    },
                    Toast.LENGTH_SHORT
                ).show()

                popup.dismiss()
            }
        }

        val share = text(
            "Share",
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(
                dp(12),
                dp(16),
                dp(28),
                dp(16)
            )

            setOnClickListener {
                shareSong(song)
                popup.dismiss()
            }
        }

        val select = text(
            "Select",
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(
                dp(12),
                dp(16),
                dp(28),
                dp(16)
            )

            setOnClickListener {
                selectLibrarySong(song)
                popup.dismiss()
            }
        }

        val info = text(
            "Song Info",
            16f,
            Color.BLACK,
            Typeface.NORMAL
        ).apply {
            setPadding(
                dp(12),
                dp(16),
                dp(28),
                dp(16)
            )
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
        box.addView(share)
        box.addView(select)
        box.addView(info)

        popup.contentView = box
        popup.width = dp(250)
        popup.height = -2
        popup.isFocusable = true
        popup.isOutsideTouchable = true

        popup.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT
            )
        )

        popup.elevation = dp(20).toFloat()

        /*
         * Open inward from the three-dot button:
         * leftward and slightly upward.
         */
        popup.showAsDropDown(
            anchor,
            -dp(218),
            -dp(170)
        )

        box.alpha = 0f
        box.scaleX = 0.90f
        box.scaleY = 0.90f
        box.translationX = dp(10).toFloat()

        box.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .setDuration(190L)
            .setInterpolator(
                android.view.animation.DecelerateInterpolator(
                    1.7f
                )
            )
            .start()
    }

    private fun showLibrarySongPopup(
        anchor: View,
        song: Song
    ) {

        val popup = android.widget.PopupWindow(
            this
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(7),
                dp(7),
                dp(7),
                dp(7)
            )

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.WHITE)
                }

            elevation = dp(18).toFloat()
        }

        fun popupItem(
            icon: String,
            title: String,
            action: () -> Unit
        ) {

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.WHITE)
                    }

                setPadding(
                    dp(8),
                    dp(3),
                    dp(12),
                    dp(3)
                )

                setOnClickListener {
                    popup.dismiss()
                    action()
                }

                setOnTouchListener { view, event ->

                    when (event.action) {

                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.animate()
                                .scaleX(0.97f)
                                .scaleY(0.97f)
                                .setDuration(90)
                                .start()
                        }

                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(120)
                                .start()
                        }
                    }

                    false
                }
            }

            val iconView = TextView(this).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                includeFontPadding = false
            }

            item.addView(
                iconView,
                LinearLayout.LayoutParams(
                    dp(34),
                    dp(44)
                )
            )

            item.addView(
                TextView(this).apply {
                    text = title
                    textSize = 15f
                    setTextColor(Color.rgb(25, 25, 25))
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f
                )
            )

            box.addView(
                item,
                LinearLayout.LayoutParams(
                    dp(240),
                    dp(44)
                )
            )
        }

        popupItem(
            "▶",
            "Play"
        ) {
            playSong(song)
        }

        popupItem(
            "♧",
            "Add to Queue"
        ) {
            playbackQueue.add(song)

            if (playbackQueue.size == 1) {
                playbackIndex = 0
            }

            Toast.makeText(
                this,
                "Added to queue",
                Toast.LENGTH_SHORT
            ).show()
        }

        popupItem(
            if (isFavorite(song)) "♥" else "♡",
            if (isFavorite(song))
                "Remove from Favorites"
            else
                "Add to Favorites"
        ) {
            val newState = !isFavorite(song)

            setFavorite(
                song,
                newState
            )

            Toast.makeText(
                this,
                if (newState)
                    "Added to Favorites"
                else
                    "Removed from Favorites",
                Toast.LENGTH_SHORT
            ).show()
        }

        popupItem(
            "＋",
            "Add to Playlist"
        ) {
            showCreatePlaylistDialog()
        }

        popupItem(
            "ⓘ",
            "Song Info"
        ) {
            showSongPopup(
                anchor,
                song
            )
        }

        popup.contentView = box
        popup.width = dp(254)
        popup.height = -2
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT
            )
        )
        popup.elevation = dp(18).toFloat()

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)

        val anchorY = location[1]
        val anchorHeight = anchor.height

        val screenHeight =
            resources.displayMetrics.heightPixels

        val anchorCenter =
            anchorY + (anchorHeight / 2)

        val popupHeightEstimate =
            dp(7 + (44 * 5) + 7)

        val belowHalf =
            anchorCenter < screenHeight / 2

        popup.showAsDropDown(
            anchor,
            -dp(205),
            if (belowHalf) {
                -dp(2)
            } else {
                -(anchorHeight + popupHeightEstimate)
            }
        )

        val content = popup.contentView

        content.alpha = 0f
        content.scaleX = 0.94f
        content.scaleY = 0.94f
        content.translationY =
            if (belowHalf) dp(-8).toFloat()
            else dp(8).toFloat()

        content.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(190)
            .setInterpolator(
                android.view.animation.DecelerateInterpolator(1.7f)
            )
            .start()
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

        addPopupItem(
            if (isFavorite(song)) {
                "Remove from Favorites"
            } else {
                "Add to Favorites"
            }
        ) {
            val newState = !isFavorite(song)

            setFavorite(song, newState)

            Toast.makeText(
                this,
                if (newState) {
                    "Added to favorites"
                } else {
                    "Removed from favorites"
                },
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
                    cover.setImageResource(R.drawable.icon)
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

                    root.animate().cancel()
                    cover.animate().cancel()

                    root.findViewWithTag<View>(
                        "full_player_controls"
                    )?.animate()?.cancel()

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
                        coverLocation[0].toFloat() +
                            cover.width / 2f

                    fullPlayerCloseStartCenterY =
                        coverLocation[1].toFloat() +
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

        val miniSize =
            dp(46).toFloat()

        val miniCenterX =
            miniLocation[0]
                miniSize / 2f

        val miniCenterY =
            miniLocation[1]
                miniSize / 2f

        val screenHeight =
            resources.displayMetrics
                .heightPixels
                .toFloat()

        /*
         * The Full Player background follows the finger
         * without fading.
         */
        val rootTranslationY =
            screenHeight *
                0.72f *
                progress

        root.translationY =
            rootTranslationY

        /*
         * As the Full Player is pulled down, expose rounded
         * top corners progressively.
         *
         * At rest the player is full-screen.
         * During the gesture it becomes a rounded panel.
         */
        root.outlineProvider =
            object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: android.graphics.Outline
                ) {
                    val radius =
                        dp(28).toFloat() * progress

                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        radius
                    )
                }
            }

        root.clipToOutline = true
        root.invalidateOutline()

        /*
         * Artwork follows a direct screen-space path
         * toward the Mini Player cover.
         */
        val centerX =
            fullPlayerCloseStartCenterX
                (
                    miniCenterX -
                        fullPlayerCloseStartCenterX
                ) * progress

        val centerY =
            fullPlayerCloseStartCenterY
                (
                    miniCenterY -
                        fullPlayerCloseStartCenterY
                ) * progress

        val containerLocation =
            IntArray(2)

        coverContainer.getLocationOnScreen(
            containerLocation
        )

        val containerCenterX =
            containerLocation[0]
                coverContainer.width / 2f

        val containerCenterY =
            containerLocation[1]
                coverContainer.height / 2f

        cover.translationX =
            centerX -
                containerCenterX

        cover.translationY =
            centerY -
                containerCenterY -
                rootTranslationY

        val fullWidth =
            cover.width
                .coerceAtLeast(1)
                .toFloat()

        val targetScale =
            miniSize /
                fullWidth

        val scale =
            fullPlayerCloseInitialScale
                (
                    targetScale -
                        fullPlayerCloseInitialScale
                ) * progress

        cover.scaleX = scale
        cover.scaleY = scale

        /*
         * Controls are completely gone when the finger
         * reaches 50% of the physical screen height.
         */
        val controlProgress =
            (
                progress /
                    (0.5f / 0.72f)
            )
                .coerceIn(0f, 1f)

        root.findViewWithTag<View>(
            "full_player_controls"
        )?.apply {

            translationY =
                -dp(10).toFloat()
                    dp(120).toFloat() *
                    controlProgress

            alpha =
                1f -
                    controlProgress
        }

        /*
         * Mini Player and navigation remain hidden
         * during the interactive drag.
         */
        miniPlayer.alpha = 0f

        miniBottomNavigation?.apply {
            translationY =
                dp(82).toFloat()

            alpha = 0f
        }
    }

    private fun restoreFullPlayerFromGesture(
        root: View,
        cover: ImageView
    ) {

        val controls =
            root.findViewWithTag<View>(
                "full_player_controls"
            )

        root.outlineProvider =
            object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: android.graphics.Outline
                ) {
                    outline.setRect(
                        0,
                        0,
                        view.width,
                        view.height
                    )
                }
            }

        root.clipToOutline = false
        root.invalidateOutline()

        root.animate()
            .translationY(0f)
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

        controls?.animate()
            ?.translationY(
                -dp(10).toFloat()
            )
            ?.alpha(1f)
            ?.setDuration(300L)
            ?.setInterpolator(
                DecelerateInterpolator()
            )
            ?.start()

        miniPlayer.animate()
            .alpha(0f)
            .setDuration(220L)
            .start()

        miniBottomNavigation?.animate()
            ?.translationY(
                dp(82).toFloat()
            )
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

        val miniSize =
            dp(46).toFloat()

        val miniCenterX =
            miniLocation[0]
                miniSize / 2f

        val miniCenterY =
            miniLocation[1]
                miniSize / 2f

        val currentCoverLocation =
            IntArray(2)

        cover.getLocationOnScreen(
            currentCoverLocation
        )

        val currentCenterX =
            currentCoverLocation[0]
                cover.width / 2f

        val currentCenterY =
            currentCoverLocation[1]
                cover.height / 2f

        val finalX =
            miniCenterX -
                currentCenterX

        val finalY =
            miniCenterY -
                currentCenterY

        val fullWidth =
            cover.width
                .coerceAtLeast(1)
                .toFloat()

        val closeDuration =
            280L

        miniPlayer.alpha = 0f

        miniBottomNavigation?.apply {
            translationY =
                dp(82).toFloat()

            alpha = 0f
        }

        cover.animate()
            .translationXBy(finalX.toFloat())
            .translationYBy(finalY.toFloat())
            .scaleX(
                miniSize /
                    fullWidth
            )
            .scaleY(
                miniSize /
                    fullWidth
            )
            .setDuration(closeDuration)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .start()

        miniPlayer.animate()
            .alpha(1f)
            .setDuration(closeDuration)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .start()

        miniBottomNavigation?.animate()
            ?.translationY(0f)
            ?.alpha(1f)
            ?.setDuration(closeDuration)
            ?.setInterpolator(
                DecelerateInterpolator()
            )
            ?.start()

        root.animate()
            .translationY(root.translationY)
            .alpha(1f)
            .setDuration(closeDuration)
            .setInterpolator(
                DecelerateInterpolator()
            )
            .withEndAction {

                root.translationY = 0f
                root.alpha = 1f

                root.outlineProvider =
                    object : android.view.ViewOutlineProvider() {
                        override fun getOutline(
                            view: View,
                            outline: android.graphics.Outline
                        ) {
                            outline.setRect(
                                0,
                                0,
                                view.width,
                                view.height
                            )
                        }
                    }

                root.clipToOutline = false
                root.invalidateOutline()

                root.findViewWithTag<View>(
                    "full_player_controls"
                )?.apply {
                    translationY =
                        -dp(10).toFloat()
                    alpha = 1f
                }

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

            /*
             * The Full Player is translated downward during
             * the interactive close gesture. Enable outline
             * clipping so the top corners are actually visible.
             */
            clipToOutline = true

            outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline
                    ) {
                        val radius = dp(28).toFloat()

                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height,
                            radius
                        )
                    }
                }
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

        val fallbackArtwork =
            android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.icon
            )

        if (getAlbumArt(song) == null && fallbackArtwork != null) {
            currentColors = extractColors(fallbackArtwork)
        }

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
            } ?: run {
                setImageResource(R.drawable.icon)
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
                tag = "full_player_controls"
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
                elevation = dp(12).toFloat()
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

        val titleRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val favorite =
            ImageView(this).apply {
                setImageResource(
                    R.drawable.ic_music_favourite_outline
                )
                scaleType =
                    ImageView.ScaleType.CENTER_INSIDE

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    val nowFavorite =
                        !isFavorite(song)

                    setFavorite(
                        song,
                        nowFavorite
                    )

                    Toast.makeText(
                        this@MainActivity,
                        if (nowFavorite) {
                            "Added to Favorites"
                        } else {
                            "Removed from Favorites"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        titleRow.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(28),
                1f
            )
        )

        titleRow.addView(
            favorite,
            LinearLayout.LayoutParams(
                dp(24),
                dp(24)
            ).apply {
                marginStart = dp(8)
            }
        )

        info.addView(
            titleRow,
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

        info.elevation = dp(40).toFloat()
        info.clipChildren = false
        info.clipToPadding = false

        bottomPanel.clipChildren = false
        bottomPanel.clipToPadding = false

        bottomPanel.addView(
            info,
            LinearLayout.LayoutParams(
                -1,
                dp(55)
            ).apply {
                topMargin = -dp(15)
            }
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

                                    it.seekTo(position)
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

        bottomPanel.addView(
            seekBar,
            LinearLayout.LayoutParams(
                -1,
                dp(10)
            ).apply {
                topMargin = -dp(9)
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
                    com.music.app.R.drawable.ic_music_backward_full
                )

                scaleType =
                    android.widget.ImageView.ScaleType.CENTER_INSIDE

                background = null

                contentDescription =
                    "Previous"

                setPadding(
                    dp(4),
                    dp(4),
                    dp(4),
                    dp(4)
                )

                setColorFilter(
                    android.graphics.PorterDuffColorFilter(
                        Color.WHITE,
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
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
                        com.music.app.R.drawable.ic_music_play_full
                )

                scaleType =
                    android.widget.ImageView.ScaleType.CENTER_INSIDE

                scaleX = 1f
                scaleY = 1f

                background = null

                contentDescription =
                    if (mediaPlayer?.isPlaying == true)
                        "Pause"
                    else
                        "Play"

                setPadding(
                    dp(4),
                    dp(4),
                    dp(4),
                    dp(4)
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
                                    com.music.app.R.drawable.ic_music_play
                                )

                                contentDescription = "Play"

                                playButton.setImageResource(
                                    com.music.app.R.drawable.ic_music_play
                                )

                            } else {

                                it.play()

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

                                playButton.setImageResource(
                                    com.music.app.R.drawable.ic_player_pause
                                )
                            }

                        } catch (_: Exception) {
                        }
                    }
                }
            }

        val next =
            ImageView(this).apply {

                setImageResource(
                    com.music.app.R.drawable.ic_music_backward_full
                )
                scaleX = -1f

                scaleType =
                    android.widget.ImageView.ScaleType.CENTER_INSIDE

                background = null

                contentDescription =
                    "Next"

                setPadding(
                    dp(4),
                    dp(4),
                    dp(4),
                    dp(4)
                )

                setColorFilter(
                    android.graphics.PorterDuffColorFilter(
                        Color.WHITE,
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
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
                dp(70),
                dp(70)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        )

        controls.addView(
            play,
            LinearLayout.LayoutParams(
                dp(78),
                dp(78)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(1)
                marginEnd = dp(1)
            }
        )

        controls.addView(
            next,
            LinearLayout.LayoutParams(
                dp(70),
                dp(70)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        )

        bottomPanel.addView(
            controls,
            LinearLayout.LayoutParams(
                -1,
                dp(84)
            ).apply {
                topMargin = -dp(10)
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

            box.addView(
                image,
                LinearLayout.LayoutParams(
                    dp(24),
                    dp(24)
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
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        )

        secondary.addView(
            cast,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        )

        secondary.addView(
            queue,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
            ).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
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
        } ?: run {
            queueCover.setImageResource(R.drawable.icon)
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

                shareSong(song)
            }

            addPopupItem("Select") {

                selectLibrarySong(song)
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

                val nowFavorite =
                    !isFavorite(song)

                setFavorite(
                    song,
                    nowFavorite
                )

                android.widget.Toast.makeText(
                    this,
                    if (nowFavorite) {
                        "Added to Favorites"
                    } else {
                        "Removed from Favorites"
                    },
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            addPopupItem("Suggest Less") {

                setSuggestLess(
                    song,
                    true
                )

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
                                .scaleX(2f)
                                .scaleY(2f)
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
                                .scaleX(2f)
                                .scaleY(2f)
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
            } ?: run {
                cover.setImageResource(R.drawable.icon)
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

        fun formatTime(ms: Long): String {

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
                                        com.music.app.R.drawable.ic_music_play
                                )

                                playButton.setImageResource(
                                    if (
                                        player.isPlaying
                                    )
                                        com.music.app.R.drawable.ic_player_pause
                                    else
                                        com.music.app.R.drawable.ic_music_play
                                )
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

                    /*
                     * The real Full Player cover is now measurable.
                     * Re-apply the current transition immediately so
                     * the expansion artwork and the real artwork share
                     * exactly the same center/size.
                     */
                    updateMiniExpansionCard(
                        miniFullTransitionProgress
                    )

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

    private val libraryPrefs by lazy {
        getSharedPreferences("music_library", MODE_PRIVATE)
    }

    private fun isFavorite(song: Song): Boolean {
        val ids = libraryPrefs.getStringSet(
            "favorite_ids",
            emptySet()
        ) ?: emptySet()

        return ids.contains(song.id.toString())
    }

    private fun setFavorite(song: Song, favorite: Boolean) {
        val ids = (
            libraryPrefs.getStringSet(
                "favorite_ids",
                emptySet()
            ) ?: emptySet()
        ).toMutableSet()

        if (favorite) {
            ids.add(song.id.toString())
        } else {
            ids.remove(song.id.toString())
        }

        libraryPrefs.edit()
            .putStringSet("favorite_ids", ids)
            .apply()
    }

    private fun incrementPlayCount(song: Song) {
        val key = "play_count_${song.id}"
        val current = libraryPrefs.getInt(key, 0)

        libraryPrefs.edit()
            .putInt(key, current + 1)
            .apply()
    }

    // ============================================================
    // Song actions
    // ============================================================

    private fun shareSong(song: Song) {

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

    private fun setSuggestLess(
        song: Song,
        enabled: Boolean
    ) {

        val ids =
            libraryPrefs.getStringSet(
                "suggest_less_ids",
                emptySet()
            )
                ?.toMutableSet()
                ?: mutableSetOf()

        if (enabled) {
            ids.add(song.id.toString())
        } else {
            ids.remove(song.id.toString())
        }

        libraryPrefs.edit()
            .putStringSet(
                "suggest_less_ids",
                ids
            )
            .apply()
    }

    private fun isSuggestLess(
        song: Song
    ): Boolean {

        return libraryPrefs
            .getStringSet(
                "suggest_less_ids",
                emptySet()
            )
            ?.contains(song.id.toString())
            ?: false
    }

    private fun selectLibrarySong(
        song: Song
    ) {

        android.app.AlertDialog.Builder(this)
            .setTitle("Select")
            .setMessage(
                "${song.title}\n${song.artist}"
            )
            .setPositiveButton(
                "Selected",
                null
            )
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun playSongNext(
        song: Song
    ) {

        val controller =
            mediaPlayer

        if (controller == null) {

            connectToPlaybackService()

            Toast.makeText(
                this,
                "Playback is still connecting",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

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

            return
        }

        if (playbackQueue.isEmpty()) {

            playSong(song)

            return
        }

        val insertIndex =
            (playbackIndex + 1)
                .coerceIn(
                    0,
                    playbackQueue.size
                )

        playbackQueue.add(
            insertIndex,
            song
        )

        try {

            controller.addMediaItem(
                insertIndex,
                mediaItemForSong(song)
            )

            Toast.makeText(
                this,
                "Added to Play Next",
                Toast.LENGTH_SHORT
            ).show()

        } catch (_: Exception) {

            playbackQueue.removeAt(
                insertIndex
            )

            Toast.makeText(
                this,
                "Couldn't add to Play Next",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showSongActionSheet(
        song: Song
    ) {

        val overlay =
            FrameLayout(this).apply {

                setBackgroundColor(
                    Color.argb(
                        90,
                        0,
                        0,
                        0
                    )
                )

                isClickable = true
                isFocusable = true
            }

        val panel =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.WHITE
                )

                elevation =
                    dp(18).toFloat()

                setPadding(
                    dp(18),
                    dp(18),
                    dp(18),
                    dp(26)
                )

                clipToPadding = false
            }

        val sheetHeight =
            dp(430)

        val panelParams =
            FrameLayout.LayoutParams(
                -1,
                sheetHeight,
                Gravity.BOTTOM
            )

        val header =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val coverHolder =
            FrameLayout(this).apply {

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.RECTANGLE

                        cornerRadius =
                            dp(14).toFloat()

                        setColor(
                            Color.rgb(
                                235,
                                235,
                                235
                            )
                        )
                    }

                clipToOutline = true

                outlineProvider =
                    object :
                        android.view.ViewOutlineProvider() {

                        override fun getOutline(
                            view: View,
                            outline: android.graphics.Outline
                        ) {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width,
                                view.height,
                                dp(14).toFloat()
                            )
                        }
                    }
            }

        val cover =
            createRealSongCover(
                song,
                112
            )

        coverHolder.addView(
            cover,
            FrameLayout.LayoutParams(
                dp(112),
                dp(112)
            )
        )

        val playOverlay =
            TextView(this).apply {

                text = "▶"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            Color.argb(
                                190,
                                255,
                                255,
                                255
                            )
                        )
                    }

                setOnClickListener {
                    playSong(song)
                }
            }

        coverHolder.addView(
            playOverlay,
            FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.CENTER
            )
        )

        header.addView(
            coverHolder,
            LinearLayout.LayoutParams(
                dp(112),
                dp(112)
            )
        )

        val info =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(16),
                    0,
                    0,
                    0
                )
            }

        val title =
            TextView(this).apply {

                text = song.title
                textSize = 18f
                setTextColor(Color.BLACK)

                typeface =
                    Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )

                maxLines = 2

                ellipsize =
                    android.text.TextUtils.TruncateAt.END

                includeFontPadding = false
            }

        val artist =
            TextView(this).apply {

                text = song.artist
                textSize = 14f

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
                    dp(8),
                    0,
                    0
                )
            }

        info.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        info.addView(
            artist,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        header.addView(
            info,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        panel.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                dp(112)
            )
        )

        panel.addView(
            View(this).apply {
                setBackgroundColor(Color.BLACK)
            },
            LinearLayout.LayoutParams(
                -1,
                dp(1)
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(8)
            }
        )

        fun addAction(
            iconRes: Int,
            fallbackIcon: String,
            textValue: String,
            mirrorIcon: Boolean = false,
            action: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(
                    dp(20),
                    0,
                    dp(20),
                    0
                )
                setOnClickListener {
                    action()
                }
            }

            val iconView = ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER
                scaleX = if (mirrorIcon) -1f else 1f
                translationY = -dp(2).toFloat()
                contentDescription = fallbackIcon
            }

            val label = TextView(this).apply {
                text = textValue
                textSize = 16f
                setTextColor(Color.BLACK)
                includeFontPadding = false
                translationY = dp(2).toFloat()
            }

            row.addView(
                iconView,
                LinearLayout.LayoutParams(
                    dp(24),
                    dp(54)
                )
            )

            row.addView(
                label,
                LinearLayout.LayoutParams(
                    0,
                    dp(54),
                    1f
                ).apply {
                    marginStart = dp(16)
                }
            )

            panel.addView(
                row,
                LinearLayout.LayoutParams(
                    -1,
                    dp(54)
                )
            )
        }

        addAction(
            R.drawable.ic_music_backward,
            "Play",
            "Play Next",
            true
        ) {
            playSongNext(song)
        }

        addAction(
            R.drawable.ic_music_share,
            "Share",
            "Share Song"
        ) {
            shareSong(song)
        }

        addAction(
            R.drawable.ic_music_favourite,
            "Favorite",
            if (isFavorite(song)) {
                "Remove from Favorites"
            } else {
                "Favorite"
            }
        ) {

            val nowFavorite =
                !isFavorite(song)

            setFavorite(
                song,
                nowFavorite
            )

            Toast.makeText(
                this,
                if (nowFavorite) {
                    "Added to Favorites"
                } else {
                    "Removed from Favorites"
                },
                Toast.LENGTH_SHORT
            ).show()
        }

        addAction(
            R.drawable.ic_music_dislike,
            "Suggest Less",
            "Suggest Less"
        ) {

            setSuggestLess(
                song,
                true
            )

            Toast.makeText(
                this,
                "We'll suggest less like this",
                Toast.LENGTH_SHORT
            ).show()
        }

        overlay.addView(
            panel,
            panelParams
        )

        val root =
            window.decorView
                .findViewById<ViewGroup>(
                    android.R.id.content
                )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )

        panel.translationY =
            sheetHeight.toFloat()

        panel.animate()
            .translationY(0f)
            .setDuration(360L)
            .setInterpolator(
                android.view.animation.PathInterpolator(
                    0.22f,
                    1f,
                    0.36f,
                    1f
                )
            )
            .start()

        overlay.setOnClickListener {
            closeSongActionSheet(
                overlay,
                panel
            )
        }

        panel.setOnClickListener {
            // Consume touches inside the sheet.
        }

        overlay.setOnKeyListener {
            _,
            keyCode,
            event ->

            if (
                keyCode ==
                android.view.KeyEvent.KEYCODE_BACK &&
                event.action ==
                android.view.KeyEvent.ACTION_UP
            ) {

                closeSongActionSheet(
                    overlay,
                    panel
                )

                true

            } else {
                false
            }
        }

        overlay.isFocusableInTouchMode = true
        overlay.requestFocus()
    }

    private fun closeSongActionSheet(
        overlay: View,
        panel: View
    ) {

        panel.animate()
            .translationY(
                panel.height.toFloat()
            )
            .setDuration(220)
            .setInterpolator(
                android.view.animation.AccelerateInterpolator()
            )
            .withEndAction {

                (overlay.parent as? ViewGroup)
                    ?.removeView(overlay)
            }
            .start()
    }

    private fun playSong(
        song: Song,
        startPosition: Long = 0L,
        smoothMiniChange: Boolean = false,
        miniTextDirection: Int = 1
    ) {

        val controller =
            mediaPlayer

        if (controller == null) {
            connectToPlaybackService()
            return
        }

        currentSong = song

        showMiniPlayer()

        if (
            playbackQueue.isEmpty() ||
            playbackQueue.none {
                it.id == song.id
            }
        ) {
            playbackQueue =
                mutableListOf(song)

            playbackIndex = 0
        } else {
            playbackIndex =
                playbackQueue.indexOfFirst {
                    it.id == song.id
                }

            if (playbackIndex < 0) {
                playbackQueue.add(song)
                playbackIndex =
                    playbackQueue.lastIndex
            }
        }

        val mediaItems =
            playbackQueue.map {
                mediaItemForSong(it)
            }

        val effectivePosition =
            if (
                startPosition <= 0 &&
                restoredPosition > 0 &&
                currentSong?.id == song.id
            ) {
                restoredPosition
            } else {
                startPosition
            }

        restoredPosition = 0

        try {

            controller.setMediaItems(
                mediaItems,
                playbackIndex,
                effectivePosition.coerceAtLeast(0L)
            )

            controller.prepare()
            controller.play()

        } catch (_: Exception) {
            return
        }

        if (smoothMiniChange) {

            animateMiniSongTextChange(
                song,
                miniTextDirection
            )

            getAlbumArt(song)?.let {
                miniCover.setImageBitmap(it)
            } ?: run {
                miniCover.setImageResource(
                    R.drawable.icon
                )
            }

        } else {

            getAlbumArt(song)?.let {
                miniCover.setImageBitmap(it)
            } ?: run {
                miniCover.setImageResource(
                    R.drawable.icon
                )
            }

            miniTitle.text = song.title
            miniArtist.text = song.artist
        }

        playButton.setImageResource(
            com.music.app.R.drawable.ic_player_pause
        )

        savePlayerState()
    }


    override fun onStart() {

        super.onStart()

        connectToPlaybackService()
    }

    override fun onPause() {

        savePlayerState()

        super.onPause()
    }

    override fun onStop() {

        /*
         * Save the exact visible playback position before
         * disconnecting the Activity from Media3.
         */
        savePlayerState()

        /*
         * The MediaController belongs to the Activity.
         * Releasing it here disconnects the Activity from
         * MusicPlaybackService without releasing the actual
         * ExoPlayer owned by the service.
         */
        mediaPlayer?.let { controller ->

            try {
                controller.removeListener(
                    mediaControllerListener
                )
            } catch (_: Exception) {
            }

            try {
                controller.release()
            } catch (_: Exception) {
            }
        }

        mediaPlayer = null

        /*
         * If the controller connection has not completed yet,
         * cancel its pending future as well.
         */
        mediaControllerFuture?.let { future ->

            try {
                MediaController.releaseFuture(
                    future
                )
            } catch (_: Exception) {
            }
        }

        mediaControllerFuture = null

        super.onStop()
    }

    override fun onDestroy() {

        savePlayerState()

        playerSaveHandler.removeCallbacks(
            playerSaveRunnable
        )

        /*
         * IMPORTANT:
         * The actual player belongs to MusicPlaybackService.
         * Destroying the Activity must never release playback.
         */
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
