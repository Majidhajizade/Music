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
    private var miniExpansionCover: ImageView? = null
    private var miniExpansionTitle: TextView? = null
    private var miniExpansionArtist: TextView? = null

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
        setBackgroundColor(Color.WHITE)
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

        content.removeAllViews()

        // ---------- HOME CONTAINER ----------
        val homeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(
            homeContainer,
            LinearLayout.LayoutParams(
                -1,
                -1
            )
        )

        // ---------- HEADER ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(26), 0, 0)
        }

        val title = text(
            "Home",
            30f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        val profile = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: android.view.View,
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

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    shape =
                        android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.rgb(235, 235, 235))
                }

            setImageResource(android.R.drawable.ic_menu_myplaces)

            setOnClickListener {
                showProfileDialog()
            }
        }

        header.addView(
            profile,
            LinearLayout.LayoutParams(
                dp(40),
                dp(40)
            )
        )

        homeContainer.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                dp(62)
            ).apply {
                topMargin = dp(18)
            }
        )

        // ---------- HOME SCROLL CONTENT ----------
        val homeScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val homeScrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0,
                0,
                0,
                dp(24)
            )
        }

        homeScroll.addView(
            homeScrollContent,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        homeContainer.addView(
            homeScroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // ---------- TOP PICKS ----------
        val picksTitle = text(
            "Top Picks for You",
            21f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        homeScrollContent.addView(
            picksTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(34)
            ).apply {
                topMargin = dp(14)
            }
        )

        val picksSubtitle = text(
            "Favorites",
            13f,
            Color.rgb(120, 120, 120),
            Typeface.NORMAL
        ).apply {
            includeFontPadding = false
        }

        homeScrollContent.addView(
            picksSubtitle,
            LinearLayout.LayoutParams(
                -1,
                dp(22)
            )
        )

        // ---------- FEATURED ----------
        if (songs.isNotEmpty()) {

            val screenWidthDp =
                resources.displayMetrics.widthPixels /
                    resources.displayMetrics.density

            // Base size at 360dp:
            // Width  = 7.3cm
            // Height = 9.5cm
            val screenScale =
                screenWidthDp / 360f

            val featuredWidth =
                (dp(276) * screenScale).toInt()

            val featuredHeight =
                (dp(359) * screenScale).toInt()

            val featuredScroll =
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode =
                        View.OVER_SCROLL_NEVER
                    clipToPadding = false
                }

            val featuredRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, dp(4), 0)
                }

            songs.take(6).forEach { song ->

                val card =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 0, 0, 0)
                        clipToOutline = true
                        background =
                            android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius =
                                    dp(16).toFloat()
                                setColor(
                                    Color.rgb(
                                        246,
                                        246,
                                        248
                                    )
                                )
                            }
                        elevation = dp(2).toFloat()
                    }

                val cover =
                    ImageView(this).apply {
                        scaleType =
                            ImageView.ScaleType.CENTER_CROP

                        clipToOutline = true

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
                                        dp(16).toFloat()
                                    )
                                }
                            }

                        background =
                            android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius =
                                    dp(16).toFloat()
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
                        }

                        setOnClickListener {
                            playSong(song)
                        }
                    }

                card.addView(
                    cover,
                    LinearLayout.LayoutParams(
                        -1,
                        featuredHeight - dp(78)
                    )
                )

                val info =
                    LinearLayout(this).apply {
                        orientation =
                            LinearLayout.VERTICAL
                        gravity =
                            Gravity.CENTER_VERTICAL
                        setPadding(
                            dp(14),
                            dp(4),
                            dp(14),
                            dp(8)
                        )
                    }

                val title =
                    text(
                        song.title,
                        16f,
                        Color.rgb(18, 18, 18),
                        Typeface.BOLD
                    ).apply {
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize =
                            android.text.TextUtils.TruncateAt.END
                    }

                val artist =
                    text(
                        song.artist,
                        13f,
                        Color.rgb(105, 105, 110),
                        Typeface.NORMAL
                    ).apply {
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize =
                            android.text.TextUtils.TruncateAt.END
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
                        dp(21)
                    )
                )

                card.addView(
                    info,
                    LinearLayout.LayoutParams(
                        -1,
                        dp(78)
                    )
                )

                card.setOnLongClickListener {
                    showSongPopup(card, song)
                    true
                }

                featuredRow.addView(
                    card,
                    LinearLayout.LayoutParams(
                        featuredWidth,
                        featuredHeight
                    ).apply {
                        rightMargin = dp(12)
                    }
                )
            }

            featuredScroll.addView(
                featuredRow,
                android.widget.FrameLayout.LayoutParams(
                    -2,
                    featuredHeight
                )
            )

            homeScrollContent.addView(
                featuredScroll,
                LinearLayout.LayoutParams(
                    -1,
                    featuredHeight
                ).apply {
                    topMargin = dp(6)
                }
            )
        }

        // ---------- RECENTLY PLAYED ----------
        val recentTitle = text(
            "Recently Played",
            21f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        homeScrollContent.addView(
            recentTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(32)
            ).apply {
                topMargin = dp(20)
            }
        )

        val recentScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode =
                    android.view.View.OVER_SCROLL_NEVER
            }

        val recentRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val recentSongs =
            songs.take(8)

        for (song in recentSongs) {

            val item =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setOnClickListener {
                        playbackQueue.clear()
                        playbackIndex = -1
                        playSong(song)
                    }
                }

            val cover =
                ImageView(this).apply {
                    scaleType =
                        ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true

                    outlineProvider =
                        object :
                            android.view.ViewOutlineProvider() {
                            override fun getOutline(
                                view: android.view.View,
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

                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape =
                                android.graphics.drawable.GradientDrawable.OVAL
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
                    }
                }

            item.addView(
                cover,
                LinearLayout.LayoutParams(
                    dp(96),
                    dp(96)
                )
            )

            val songTitle =
                text(
                    song.title,
                    12f,
                    Color.rgb(35, 35, 35),
                    Typeface.NORMAL
                ).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }

            item.addView(
                songTitle,
                LinearLayout.LayoutParams(
                    dp(92),
                    dp(18)
                ).apply {
                    topMargin = dp(6)
                }
            )

            recentRow.addView(
                item,
                LinearLayout.LayoutParams(
                    dp(102),
                    dp(118)
                ).apply {
                    rightMargin = dp(10)
                }
            )
        }

        recentScroll.addView(
            recentRow,
            LinearLayout.LayoutParams(
                -2,
                dp(126)
            )
        )

        homeScrollContent.addView(
            recentScroll,
            LinearLayout.LayoutParams(
                -1,
                dp(126)
            ).apply {
                topMargin = dp(4)
            }
        )
    }

    private fun showLibrary() {

        content.removeAllViews()

        // ---------- LIBRARY CONTAINER ----------
        val libraryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(
            libraryContainer,
            LinearLayout.LayoutParams(-1, -1)
        )

        // ---------- FIXED HEADER ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0,
                dp(12),
                0,
                dp(10)
            )
        }

        val title = text(
            "Songs",
            32f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(-1, dp(40)).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            }
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
                leftMargin = 0
                rightMargin = 0
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
                    setImageResource(R.drawable.ic_player_shuffle)
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

        actions.setPadding(
            0,
            0,
            0,
            0
        )

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
                leftMargin = 0
                rightMargin = 0
                bottomMargin = dp(18)
            }
        )

        libraryContainer.addView(
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
            overScrollMode =
                android.view.View.OVER_SCROLL_NEVER
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                0,
                dp(18),
                dp(20)
            )
        }

        fun renderSongs(query: String = "") {

            list.removeAllViews()

            val q = query.trim()

            val filtered = if (q.isEmpty()) {
                songs
            } else {
                songs.filter {
                    it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
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
                }

                // COVER
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
                    }
                }

                row.addView(
                    cover,
                    LinearLayout.LayoutParams(
                        dp(48),
                        dp(48)
                    )
                )

                // INFO
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
                        showSongMenu(view, song)
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
                            Color.rgb(232, 232, 232)
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
            ViewGroup.LayoutParams(-1, -2)
        )

        libraryContainer.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // ---------- SEARCH FUNCTION ----------
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
                    val params =
                        it.playbackParams

                    params.speed = speed

                    it.playbackParams = params
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
                    left +
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
                                0.5f +
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
            setBackgroundColor(Color.WHITE)
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
                                    createMiniExpansionCard()
                                }
                            }
                        }

                        if (!miniGestureDragging) {
                            return@OnTouchListener false
                        }

                        if (miniGestureVertical) {

                            updateMiniExpansionCard(
                                dy = dy
                            )

                            if (dy > dp(18)) {
                                scheduleMiniHide(layout)
                            } else {
                                cancelMiniHide()
                            }

                            miniGestureLastY = event.rawY

                            true

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

        if (miniExpansionCard != null) {
            return
        }

        if (!::miniPlayer.isInitialized) {
            return
        }

        val song = currentSong ?: return

        val decor =
            window.decorView as? ViewGroup
                ?: return

        val location =
            IntArray(2)

        miniPlayer.getLocationOnScreen(
            location
        )

        val decorLocation =
            IntArray(2)

        decor.getLocationOnScreen(
            decorLocation
        )

        val left =
            location[0] - decorLocation[0]

        val top =
            location[1] - decorLocation[1]

        val width =
            miniPlayer.width

        val height =
            miniPlayer.height

        val card =
            FrameLayout(this).apply {

                clipChildren = false

                background =
                    createMiniExpansionBackground(
                        song
                    )

                elevation =
                    dp(12).toFloat()
            }

        val cover =
            ImageView(this).apply {

                scaleType =
                    ImageView.ScaleType.CENTER_CROP

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
                    setImageResource(
                        R.drawable.ic_music
                    )
                }

                alpha = 0f
                rotation = 110f
                scaleX = 0.28f
                scaleY = 0.28f
            }

        val title =
            text(
                song.title,
                20f,
                Color.WHITE,
                Typeface.BOLD
            ).apply {

                maxLines = 1

                ellipsize =
                    TextUtils.TruncateAt.END

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

                ellipsize =
                    TextUtils.TruncateAt.END

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

                gravity =
                    Gravity.BOTTOM

                leftMargin =
                    dp(28)

                rightMargin =
                    dp(28)

                bottomMargin =
                    dp(34)
            }
        )

        card.addView(
            artist,
            FrameLayout.LayoutParams(
                -1,
                dp(22)
            ).apply {

                gravity =
                    Gravity.BOTTOM

                leftMargin =
                    dp(28)

                rightMargin =
                    dp(28)

                bottomMargin =
                    dp(10)
            }
        )

        decor.addView(
            card,
            FrameLayout.LayoutParams(
                width,
                height
            ).apply {

                leftMargin = left
                topMargin = top
            }
        )

        miniExpansionCard = card
        miniExpansionCover = cover
        miniExpansionTitle = title
        miniExpansionArtist = artist

        /*
         * Hide the original Mini Player while the expansion
         * card follows the finger.
         */
        miniPlayer.alpha = 0f
    }

    private fun updateMiniExpansionCard(
        dy: Float
    ) {

        val card =
            miniExpansionCard
                ?: return

        val cover =
            miniExpansionCover
                ?: return

        val title =
            miniExpansionTitle
                ?: return

        val artist =
            miniExpansionArtist
                ?: return

        val screenHeight =
            resources.displayMetrics
                .heightPixels
                .toFloat()

        val miniHeight =
            miniPlayer.height
                .coerceAtLeast(
                    dp(60)
                )

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

        val lp =
            card.layoutParams
                as? FrameLayout.LayoutParams
                ?: return

        /*
         * The bottom edge stays fixed.
         * The card grows upward with the finger.
         */
        lp.height =
            (
                miniHeight +
                upward
            ).toInt()

        lp.topMargin =
            (
                miniPlayerTopInDecor() -
                upward
            ).toInt()

        card.layoutParams = lp

        /*
         * Cover:
         * starts tiny + 110 degrees,
         * ends large + 0 degrees.
         */
        val maxCover =
            (
                resources.displayMetrics.widthPixels -
                dp(48)
            ).coerceAtLeast(
                dp(46)
            )

        val coverSize =
            (
                dp(46) +
                (
                    maxCover -
                    dp(46)
                ) * progress
            ).toInt()

        val coverLp =
            cover.layoutParams

        coverLp.width =
            coverSize

        coverLp.height =
            coverSize

        cover.layoutParams =
            coverLp

        cover.alpha =
            (
                progress * 1.25f
            ).coerceIn(
                0f,
                1f
            )

        cover.rotation =
            110f * (1f - progress)

        cover.scaleX = 1f
        cover.scaleY = 1f

        title.alpha =
            (
                (progress - 0.18f) /
                0.35f
            ).coerceIn(
                0f,
                1f
            )

        artist.alpha =
            (
                (progress - 0.24f) /
                0.35f
            ).coerceIn(
                0f,
                0.68f
            )
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

    private fun completeMiniExpansion(
        layout: LinearLayout
    ) {

        val card =
            miniExpansionCard
                ?: return

        val cover =
            miniExpansionCover
                ?: return

        val title =
            miniExpansionTitle
                ?: return

        val artist =
            miniExpansionArtist
                ?: return

        val decor =
            window.decorView as? ViewGroup
                ?: return

        val screenWidth =
            resources.displayMetrics
                .widthPixels

        val screenHeight =
            resources.displayMetrics
                .heightPixels

        val lp =
            card.layoutParams
                as? FrameLayout.LayoutParams
                ?: return

        lp.leftMargin = 0
        lp.topMargin = 0
        lp.width = screenWidth
        lp.height = screenHeight

        card.layoutParams = lp

        card.animate()
            .alpha(1f)
            .setDuration(430L)
            .setInterpolator(
                android.view.animation
                    .DecelerateInterpolator()
            )
            .start()

        val coverSize =
            screenWidth -
            dp(48)

        val coverLp =
            cover.layoutParams

        coverLp.width =
            coverSize

        coverLp.height =
            coverSize

        cover.layoutParams =
            coverLp

        cover.animate()
            .alpha(1f)
            .rotation(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(430L)
            .setInterpolator(
                android.view.animation
                    .DecelerateInterpolator()
            )
            .start()

        title.animate()
            .alpha(1f)
            .setDuration(280L)
            .start()

        artist.animate()
            .alpha(0.68f)
            .setDuration(300L)
            .start()

        card.postDelayed({

            removeMiniExpansionCard()

            layout.translationX = 0f
            layout.translationY = 0f
            layout.alpha = 1f

            miniCover.rotation = 0f
            miniCover.scaleX = 1f
            miniCover.scaleY = 1f

            if (currentSong != null) {
                showNowPlaying()
            }

        }, 430L)
    }

    private fun cancelMiniExpansion(
        layout: LinearLayout
    ) {

        val card =
            miniExpansionCard

        if (card == null) {

            layout.translationX = 0f
            layout.translationY = 0f
            layout.alpha = 1f

            return
        }

        card.animate()
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(
                android.view.animation
                    .DecelerateInterpolator()
            )
            .withEndAction {

                removeMiniExpansionCard()

                layout.translationX = 0f
                layout.translationY = 0f
                layout.alpha = 1f

                miniCover.rotation = 0f
                miniCover.scaleX = 1f
                miniCover.scaleY = 1f
            }
            .start()
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
            smoothMiniChange = true
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
            smoothMiniChange = true
        )
    }

    private fun animateMiniSongTextChange(
        song: Song
    ) {

        miniTitle.animate().cancel()
        miniArtist.animate().cancel()

        miniTitle.animate()
            .alpha(0f)
            .translationY(dp(3).toFloat())
            .setDuration(120L)
            .setInterpolator(
                android.view.animation.DecelerateInterpolator()
            )
            .withEndAction {

                miniTitle.text = song.title
                miniTitle.translationY = -dp(3).toFloat()

                miniTitle.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(230L)
                    .setInterpolator(
                        android.view.animation.DecelerateInterpolator()
                    )
                    .start()
            }
            .start()

        miniArtist.animate()
            .alpha(0f)
            .translationY(dp(3).toFloat())
            .setDuration(120L)
            .setInterpolator(
                android.view.animation.DecelerateInterpolator()
            )
            .withEndAction {

                miniArtist.text = song.artist
                miniArtist.translationY = -dp(3).toFloat()

                miniArtist.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(230L)
                    .setInterpolator(
                        android.view.animation.DecelerateInterpolator()
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
            playSong(next)
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
            playSong(previous)
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

    private fun createBottomNavigation(): LinearLayout {

        val prefs = getSettingsPrefs()

        val showHome =
            prefs.getBoolean("tab_home", true)

        val showLibrary =
            prefs.getBoolean("tab_library", true)

        val showSettings =
            prefs.getBoolean("tab_settings", true)

        val enabledCount =
            listOf(
                showHome,
                showLibrary,
                showSettings
            ).count { it }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.WHITE)
        }

        /*
         * Settings is always kept accessible.
         * This prevents the user from hiding every navigation
         * destination and getting stuck in the app.
         */
        val finalShowSettings =
            showSettings || enabledCount == 0

        val items = mutableListOf<Pair<String, () -> Unit>>()

        if (showHome) {
            items.add(
                "Home" to {
                    showHome()
                }
            )
        }

        if (showLibrary) {
            items.add(
                "Library" to {
                    showLibrary()
                }
            )
        }

        if (finalShowSettings) {
            items.add(
                "Settings" to {
                    showSettings()
                }
            )
        }

        val weight =
            1f / items.size.coerceAtLeast(1)

        items.forEach { item ->

            val label = item.first
            val action = item.second

            val icon =
                when (label) {
                    "Home" -> "⌂"
                    "Library" -> "♫"
                    else -> "⚙"
                }

            nav.addView(
                navItem(
                    icon,
                    label,
                    action
                ),
                LinearLayout.LayoutParams(
                    0,
                    dp(64),
                    weight
                )
            )
        }

        return nav
    }

    private fun navItem(
        icon: String,
        label: String,
        action: () -> Unit
    ): LinearLayout {

        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(1))

            setOnClickListener {
                action()
            }
        }

        val iconView = text(
            icon,
            28f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            paint.isFakeBoldText = true
        }

        val labelView = text(
            label,
            10f,
            Color.rgb(70, 70, 70),
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        item.addView(
            iconView,
            LinearLayout.LayoutParams(
                -1,
                dp(32)
            )
        )

        item.addView(
            labelView,
            LinearLayout.LayoutParams(
                -1,
                dp(18)
            )
        )

        return item
    }

    private fun showProfileDialog() {

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(35, 30, 35, 25)
            setBackgroundColor(Color.WHITE)
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
            setBackgroundColor(Color.WHITE)
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
        }

        dialog.show()

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
        smoothMiniChange: Boolean = false
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
            animateMiniSongTextChange(song)

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
