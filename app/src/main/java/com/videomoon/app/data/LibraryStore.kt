package com.videomoon.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * アプリ内ライブラリ。音源・テイク・サムネイルはすべてアプリ専用領域に保存する。
 * 一覧はJSON1ファイル（小さいのでDBは使わない）。
 */
class LibraryStore(context: Context) {
    val root = File(context.filesDir, "library").apply { mkdirs() }
    val tracksDir = File(root, "tracks").apply { mkdirs() }
    val takesDir = File(root, "takes").apply { mkdirs() }
    val thumbsDir = File(root, "thumbs").apply { mkdirs() }
    private val peaksDir = File(root, "peaks").apply { mkdirs() }
    private val index = File(root, "index.json")
    private val prefs = context.getSharedPreferences("videomoon", Context.MODE_PRIVATE)

    fun load(): Pair<List<Track>, List<Take>> {
        if (!index.exists()) return emptyList<Track>() to emptyList()
        return try {
            val o = JSONObject(index.readText())
            val ta = o.optJSONArray("tracks") ?: JSONArray()
            val ka = o.optJSONArray("takes") ?: JSONArray()
            (0 until ta.length()).map { Track.from(ta.getJSONObject(it)) } to
                (0 until ka.length()).map { Take.from(ka.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList<Track>() to emptyList()
        }
    }

    @Synchronized
    fun save(tracks: List<Track>, takes: List<Take>) {
        val o = JSONObject()
            .put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
            .put("takes", JSONArray().apply { takes.forEach { put(it.toJson()) } })
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(index)
    }

    fun trackFile(t: Track) = File(tracksDir, t.fileName)
    fun takeFile(t: Take) = File(takesDir, t.fileName)
    fun thumbFile(t: Take) = t.thumbName?.let { File(thumbsDir, it) }

    fun loadPeaks(trackId: String): FloatArray? {
        val f = File(peaksDir, "$trackId.bin")
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { s ->
                val n = s.readInt(); FloatArray(n) { s.readFloat() }
            }
        } catch (e: Exception) { null }
    }

    fun savePeaks(trackId: String, p: FloatArray) {
        DataOutputStream(File(peaksDir, "$trackId.bin").outputStream().buffered()).use { s ->
            s.writeInt(p.size); p.forEach { s.writeFloat(it) }
        }
    }

    fun deletePeaks(trackId: String) { File(peaksDir, "$trackId.bin").delete() }

    fun loadSettings(): AppSettings =
        prefs.getString("settings", null)?.let { runCatching { AppSettings.from(JSONObject(it)) }.getOrNull() } ?: AppSettings()

    fun saveSettings(s: AppSettings) { prefs.edit().putString("settings", s.toJson().toString()).apply() }

    fun loadActiveClip(): Pair<String, String>? {
        val t = prefs.getString("activeTrack", null) ?: return null
        val c = prefs.getString("activeClip", null) ?: return null
        return t to c
    }

    fun saveActiveClip(v: Pair<String, String>?) {
        prefs.edit().putString("activeTrack", v?.first).putString("activeClip", v?.second).apply()
    }
}
