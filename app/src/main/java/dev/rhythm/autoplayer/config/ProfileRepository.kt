package dev.rhythm.autoplayer.config

import android.content.Context
import dev.rhythm.autoplayer.core.CalibrationProfile
import dev.rhythm.autoplayer.core.ScrollDirection
import org.json.JSONArray
import org.json.JSONObject

class ProfileRepository(context: Context) {
    private val preferences = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun names(): List<String> = preferences.getStringSet(KEY_NAMES, emptySet()).orEmpty().sorted()

    fun selectedName(): String = preferences.getString(KEY_SELECTED, "Default") ?: "Default"

    fun select(name: String) {
        if (name in names()) preferences.edit().putString(KEY_SELECTED, name).apply()
    }

    fun load(name: String = selectedName()): CalibrationProfile {
        val raw = preferences.getString("profile:$name", null) ?: return CalibrationProfile(name = name)
        return runCatching {
            val json = JSONObject(raw)
            val centersJson = json.optJSONArray("laneCenters") ?: JSONArray()
            val centers = (0 until centersJson.length()).map { centersJson.getDouble(it).toFloat() }
            CalibrationProfile(
                name = name,
                laneCount = json.optInt("laneCount", 4),
                laneCenters = centers.ifEmpty { CalibrationProfile.evenlySpacedCenters(json.optInt("laneCount", 4)) },
                hitLineY = json.optDouble("hitLineY", 0.82).toFloat(),
                direction = runCatching { ScrollDirection.valueOf(json.optString("direction", "DOWN")) }.getOrDefault(ScrollDirection.DOWN),
                sensitivity = json.optDouble("sensitivity", 0.18).toFloat(),
                confidenceThreshold = json.optDouble("confidenceThreshold", 0.45).toFloat(),
                timingOffsetMs = json.optLong("timingOffsetMs", 0L),
                fallbackSpeedPxPerSecond = json.optDouble("fallbackSpeedPxPerSecond", 900.0).toFloat(),
                frameIntervalMs = json.optLong("frameIntervalMs", 33L),
                observationOnly = json.optBoolean("observationOnly", true),
                overlayEnabled = json.optBoolean("overlayEnabled", true)
            ).normalized()
        }.getOrDefault(CalibrationProfile(name = name))
    }

    fun save(profile: CalibrationProfile) {
        val normalized = profile.normalized()
        val centers = JSONArray().apply { normalized.laneCenters.forEach { put(it.toDouble()) } }
        val json = JSONObject()
            .put("laneCount", normalized.laneCount)
            .put("laneCenters", centers)
            .put("hitLineY", normalized.hitLineY)
            .put("direction", normalized.direction.name)
            .put("sensitivity", normalized.sensitivity)
            .put("confidenceThreshold", normalized.confidenceThreshold)
            .put("timingOffsetMs", normalized.timingOffsetMs)
            .put("fallbackSpeedPxPerSecond", normalized.fallbackSpeedPxPerSecond)
            .put("frameIntervalMs", normalized.frameIntervalMs)
            .put("observationOnly", normalized.observationOnly)
            .put("overlayEnabled", normalized.overlayEnabled)
        val updated = names().toMutableSet().apply { add(normalized.name) }
        preferences.edit()
            .putString("profile:${normalized.name}", json.toString())
            .putStringSet(KEY_NAMES, updated)
            .putString(KEY_SELECTED, normalized.name)
            .apply()
    }

    fun delete(name: String) {
        val remaining = names().toMutableSet().apply { remove(name) }
        preferences.edit().remove("profile:$name").putStringSet(KEY_NAMES, remaining)
            .putString(KEY_SELECTED, remaining.sorted().firstOrNull() ?: "Default").apply()
    }

    fun rename(oldName: String, newName: String) {
        val cleanName = newName.trim().take(40)
        if (cleanName.isEmpty() || cleanName == oldName) return
        val profile = load(oldName).copy(name = cleanName)
        delete(oldName)
        save(profile)
    }

    companion object {
        private const val KEY_NAMES = "profile_names"
        private const val KEY_SELECTED = "selected_profile"
    }
}