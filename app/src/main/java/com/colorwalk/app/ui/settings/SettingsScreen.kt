package com.colorwalk.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.colorwalk.app.notification.AlarmScheduler
import com.colorwalk.app.notification.NotificationPrefs
import com.colorwalk.app.ui.theme.ThemeMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }

    var selectedTheme       by remember { mutableStateOf(NotificationPrefs.getThemeMode(context)) }
    var notificationsEnabled by remember { mutableStateOf(NotificationPrefs.isEnabled(context)) }
    var morningEnabled      by remember { mutableStateOf(NotificationPrefs.isMorningEnabled(context)) }
    var eveningEnabled      by remember { mutableStateOf(NotificationPrefs.isEveningEnabled(context)) }
    var morningHour         by remember { mutableIntStateOf(NotificationPrefs.getMorningHour(context)) }
    var morningMinute       by remember { mutableIntStateOf(NotificationPrefs.getMorningMinute(context)) }
    var eveningHour         by remember { mutableIntStateOf(NotificationPrefs.getEveningHour(context)) }
    var eveningMinute       by remember { mutableIntStateOf(NotificationPrefs.getEveningMinute(context)) }
    var showMorningPicker   by remember { mutableStateOf(false) }
    var showEveningPicker   by remember { mutableStateOf(false) }

    if (showMorningPicker) {
        val pickerState = rememberTimePickerState(initialHour = morningHour, initialMinute = morningMinute)
        AlertDialog(
            onDismissRequest = { showMorningPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.widthIn(min = 280.dp),
            title = { Text("Morning reminder") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    morningHour = pickerState.hour
                    morningMinute = pickerState.minute
                    NotificationPrefs.setMorning(context, pickerState.hour, pickerState.minute)
                    if (notificationsEnabled && morningEnabled) AlarmScheduler.scheduleMorning(context)
                    showMorningPicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showMorningPicker = false }) { Text("Cancel") }
            }
        )
    }

    if (showEveningPicker) {
        val pickerState = rememberTimePickerState(initialHour = eveningHour, initialMinute = eveningMinute)
        AlertDialog(
            onDismissRequest = { showEveningPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.widthIn(min = 280.dp),
            title = { Text("Evening reminder") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    eveningHour = pickerState.hour
                    eveningMinute = pickerState.minute
                    NotificationPrefs.setEvening(context, pickerState.hour, pickerState.minute)
                    if (notificationsEnabled && eveningEnabled) AlarmScheduler.scheduleEvening(context)
                    showEveningPicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showEveningPicker = false }) { Text("Cancel") }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    "Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {

                // ── About ─────────────────────────────────────────────────────
                SettingsSectionHeader("About")

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Hue & Seek", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "A daily color walk — find today's color in the world around you.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Version $versionName",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Display ──────────────────────────────────────────────────
                SettingsSectionHeader("Display")

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Theme", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        ThemeSelector(
                            selected = selectedTheme,
                            onSelect = { mode ->
                                selectedTheme = mode
                                NotificationPrefs.setThemeMode(context, mode)
                                onThemeChange(mode)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Notifications ─────────────────────────────────────────────
                SettingsSectionHeader("Notifications")

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // Master toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Daily reminders", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Notified only if you haven't completed today's walk",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    lineHeight = 16.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = notificationsEnabled,
                                onCheckedChange = { enabled ->
                                    notificationsEnabled = enabled
                                    NotificationPrefs.setEnabled(context, enabled)
                                    if (enabled) AlarmScheduler.scheduleBoth(context)
                                    else AlarmScheduler.cancel(context)
                                }
                            )
                        }

                        if (notificationsEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                            // Morning row
                            ReminderSlotRow(
                                label      = "Morning",
                                hour       = morningHour,
                                minute     = morningMinute,
                                enabled    = morningEnabled,
                                onToggle   = { on ->
                                    morningEnabled = on
                                    NotificationPrefs.setMorningEnabled(context, on)
                                    if (on) AlarmScheduler.scheduleMorning(context)
                                    else AlarmScheduler.cancelMorning(context)
                                },
                                onChangeTap = { showMorningPicker = true }
                            )

                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                            // Evening row
                            ReminderSlotRow(
                                label      = "Evening",
                                hour       = eveningHour,
                                minute     = eveningMinute,
                                enabled    = eveningEnabled,
                                onToggle   = { on ->
                                    eveningEnabled = on
                                    NotificationPrefs.setEveningEnabled(context, on)
                                    if (on) AlarmScheduler.scheduleEvening(context)
                                    else AlarmScheduler.cancelEvening(context)
                                },
                                onChangeTap = { showEveningPicker = true }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderSlotRow(
    label: String,
    hour: Int,
    minute: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onChangeTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                formatTime(hour, minute),
                fontSize = 13.sp,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
        TextButton(
            onClick = onChangeTap,
            enabled = enabled
        ) { Text("Change") }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp)
    )
}

@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, label) ->
            val isSelected = mode == selected
            if (isSelected) {
                Button(
                    onClick = { onSelect(mode) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(mode) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) { Text(label, fontSize = 13.sp) }
            }
        }
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val amPm = if (hour < 12) "AM" else "PM"
    return String.format(Locale.getDefault(), "%d:%02d %s", h, minute, amPm)
}
