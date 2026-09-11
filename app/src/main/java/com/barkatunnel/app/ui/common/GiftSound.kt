package com.barkatunnel.app.ui.common
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.barkatunnel.app.R

class GiftSound private constructor(private var player: MediaPlayer?) {
    @Synchronized fun release() { val current=player;player=null;runCatching { current?.release() } }
    companion object {
        fun play(context: Context): GiftSound? {
            val player=MediaPlayer();val sound=GiftSound(player)
            return try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                context.resources.openRawResourceFd(R.raw.gift_celebration).use { player.setDataSource(it.fileDescriptor,it.startOffset,it.length) }
                player.setVolume(1f,1f)
                player.setOnPreparedListener { runCatching { it.start() }.onFailure { sound.release() } }
                player.setOnCompletionListener { sound.release() }
                player.setOnErrorListener { _,_,_ -> sound.release();true }
                player.prepareAsync();sound
            } catch (_: Exception) { sound.release();null }
        }
    }
}
