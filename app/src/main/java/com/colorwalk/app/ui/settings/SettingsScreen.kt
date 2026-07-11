package com.colorwalk.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.colorwalk.app.domain.WALK_COLORS
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

    // Re-check every time the screen resumes (e.g. after user returns from system settings).
    var notificationsBlocked by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notificationsBlocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
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
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {

                // ── About ─────────────────────────────────────────────────────
                SettingsSectionHeader("About")

                Card(
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Hue & Seek",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                brush = Brush.horizontalGradient(
                                    WALK_COLORS.map { it.composeColor }
                                )
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        // The eight walk colors — the app's identity in one row
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WALK_COLORS.forEach { walkColor ->
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(walkColor.composeColor)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "A daily color walk — find today's color in the world around you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                "v$versionName",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
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

                if (notificationsBlocked) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Notifications blocked",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "Reminders won't appear. Tap to enable in system settings.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }) {
                                Text("Open", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

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

                            // Informational only — the last-chance nudge has no toggle of
                            // its own; it rides on the reminders above (and can be muted
                            // per-channel in system settings).
                            if (morningEnabled || eveningEnabled) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                val lastChanceMinute = AlarmScheduler.lastChanceMinuteOfDay(
                                    eveningEnabled, eveningHour * 60 + eveningMinute
                                )
                                val lastChanceText = if (lastChanceMinute != null) {
                                    val cal = java.util.Calendar.getInstance().apply {
                                        set(java.util.Calendar.HOUR_OF_DAY, lastChanceMinute / 60)
                                        set(java.util.Calendar.MINUTE, lastChanceMinute % 60)
                                    }
                                    val timeStr = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                                        .format(cal.time)
                                    "Miss your reminders? A silent last-call nudge arrives around $timeStr on days your walk isn't done — no sound, no buzz."
                                } else {
                                    "Your evening reminder is late enough to double as the day's last call — no extra nudge is sent."
                                }
                                Text(
                                    lastChanceText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
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
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
