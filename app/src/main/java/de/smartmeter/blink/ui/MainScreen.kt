package de.smartmeter.blink.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import de.smartmeter.blink.BlinkViewModel
import de.smartmeter.blink.LightSourceMode
import de.smartmeter.blink.MeterProfile
import de.smartmeter.blink.MeterTiming
import de.smartmeter.blink.QuickAction
import de.smartmeter.blink.R
import de.smartmeter.blink.ui.theme.WarningRed
import java.util.Locale

private data class TimingSlider(
    val label: String,
    val value: Int,
    val unit: String,
    val range: IntRange,
    val steps: Int,
    val set: (Int) -> Unit,
)



@Composable
fun BlinkApp(viewModel: BlinkViewModel)
{
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activeProfile = state.activeProfile ?: MeterProfile.DEFAULT

    val sExportDone = stringResource(R.string.export_done)
    val sExportFailed = stringResource(R.string.export_failed)
    val sImportEmpty = stringResource(R.string.import_empty)
    val sImportFailed = stringResource(R.string.import_failed)

    var expanded by rememberSaveable { mutableStateOf(false) }
    var sendPinFirstOptical by rememberSaveable { mutableStateOf(true) }
    var sendPinFirstPin by rememberSaveable { mutableStateOf(true) }
    var showPinRequiredDialog by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onTorchPermissionResult(granted)
    }

    val chooseTorch = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.selectLightSource(LightSourceMode.TORCH)
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // The torch needs the CAMERA permission (setTorchMode throws without it on
    // many devices), so ask once up front instead of only on the Torch chip.
    var torchPermissionAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && !torchPermissionAsked) {
            torchPermissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /* JSON export / import ------------------------------------------- */
    var exportPending by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = exportPending
        exportPending = null
        if (uri != null && json != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                viewModel.notify(sExportDone)
            }
            catch (e: Exception)
            {
                viewModel.notify(String.format(Locale.ROOT, sExportFailed, e.message))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { ins ->
                    ins.readBytes().toString(Charsets.UTF_8)
                }
                if (text != null) viewModel.importProfilesJson(text)
                else viewModel.notify(sImportEmpty)
            }
            catch (e: Exception)
            {
                viewModel.notify(String.format(Locale.ROOT, sImportFailed, e.message))
            }
        }
    }
    val safeName = activeProfile.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val doExportCurrent = {
        exportPending = viewModel.exportProfilesJson(onlyActive = true)
        exportLauncher.launch("meter-profile-$safeName.json")
    }
    val doExportAll = {
        exportPending = viewModel.exportProfilesJson(onlyActive = false)
        exportLauncher.launch("meter-profiles.json")
    }
    val doImport = {
        importLauncher.launch(arrayOf("application/json"))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            /* Meter profile --------------------------------------------- */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.meter_label), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                ProfileSelector(
                    profile = activeProfile,
                    profiles = state.profiles,
                    enabled = !state.running,
                    onSelect = { viewModel.selectProfile(it) },
                    onAdd = { viewModel.addProfile() },
                    onDelete = { viewModel.deleteProfile(activeProfile.id) },
                    onExportCurrent = { doExportCurrent() },
                    onExportAll = { doExportAll() },
                    onImport = { doImport() },
                )
            }
            val profileDescription = activeProfile.description.ifEmpty {
                activeProfile.descriptionRes?.let { stringResource(it) }.orEmpty()
            }
            if (profileDescription.isNotEmpty()) {
                Text(
                    text = profileDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            /* Light source selection ---------------------------------- */
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = state.lightSource == LightSourceMode.TORCH,
                    onClick = { chooseTorch() },
                    label = { Text(stringResource(R.string.light_torch)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = state.lightSource == LightSourceMode.SCREEN,
                    onClick = { viewModel.selectLightSource(LightSourceMode.SCREEN) },
                    label = { Text(stringResource(R.string.light_screen)) },
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { viewModel.testLight() },
                    enabled = !state.running,
                ) {
                    Text(stringResource(R.string.test_light))
                }
            }
            val torchError = state.torchError
            if (torchError != null) {
                Text(
                    text = torchError,
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningRed,
                )
            }
            if (state.lightSource == LightSourceMode.TORCH && !state.cameraPermissionGranted) {
                Text(
                    text = stringResource(R.string.torch_perm_needed_ui),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningRed,
                )
            }
            if (state.lightSource == LightSourceMode.TORCH && !state.torchAvailable) {
                Text(
                    text = stringResource(R.string.torch_no_device_ui),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningRed,
                )
            }

            var showDark by rememberSaveable { mutableStateOf(state.darkScreen) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.darken_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showDark,
                    onCheckedChange = {
                        showDark = it
                        viewModel.setDarkScreen(it)
                    },
                )
            }

            /* PIN entry ------------------------------------------------ */
            Text(stringResource(R.string.pin_label), style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    if (activeProfile.pinLength <= 4) 12.dp else 6.dp
                ),
            ) {
                repeat(activeProfile.pinLength) { i ->
                    val d = state.pin.getOrNull(i)?.toString()?.takeIf { it.all { c -> c in '0'..'9' } } ?: "·"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .background(
                                when {
                                    state.activeDigit == i -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = d,
                            fontSize = if (activeProfile.pinLength <= 4) 32.sp else 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.activeDigit == i) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                            else
                            {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { d ->
                            KeypadButton(
                                digit = d,
                                enabled = !state.running,
                                onClick = { viewModel.appendDigit(d) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeypadButton(digit = null, enabled = !state.running, onClick = { viewModel.deleteDigit() }, label = "\u232B")
                    KeypadButton(digit = 0, enabled = !state.running, onClick = { viewModel.appendDigit(0) })
                    KeypadButton(
                        digit = null,
                        enabled = !state.running,
                        onClick = { viewModel.clearPin() },
                        label = stringResource(R.string.clear_key),
                        textSize = 16.sp,
                    )
                }
            }

            Button(
                onClick = { viewModel.sendPin() },
                enabled = state.pin.length == activeProfile.pinLength || state.running,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = if (state.running) stringResource(R.string.stop)
                    else stringResource(R.string.send_pin),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (state.running) {
                LinearProgressIndicator(
                    progress = { state.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = state.progress,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            /* Quick actions -------------------------------------------- */
            Text(stringResource(R.string.quick_actions), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.quick_actions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuickActionCard(
                title = stringResource(R.string.qa_inf_title),
                subtitle = stringResource(R.string.qa_inf_subtitle),
                scrolls = activeProfile.opticalScrolls,
                sendPinFirst = sendPinFirstOptical,
                onSendPinFirst = { sendPinFirstOptical = it },
                enabled = !state.running,
                onRun = {
                    if (sendPinFirstOptical && state.pin.length != activeProfile.pinLength) {
                        showPinRequiredDialog = true
                    }
                    else
                    {
                        viewModel.runQuickAction(QuickAction.OPTICAL, sendPinFirstOptical)
                    }
                },
            )
            QuickActionCard(
                title = stringResource(R.string.qa_pin_title),
                subtitle = stringResource(R.string.qa_pin_subtitle),
                scrolls = activeProfile.pinScrolls,
                sendPinFirst = sendPinFirstPin,
                onSendPinFirst = { sendPinFirstPin = it },
                enabled = !state.running,
                onRun = {
                    if (sendPinFirstPin && state.pin.length != activeProfile.pinLength) {
                        showPinRequiredDialog = true
                    }
                    else
                    {
                        viewModel.runQuickAction(QuickAction.PIN, sendPinFirstPin)
                    }
                },
            )

            /* Manual pulses -------------------------------------------- */
            Text(stringResource(R.string.manual_pulses), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ElevatedButton(
                    onClick = { viewModel.manualShort() },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.pulse_short))
                }
                ElevatedButton(
                    onClick = { viewModel.manualLong() },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.pulse_long))
                }
            }

            /* Settings -------------------------------------------------- */
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_label), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.collapse) else stringResource(R.string.timing_steps))
                }
            }
            if (expanded) {
                SettingsCard(
                    profile = activeProfile,
                    wakeMeter = state.wakeMeter,
                    onTiming = viewModel::updateTiming,
                    onPinLength = viewModel::setPinLength,
                    onOpticalScrolls = viewModel::setOpticalScrolls,
                    onPinScrolls = viewModel::setPinScrolls,
                    onWake = viewModel::setWakeMeter,
                )
            }

            /* Warning --------------------------------------------------- */
            Card {
                Text(
                text = stringResource(R.string.warning_text),
                style = MaterialTheme.typography.bodySmall,
                color = WarningRed,
                modifier = Modifier.padding(12.dp),
            )
            }

            Text(
                text = stringResource(R.string.tip_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        /* Sequence overlays (shown while a run is active) */
        if (state.running) {
            val dark = state.darkScreen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (dark) Color.Black else Color.Transparent)
                    .clickable { viewModel.stop() },
            ) {
                if (dark) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.progress,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            progress = { state.progressFraction },
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = stringResource(R.string.tap_to_stop),
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp),
                    )
                }
            }
            if (state.lightSource == LightSourceMode.SCREEN && state.strobe) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                )
    }
}

    if (showPinRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showPinRequiredDialog = false },
            title = { Text(stringResource(R.string.pin_required_title)) },
            text = {
                Text(
                    String.format(
                        Locale.ROOT,
                        stringResource(R.string.pin_required_body),
                        activeProfile.pinLength,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showPinRequiredDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}
    }

@Composable
private fun ProfileSelector(
    profile: MeterProfile,
    profiles: List<MeterProfile>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onExportCurrent: () -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(profile.name + "  \u25BE")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text((if (p.id == profile.id) "\u2713 " else "") + p.name) },
                    onClick = {
                        onSelect(p.id)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_new)) },
                onClick = {
                    onAdd()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_export_current)) },
                onClick = {
                    onExportCurrent()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_export_all)) },
                onClick = {
                    onExportAll()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_import)) },
                onClick = {
                    onImport()
                    open = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_delete)) },
                enabled = profiles.size > 1,
                onClick = {
                    onDelete()
                    open = false
                },
            )
        }
    }
}

@Composable
private fun KeypadButton(
    digit: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String = digit?.toString() ?: "",
    textSize: TextUnit = 24.sp,
) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(64.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = textSize,
            maxLines = 1,
            fontWeight = if (digit != null) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    scrolls: Int,
    sendPinFirst: Boolean,
    onSendPinFirst: (Boolean) -> Unit,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.qa_send_pin_first),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = sendPinFirst,
                    onCheckedChange = onSendPinFirst,
                )
            }
            if (!sendPinFirst) {
                Text(
                    text = stringResource(R.string.qa_skip_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningRed,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.steps_after_pin, scrolls),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onRun, enabled = enabled) {
                    Text(stringResource(R.string.run))
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    profile: MeterProfile,
    wakeMeter: Boolean,
    onTiming: (MeterTiming) -> Unit,
    onPinLength: (Int) -> Unit,
    onOpticalScrolls: (Int) -> Unit,
    onPinScrolls: (Int) -> Unit,
    onWake: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var showWake by rememberSaveable { mutableStateOf(wakeMeter) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wake_label), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.wake_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = showWake,
                    onCheckedChange = {
                        showWake = it
                        onWake(it)
                    },
                )
            }

            NumberStepper(
                label = stringResource(R.string.pin_length),
                value = profile.pinLength,
                min = 1,
                max = 8,
                onChanged = onPinLength,
            )

            val t = profile.timing
            val sliders = listOf(
                TimingSlider(
                    stringResource(R.string.t_pulse_length), t.pulseLengthMs, "ms", 100..400, 11,
                ) {
                    onTiming(t.copy(pulseLengthMs = it))
                },
                TimingSlider(
                    stringResource(R.string.t_pulse_gap), t.pulseGapMs, "ms", 100..600, 15,
                ) {
                    onTiming(t.copy(pulseGapMs = it))
                },
                TimingSlider(
                    stringResource(R.string.t_digit_wait), t.digitWaitMs, "s", 2000..5000, 24,
                ) {
                    onTiming(t.copy(digitWaitMs = it))
                },
                TimingSlider(
                    stringResource(R.string.t_long_pulse), t.longPulseMs, "s", 4000..9000, 15,
                ) {
                    onTiming(t.copy(longPulseMs = it))
                },
            )
            sliders.forEach { slider ->
                TimingSliderRow(t, slider)
            }

            NumberStepper(
                label = stringResource(R.string.t_steps_inf),
                value = profile.opticalScrolls,
                min = 0,
                max = 16,
                onChanged = onOpticalScrolls,
            )
            NumberStepper(
                label = stringResource(R.string.t_steps_pin),
                value = profile.pinScrolls,
                min = 0,
                max = 16,
                onChanged = onPinScrolls,
            )
        }
    }
}

@Composable
private fun TimingSliderRow(timing: MeterTiming, slider: TimingSlider)
{
    val value = slider.value.toFloat()
    Column {
        Row {
            Text(slider.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            val display = if (slider.unit == "s") "${"%.1f".format(value / 1000f)} s" else "$value ${slider.unit}"
            Text(display, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = { slider.set(it.toInt()) },
            valueRange = slider.range.first.toFloat()..slider.range.last.toFloat(),
            steps = slider.steps,
        )
    }
}

@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    min: Int = 0,
    max: Int = 16,
    onChanged: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onChanged(value - 1) }, enabled = value > min) {
            Text("\u2212")
        }
        Text(
            text = "$value",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedButton(onClick = { onChanged(value + 1) }, enabled = value < max) {
            Text("+")
        }
    }
}







