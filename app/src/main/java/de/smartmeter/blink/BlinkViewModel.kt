package de.smartmeter.blink

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Drives the optical interface of a configurable meter profile: turns the PIN
 * digits into light pulses and walks the unlocked menu to toggle the
 * inF / Pin settings.
 *
 * All user-facing messages are resolved through string resources so the UI
 * follows the in-app language choice (see [LocaleHelper]).
 */
class BlinkViewModel(application: Application) : AndroidViewModel(application)
{

    data class UiState(
        val pin: String = "",
        val lightSource: LightSourceMode = LightSourceMode.SCREEN,
        val torchAvailable: Boolean = true,
        val cameraPermissionGranted: Boolean = true,
        val torchError: String? = null,
        val darkScreen: Boolean = true,
        val wakeMeter: Boolean = true,
        val profiles: List<MeterProfile> = emptyList(),
        val activeProfileId: String = "",
        val running: Boolean = false,
        val activeDigit: Int = -1,
        val strobe: Boolean = false,
        val inQuickAction: QuickAction? = null,
        val progressFraction: Float = 0f,
        val progress: String = "",
    ) {
        val activeProfile: MeterProfile?
            get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val flashlight = FlashlightController(application)
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var job: Job? = null

    init {
        val granted = ContextCompat.checkSelfPermission(
            application, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val profiles = loadProfiles()
        val savedActive = prefs.getString(K_ACTIVE, null)
        val activeId = profiles.firstOrNull { it.id == savedActive }?.id ?: profiles.first().id
        val active = profiles.first { it.id == activeId }

        _uiState.update {
            it.copy(
                lightSource = readPrefEnum(K_LIGHT, LightSourceMode.SCREEN),
                torchAvailable = flashlight.torchAvailable,
                cameraPermissionGranted = granted,
                darkScreen = prefs.getBoolean(K_DARK, true),
                wakeMeter = prefs.getBoolean(K_WAKE, true),
                profiles = profiles,
                activeProfileId = activeId,
                progress = str(R.string.vm_ready_meter, active.name),
            )
        }

        // If torch is impossible, fall back to the screen right away.
        if (_uiState.value.lightSource == LightSourceMode.TORCH && !flashlight.torchAvailable) {
            _uiState.update {
                it.copy(
                    lightSource = LightSourceMode.SCREEN,
                    torchError = flashlight.lastError ?: str(R.string.vm_no_flash),
                )
            }
        }
    }

    override fun onCleared()
    {
        scope.cancel()
        super.onCleared()
    }

    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    /* ------------------------------------------------------------------ */
    /* Input                                                              */
    /* ------------------------------------------------------------------ */

    fun appendDigit(digit: Int)
    {
        val s = _uiState.value
        val profile = s.activeProfile ?: return
        if (!s.running && digit in 0..9 && s.pin.length < profile.pinLength) {
            _uiState.update { it.copy(pin = it.pin + digit) }
        }
    }

    fun deleteDigit()
    {
        if (!_uiState.value.running) {
            _uiState.update { it.copy(pin = it.pin.dropLast(1)) }
        }
    }

    fun clearPin()
    {
        if (!_uiState.value.running) {
            _uiState.update { it.copy(pin = "") }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Meter profiles                                                     */
    /* ------------------------------------------------------------------ */

    fun selectProfile(id: String)
    {
        val s = _uiState.value
        if (id == s.activeProfileId || s.profiles.none { it.id == id }) return
        _uiState.update {
            it.copy(
                activeProfileId = id,
                pin = "",
                progress = str(R.string.vm_ready_meter, activeProfileOf(it.profiles, id).name),
            )
        }
        prefs.edit().putString(K_ACTIVE, id).apply()
    }

    fun addProfile()
    {
        val s = _uiState.value
        val base = s.activeProfile ?: return
        val id = "custom_${System.currentTimeMillis()}"
        val p = base.copy(
            id = id,
            name = "Custom ${s.profiles.size}",
            description = "",
        )
        saveProfile(p)
        val profiles = s.profiles + p
        persistProfileList(profiles)
        prefs.edit().putString(K_ACTIVE, id).apply()
        _uiState.update {
            it.copy(
                profiles = profiles,
                activeProfileId = id,
                progress = str(R.string.vm_profile_created, p.name, base.name),
            )
        }
    }

    fun deleteProfile(id: String)
    {
        val s = _uiState.value
        if (s.profiles.size <= 1 || s.profiles.none { it.id == id }) return
        val remaining = s.profiles.filterNot { it.id == id }
        removeProfilePrefs(id)
        persistProfileList(remaining)
        val newActive = if (id == s.activeProfileId) remaining.first().id else s.activeProfileId
        prefs.edit().putString(K_ACTIVE, newActive).apply()
        _uiState.update { it.copy(profiles = remaining, activeProfileId = newActive) }
    }

    /* ------------------------------------------------------------------ */
    /* JSON import / export                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Serializes the active profile (if [onlyActive]) or all known profiles
     * to a JSON string.
     */
    fun exportProfilesJson(onlyActive: Boolean): String
    {
        val s = _uiState.value
        val toExport = if (onlyActive) listOfNotNull(s.activeProfile) else s.profiles
        val arr = JSONArray()
        toExport.forEach { arr.put(toJson(it)) }
        return arr.toString(2)
    }

    /**
     * Parses a JSON profile list (or a single profile object) and merges it
     * into the known profiles: existing ids are updated in place, new ids are
     * added. Out-of-range values are clamped to sane limits.
     */
    fun importProfilesJson(json: String)
    {
        try {
            val value = JSONTokener(json).nextValue()
            val arr = when (value) {
                is JSONArray -> value
                is JSONObject -> JSONArray().put(value)
                else -> throw IllegalArgumentException("Not a profile list")
            }
            val s = _uiState.value
            val merged = s.profiles.toMutableList()
            var added = 0
            var updated = 0
            var skipped = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj == null) {
                    skipped++
                    continue
                }
                val id = obj.optString("id").trim()
                if (id.isEmpty()) {
                    skipped++
                    continue
                }
                val p = MeterProfile(
                    id = id,
                    name = obj.optString("name").trim().ifBlank { "Imported profile" },
                    pinLength = obj.optInt("pinLength", 4).coerceIn(1, 8),
                    timing = MeterTiming(
                        pulseLengthMs = obj.optInt("pulseLengthMs", 180).coerceIn(50, 2000),
                        pulseGapMs = obj.optInt("pulseGapMs", 320).coerceIn(50, 2000),
                        digitWaitMs = obj.optInt("digitWaitMs", 3400).coerceIn(500, 15000),
                        longPulseMs = obj.optInt("longPulseMs", 6000).coerceIn(1000, 20000),
                    ),
                    opticalScrolls = obj.optInt("opticalScrolls", 8).coerceIn(0, 50),
                    pinScrolls = obj.optInt("pinScrolls", 9).coerceIn(0, 50),
                    description = obj.optString("description", ""),
                )
                saveProfile(p)
                val idx = merged.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    merged[idx] = p
                    updated++
                }
                else
                {
                    merged += p
                    added++
                }
            }
            persistProfileList(merged)
            val newActive = if (merged.any { it.id == s.activeProfileId }) {
                s.activeProfileId
            }
            else
            {
                merged.first().id
            }
            prefs.edit().putString(K_ACTIVE, newActive).apply()
            _uiState.update {
                it.copy(
                    profiles = merged,
                    activeProfileId = newActive,
                    progress = str(R.string.vm_import_summary, added, updated, skipped),
                )
            }
        }
        catch (e: Exception)
        {
            _uiState.update { it.copy(progress = str(R.string.vm_error, e.message.orEmpty())) }
        }
    }

    /** Shows a transient status message (used after writing an export file). */
    fun notify(msg: String)
    {
        if (!_uiState.value.running) {
            _uiState.update { it.copy(progress = msg) }
        }
    }

    private fun toJson(p: MeterProfile): JSONObject = JSONObject().apply {
        put("format", 1)
        put("id", p.id)
        put("name", p.name)
        put("pinLength", p.pinLength)
        put("pulseLengthMs", p.timing.pulseLengthMs)
        put("pulseGapMs", p.timing.pulseGapMs)
        put("digitWaitMs", p.timing.digitWaitMs)
        put("longPulseMs", p.timing.longPulseMs)
        put("opticalScrolls", p.opticalScrolls)
        put("pinScrolls", p.pinScrolls)
        put("description", p.description)
    }

    /* ------------------------------------------------------------------ */
    /* Settings & light source                                            */
    /* ------------------------------------------------------------------ */

    fun updateTiming(timing: MeterTiming)
    {
        val s = _uiState.value
        val profile = s.activeProfile ?: return
        updateProfile(profile.copy(timing = timing))
    }

    fun setPinLength(n: Int)
    {
        val s = _uiState.value
        val profile = s.activeProfile ?: return
        val clamped = n.coerceIn(1, 8)
        if (clamped == profile.pinLength) return
        updateProfile(profile.copy(pinLength = clamped))
        if (s.pin.length > clamped) {
            _uiState.update { it.copy(pin = it.pin.take(clamped)) }
        }
    }

    fun setOpticalScrolls(n: Int)
    {
        val s = _uiState.value
        val profile = s.activeProfile ?: return
        updateProfile(profile.copy(opticalScrolls = n.coerceIn(0, 16)))
    }

    fun setPinScrolls(n: Int)
    {
        val s = _uiState.value
        val profile = s.activeProfile ?: return
        updateProfile(profile.copy(pinScrolls = n.coerceIn(0, 16)))
    }

    fun updateProfile(profile: MeterProfile)
    {
        saveProfile(profile)
        _uiState.update {
            it.copy(profiles = it.profiles.map { p -> if (p.id == profile.id) profile else p })
        }
    }

    fun setDarkScreen(on: Boolean)
    {
        _uiState.update { it.copy(darkScreen = on) }
        prefs.edit().putBoolean(K_DARK, on).apply()
    }

    fun setWakeMeter(on: Boolean)
    {
        _uiState.update { it.copy(wakeMeter = on) }
        prefs.edit().putBoolean(K_WAKE, on).apply()
    }

    fun selectLightSource(mode: LightSourceMode)
    {
        if (_uiState.value.running) return
        if (mode == LightSourceMode.TORCH) {
            if (!flashlight.torchAvailable) {
                _uiState.update {
                    it.copy(
                        lightSource = LightSourceMode.SCREEN,
                        progress = str(R.string.vm_no_torch),
                        torchError = flashlight.lastError,
                    )
                }
                return
            }
            if (!permissionGranted()) {
                _uiState.update {
                    it.copy(
                        progress = str(R.string.vm_torch_permission_needed),
                        torchError = str(R.string.flash_permission_missing),
                    )
                }
                return
            }
        }
        _uiState.update { it.copy(lightSource = mode, torchError = null) }
        prefs.edit().putString(K_LIGHT, mode.name).apply()
    }

    /** Called with the result of the runtime CAMERA permission prompt. */
    fun onTorchPermissionResult(granted: Boolean)
    {
        _uiState.update { it.copy(cameraPermissionGranted = granted) }
        if (granted) {
            selectLightSource(LightSourceMode.TORCH)
        }
        else
        {
            _uiState.update {
                it.copy(
                    lightSource = LightSourceMode.SCREEN,
                    progress = str(R.string.vm_camera_denied),
                    torchError = str(R.string.flash_permission_denied),
                )
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Operations                                                         */
    /* ------------------------------------------------------------------ */

    fun testLight()
    {
        if (_uiState.value.running) return
        runOperation(str(R.string.vm_test_light_label), 2000L) { pr ->
            lightOn(true)
            pr.report(1000, str(R.string.vm_test_1s))
            delay(1000)
            pr.report(1000, str(R.string.vm_test_2s))
            delay(1000)
            lightOff()
        }
    }

    /** Manual "short" pulse (scrolls the meter menu). */
    fun manualShort()
    {
        if (_uiState.value.running) return
        val t = _uiState.value.activeProfile?.timing ?: return
        runOperation(str(R.string.vm_short_label), t.manualShortMs.toLong()) { pr ->
            lightOn(true)
            pr.report(t.manualShortMs.toLong(), str(R.string.vm_short_pulse, t.manualShortMs))
            delay(t.manualShortMs.toLong())
            lightOff()
        }
    }

    /** Manual "long" pulse (selects/toggles the meter menu item). */
    fun manualLong()
    {
        if (_uiState.value.running) return
        val t = _uiState.value.activeProfile?.timing ?: return
        runOperation(str(R.string.vm_long_label), t.longPulseMs.toLong()) { pr ->
            lightOn(true)
            pr.report(
                t.longPulseMs.toLong(),
                str(R.string.vm_long_pulse, "%.1f".format(t.longPulseMs / 1000.0)),
            )
            delay(t.longPulseMs.toLong())
            lightOff()
        }
    }

    /** Sends the PIN over light. */
    fun sendPin()
    {
        val s = _uiState.value
        if (s.running) {
            stop()
            return
        }
        val profile = s.activeProfile ?: return
        if (s.pin.length != profile.pinLength) return
        val t = profile.timing
        val wake = s.wakeMeter
        val total = estimatePin(s.pin, t) +
            (if (wake) (t.manualShortMs + t.wakeWaitMs).toLong() else 0L)
        runOperation(str(R.string.vm_send_pin_label), total) { pr ->
            countdown(pr)
            wakeMeter(t, wake, pr)
            sendPinDigits(s.pin, t, pr)
        }
    }

    /**
     * Full macro: wake (optional) → PIN (if the meter asks for one) → scroll
     * to the target menu item → hold the long flash to toggle it.
     *
     * [sendPinFirst] is the per-run choice of whether the meter prompts for a
     * PIN: when it shows "Pin oFF", every flash scrolls the menu instead, so
     * the PIN phase must be skipped.
     */
    fun runQuickAction(action: QuickAction, sendPinFirst: Boolean = true)
    {
        val s = _uiState.value
        if (s.running) {
            stop()
            return
        }
        val profile = s.activeProfile ?: return
        if (sendPinFirst && s.pin.length != profile.pinLength) {
            _uiState.update { it.copy(progress = str(R.string.vm_pin_enter_first, profile.pinLength)) }
            return
        }
        val scrolls = if (action == QuickAction.OPTICAL) profile.opticalScrolls else profile.pinScrolls
        val t = profile.timing
        val wake = s.wakeMeter
        val digitsMs = if (sendPinFirst) estimatePin(s.pin, t) - COUNTDOWN_MS else 0L
        val total = COUNTDOWN_MS + digitsMs +
            (if (wake) (t.manualShortMs + t.wakeWaitMs).toLong() else 0L) +
            scrolls * (t.manualShortMs + t.pulseGapMs).toLong() +
            t.longPulseMs.toLong()

        val actionLabel = action.label(getApplication())
        runOperation(str(R.string.vm_run_action_label, actionLabel), total) { pr ->
            _uiState.update { it.copy(inQuickAction = action) }
            countdown(pr)
            wakeMeter(t, wake, pr)
            if (sendPinFirst) {
                sendPinDigits(s.pin, t, pr)
            }
            else
            {
                pr.report(0, str(R.string.vm_skip_pin))
            }
            pr.report(0, str(R.string.vm_scrolling, scrolls, actionLabel))
            repeat(scrolls) {
                lightOn(true)
                pr.report(t.manualShortMs.toLong(), str(R.string.vm_scroll_step, it + 1, scrolls))
                delay(t.manualShortMs.toLong())
                lightOff()
                pr.report(t.pulseGapMs.toLong())
                delay(t.pulseGapMs.toLong())
            }
            pr.report(0, str(R.string.vm_holding, "%.1f".format(t.longPulseMs / 1000.0), actionLabel))
            lightOn(true)
            pr.report(t.longPulseMs.toLong(), str(R.string.vm_long_hold))
            delay(t.longPulseMs.toLong())
            lightOff()
        }
    }

    fun stop()
    {
        job?.cancel()
        _uiState.update { it.copy(progress = str(R.string.vm_stopping)) }
    }

    /* ------------------------------------------------------------------ */
    /* Internals                                                          */
    /* ------------------------------------------------------------------ */

    private fun permissionGranted(): Boolean
    {
        val app = getApplication<Application>()
        return ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun currentLight(): LightSource
    {
        val s = _uiState.value
        return if (s.lightSource == LightSourceMode.TORCH) {
            TorchLightSource(flashlight)
        }
        else
        {
            ScreenLightSource { on -> _uiState.update { it.copy(strobe = on) } }
        }
    }

    private fun lightOn(vibrateFeedback: Boolean = false)
    {
        if (_uiState.value.lightSource == LightSourceMode.TORCH) {
            val ok = flashlight.setTorch(true)
            _uiState.update {
                it.copy(strobe = false, torchError = if (ok) null else flashlight.lastError)
            }
            if (ok && vibrateFeedback) vibrate(35)
        }
        else
        {
            _uiState.update { it.copy(strobe = true, torchError = null) }
            if (vibrateFeedback) vibrate(35)
        }
    }

    private fun lightOff()
    {
        if (_uiState.value.lightSource == LightSourceMode.TORCH) {
            flashlight.setTorch(false)
        }
        _uiState.update { it.copy(strobe = false) }
    }

    private suspend fun countdown(pr: Progress)
    {
        for (n in 3 downTo 1) {
            pr.report(1000, str(R.string.vm_countdown, n))
            delay(1000)
        }
    }

    /** Sends one short pulse so a sleeping meter wakes into the display test. */
    private suspend fun wakeMeter(timing: MeterTiming, wake: Boolean, pr: Progress)
    {
        if (!wake) return
        pr.report(0, str(R.string.vm_wake_start))
        lightOn(true)
        pr.report(timing.manualShortMs.toLong(), str(R.string.vm_wake_pulse, timing.manualShortMs))
        delay(timing.manualShortMs.toLong())
        lightOff()
        pr.report(timing.wakeWaitMs.toLong(), str(R.string.vm_wake_wait))
        delay(timing.wakeWaitMs.toLong())
    }

    private fun estimatePin(pin: String, t: MeterTiming): Long
    {
        var ms = COUNTDOWN_MS
        pin.forEachIndexed { i, ch ->
            val d = ch.digitToInt()
            ms += d * (t.pulseLengthMs + t.pulseGapMs).toLong()
            if (i < pin.length - 1) ms += t.digitWaitMs.toLong()
        }
        return ms
    }

    private suspend fun sendPinDigits(pin: String, timing: MeterTiming, pr: Progress)
    {
        for (i in pin.indices) {
            _uiState.update { it.copy(activeDigit = i) }
            val d = pin[i].digitToInt()
            pr.report(
                0,
                if (d == 0) str(R.string.vm_digit_zero, i + 1, pin.length)
                else str(R.string.vm_digit_flashing, i + 1, pin.length, d),
            )
            repeat(d) { flash ->
                lightOn(true)
                pr.report(
                    timing.pulseLengthMs.toLong(),
                    str(R.string.vm_digit_flash, i + 1, pin.length, flash + 1, d),
                )
                delay(timing.pulseLengthMs.toLong())
                lightOff()
                pr.report(timing.pulseGapMs.toLong())
                delay(timing.pulseGapMs.toLong())
            }
            if (i < pin.length - 1) {
                pr.report(0, str(R.string.vm_digit_wait, i + 1))
                pr.report(timing.digitWaitMs.toLong())
                delay(timing.digitWaitMs.toLong())
            }
        }
    }

    private fun runOperation(
        label: String,
        estimatedTotalMs: Long,
        block: suspend (Progress) -> Unit,
    ) {
        job?.cancel()
        val progress = Progress(estimatedTotalMs)
        _uiState.update {
            it.copy(running = true, progress = label, progressFraction = 0f, inQuickAction = null)
        }
        job = scope.launch {
            try {
                block(progress)
                progress.report(0, str(R.string.vm_done))
            }
            catch (e: CancellationException)
            {
                progress.report(0, str(R.string.vm_stopped))
            }
            catch (e: Exception)
            {
                progress.report(0, str(R.string.vm_error, e.message.orEmpty()))
            }
            finally
            {
                lightOff()
                vibratePattern()
                _uiState.update {
                    it.copy(
                        running = false,
                        strobe = false,
                        activeDigit = -1,
                        inQuickAction = null,
                        progressFraction = 0f,
                    )
                }
            }
        }
    }

    /** Tracks elapsed time vs. the estimated total so the UI can show progress. */
    private inner class Progress(private val totalMs: Long) {
        private var elapsed: Long = 0

        fun report(ms: Long, msg: String? = null)
        {
            elapsed += ms
            val fraction = if (totalMs <= 0) 0f else (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
            _uiState.update { state ->
                val text = if (msg != null) {
                    val remaining = totalMs - elapsed
                    if (remaining > 0) msg + str(R.string.vm_eta, (remaining + 999) / 1000) else msg
                }
                else
                {
                    state.progress
                }
                state.copy(progressFraction = fraction, progress = text)
            }
        }
    }

    private fun vibrate(ms: Long)
    {
        val app = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                }
                else
                {
                    @Suppress("DEPRECATION")
                    app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            else
            {
                @Suppress("DEPRECATION")
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(ms)
            }
        }
        catch (_: Exception)
        {
        }
    }

    private fun vibratePattern()
    {
        val app = getApplication<Application>()
        try {
            val pattern = longArrayOf(0, 60, 60, 60)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                }
                else
                {
                    @Suppress("DEPRECATION")
                    app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
            else
            {
                @Suppress("DEPRECATION")
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(pattern, -1)
            }
        }
        catch (_: Exception)
        {
        }
    }

    /* ------------------------------------------------------------------ */
    /* Profile persistence                                                */
    /* ------------------------------------------------------------------ */

    private fun loadProfiles(): List<MeterProfile>
    {
        val ids = prefs.getString(K_PROFILES, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val profiles = ids.mapNotNull { loadProfile(it) }
        return if (profiles.isEmpty()) MeterProfile.loadPredefined(getApplication()) else profiles
    }

    private fun loadProfile(id: String): MeterProfile?
    {
        val name = prefs.getString(nameKey(id), null) ?: return null
        val def = MeterProfile.loadPredefined(getApplication())
            .firstOrNull { it.id == id } ?: MeterProfile.DEFAULT
        return MeterProfile(
            id = id,
            name = name,
            pinLength = prefs.getInt(pinKey(id), def.pinLength),
            timing = MeterTiming(
                pulseLengthMs = prefs.getInt(pulseKey(id), def.timing.pulseLengthMs),
                pulseGapMs = prefs.getInt(gapKey(id), def.timing.pulseGapMs),
                digitWaitMs = prefs.getInt(digitKey(id), def.timing.digitWaitMs),
                longPulseMs = prefs.getInt(longKey(id), def.timing.longPulseMs),
            ),
            opticalScrolls = prefs.getInt(optKey(id), def.opticalScrolls),
            pinScrolls = prefs.getInt(pinscKey(id), def.pinScrolls),
            descriptionRes = MeterProfile.findDescriptionRes(getApplication(), id),
        )
    }

    private fun saveProfile(p: MeterProfile)
    {
        with(prefs.edit()) {
            putString(nameKey(p.id), p.name)
            putInt(pinKey(p.id), p.pinLength)
            putInt(pulseKey(p.id), p.timing.pulseLengthMs)
            putInt(gapKey(p.id), p.timing.pulseGapMs)
            putInt(digitKey(p.id), p.timing.digitWaitMs)
            putInt(longKey(p.id), p.timing.longPulseMs)
            putInt(optKey(p.id), p.opticalScrolls)
            putInt(pinscKey(p.id), p.pinScrolls)
        }.apply()
    }

    private fun removeProfilePrefs(id: String)
    {
        with(prefs.edit()) {
            remove(nameKey(id))
            remove(pinKey(id))
            remove(pulseKey(id))
            remove(gapKey(id))
            remove(digitKey(id))
            remove(longKey(id))
            remove(optKey(id))
            remove(pinscKey(id))
        }.apply()
    }

    private fun persistProfileList(profiles: List<MeterProfile>)
    {
        prefs.edit().putString(K_PROFILES, profiles.joinToString(",") { it.id }).apply()
    }

    private fun activeProfileOf(profiles: List<MeterProfile>, id: String): MeterProfile
    {
        return profiles.firstOrNull { it.id == id } ?: profiles.first()
    }

    private fun readPrefEnum(name: String, def: LightSourceMode): LightSourceMode
    {
        return runCatching { LightSourceMode.valueOf(prefs.getString(name, def.name) ?: def.name) }
            .getOrDefault(def)
    }

    companion object {
        private const val PREFS = "settings"
        private const val K_LIGHT = "light_source"
        private const val K_DARK = "dark_screen"
        private const val K_WAKE = "wake_meter"
        private const val K_PROFILES = "profiles"
        private const val K_ACTIVE = "active_profile"
        private const val COUNTDOWN_MS = 3000L

        private fun nameKey(id: String) = "profile_name_$id"
        private fun pinKey(id: String) = "profile_pinlen_$id"
        private fun pulseKey(id: String) = "profile_pulse_$id"
        private fun gapKey(id: String) = "profile_gap_$id"
        private fun digitKey(id: String) = "profile_digit_$id"
        private fun longKey(id: String) = "profile_long_$id"
        private fun optKey(id: String) = "profile_optical_$id"
        private fun pinscKey(id: String) = "profile_pin_scrolls_$id"
    }
}