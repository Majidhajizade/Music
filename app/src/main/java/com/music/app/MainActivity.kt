package com.music.app

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
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var playButton: TextView

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            loadMusic()
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

        if (hasAudioPermission()) {
            loadMusic()
        } else {
            requestAudioPermission()
        }
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
        root.addView(
            createMiniPlayer(),
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
                dp(18),
                dp(12),
                dp(18),
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
            LinearLayout.LayoutParams(-1, dp(40))
        )

        val count = text(
            "${songs.size} Songs",
            13f,
            Color.rgb(125, 125, 125),
            Typeface.NORMAL
        ).apply {
            includeFontPadding = false
        }

        header.addView(
            count,
            LinearLayout.LayoutParams(-1, dp(20))
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
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(232, 232, 232))
            }
        }

        header.addView(
            search,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
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
                    songs.toMutableList()

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
                    songs.shuffled().toMutableList()

                playbackIndex = 0

                playSong(
                    playbackQueue[playbackIndex]
                )
            }
        }

        actions.setPadding(
            dp(12),
            0,
            dp(12),
            0
        )

        actions.addView(
            playAction,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                rightMargin = dp(5)
            }
        )

        actions.addView(
            shuffleAction,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                leftMargin = dp(5)
            }
        )

        header.addView(
            actions,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            ).apply {
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
                                    dp(8).toFloat()
                                )
                            }
                        }

                    background =
                        GradientDrawable().apply {
                            cornerRadius =
                                dp(8).toFloat()
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
                        dp(56),
                        dp(56)
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
                            Color.rgb(238, 238, 238)
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
        content.setPadding(dp(18), dp(24), dp(18), dp(30))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }

        val title = text(
            "Settings",
            34f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        page.addView(
            title,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(18)
            }
        )

        // ---------- ACCOUNT ----------
        val account = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(247, 247, 249))
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(1).toFloat()

            setOnClickListener {
                showProfileDialog()
            }
        }

        val accountIcon = TextView(this).apply {
            text = "●"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(90, 90, 95))
        }

        account.addView(
            accountIcon,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                rightMargin = dp(12)
            }
        )

        val accountTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        accountTexts.addView(
            text(
                "Music",
                17f,
                Color.rgb(20, 20, 22),
                Typeface.BOLD
            )
        )

        accountTexts.addView(
            text(
                "Apple Music style player",
                13f,
                Color.rgb(120, 120, 125),
                Typeface.NORMAL
            ).apply {
                setPadding(0, dp(3), 0, 0)
            }
        )

        account.addView(
            accountTexts,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        account.addView(
            TextView(this).apply {
                text = "›"
                textSize = 28f
                setTextColor(Color.rgb(155, 155, 160))
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(28), dp(48))
        )

        page.addView(
            account,
            LinearLayout.LayoutParams(-1, dp(76)).apply {
                bottomMargin = dp(28)
            }
        )

        // ---------- PLAYBACK ----------
        addSettingsSectionTo(page, "PLAYBACK")

        addAppleSettingTo(
            page,
            "♫",
            "Audio Quality",
            "High"
        ) {
            showChoiceDialog(
                "Audio Quality",
                arrayOf("High", "Lossless", "Automatic")
            )
        }

        addAppleSettingTo(
            page,
            "∞",
            "Gapless Playback",
            "On"
        ) {
            Toast.makeText(this, "Gapless Playback", Toast.LENGTH_SHORT).show()
        }

        addAppleSettingTo(
            page,
            "◉",
            "Normalize Volume",
            "On"
        ) {
            Toast.makeText(this, "Normalize Volume", Toast.LENGTH_SHORT).show()
        }

        // ---------- APPEARANCE ----------
        addSettingsSectionTo(page, "APPEARANCE")

        addAppleSettingTo(
            page,
            "☼",
            "Theme",
            "System"
        ) {
            showChoiceDialog(
                "Theme",
                arrayOf("System", "Light", "Dark")
            )
        }

        // ---------- LIBRARY ----------
        addSettingsSectionTo(page, "LIBRARY")

        addAppleSettingTo(
            page,
            "♫",
            "Music Library",
            "${songs.size} Songs"
        ) {
            showLibrary()
        }

        addAppleSettingTo(
            page,
            "↻",
            "Recently Played",
            "On"
        ) {
            Toast.makeText(this, "Recently Played", Toast.LENGTH_SHORT).show()
        }

        // ---------- ABOUT ----------
        addSettingsSectionTo(page, "ABOUT")

        addAppleSettingTo(
            page,
            "ⓘ",
            "About Music",
            "Version 1.0"
        ) {
            AlertDialog.Builder(this)
                .setTitle("Music")
                .setMessage("Apple Music inspired music player.")
                .setPositiveButton("Done", null)
                .show()
        }

        scroll.addView(
            page,
            android.view.ViewGroup.LayoutParams(-1, -2)
        )

        content.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun addSettingsSectionTo(
        parent: LinearLayout,
        titleValue: String
    ) {
        val section = TextView(this).apply {
            text = titleValue
            textSize = 12f
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            setTextColor(Color.rgb(125, 125, 130))
            letterSpacing = 0.08f
            includeFontPadding = false
            setPadding(dp(4), 0, 0, dp(8))
        }

        parent.addView(
            section,
            LinearLayout.LayoutParams(-1, dp(28)).apply {
                topMargin = dp(8)
            }
        )
    }

    private fun addAppleSettingTo(
        parent: LinearLayout,
        iconValue: String,
        titleValue: String,
        value: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)

            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(248, 248, 250))
                cornerRadius = dp(14).toFloat()
            }

            isClickable = true
            isFocusable = true
            setOnClickListener {
                action()
            }
        }

        val icon = TextView(this).apply {
            text = iconValue
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(45, 45, 48))
        }

        row.addView(
            icon,
            LinearLayout.LayoutParams(dp(38), dp(52)).apply {
                rightMargin = dp(10)
            }
        )

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        texts.addView(
            text(
                titleValue,
                16f,
                Color.rgb(25, 25, 27),
                Typeface.NORMAL
            )
        )

        texts.addView(
            text(
                value,
                12f,
                Color.rgb(135, 135, 140),
                Typeface.NORMAL
            ).apply {
                setPadding(0, dp(2), 0, 0)
            }
        )

        row.addView(
            texts,
            LinearLayout.LayoutParams(0, dp(58), 1f)
        )

        row.addView(
            TextView(this).apply {
                text = "›"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(165, 165, 170))
            },
            LinearLayout.LayoutParams(dp(24), dp(58))
        )

        parent.addView(
            row,
            LinearLayout.LayoutParams(-1, dp(58)).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun addSettingsSection(
        titleValue: String
    ) {
        val section = text(
            titleValue.uppercase(),
            11f,
            Color.rgb(120, 120, 125),
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
            setPadding(
                dp(4),
                dp(18),
                dp(4),
                dp(8)
            )
        }

        content.addView(
            section,
            LinearLayout.LayoutParams(
                -1,
                dp(40)
            )
        )
    }

    private fun addAppleSetting(
        icon: String,
        titleValue: String,
        value: String,
        action: () -> Unit
    ) {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
                setPadding(
                    dp(14),
                    0,
                    dp(10),
                    0
                )

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius =
                            dp(15).toFloat()
                        setColor(
                            Color.rgb(
                                247,
                                247,
                                249
                            )
                        )
                    }

                setOnClickListener {
                    action()
                }
            }

        val iconView =
            TextView(this).apply {
                text = icon
                textSize = 19f
                setTextColor(
                    Color.rgb(
                        25,
                        25,
                        27
                    )
                )
                gravity = Gravity.CENTER
                includeFontPadding = false
            }

        row.addView(
            iconView,
            LinearLayout.LayoutParams(
                dp(34),
                dp(42)
            )
        )

        val names =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        names.addView(
            text(
                titleValue,
                15f,
                Color.rgb(25, 25, 27),
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                -1,
                dp(23)
            )
        )

        names.addView(
            text(
                value,
                12f,
                Color.rgb(120, 120, 125),
                Typeface.NORMAL
            ).apply {
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                -1,
                dp(18)
            )
        )

        row.addView(
            names,
            LinearLayout.LayoutParams(
                0,
                -1,
                1f
            )
        )

        row.addView(
            text(
                "›",
                27f,
                Color.rgb(165, 165, 170),
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                dp(26),
                -1
            )
        )

        content.addView(
            row,
            LinearLayout.LayoutParams(
                -1,
                dp(62)
            ).apply {
                bottomMargin = dp(8)
            }
        )
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

        return layout
    }

    private fun createBottomNavigation(): LinearLayout {

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.WHITE)
        }

        nav.addView(
            navItem("⌂", "Home") { showHome() },
            LinearLayout.LayoutParams(0, dp(64), 1f)
        )

        nav.addView(
            navItem("♫", "Library") { showLibrary() },
            LinearLayout.LayoutParams(0, dp(64), 1f)
        )

        nav.addView(
            navItem("⚙", "Settings") { showSettings() },
            LinearLayout.LayoutParams(0, dp(64), 1f)
        )

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

        val dialog = android.app.Dialog(this)

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

            minHeight = dp(14)
            minimumHeight = dp(14)

            setPadding(
                0,
                dp(1),
                0,
                dp(1)
            )

            // No circular thumb.
            thumb = null

            val backgroundTrack =
                android.graphics.drawable.GradientDrawable().apply {
                    shape =
                        android.graphics.drawable.GradientDrawable.RECTANGLE

                    cornerRadius =
                        dp(5).toFloat()

                    setColor(
                        Color.argb(
                            75,
                            255,
                            255,
                            255
                        )
                    )
                }

            val progressTrack =
                android.graphics.drawable.GradientDrawable().apply {
                    shape =
                        android.graphics.drawable.GradientDrawable.RECTANGLE

                    cornerRadius =
                        dp(5).toFloat()

                    setColor(
                        Color.WHITE
                    )
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

                                    if (it.duration > 0) {

                                        val position =
                                            (
                                                it.duration.toLong() *
                                                    progress
                                            ) / 1000L

                                        it.seekTo(
                                            position.toInt()
                                        )
                                    }

                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    override fun onStartTrackingTouch(
                        seekBar: SeekBar?
                    ) {}

                    override fun onStopTrackingTouch(
                        seekBar: SeekBar?
                    ) {}
                }
            )
        }

        bottomPanel.addView(
            seekBar,
            LinearLayout.LayoutParams(
                -1,
                dp(16)
            ).apply {
                topMargin = dp(3)
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
                    com.music.app.R.drawable.ic_player_previous
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

                        dialog.dismiss()

                        showNowPlaying()
                    }
                }
            }

        val play =
            ImageView(this).apply {

                setImageResource(
                    if (
                        mediaPlayer?.isPlaying ==
                        true
                    )
                        com.music.app.R.drawable.ic_player_pause
                    else
                        com.music.app.R.drawable.ic_player_play
                )

                scaleType =
                    android.widget.ImageView.ScaleType.CENTER_INSIDE

                background = null

                contentDescription =
                    if (
                        mediaPlayer?.isPlaying ==
                        true
                    )
                        "Pause"
                    else
                        "Play"

                background = null

                setPadding(
                    0,
                    0,
                    0,
                    0
                )

                setOnClickListener {

                    mediaPlayer?.let {

                        try {

                            if (it.isPlaying) {

                                it.pause()

                                setImageResource(
                                    com.music.app.R.drawable.ic_player_play
                                )

                                playButton.text =
                                    "▶"

                            } else {

                                it.start()

                                setImageResource(
                                    com.music.app.R.drawable.ic_player_pause
                                )

                                playButton.text =
                                    "▮▮"
                            }

                        } catch (_: Exception) {
                        }
                    }
                }
            }

        val next =
            ImageView(this).apply {

                setImageResource(
                    com.music.app.R.drawable.ic_player_next
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

                        dialog.dismiss()

                        showNowPlaying()
                    }
                }
            }

        controls.addView(
            next,
            LinearLayout.LayoutParams(
                dp(50),
                dp(50)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(10)
                marginEnd = dp(8)
            }
        )

        controls.addView(
            play,
            LinearLayout.LayoutParams(
                dp(72),
                dp(72)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        )

        controls.addView(
            previous,
            LinearLayout.LayoutParams(
                dp(50),
                dp(50)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(8)
                marginEnd = dp(10)
            }
        )

        bottomPanel.addView(
            controls,
            LinearLayout.LayoutParams(
                -1,
                dp(72)
            ).apply {
                topMargin = dp(2)
            }
        )

        // ---------- SECONDARY ----------

        val secondary =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

        val lyrics = ImageView(this).apply {
            setImageResource(
                com.music.app.R.drawable.ic_player_lyrics
            )

            scaleType =
                ImageView.ScaleType.CENTER_INSIDE

            background = null

            alpha = 0.78f

            contentDescription =
                "Lyrics"
        }

        val cast = ImageView(this).apply {
            setImageResource(
                com.music.app.R.drawable.ic_player_cast
            )

            scaleType =
                ImageView.ScaleType.CENTER_INSIDE

            background = null

            alpha = 0.78f

            contentDescription =
                "Cast"
        }

        val queue = ImageView(this).apply {
            setImageResource(
                com.music.app.R.drawable.ic_player_queue
            )

            scaleType =
                ImageView.ScaleType.CENTER_INSIDE

            background = null

            alpha = 0.78f

            contentDescription =
                "Queue"
        }

        secondary.addView(
            lyrics,
            LinearLayout.LayoutParams(
                0,
                dp(40),
                1f
            )
        )

        secondary.addView(
            cast,
            LinearLayout.LayoutParams(
                0,
                dp(40),
                1f
            )
        )

        secondary.addView(
            queue,
            LinearLayout.LayoutParams(
                0,
                dp(40),
                1f
            )
        )

        bottomPanel.addView(
            secondary,
            LinearLayout.LayoutParams(
                -1,
                dp(42)
            )
        )

        root.addView(
            bottomPanel,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ---------- PROGRESS ----------

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

        // ---------- SWIPE DOWN TO MINI PLAYER ----------

        var downY = 0f
        var dragging = false

        root.setOnTouchListener { view, event ->

            when (event.actionMasked) {

                android.view.MotionEvent.ACTION_DOWN -> {

                    downY = event.rawY
                    dragging = false

                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {

                    val delta =
                        event.rawY - downY

                    if (delta > dp(8)) {

                        dragging = true

                        val limited =
                            delta.coerceAtLeast(0f)

                        view.translationY =
                            limited

                        view.alpha =
                            (
                                1f -
                                    limited /
                                    (view.height
                                        .toFloat()
                                        .coerceAtLeast(1f))
                            ).coerceIn(
                                0.35f,
                                1f
                            )
                    }

                    true
                }

                android.view.MotionEvent.ACTION_UP -> {

                    val delta =
                        event.rawY - downY

                    if (
                        dragging &&
                        delta > dp(120)
                    ) {

                        view.animate()
                            .translationY(
                                view.height
                                    .toFloat()
                            )
                            .alpha(0f)
                            .setDuration(260L)
                            .setInterpolator(
                                android.view.animation
                                    .AccelerateDecelerateInterpolator()
                            )
                            .withEndAction {
                                dialog.dismiss()
                            }
                            .start()

                    } else {

                        view.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(240L)
                            .setInterpolator(
                                android.view.animation
                                    .OvershootInterpolator(0.7f)
                            )
                            .start()
                    }

                    true
                }

                android.view.MotionEvent.ACTION_CANCEL -> {

                    view.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(220L)
                        .start()

                    true
                }

                else -> false
            }
        }

        // ---------- SHOW ----------

        dialog.setContentView(root)

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT
            )
        )

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

            window.setLayout(-1, -1)

            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(
                    Color.TRANSPARENT
                )
            )

            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }

            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)

                window.insetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }

            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            }

            window.addFlags(
                android.view.WindowManager
                    .LayoutParams
                    .FLAG_LAYOUT_NO_LIMITS
            )
        }
    }

    private fun playSong(song: Song) {

        mediaPlayer?.release()
        mediaPlayer = null

        currentSong = song

        // If the song is not part of the current queue,
        // treat this as a direct single-song playback.
        if (playbackQueue.isEmpty() ||
            playbackQueue.none { it.id == song.id }
        ) {
            playbackQueue = mutableListOf(song)
            playbackIndex = 0
        } else {
            playbackIndex =
                playbackQueue.indexOfFirst { it.id == song.id }
        }

        val uri = ContentUris.withAppendedId(
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
                start()

                setOnCompletionListener {

                    val nextIndex = playbackIndex + 1

                    if (
                        nextIndex >= 0 &&
                        nextIndex < playbackQueue.size
                    ) {
                        playbackIndex = nextIndex
                        playSong(playbackQueue[playbackIndex])
                    } else {
                        playbackQueue.clear()
                        playbackIndex = -1

                        playButton.text = "▶"
                    }
                }
            }

        getAlbumArt(song)?.let {
            miniCover.setImageBitmap(it)
        } ?: run {
            miniCover.setImageResource(0)
        }

        miniTitle.text = song.title
        miniArtist.text = song.artist
        playButton.text = "Ⅱ"
    }

    override fun onDestroy() {

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
