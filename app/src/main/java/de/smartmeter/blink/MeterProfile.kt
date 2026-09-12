package de.smartmeter.blink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A named, fully configurable meter profile.
 *
 * Everything the sequence needs is stored here so different meter models (or
 * tweaked timings) can be saved and reused later. Predefined (built-in)
 * profiles are shipped in the asset file `assets/meter_profiles.json` in the
 * same `format:1` JSON used by export/import and are loaded once at startup via
 * [loadPredefined]. The bundled E320 preset reflects the values verified
 * against the Landis+Gyr/EYKON operation sheet.
 */
data class MeterProfile(
    val id: String,
    val name: String,
    val pinLength: Int = 4,
    val timing: MeterTiming = MeterTiming(),
    val opticalScrolls: Int = QuickAction.OPTICAL.defaultScrollsFromPower,
    val pinScrolls: Int = QuickAction.PIN.defaultScrollsFromPower,
    val description: String = "",
    /** Optional localized description used when [description] is blank. */
    val descriptionRes: Int? = null,
) {
    companion object {
        /** Id of the bundled standard meter. */
        const val DEFAULT_ID = "e320"

        /**
         * Last-resort fallback used when the asset file is missing or invalid,
         * so the app always has a usable profile.
         */
        val DEFAULT = MeterProfile(
            id = DEFAULT_ID,
            name = "Landis+Gyr/EYKON E320",
            pinLength = 4,
            timing = MeterTiming(),
            opticalScrolls = QuickAction.OPTICAL.defaultScrollsFromPower,
            pinScrolls = QuickAction.PIN.defaultScrollsFromPower,
        )

        @Volatile
        private var predefinedCache: List<MeterProfile>? = null

        /**
         * Reads the predefined meter configs from `assets/meter_profiles.json`
         * (parsed once, then cached). Falls back to [DEFAULT] if the file is
         * missing or invalid so the app never starts without a profile.
         */
        fun loadPredefined(context: Context): List<MeterProfile>
        {
            predefinedCache?.let { return it }
            return synchronized(this) {
                predefinedCache ?: runCatching {
                    context.assets.open("meter_profiles.json")
                        .bufferedReader()
                        .use { it.readText() }
                        .let { parseProfilesJson(it, context) }
                }.getOrElse { listOf(DEFAULT) }.also { predefinedCache = it }
            }
        }

        /**
         * Parses a JSON profile list (or a single object) using the same shape
         * as the app's export format. Numeric fields are clamped to sane
         * limits, duplicate ids are dropped. Static so it can be unit tested
         * without Android.
         */
        fun parseProfilesJson(raw: String, context: Context? = null): List<MeterProfile>
        {
            val value = JSONTokener(raw).nextValue()
            val arr = when (value) {
                is JSONArray -> value
                is JSONObject -> JSONArray().put(value)
                else -> return emptyList()
            }
            val out = ArrayList<MeterProfile>(arr.length())
            val seenIds = HashSet<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val p = fromJson(obj, context) ?: continue
                if (seenIds.add(p.id)) {
                    out += p
                }
            }
            return out
        }

        /** Converts one `format:1` JSON object into a clamped profile. */
        fun fromJson(obj: JSONObject, context: Context? = null): MeterProfile?
        {
            val id = obj.optString("id").trim()
            if (id.isEmpty()) return null
            val descriptionRes = context?.let { findDescriptionRes(it, id) }
            return MeterProfile(
                id = id,
                name = obj.optString("name").trim().ifBlank { "Default profile" },
                pinLength = obj.optInt("pinLength", 4).coerceIn(1, 8),
                timing = MeterTiming(
                    pulseLengthMs = obj.optInt("pulseLengthMs", 180).coerceIn(50, 2000),
                    pulseGapMs = obj.optInt("pulseGapMs", 320).coerceIn(50, 2000),
                    digitWaitMs = obj.optInt("digitWaitMs", 3400).coerceIn(500, 15000),
                    longPulseMs = obj.optInt("longPulseMs", 6000).coerceIn(1000, 20000),
                ),
                opticalScrolls = obj.optInt("opticalScrolls", 8).coerceIn(0, 50),
                pinScrolls = obj.optInt("pinScrolls", 9).coerceIn(0, 50),
                description = if (descriptionRes != null) "" else obj.optString("description", ""),
                descriptionRes = descriptionRes,
            )
        }

        /**
         * Resolves an optional localized description following the convention
         * `meter_profile_<id>_desc`, e.g. `meter_profile_e320_desc`.
         */
        fun findDescriptionRes(context: Context, id: String): Int?
        {
            val resId = context.resources.getIdentifier(
                "meter_profile_${id}_desc", "string", context.packageName
            )
            return if (resId != 0) resId else null
        }
    }
}