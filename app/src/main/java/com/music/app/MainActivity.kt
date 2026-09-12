package com.music.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
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

data class Song(
    val id: Long,
    val title: String,
    val artist: String
)

class MainActivity : ComponentActivity() {

    private val songs = mutableListOf<Song>()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private var currentTab = 0

    private lateinit var content: LinearLayout
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var playButton: TextView

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            loadMusic()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasPermission()) {
            loadMusic()
        } else {
            requestPermission()
        }
    }

    private fun hasPermission(): Boolean {
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

    private fun requestPermission() {
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

    private fun showMain() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 0)
        }

        root.addView(
            content,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        miniPlayer = createMiniPlayer()

        root.addView(
            miniPlayer,
            LinearLayout.LayoutParams(
                -1,
                72
            )
        )

        root.addView(
            createBottomNavigation(),
            LinearLayout.LayoutParams(
                -1,
                78
            )
        )

        setContentView(root)

        showHome()
    }

    private fun showHome() {

        currentTab = 0
        content.removeAllViews()

        val title = text(
            "Music",
            34f,
            Color.BLACK,
            Typeface.BOLD
        )

        content.addView(title)

        val greeting = text(
            "Listen to your favorite songs",
            16f,
            Color.GRAY,
            Typeface.NORMAL
        )

        greeting.setPadding(0, 6, 0, 28)
        content.addView(greeting)

        val section = text(
            "Recently Added",
            22f,
            Color.BLACK,
            Typeface.BOLD
        )

        content.addView(section)

        if (songs.isEmpty()) {

            val empty = text(
                "No music found on this device",
                16f,
                Color.GRAY,
                Typeface.NORMAL
            )

            empty.gravity = Gravity.CENTER
            content.addView(
                empty,
                LinearLayout.LayoutParams(
                    -1,
                    180
                )
            )

        } else {

            songs.take(5).forEach {
                addSongRow(content, it)
            }
        }
    }

    private fun showLibrary() {

        currentTab = 1
        content.removeAllViews()

        content.addView(
            text(
                "Library",
                34f,
                Color.BLACK,
                Typeface.BOLD
            )
        )

        content.addView(
            text(
                "${songs.size} Songs",
                16f,
                Color.GRAY,
                Typeface.NORMAL
            )
        )

        val scroll = ScrollView(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
        }

        songs.forEach {
            addSongRow(list, it)
        }

        scroll.addView(list)

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

        currentTab = 2
        content.removeAllViews()

        content.addView(
            text(
                "Settings",
                34f,
                Color.BLACK,
                Typeface.BOLD
            )
        )

        content.addView(
            text(
                "Playback",
                21f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                setPadding(0, 30, 0, 12)
            }
        )

        addSetting(
            "Audio Quality",
            "High"
        )

        addSetting(
            "Gapless Playback",
            "Enabled"
        )

        addSetting(
            "Normalize Volume",
            "Off"
        )

        addSetting(
            "Theme",
            "Black & White"
        )

        content.addView(
            text(
                "Music",
                21f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                setPadding(0, 35, 0, 12)
            }
        )

        addSetting(
            "Songs on Device",
            songs.size.toString()
        )

        addSetting(
            "Version",
            "1.0"
        )
    }

    private fun addSetting(
        titleText: String,
        value: String
    ) {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 18, 0, 18)
        }

        val title = text(
            titleText,
            16f,
            Color.BLACK,
            Typeface.NORMAL
        )

        val valueText = text(
            value,
            14f,
            Color.GRAY,
            Typeface.NORMAL
        )

        row.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        row.addView(valueText)

        content.addView(row)
    }

    private fun addSongRow(
        container: LinearLayout,
        song: Song
    ) {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)

            setOnClickListener {
                playSong(song)
            }
        }

        val cover = TextView(this).apply {
            text = "♪"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
        }

        row.addView(
            cover,
            LinearLayout.LayoutParams(
                58,
                58
            )
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 10, 0)
        }

        info.addView(
            text(
                song.title,
                16f,
                Color.BLACK,
                Typeface.BOLD
            )
        )

        info.addView(
            text(
                song.artist,
                14f,
                Color.GRAY,
                Typeface.NORMAL
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

        val more = text(
            "⋯",
            25f,
            Color.BLACK,
            Typeface.BOLD
        )

        more.gravity = Gravity.CENTER

        row.addView(
            more,
            LinearLayout.LayoutParams(
                45,
                60
            )
        )

        container.addView(row)
    }

    private fun createMiniPlayer(): LinearLayout {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 6, 12, 6)
            setBackgroundColor(Color.BLACK)
        }

        val icon = TextView(this).apply {
            text = "♪"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        layout.addView(
            icon,
            LinearLayout.LayoutParams(
                50,
                50
            )
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 8, 0)
        }

        miniTitle = text(
            "Nothing Playing",
            15f,
            Color.WHITE,
            Typeface.BOLD
        )

        miniArtist = text(
            "Choose a song",
            12f,
            Color.LTGRAY,
            Typeface.NORMAL
        )

        info.addView(miniTitle)
        info.addView(miniArtist)

        layout.addView(
            info,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        playButton = text(
            "▶",
            22f,
            Color.WHITE,
            Typeface.BOLD
        )

        playButton.gravity = Gravity.CENTER

        playButton.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                playButton.text = "▶"
            } else {
                mediaPlayer?.start()
                playButton.text = "Ⅱ"
            }
        }

        layout.addView(
            playButton,
            LinearLayout.LayoutParams(
                50,
                50
            )
        )

        return layout
    }

    private fun createBottomNavigation(): LinearLayout {

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 14)
            setBackgroundColor(Color.WHITE)
        }

        nav.addView(
            navItem("⌂", "Home") {
                showHome()
            },
            LinearLayout.LayoutParams(
                0,
                58,
                1f
            )
        )

        nav.addView(
            navItem("♫", "Library") {
                showLibrary()
            },
            LinearLayout.LayoutParams(
                0,
                58,
                1f
            )
        )

        nav.addView(
            navItem("⚙", "Settings") {
                showSettings()
            },
            LinearLayout.LayoutParams(
                0,
                58,
                1f
            )
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
            setOnClickListener {
                action()
            }
        }

        item.addView(
            text(
                icon,
                22f,
                Color.BLACK,
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        item.addView(
            text(
                label,
                11f,
                Color.BLACK,
                Typeface.NORMAL
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        return item
    }

    private fun playSong(song: Song) {

        mediaPlayer?.release()

        val uri: Uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            song.id
        )

        mediaPlayer = MediaPlayer().apply {

            setDataSource(
                this@MainActivity,
                uri
            )

            prepare()

            setOnCompletionListener {
                playButton.text = "▶"
            }

            start()
        }

        currentSong = song

        miniTitle.text = song.title
        miniArtist.text = song.artist
        playButton.text = "Ⅱ"

        Toast.makeText(
            this,
            "Playing ${song.title}",
            Toast.LENGTH_SHORT
        ).show()
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
