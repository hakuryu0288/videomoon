package com.videomoon.app.camera

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videomoon.app.data.Clip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 「音楽を流しながら撮る」の心臓部。
 *
 * 1. 曲のクリップ（プリロールがあれば少し前から）を再生開始
 * 2. 再生が実際に始まり、クリップ開始位置に達したら録画開始（マイク無し）
 * 3. 録画開始イベントの瞬間に曲のどこが鳴っていたか（audioStartMs）を記録
 * 4. クリップの終わりで自動停止
 *
 * 映像ファイルは無音。再生・書き出し時に audioStartMs から曲を重ねるので、
 * 動画に入る音は流した音楽だけになり、周りの音やスピーカーの反響は入らない。
 */
class RecordController(context: Context, private val engine: CameraEngine) {

    sealed interface Phase {
        data object Idle : Phase
        data object PreRoll : Phase
        data class Recording(val startedAt: Long) : Phase
        data object Finishing : Phase
    }

    data class Result(val file: File, val durationMs: Long, val audioStartMs: Long, val error: String?)

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
    }

    private var loadedKey: String? = null
    private var recording: Recording? = null
    @Volatile private var stopRequested = false

    /** 撮影画面でクリップが選ばれたら前もって準備しておく（録画開始の遅れを減らす） */
    fun load(trackFile: File, clip: Clip) {
        val pre = clip.prerollMs.coerceAtMost(clip.startMs)
        val key = "${trackFile.absolutePath}:${clip.startMs - pre}:${clip.endMs}"
        if (key == loadedKey) return
        loadedKey = key
        player.setMediaItem(
            MediaItem.Builder().setUri(Uri.fromFile(trackFile))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs - pre)
                        .setEndPositionMs(clip.endMs)
                        .build()
                ).build()
        )
        player.prepare()
    }

    fun unload() { player.stop(); player.clearMediaItems(); loadedKey = null }

    fun requestStop() { stopRequested = true }

    /** 曲の中で今どこが鳴っているか（プログレス表示用） */
    fun clipPositionMs(): Long = player.currentPosition

    suspend fun record(outFile: File, clip: Clip?, volume: Float, onPhase: (Phase) -> Unit): Result = withContext(Dispatchers.Main) {
        stopRequested = false
        val pre = clip?.prerollMs?.coerceAtMost(clip.startMs) ?: 0L
        var audioStart = 0L

        if (clip != null) {
            player.volume = volume
            player.seekTo(0)
            player.playWhenReady = true
            if (pre > 0) onPhase(Phase.PreRoll)
            // 再生が本当に始まり、録画を始める位置まで進むのを待つ
            val ok = withTimeoutOrNull(pre + 5_000) {
                while (!(player.isPlaying && player.currentPosition >= pre - 5)) {
                    if (stopRequested) return@withTimeoutOrNull false
                    delay(3)
                }
                true
            }
            if (ok != true) {
                player.pause()
                onPhase(Phase.Idle)
                return@withContext Result(outFile, 0, 0, if (stopRequested) "cancel" else "音楽を再生できませんでした")
            }
        }

        val started = CompletableDeferred<Long>()
        val finalized = CompletableDeferred<VideoRecordEvent.Finalize>()
        val rec = engine.startRecording(outFile) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    if (clip != null) audioStart = (clip.startMs - pre) + player.currentPosition
                    started.complete(SystemClock.elapsedRealtime())
                }
                is VideoRecordEvent.Finalize -> finalized.complete(ev)
                else -> Unit
            }
        }
        if (rec == null) {
            player.pause(); onPhase(Phase.Idle)
            return@withContext Result(outFile, 0, 0, "録画を開始できませんでした")
        }
        recording = rec

        val t0 = withTimeoutOrNull(3_000) { started.await() }
        if (t0 == null) {
            rec.stop(); player.pause(); onPhase(Phase.Idle)
            return@withContext Result(outFile, 0, 0, "録画を開始できませんでした")
        }
        onPhase(Phase.Recording(t0))

        // クリップの終わり（曲がENDED）か、停止ボタンまで待つ
        while (!stopRequested && !finalized.isCompleted) {
            if (clip != null && player.playbackState == Player.STATE_ENDED) break
            delay(8)
        }
        onPhase(Phase.Finishing)
        rec.stop()
        if (clip != null) player.pause()
        val fin = finalized.await()
        recording = null
        onPhase(Phase.Idle)

        val dur = fin.recordingStats.recordedDurationNanos / 1_000_000
        val err = if (!outFile.exists() || dur <= 0) "録画に失敗しました（${fin.error}）" else null
        Result(outFile, dur, audioStart, err)
    }

    fun release() {
        recording?.stop()
        player.release()
    }
}
