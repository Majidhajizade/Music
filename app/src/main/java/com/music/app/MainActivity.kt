package com.music.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import android.graphics.Color
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val permissionCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // بدون Loading گیرکننده؛ مستقیم وارد Music می‌شویم
        if (hasAudioPermission()) {
            showMusicHome()
        } else {
            requestAudioPermission()
        }
    }

    private fun hasAudioPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAudioPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                permissionCode
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                permissionCode
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionCode) {
            showMusicHome()
        }
    }

    private fun showMusicHome() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(28, 35, 28, 0)
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
            setPadding(0, 8, 0, 20)
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

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val cursor = contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )

        cursor?.use {

            val idColumn = it.getColumnIndexOrThrow(
                MediaStore.Audio.Media._ID
            )

            val titleColumn = it.getColumnIndexOrThrow(
                MediaStore.Audio.Media.TITLE
            )

            val artistColumn = it.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ARTIST
            )

            while (it.moveToNext()) {

                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn)
                val artist = it.getString(artistColumn)

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

        val uri = android.content.ContentUris.withAppendedId(
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
