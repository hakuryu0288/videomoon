package com.videomoon.app.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

object MediaTools {

    /** 共有・ファイル選択で受け取ったUriをアプリ内にコピーする。戻り値: (表示名, コピー先) */
    fun copyIntoApp(context: Context, uri: Uri, dir: File, id: String): Pair<String, File> {
        val cr = context.contentResolver
        var name = "音源"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty {
            when (cr.getType(uri)) { "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"; "video/mp4" -> "mp4"; else -> "bin" }
        }
        val out = File(dir, "$id.$ext")
        cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: error("ファイルを開けませんでした")
        return name.substringBeforeLast('.') to out
    }

    fun durationMs(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { r.release() }
    }

    fun hasAudioTrack(file: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (e: Exception) { false } finally { ex.release() }
    }

    /**
     * 音声をデコードして波形用のピーク値（1秒あたり perSecond 個, 0..1）を作る。
     * MP3/AAC/FLAC/WAV、動画ファイル内の音声にも対応。
     */
    fun decodePeaks(file: File, perSecond: Int = 50): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val ti = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var perBucket = max(1, sr / perSecond)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = ArrayList<Float>(perSecond * 300)
            var bucketMax = 0f
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            try {
                while (!outEos) {
                    if (!inEos) {
                        val ii = codec.dequeueInputBuffer(5_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 5_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        perBucket = max(1, sr / perSecond)
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size); buf.order(ByteOrder.nativeOrder())
                        if (isFloat) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= ch) {
                                var v = 0f
                                repeat(ch) { v = max(v, abs(fb.get())) }
                                bucketMax = max(bucketMax, v)
                                if (++count >= perBucket) { out.add(bucketMax); bucketMax = 0f; count = 0 }
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= ch) {
                                var v = 0
                                repeat(ch) { v = max(v, abs(sb.get().toInt())) }
                                bucketMax = max(bucketMax, v / 32768f)
                                if (++count >= perBucket) { out.add(bucketMax); bucketMax = 0f; count = 0 }
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            val arr = out.toFloatArray()
            val peak = arr.maxOrNull() ?: 0f
            if (peak > 0f) for (i in arr.indices) arr[i] /= peak
            return arr
        } finally {
            ex.release()
        }
    }

    fun makeVideoThumb(video: File, out: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            val bmp = r.getFrameAtTime(600_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return false
            saveScaled(bmp, out); true
        } catch (e: Exception) { false } finally { r.release() }
    }

    fun makePhotoThumb(photo: File, out: File): Boolean = try {
        val o = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeFile(photo.absolutePath, o)
        if (bmp != null) { saveScaled(bmp, out); true } else false
    } catch (e: Exception) { false }

    private fun saveScaled(bmp: Bitmap, out: File) {
        val w = 360
        val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(bmp, w, h, true)
        out.outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    /** 端末のギャラリー（ムービー/otsuuchicam または ピクチャ/otsuuchicam）へ保存 */
    fun saveToGallery(context: Context, src: File, displayName: String, isVideo: Boolean): Uri {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/otsuuchicam"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(collection, values) ?: error("保存先を作成できませんでした")
        cr.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }
}
