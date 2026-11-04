package com.videomoon.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 曲の中の「使う範囲」。1曲に何個でも保存できる。 */
data class Clip(
    val id: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    /** 開始位置の何ms前から音楽を流すか（録画は startMs から） */
    val prerollMs: Long = 0,
) {
    val lengthMs get() = endMs - startMs

    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("start", startMs).put("end", endMs)
        .put("fadeIn", fadeInMs).put("fadeOut", fadeOutMs).put("preroll", prerollMs)

    companion object {
        fun from(o: JSONObject) = Clip(
            id = o.getString("id"), name = o.optString("name", "クリップ"),
            startMs = o.getLong("start"), endMs = o.getLong("end"),
            fadeInMs = o.optLong("fadeIn"), fadeOutMs = o.optLong("fadeOut"), prerollMs = o.optLong("preroll"),
        )
    }
}

/** 取り込んだ音源（アプリ内にコピーして保持する） */
data class Track(
    val id: String,
    val name: String,
    val fileName: String,
    val durationMs: Long,
    val createdAt: Long,
    val clips: List<Clip> = emptyList(),
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("file", fileName).put("duration", durationMs)
        .put("created", createdAt)
        .put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): Track {
            val arr = o.optJSONArray("clips") ?: JSONArray()
            return Track(
                id = o.getString("id"), name = o.getString("name"), fileName = o.getString("file"),
                durationMs = o.getLong("duration"), createdAt = o.getLong("created"),
                clips = (0 until arr.length()).map { Clip.from(arr.getJSONObject(it)) },
            )
        }
    }
}

enum class TakeKind { VIDEO, PHOTO }

/**
 * 撮影したテイク。動画は「無音の映像ファイル」と「どの曲のどこから音楽が流れていたか」を持つ。
 * 再生時・書き出し時にこの情報で音楽を正確に重ねる。
 */
data class Take(
    val id: String,
    val kind: TakeKind,
    val fileName: String,
    val thumbName: String?,
    val createdAt: Long,
    val title: String,
    val durationMs: Long = 0,
    val fps: Int = 0,
    val quality: String = "",
    val trackId: String? = null,
    val clipId: String? = null,
    /** 録画開始の瞬間に流れていた曲中の位置 */
    val audioStartMs: Long = 0,
    /** 曲のこの位置より後は使わない（クリップの終わり） */
    val audioLimitMs: Long = 0,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
) {
    fun toJson() = JSONObject()
        .put("id", id).put("kind", kind.name).put("file", fileName).put("thumb", thumbName ?: "")
        .put("created", createdAt).put("title", title).put("duration", durationMs).put("fps", fps)
        .put("quality", quality).put("trackId", trackId ?: "").put("clipId", clipId ?: "")
        .put("audioStart", audioStartMs).put("audioLimit", audioLimitMs)
        .put("fadeIn", fadeInMs).put("fadeOut", fadeOutMs)

    companion object {
        fun from(o: JSONObject) = Take(
            id = o.getString("id"), kind = TakeKind.valueOf(o.getString("kind")),
            fileName = o.getString("file"), thumbName = o.optString("thumb").ifEmpty { null },
            createdAt = o.getLong("created"), title = o.optString("title"),
            durationMs = o.optLong("duration"), fps = o.optInt("fps"), quality = o.optString("quality"),
            trackId = o.optString("trackId").ifEmpty { null }, clipId = o.optString("clipId").ifEmpty { null },
            audioStartMs = o.optLong("audioStart"), audioLimitMs = o.optLong("audioLimit"),
            fadeInMs = o.optLong("fadeIn"), fadeOutMs = o.optLong("fadeOut"),
        )
    }
}

data class AppSettings(
    val fps: Int = 60,
    val quality: String = "1080",      // "720" | "1080" | "4K"
    /** "auto"=端末の向きに合わせる / "portrait"=縦で固定 / "landscape"=横で固定 */
    val orientationMode: String = "auto",
    val stabilization: Boolean = true,
    val flickerHz: Int = 50,           // 東日本50 / 西日本60
    val countdown: Int = 3,
    val grid: Boolean = true,
    val histogram: Boolean = true,
    val zebra: Boolean = false,
    val zebraLevel: Int = 95,
    val peaking: Boolean = false,
    val mirrorPreview: Boolean = true,
    val mirrorSave: Boolean = false,
    val monitorVolume: Float = 0.9f,
    val syncOffsetMs: Int = 0,         // ＋で音を遅らせる
    val autoReview: Boolean = true,
    val continuous: Boolean = false,
    val hevc: Boolean = false,
    val frontCamera: Boolean = false,
) {
    fun toJson() = JSONObject()
        .put("fps", fps).put("quality", quality).put("orientation", orientationMode).put("stab", stabilization)
        .put("flicker", flickerHz).put("countdown", countdown).put("grid", grid).put("histo", histogram)
        .put("zebra", zebra).put("zebraLv", zebraLevel).put("peaking", peaking)
        .put("mirrorPreview", mirrorPreview).put("mirrorSave", mirrorSave)
        .put("monitorVol", monitorVolume.toDouble()).put("syncMs", syncOffsetMs)
        .put("autoReview", autoReview).put("continuous", continuous).put("hevc", hevc).put("front", frontCamera)

    companion object {
        fun from(o: JSONObject): AppSettings {
            val d = AppSettings()
            return AppSettings(
                fps = o.optInt("fps", d.fps), quality = o.optString("quality", d.quality),
                orientationMode = o.optString("orientation", d.orientationMode), stabilization = o.optBoolean("stab", d.stabilization),
                flickerHz = o.optInt("flicker", d.flickerHz), countdown = o.optInt("countdown", d.countdown),
                grid = o.optBoolean("grid", d.grid), histogram = o.optBoolean("histo", d.histogram),
                zebra = o.optBoolean("zebra", d.zebra), zebraLevel = o.optInt("zebraLv", d.zebraLevel),
                peaking = o.optBoolean("peaking", d.peaking), mirrorPreview = o.optBoolean("mirrorPreview", d.mirrorPreview),
                mirrorSave = o.optBoolean("mirrorSave", d.mirrorSave),
                monitorVolume = o.optDouble("monitorVol", d.monitorVolume.toDouble()).toFloat(),
                syncOffsetMs = o.optInt("syncMs", d.syncOffsetMs), autoReview = o.optBoolean("autoReview", d.autoReview),
                continuous = o.optBoolean("continuous", d.continuous), hevc = o.optBoolean("hevc", d.hevc),
                frontCamera = o.optBoolean("front", d.frontCamera),
            )
        }
    }
}

fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
