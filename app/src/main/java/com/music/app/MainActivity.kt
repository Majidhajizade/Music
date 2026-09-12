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

        // Apple Music style mini player
        root.addView(
            createMiniPlayer(),
            LinearLayout.LayoutParams(-1, dp(64)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
        )

        // Apple Music style tab bar
        root.addView(
            createBottomNavigation(),
            LinearLayout.LayoutParams(-1, dp(72)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                topMargin = 0
                bottomMargin = dp(6)
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

        // ---------- HOME HEADER ----------
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val title = text(
            "Home",
            30f,
            Color.BLACK,
            Typeface.BOLD
        ).apply {
            includeFontPadding = false
        }

        topRow.addView(
            title,
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(235, 235, 235))
            }

            outlineProvider = object : android.view.ViewOutlineProvider() {
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

            setOnClickListener {
                showProfileDialog()
            }
        }

        topRow.addView(
            avatar,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                leftMargin = dp(12)
            }
        )

        content.addView(
            topRow,
            LinearLayout.LayoutParams(-1, dp(50))
        )

        updateAvatar()

        // ---------- MADE FOR YOU ----------
        content.addView(
            text(
                "Top Picks for You",
                22f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
                setPadding(0, dp(28), 0, dp(4))
            }
        )

        content.addView(
            text(
                "Favorites",
                13f,
                Color.rgb(115, 115, 115),
                Typeface.NORMAL
            ).apply {
                includeFontPadding = false
                setPadding(0, 0, 0, dp(12))
            }
        )

        val favorite = ImageView(this).apply {

            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: android.view.View,
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

            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(30, 30, 30),
                    Color.rgb(85, 85, 85),
                    Color.rgb(10, 10, 10)
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }

            if (songs.isNotEmpty()) {
                getAlbumArt(songs[0])?.let {
                    setImageBitmap(it)
                }
            }

            setOnClickListener {
                if (songs.isNotEmpty()) {
                    playSong(songs[0])
                }
            }
        }

        val screenWidth = resources.displayMetrics.widthPixels

        val pickWidth = (screenWidth - dp(48))
            .coerceAtLeast(dp(220))

        val pickHeight = (pickWidth * 9 / 16)
            .coerceAtMost(dp(250))

        content.addView(
            favorite,
            LinearLayout.LayoutParams(
                pickWidth,
                pickHeight
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        // ---------- RECENTLY PLAYED ----------
        content.addView(
            text(
                "Recently Played",
                21f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
                setPadding(0, dp(28), 0, dp(12))
            }
        )

        val recentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(4))
        }

        val recent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        songs.take(8).forEach { song ->

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, dp(14), 0)

                setOnClickListener {
                    playSong(song)
                }
            }

            val cover = ImageView(this).apply {
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
                        setColor(Color.rgb(35, 35, 35))
                    }

                getAlbumArt(song)?.let {
                    setImageBitmap(it)
                }
            }

            item.addView(
                cover,
                LinearLayout.LayoutParams(
                    dp(92),
                    dp(92)
                )
            )

            val songTitle = text(
                song.title,
                12f,
                Color.rgb(25, 25, 25),
                Typeface.BOLD
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize =
                    android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(7), 0, 0)
            }

            item.addView(
                songTitle,
                LinearLayout.LayoutParams(
                    dp(92),
                    dp(34)
                )
            )

            recent.addView(item)
        }

        recentScroll.addView(recent)

        content.addView(
            recentScroll,
            LinearLayout.LayoutParams(-1, dp(132))
        )
    }

    private fun showLibrary() {

        content.removeAllViews()

        // ---------- LIBRARY HEADER ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }

        header.addView(
            text(
                "Library",
                30f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                includeFontPadding = false
            }
        )

        header.addView(
            text(
                "${songs.size} Songs",
                13f,
                Color.rgb(115, 115, 115),
                Typeface.NORMAL
            ).apply {
                includeFontPadding = false
                setPadding(0, dp(5), 0, 0)
            }
        )

        content.addView(header)

        // ---------- SONG LIST ----------
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        songs.forEach { song ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))

                setOnClickListener {
                    playSong(song)
                }
            }

            // Album artwork
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
                                dp(7).toFloat()
                            )
                        }
                    }

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(7).toFloat()
                        setColor(Color.rgb(35, 35, 35))
                    }

                getAlbumArt(song)?.let {
                    setImageBitmap(it)
                }
            }

            row.addView(
                cover,
                LinearLayout.LayoutParams(
                    dp(58),
                    dp(58)
                )
            )

            // Song information
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, dp(6), 0)
            }

            info.addView(
                text(
                    song.title,
                    15f,
                    Color.rgb(20, 20, 20),
                    Typeface.BOLD
                ).apply {
                    maxLines = 1
                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }
            )

            info.addView(
                text(
                    song.artist,
                    12f,
                    Color.rgb(115, 115, 115),
                    Typeface.NORMAL
                ).apply {
                    maxLines = 1
                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                    setPadding(0, dp(5), 0, 0)
                }
            )

            row.addView(
                info,
                LinearLayout.LayoutParams(0, dp(62), 1f)
            )

            // More button
            val more = text(
                "⋯",
                24f,
                Color.rgb(80, 80, 80),
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
                    dp(58)
                )
            )

            list.addView(row)

            // Apple-style subtle separator
            list.addView(
                View(this).apply {
                    setBackgroundColor(Color.rgb(235, 235, 235))
                },
                LinearLayout.LayoutParams(
                    -1,
                    dp(1)
                )
            )
        }

        scroll.addView(list)

        content.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun showSettings() {

        content.removeAllViews()

        content.addView(
            text(
                "Settings",
                34f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                setPadding(0, 10, 0, 25)
            }
        )

        addSetting(
            "Audio Quality",
            "High"
        ) {
            showChoiceDialog(
                "Audio Quality",
                arrayOf("Standard", "High", "Very High")
            )
        }

        addSetting(
            "Gapless Playback",
            "Enabled"
        ) {
            Toast.makeText(
                this,
                "Gapless Playback enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        addSetting(
            "Normalize Volume",
            "Off"
        ) {
            Toast.makeText(
                this,
                "Volume normalization selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        addSetting(
            "Theme",
            "Black & White"
        ) {
            showChoiceDialog(
                "Theme",
                arrayOf("Black & White", "System")
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
            setPadding(4, 20, 4, 20)
            setOnClickListener {
                action()
            }
        }

        row.addView(
            text(
                titleValue,
                17f,
                Color.BLACK,
                Typeface.NORMAL
            ),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        row.addView(
            text(
                value,
                14f,
                Color.GRAY,
                Typeface.NORMAL
            )
        )

        content.addView(row)
    }

    private fun createMiniPlayer(): LinearLayout {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(8), dp(5))

            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(247, 247, 247))
            }
        }

        // Album artwork
        miniCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true

            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: android.view.View,
                    outline: android.graphics.Outline
                ) {
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        dp(7).toFloat()
                    )
                }
            }

            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(90, 90, 90),
                    Color.rgb(25, 25, 25)
                )
            ).apply {
                cornerRadius = dp(7).toFloat()
            }
        }

        layout.addView(
            miniCover,
            LinearLayout.LayoutParams(dp(50), dp(50))
        )

        // Song information
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(6), 0)
        }

        miniTitle = text(
            "Nothing Playing",
            13f,
            Color.rgb(20, 20, 20),
            Typeface.BOLD
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        miniArtist = text(
            "Choose a song",
            11f,
            Color.rgb(115, 115, 115),
            Typeface.NORMAL
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        info.addView(
            miniTitle,
            LinearLayout.LayoutParams(-1, dp(21))
        )

        info.addView(
            miniArtist,
            LinearLayout.LayoutParams(-1, dp(20))
        )

        layout.addView(
            info,
            LinearLayout.LayoutParams(0, -1, 1f)
        )

        // Play / Pause
        playButton = TextView(this).apply {
            text = "▶"
            textSize = 19f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false

            background = null

            setOnClickListener {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        playButton.text = "▶"
                    } else {
                        it.start()
                        playButton.text = "Ⅱ"
                    }
                }
            }
        }

        layout.addView(
            playButton,
            LinearLayout.LayoutParams(dp(42), dp(50))
        )

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
            setPadding(dp(8), dp(0), dp(8), dp(0))
            setBackgroundColor(Color.WHITE)
        }

        nav.addView(
            navItem("⌂", "Home") { showHome() },
            LinearLayout.LayoutParams(0, dp(62), 1f)
        )

        nav.addView(
            navItem("♫", "Library") { showLibrary() },
            LinearLayout.LayoutParams(0, dp(62), 1f)
        )

        nav.addView(
            navItem("⚙", "Settings") { showSettings() },
            LinearLayout.LayoutParams(0, dp(62), 1f)
        )

        return nav
    }

    private fun navItem(
        icon: String,
        label: String,
        action: () -> Unit
    ): LinearLayout {

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(0))

            setOnClickListener {
                action()
            }

            addView(
                text(
                    icon,
                    21f,
                    Color.rgb(35, 35, 35),
                    Typeface.NORMAL
                ).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(-1, dp(34))
            )

            addView(
                text(
                    label,
                    10f,
                    Color.rgb(70, 70, 70),
                    Typeface.BOLD
                ).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(-1, dp(20))
            )
        }
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
            setPadding(dp(22), dp(12), dp(22), dp(18))
        }

        // ---------- TOP BAR ----------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val close = TextView(this).apply {
            text = "⌄"
            textSize = 30f
            setTextColor(Color.BLACK)
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
            11f,
            Color.rgb(90, 90, 90),
            Typeface.BOLD
        ).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = 0.12f
        }

        topBar.addView(
            topTitle,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val more = TextView(this).apply {
            text = "•••"
            textSize = 18f
            setTextColor(Color.BLACK)
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
                            dp(18).toFloat()
                        )
                    }
                }

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.rgb(30, 30, 30))
                }

            getAlbumArt(song)?.let {
                setImageBitmap(it)
            }
        }

        val screenWidth =
            resources.displayMetrics.widthPixels

        val coverSize =
            (screenWidth - dp(44))
                .coerceAtMost(dp(390))
                .coerceAtLeast(dp(250))

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
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
        )

        // ---------- SONG INFO ----------
        val songInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val songTitle = text(
            song.title,
            22f,
            Color.BLACK,
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
            setPadding(0, dp(6), 0, 0)
        }

        songInfo.addView(songTitle)
        songInfo.addView(songArtist)

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
                dp(28)
            )
        )

        // ---------- TIME ----------
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val elapsed = text(
            "0:00",
            11f,
            Color.rgb(105, 105, 105),
            Typeface.NORMAL
        ).apply {
            includeFontPadding = false
        }

        val remaining = text(
            "-0:00",
            11f,
            Color.rgb(105, 105, 105),
            Typeface.NORMAL
        ).apply {
            gravity = Gravity.RIGHT
            includeFontPadding = false
        }

        timeRow.addView(
            elapsed,
            LinearLayout.LayoutParams(0, dp(20), 1f)
        )

        timeRow.addView(
            remaining,
            LinearLayout.LayoutParams(0, dp(20), 1f)
        )

        root.addView(
            timeRow,
            LinearLayout.LayoutParams(-1, dp(20))
        )

        // ---------- MAIN CONTROLS ----------
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val previous = TextView(this).apply {
            text = "⏮"
            textSize = 26f
            setTextColor(Color.BLACK)
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

            textSize = 34f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false

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
            textSize = 26f
            setTextColor(Color.BLACK)
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
                dp(82),
                dp(64)
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
                dp(70)
            )
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
