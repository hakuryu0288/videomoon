package com.videomoon.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.videomoon.app.data.Take
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 音楽をどこから何ms使うか（音ズレ補正込み） */
data class AudioWindow(val startMs: Long, val lengthMs: Long)

fun audioWindow(take: Take, syncOffsetMs: Int): AudioWindow {
    // ＋補正 = 音を遅らせる = 映像の0秒地点では曲のより手前が鳴っている
    val start = (take.audioStartMs - syncOffsetMs).coerceAtLeast(0)
    val limit = if (take.audioLimitMs > 0) take.audioLimitMs - start else take.durationMs
    val len = minOf(take.durationMs, limit).coerceAtLeast(100)
    return AudioWindow(start, len)
}

/** アプリ内で再生するとき用：無音の映像＋曲の該当区間を同期再生するMediaSource */
fun buildTakeSource(context: Context, videoFile: File, trackFile: File?, take: Take, syncOffsetMs: Int): MediaSource {
    val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
    val video = factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(videoFile)))
    if (trackFile == null || !trackFile.exists()) return video
    val w = audioWindow(take, syncOffsetMs)
    val audio = ClippingMediaSource(
        factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(trackFile))),
        w.startMs * 1000, (w.startMs + w.lengthMs) * 1000,
    )
    return MergingMediaSource(/* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true, video, audio)
}

object Exporter {

    fun fileName(take: Take): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(take.createdAt))
        return if (take.fileName.endsWith(".jpg")) "videomoon_$f.jpg" else "videomoon_${f}_${take.fps}p.mp4"
    }

    /**
     * 無音の映像と音楽の区間を1本のMP4に合成し、ギャラリーへ保存する。
     * 映像のフレームレートはそのまま（60fpsなら60fps）。
     */
    suspend fun exportVideo(
        context: Context,
        take: Take,
        videoFile: File,
        trackFile: File?,
        syncOffsetMs: Int,
        hevc: Boolean,
        onProgress: (Float) -> Unit,
    ): Uri {
        if (trackFile == null || !trackFile.exists()) {
            return withContext(Dispatchers.IO) { MediaTools.saveToGallery(context, videoFile, fileName(take), true) }
        }
        val out = File(context.cacheDir, "export_${take.id}.mp4").apply { delete() }
        val w = audioWindow(take, syncOffsetMs)

        val videoItem = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(videoFile))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(w.lengthMs).build())
                .build()
        ).setRemoveAudio(true).build()

        val audioItem = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(trackFile))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(w.startMs).setEndPositionMs(w.startMs + w.lengthMs).build()
                ).build()
        ).setRemoveVideo(true)
            .setEffects(Effects(listOf(FadeAudioProcessor(take.fadeInMs, take.fadeOutMs, w.lengthMs)), emptyList()))
            .build()

        val composition = Composition.Builder(
            EditedMediaItemSequence(videoItem),
            EditedMediaItemSequence(audioItem),
        ).build()

        withContext(Dispatchers.Main) {
            coroutineScope {
                val transformerHolder = arrayOfNulls<Transformer>(1)
                val progressJob = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        delay(200)
                        val t = transformerHolder[0] ?: continue
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        val t = Transformer.Builder(context)
                            .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (cont.isActive) cont.resumeWithException(exportException)
                                }
                            })
                            .build()
                        transformerHolder[0] = t
                        cont.invokeOnCancellation { t.cancel() }
                        t.start(composition, out.absolutePath)
                    }
                } finally {
                    progressJob.cancel()
                }
            }
        }
        onProgress(1f)
        return withContext(Dispatchers.IO) {
            val uri = MediaTools.saveToGallery(context, out, fileName(take), true)
            out.delete()
            uri
        }
    }

    suspend fun exportPhoto(context: Context, take: Take, file: File): Uri =
        withContext(Dispatchers.IO) { MediaTools.saveToGallery(context, file, fileName(take), false) }
}
