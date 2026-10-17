package dev.rhythm.autoplayer.config

import android.content.Context
import dev.rhythm.autoplayer.core.CalibrationProfile
import dev.rhythm.autoplayer.core.Receptor
import org.json.JSONArray
import org.json.JSONObject

class ProfileRepository(context: Context) {
    private val preferences = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun names(): List<String> = preferences.getStringSet(KEY_NAMES, emptySet()).orEmpty().toList().sorted()

    fun selectedName(): String = preferences.getString(KEY_SELECTED, "Default") ?: "Default"

    fun select(name: String) {
        if (name in names()) preferences.edit().putString(KEY_SELECTED, name).apply()
    }

    fun load(name: String = selectedName()): CalibrationProfile {
        val raw = preferences.getString("profile:$name", null) ?: return CalibrationProfile(name = name).normalized()
        return runCatching {
            val json = JSONObject(raw)
            val count = json.optInt("laneCount", 4)
            val xs = json.optJSONArray("xs")
            val ys = json.optJSONArray("ys")
            val receptors = if (xs != null && ys != null && xs.length() == ys.length() && xs.length() > 0) {
                (0 until xs.length()).map { Receptor(xs.getDouble(it).toFloat(), ys.getDouble(it).toFloat()) }
            } else {
                CalibrationProfile.defaultReceptors(count)
            }
            CalibrationProfile(
                name = name,
                laneCount = count,
                receptors = receptors,
                buttonSize = json.optDouble("buttonSize", 0.05).toFloat(),
                sensitivity = json.optDouble("sensitivity", 0.18).toFloat(),
                confidenceThreshold = json.optDouble("confidenceThreshold", 0.45).toFloat(),
                timingOffsetMs = json.optLong("timingOffsetMs", 0L),
                fallbackSpeed = json.optDouble("fallbackSpeed", 1.0).toFloat(),
                frameIntervalMs = json.optLong("frameIntervalMs", 20L),
                showMarkers = json.optBoolean("showMarkers", true)
            ).normalized()
        }.getOrDefault(CalibrationProfile(name = name).normalized())
    }

    fun save(profile: CalibrationProfile) {
        val p = profile.normalized()
        val xs = JSONArray().apply { p.receptors.forEach { put(it.x.toDouble()) } }
        val ys = JSONArray().apply { p.receptors.forEach { put(it.y.toDouble()) } }
        val json = JSONObject()
            .put("laneCount", p.laneCount)
            .put("xs", xs)
            .put("ys", ys)
            .put("buttonSize", p.buttonSize.toDouble())
            .put("sensitivity", p.sensitivity.toDouble())
            .put("confidenceThreshold", p.confidenceThreshold.toDouble())
            .put("timingOffsetMs", p.timingOffsetMs)
            .put("fallbackSpeed", p.fallbackSpeed.toDouble())
            .put("frameIntervalMs", p.frameIntervalMs)
            .put("showMarkers", p.showMarkers)
        val updated = names().toMutableSet().apply { add(p.name) }
        preferences.edit()
            .putString("profile:${p.name}", json.toString())
            .putStringSet(KEY_NAMES, updated)
            .putString(KEY_SELECTED, p.name)
            .apply()
    }

    fun delete(name: String) {
        val remaining = names().toMutableSet().apply { remove(name) }
        preferences.edit().remove("profile:$name").putStringSet(KEY_NAMES, remaining)
            .putString(KEY_SELECTED, remaining.sorted().firstOrNull() ?: "Default").apply()
    }

    fun rename(oldName: String, newName: String) {
        val clean = newName.trim().take(40)
        if (clean.isEmpty() || clean == oldName) return
        val profile = load(oldName).copy(name = clean)
        delete(oldName)
        save(profile)
    }

    companion object {
        private const val KEY_NAMES = "profile_names"
        private const val KEY_SELECTED = "selected_profile"
    }
}
