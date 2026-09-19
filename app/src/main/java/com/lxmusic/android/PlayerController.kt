package com.lxmusic.android

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class PlayerController(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    var currentUrl: String? = null
        private set
    var currentSong: Song? = null
        private set

    fun play(song: Song, url: String) {
        currentSong=song; currentUrl=url
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady=true
    }
    fun toggle(){ if(player.isPlaying) player.pause() else player.play() }
    fun release(){ player.release() }
}
