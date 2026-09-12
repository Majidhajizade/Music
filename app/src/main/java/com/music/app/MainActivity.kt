package com.music.app

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

    private fun showMain() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 0)
        }

        root.addView(
            content,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        root.addView(
            createMiniPlayer(),
            LinearLayout.LayoutParams(-1, 74)
        )

        root.addView(
            createBottomNavigation(),
            LinearLayout.LayoutParams(-1, 82)
        )

        setContentView(root)

        showHome()
    }

    private fun showHome() {

        content.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleBox.addView(
            text("Home", 34f, Color.BLACK, Typeface.BOLD)
        )

        titleBox.addView(
            View(this).apply {
                setBackgroundColor(Color.LTGRAY)
            },
            LinearLayout.LayoutParams(-1, 1).apply {
                topMargin = 22
            }
        )

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            setPadding(5, 5, 5, 5)

            setOnClickListener {
                showProfileDialog()
            }
        }

        header.addView(
            avatar,
            LinearLayout.LayoutParams(54, 54).apply {
                leftMargin = 16
            }
        )

        content.addView(header)

        updateAvatar()

        content.addView(
            text(
                "Top Picks for You",
                23f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                setPadding(0, 28, 0, 4)
            }
        )

        content.addView(
            text(
                "Favorites",
                15f,
                Color.GRAY,
                Typeface.NORMAL
            )
        )

        val favorite = TextView(this).apply {
            text = if (songs.isNotEmpty()) songs[0].title else "Favorites"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(15, 15, 15, 18)
            setBackgroundColor(Color.BLACK)

            setOnClickListener {
                if (songs.isNotEmpty()) playSong(songs[0])
            }
        }

        content.addView(
            favorite,
            LinearLayout.LayoutParams(-1, 300).apply {
                topMargin = 12
            }
        )

        content.addView(
            text(
                "Recently Played",
                21f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                setPadding(0, 25, 0, 12)
            }
        )

        val recentScroll = HorizontalScrollView(this)

        val recent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        songs.take(8).forEach { song ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 14, 0)

                setOnClickListener {
                    playSong(song)
                }
            }

            val cover = TextView(this).apply {
                text = "♪"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
            }

            item.addView(
                cover,
                LinearLayout.LayoutParams(110, 110)
            )

            item.addView(
                text(
                    it.title,
                    12f,
                    Color.BLACK,
                    Typeface.BOLD
                ).apply {
                    maxLines = 1
                }
            )

            recent.addView(item)
        }

        recentScroll.addView(recent)
        content.addView(recentScroll)
    }

    private fun showLibrary() {

        content.removeAllViews()

        content.addView(
            text(
                "Library",
                34f,
                Color.BLACK,
                Typeface.BOLD
            ).apply {
                setPadding(0, 10, 0, 4)
            }
        )

        content.addView(
            text(
                "${songs.size} Songs",
                15f,
                Color.GRAY,
                Typeface.NORMAL
            ).apply {
                setPadding(0, 0, 0, 14)
            }
        )

        val scroll = ScrollView(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        songs.forEach { song ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 9, 0, 9)

                setOnClickListener {
                    playSong(song)
                }
            }

            val cover = TextView(this).apply {
                text = "♪"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
            }

            row.addView(
                cover,
                LinearLayout.LayoutParams(58, 58)
            )

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 0, 5, 0)
            }

            info.addView(
                text(song.title, 16f, Color.BLACK, Typeface.BOLD)
            )

            info.addView(
                text(song.artist, 13f, Color.GRAY, Typeface.NORMAL)
            )

            row.addView(
                info,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val more = text(
                "⋮",
                25f,
                Color.BLACK,
                Typeface.BOLD
            )

            more.gravity = Gravity.CENTER

            more.setOnClickListener { view ->
                showSongMenu(view, song)
            }

            row.addView(
                more,
                LinearLayout.LayoutParams(42, 60)
            )

            list.addView(row)
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
            setPadding(16, 8, 12, 8)
            setBackgroundColor(Color.BLACK)
        }

        val icon = TextView(this).apply {
            text = "♪"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        layout.addView(
            icon,
            LinearLayout.LayoutParams(48, 48)
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 8, 0)
        }

        miniTitle = text(
            "Nothing Playing",
            14f,
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
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        playButton = text(
            "▶",
            21f,
            Color.WHITE,
            Typeface.BOLD
        )

        playButton.gravity = Gravity.CENTER

        playButton.setOnClickListener {
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

        layout.addView(
            playButton,
            LinearLayout.LayoutParams(48, 48)
        )

        return layout
    }

    private fun createBottomNavigation(): LinearLayout {

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 8, 20, 14)
            setBackgroundColor(Color.WHITE)
        }

        nav.addView(
            navItem("⌂", "Home") { showHome() },
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        nav.addView(
            navItem("♫", "Library") { showLibrary() },
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        nav.addView(
            navItem("⚙", "Settings") { showSettings() },
            LinearLayout.LayoutParams(0, 60, 1f)
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
            setBackgroundColor(Color.WHITE)

            setOnClickListener {
                action()
            }

            addView(
                text(icon, 26f, Color.BLACK, Typeface.NORMAL)
                    .apply { gravity = Gravity.CENTER }
            )

            addView(
                text(label, 11f, Color.BLACK, Typeface.NORMAL)
                    .apply { gravity = Gravity.CENTER }
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
