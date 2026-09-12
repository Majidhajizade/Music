package com.music.app

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

        root.addView(
            content,
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

        // ---------- HEADER ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
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

        content.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            ).apply {
                topMargin = dp(6)
            }
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

        content.addView(
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

        content.addView(
            picksSubtitle,
            LinearLayout.LayoutParams(
                -1,
                dp(22)
            )
        )

        // ---------- FEATURED COVER ----------
        if (songs.isNotEmpty()) {

            val featuredSong =
                currentSong ?: songs.first()

            val featuredCover =
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
                                    dp(14).toFloat()
                                )
                            }
                        }

                    background =
                        android.graphics.drawable.GradientDrawable().apply {
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

                    getAlbumArt(featuredSong)?.let {
                        setImageBitmap(it)
                    }

                    setOnClickListener {
                        playSong(featuredSong)
                    }
                }

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

            val featuredWrap =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 0)
                }

            featuredWrap.addView(
                featuredCover,
                LinearLayout.LayoutParams(
                    -1,
                    featuredHeight
                )
            )

            val featuredInfo =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        dp(2),
                        dp(9),
                        dp(2),
                        0
                    )
                }

            val featuredTitle =
                text(
                    featuredSong.title,
                    16f,
                    Color.rgb(20, 20, 20),
                    Typeface.BOLD
                ).apply {
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                }

            featuredInfo.addView(
                featuredTitle,
                LinearLayout.LayoutParams(
                    -1,
                    dp(22)
                )
            )

            val featuredArtist =
                text(
                    featuredSong.artist,
                    13f,
                    Color.rgb(120, 120, 120),
                    Typeface.NORMAL
                ).apply {
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                }

            featuredInfo.addView(
                featuredArtist,
                LinearLayout.LayoutParams(
                    -1,
                    dp(19)
                ).apply {
                    topMargin = dp(2)
                }
            )

            featuredWrap.addView(
                featuredInfo,
                LinearLayout.LayoutParams(
                    -1,
                    dp(46)
                )
            )

            content.addView(
                featuredWrap,
                LinearLayout.LayoutParams(
                    featuredWidth,
                    featuredHeight + dp(46)
                ).apply {
                    topMargin = dp(6)
                    gravity = Gravity.CENTER_HORIZONTAL
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

        content.addView(
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

        content.addView(
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

        // ---------- HEADER ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(14))
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
                dp(42)
            )
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
            LinearLayout.LayoutParams(
                -1,
                dp(22)
            )
        )

        content.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                dp(78)
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
        }

        songs.forEach { song ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(8)
                )

                setOnClickListener {
                    playSong(song)
                }
            }

            // ---------- COVER ----------
            val cover = ImageView(this).apply {
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
                                dp(9).toFloat()
                            )
                        }
                    }

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius =
                            dp(9).toFloat()

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
                    dp(64),
                    dp(64)
                )
            )

            // ---------- INFO ----------
            val info =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        dp(14),
                        0,
                        dp(8),
                        0
                    )
                }

            val songTitle = text(
                song.title,
                15f,
                Color.rgb(18, 18, 18),
                Typeface.BOLD
            ).apply {
                maxLines = 1
                ellipsize =
                    android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            }

            val artist = text(
                song.artist,
                12f,
                Color.rgb(125, 125, 125),
                Typeface.NORMAL
            ).apply {
                maxLines = 1
                ellipsize =
                    android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )
            }

            info.addView(
                songTitle,
                LinearLayout.LayoutParams(
                    -1,
                    dp(21)
                )
            )

            info.addView(
                artist,
                LinearLayout.LayoutParams(
                    -1,
                    dp(19)
                )
            )

            row.addView(
                info,
                LinearLayout.LayoutParams(
                    0,
                    dp(64),
                    1f
                )
            )

            // ---------- MORE ----------
            val more = text(
                "•••",
                13f,
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
                    dp(38),
                    dp(64)
                )
            )

            list.addView(
                row,
                LinearLayout.LayoutParams(
                    -1,
                    dp(76)
                )
            )

            // ---------- SEPARATOR ----------
            list.addView(
                View(this).apply {
                    setBackgroundColor(
                        Color.rgb(
                            238,
                            238,
                            238
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    -1,
                    dp(1)
                )
            )
        }

        scroll.addView(
            list,
            android.view.ViewGroup.LayoutParams(
                -1,
                -2
            )
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

    private fun showSettings() {
        content.removeAllViews()

        content.setPadding(
            dp(20),
            dp(14),
            dp(20),
            dp(20)
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(18))
        }

        header.addView(
            text(
                "Settings",
                32f,
                Color.rgb(15, 15, 15),
                Typeface.BOLD
            ),
            LinearLayout.LayoutParams(
                -1,
                dp(42)
            )
        )

        header.addView(
            text(
                "Customize your music experience",
                14f,
                Color.rgb(110, 110, 110),
                Typeface.NORMAL
            ),
            LinearLayout.LayoutParams(
                -1,
                dp(24)
            )
        )

        content.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                dp(84)
            )
        )

        addSettingsSection("PLAYBACK")

        addSetting(
            "Audio Quality",
            "High"
        ) {
            showChoiceDialog(
                "Audio Quality",
                arrayOf("Low", "Medium", "High")
            )
        }

        addSetting(
            "Gapless Playback",
            "On"
        ) {
            Toast.makeText(
                this,
                "Gapless Playback enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        addSetting(
            "Normalize Volume",
            "On"
        ) {
            Toast.makeText(
                this,
                "Volume normalization enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        addSettingsSection("APPEARANCE")

        addSetting(
            "Theme",
            "System"
        ) {
            showChoiceDialog(
                "Theme",
                arrayOf("System", "Light", "Dark")
            )
        }
    }

    private fun addSettingsSection(titleValue: String) {
        val section = text(
            titleValue,
            12f,
            Color.rgb(120, 120, 125),
            Typeface.BOLD
        ).apply {
            setPadding(
                dp(4),
                dp(18),
                dp(4),
                dp(6)
            )
        }

        content.addView(
            section,
            LinearLayout.LayoutParams(
                -1,
                dp(42)
            )
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

        val dialog = android.app.Dialog(this)

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

        val song = currentSong ?: return

        val dialog = android.app.Dialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(8), dp(20), dp(18))
        }

        // ---------- TOP BAR ----------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val close = TextView(this).apply {
            text = "⌄"
            textSize = 28f
            setTextColor(Color.rgb(25, 25, 25))
            gravity = Gravity.CENTER
            includeFontPadding = false

            setOnClickListener {
                dialog.dismiss()
            }
        }

        topBar.addView(
            close,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val topTitle = text(
            "NOW PLAYING",
            10f,
            Color.rgb(110, 110, 110),
            Typeface.BOLD
        ).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = 0.16f
        }

        topBar.addView(
            topTitle,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val more = TextView(this).apply {
            text = "•••"
            textSize = 17f
            setTextColor(Color.rgb(25, 25, 25))
            gravity = Gravity.CENTER
            includeFontPadding = false

            setOnClickListener {
                showSongMenu(this, song)
            }
        }

        topBar.addView(
            more,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        root.addView(
            topBar,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        // ---------- ALBUM ART ----------
        val cover = ImageView(this).apply {
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
                            dp(14).toFloat()
                        )
                    }
                }

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.rgb(235, 235, 235))
                }

            getAlbumArt(song)?.let {
                setImageBitmap(it)
            }
        }

        val screenWidth =
            resources.displayMetrics.widthPixels

        val coverSize =
            (screenWidth - dp(40))
                .coerceAtMost(dp(390))
                .coerceAtLeast(dp(240))

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
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
        )

        // ---------- SONG INFO ----------
        val songInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val songTitle = text(
            song.title,
            22f,
            Color.rgb(15, 15, 15),
            Typeface.BOLD
        ).apply {
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        val songArtist = text(
            song.artist,
            15f,
            Color.rgb(105, 105, 105),
            Typeface.NORMAL
        ).apply {
            maxLines = 1
            ellipsize =
                android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(5), 0, 0)
        }

        songInfo.addView(
            songTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(27)
            )
        )

        songInfo.addView(
            songArtist,
            LinearLayout.LayoutParams(
                -1,
                dp(23)
            )
        )

        root.addView(
            songInfo,
            LinearLayout.LayoutParams(-1, dp(58))
        )

        // ---------- SEEK BAR ----------
        val seekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setPadding(0, 0, 0, 0)

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

        root.addView(
            seekBar,
            LinearLayout.LayoutParams(
                -1,
                dp(30)
            ).apply {
                topMargin = dp(2)
            }
        )

        // ---------- TIME ----------
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val elapsed = text(
            "0:00",
            11f,
            Color.rgb(115, 115, 115),
            Typeface.NORMAL
        ).apply {
            includeFontPadding = false
        }

        val remaining = text(
            "-0:00",
            11f,
            Color.rgb(115, 115, 115),
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.RIGHT
            includeFontPadding = false
        }

        timeRow.addView(
            elapsed,
            LinearLayout.LayoutParams(0, dp(18), 1f)
        )

        timeRow.addView(
            remaining,
            LinearLayout.LayoutParams(0, dp(18), 1f)
        )

        root.addView(
            timeRow,
            LinearLayout.LayoutParams(-1, dp(18))
        )

        // ---------- MAIN CONTROLS ----------
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val previous = TextView(this).apply {
            text = "⏮"
            textSize = 25f
            setTextColor(Color.rgb(20, 20, 20))
            gravity = Gravity.CENTER
            includeFontPadding = false

            setOnClickListener {
                val index =
                    songs.indexOfFirst {
                        it.id == currentSong?.id
                    }

                if (index > 0) {
                    playSong(songs[index - 1])
                    dialog.dismiss()
                    showNowPlaying()
                }
            }
        }

        val play = TextView(this).apply {
            text =
                if (mediaPlayer?.isPlaying == true)
                    "Ⅱ"
                else
                    "▶"

            textSize = 31f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    shape =
                        android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.rgb(20, 20, 20))
                }

            setOnClickListener {
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) {
                            it.pause()
                            text = "▶"
                            playButton.text = "▶"
                        } else {
                            it.start()
                            text = "Ⅱ"
                            playButton.text = "Ⅱ"
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val next = TextView(this).apply {
            text = "⏭"
            textSize = 25f
            setTextColor(Color.rgb(20, 20, 20))
            gravity = Gravity.CENTER
            includeFontPadding = false

            setOnClickListener {
                val index =
                    songs.indexOfFirst {
                        it.id == currentSong?.id
                    }

                if (
                    index >= 0 &&
                    index < songs.lastIndex
                ) {
                    playSong(songs[index + 1])
                    dialog.dismiss()
                    showNowPlaying()
                }
            }
        }

        controls.addView(
            previous,
            LinearLayout.LayoutParams(
                dp(72),
                dp(64)
            )
        )

        controls.addView(
            play,
            LinearLayout.LayoutParams(
                dp(68),
                dp(68)
            )
        )

        controls.addView(
            next,
            LinearLayout.LayoutParams(
                dp(72),
                dp(64)
            )
        )

        root.addView(
            controls,
            LinearLayout.LayoutParams(
                -1,
                dp(76)
            ).apply {
                topMargin = dp(2)
            }
        )

        // ---------- SECONDARY CONTROLS ----------
        val secondary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val shuffle = text(
            "⤨",
            22f,
            Color.rgb(90, 90, 90),
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.CENTER
        }

        val repeat = text(
            "↻",
            22f,
            Color.rgb(90, 90, 90),
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.CENTER
        }

        secondary.addView(
            shuffle,
            LinearLayout.LayoutParams(
                dp(70),
                dp(42)
            )
        )

        secondary.addView(
            repeat,
            LinearLayout.LayoutParams(
                dp(70),
                dp(42)
            )
        )

        root.addView(
            secondary,
            LinearLayout.LayoutParams(
                -1,
                dp(42)
            )
        )

        // ---------- PROGRESS UPDATER ----------
        val handler =
            android.os.Handler(
                android.os.Looper.getMainLooper()
            )

        fun formatTime(ms: Int): String {
            val totalSeconds =
                (ms / 1000).coerceAtLeast(0)

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

        val updater = object : Runnable {

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
                                formatTime(position)

                            remaining.text =
                                "-" + formatTime(
                                    duration - position
                                )

                            play.text =
                                if (player.isPlaying)
                                    "Ⅱ"
                                else
                                    "▶"

                            playButton.text =
                                if (player.isPlaying)
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

        handler.post(updater)

        // ---------- SHOW ----------
        dialog.setContentView(root)

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                Color.WHITE
            )
        )

        dialog.setOnDismissListener {
            handler.removeCallbacks(updater)
        }

        dialog.show()

        dialog.window?.setLayout(
            -1,
            -1
        )
    }

    private fun playSong(song: Song) {

        mediaPlayer?.release()

        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            song.id
        )

        mediaPlayer = android.media.MediaPlayer().apply {

            setDataSource(
                this@MainActivity,
                uri
            )

            prepare()
            start()

            setOnCompletionListener {
                playButton.text = "▶"
            }
        }

        currentSong = song

        getAlbumArt(song)?.let {
            miniCover.setImageBitmap(it)
        } ?: run {
            miniCover.setImageResource(0)
        }

        miniTitle.text = song.title
        miniArtist.text = song.artist
        playButton.text = "Ⅱ"
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        style: Int
    ): TextView {

        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setTypeface(null, style)
        }
    }

    override fun onDestroy() {

        mediaPlayer?.release()
        mediaPlayer = null

        super.onDestroy()
    }
}
