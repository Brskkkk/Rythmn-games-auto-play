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
            val legacy = readPoints(json, "xs", "ys")
            val watchGuides = readPoints(json, "watchXs", "watchYs").ifEmpty {
                legacy.ifEmpty { CalibrationProfile.defaultReceptors(count) }
            }
            val inputTargets = readPoints(json, "inputXs", "inputYs").ifEmpty {
                legacy.ifEmpty { watchGuides }
            }
            CalibrationProfile(
                name = name,
                laneCount = count,
                watchGuides = watchGuides,
                inputTargets = inputTargets,
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
        val json = JSONObject()
            .put("laneCount", p.laneCount)
            .put("watchXs", JSONArray().apply { p.watchGuides.forEach { put(it.x.toDouble()) } })
            .put("watchYs", JSONArray().apply { p.watchGuides.forEach { put(it.y.toDouble()) } })
            .put("inputXs", JSONArray().apply { p.inputTargets.forEach { put(it.x.toDouble()) } })
            .put("inputYs", JSONArray().apply { p.inputTargets.forEach { put(it.y.toDouble()) } })
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

    private fun readPoints(json: JSONObject, xKey: String, yKey: String): List<Receptor> {
        val xs = json.optJSONArray(xKey) ?: return emptyList()
        val ys = json.optJSONArray(yKey) ?: return emptyList()
        if (xs.length() == 0 || xs.length() != ys.length()) return emptyList()
        return (0 until xs.length()).map { Receptor(xs.getDouble(it).toFloat(), ys.getDouble(it).toFloat()) }
    }

    companion object {
        private const val KEY_NAMES = "profile_names"
        private const val KEY_SELECTED = "selected_profile"
    }
}
