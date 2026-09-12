package com.music.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            showMusicHome()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasAudioPermission()) {
            showMusicHome()
        } else {
            requestAudioPermission()
        }
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        permissionLauncher.launch(permission)
    }

    private fun showMusicHome() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(28, 45, 28, 20)
        }

        val title = TextView(this).apply {
            text = "Music"
            textSize = 32f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Songs"
            textSize = 18f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 25)
        }

        val songsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(songsList)

        loadSongs(songsList)

        setContentView(root)
    }

    private fun loadSongs(container: LinearLayout) {

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )

        cursor?.use {

            val idColumn =
                it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)

            val titleColumn =
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            val artistColumn =
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (it.moveToNext()) {

                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn) ?: "Unknown"
                val artist = it.getString(artistColumn) ?: "Unknown Artist"

                val song = TextView(this).apply {
                    text = "$title\n$artist"
                    textSize = 17f
                    setTextColor(Color.BLACK)
                    setPadding(12, 18, 12, 18)

                    setOnClickListener {
                        playSong(id)
                    }
                }

                container.addView(song)
            }
        }
    }

    private fun playSong(id: Long) {

        mediaPlayer?.release()

        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            id
        )

        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            prepare()
            start()
        }

        Toast.makeText(
            this,
            "در حال پخش",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}
