package com.music.app

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicPlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        player =
            ExoPlayer.Builder(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()

        mediaSession =
            MediaSession.Builder(
                this,
                player
            ).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        /*
         * Keep the foreground playback service alive while
         * music is actively playing.
         *
         * If playback is already paused, the service may stop.
         */
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {

        mediaSession?.release()
        mediaSession = null

        player.release()

        super.onDestroy()
    }
}
