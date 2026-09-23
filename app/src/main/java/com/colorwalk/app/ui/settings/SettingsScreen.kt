package com.colorwalk.app.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
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
import com.colorwalk.app.data.PrivacyPrefs
import com.colorwalk.app.domain.WALK_COLORS
import com.colorwalk.app.notification.AlarmScheduler
import com.colorwalk.app.notification.NotificationPrefs
import com.colorwalk.app.ui.theme.ThemeMode
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.colorwalk.app.R
import android.content.Context
import java.util.Calendar
import com.colorwalk.app.ui.components.formatClockTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    // Supplied by the hub so pulling past the list's end still swipes back to Home.
    nestedScrollConnection: NestedScrollConnection? = null
) {
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }

    var selectedTheme       by remember { mutableStateOf(NotificationPrefs.getThemeMode(context)) }
    var notificationsEnabled by remember { mutableStateOf(NotificationPrefs.isEnabled(context)) }

    // Re-check every time the screen resumes (e.g. after user returns from system settings).
    var notificationsBlocked by remember { mutableStateOf(false) }
    // API 31+: exact alarms aren't pre-granted on Android 14+, so reminders fall back
    // to a ~15-minute inexact window unless the user allows precise timing.
    var exactAlarmsDenied by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notificationsBlocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
            exactAlarmsDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
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
            title = { Text(stringResource(R.string.settings_morning_reminder)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    morningHour = pickerState.hour
                    morningMinute = pickerState.minute
                    NotificationPrefs.setMorning(context, pickerState.hour, pickerState.minute)
                    if (notificationsEnabled && morningEnabled) AlarmScheduler.scheduleMorning(context)
                    showMorningPicker = false
                }) { Text(stringResource(R.string.settings_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showMorningPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showEveningPicker) {
        val pickerState = rememberTimePickerState(initialHour = eveningHour, initialMinute = eveningMinute)
        AlertDialog(
            onDismissRequest = { showEveningPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.widthIn(min = 280.dp),
            title = { Text(stringResource(R.string.settings_evening_reminder)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    eveningHour = pickerState.hour
                    eveningMinute = pickerState.minute
                    NotificationPrefs.setEvening(context, pickerState.hour, pickerState.minute)
                    if (notificationsEnabled && eveningEnabled) AlarmScheduler.scheduleEvening(context)
                    showEveningPicker = false
                }) { Text(stringResource(R.string.settings_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showEveningPicker = false }) { Text(stringResource(R.string.action_cancel)) }
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
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // BUG-011: scrollable — the content (~850dp with a banner) overflowed small
            // phones and large font scales, cutting off the evening reminder controls.
            Column(
                modifier = Modifier
                    .then(nestedScrollConnection?.let { Modifier.nestedScroll(it) } ?: Modifier)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()   // BUG-033: last row clears the nav bar
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {

                // ── About ─────────────────────────────────────────────────────
                SettingsSectionHeader(stringResource(R.string.settings_section_about))

                Card(
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.app_name),
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
                            stringResource(R.string.settings_about_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                stringResource(R.string.settings_version, versionName),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Display ──────────────────────────────────────────────────
                SettingsSectionHeader(stringResource(R.string.settings_section_display))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_theme), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                SettingsSectionHeader(stringResource(R.string.settings_section_notifications))

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
                                    stringResource(R.string.settings_notifications_blocked),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    stringResource(R.string.settings_notifications_blocked_body),
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
                                Text(stringResource(R.string.settings_open), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else if (notificationsEnabled && exactAlarmsDenied &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    // Never shown together with the "blocked" banner — with notifications
                    // blocked, alarm precision is moot; one actionable problem at a time.
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_exact_alarm_title),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    stringResource(R.string.settings_exact_alarm_body),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                // Granting fires SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED;
                                // BootReceiver re-arms the reminders as exact alarms (L-10).
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }) {
                                Text(stringResource(R.string.settings_allow), color = MaterialTheme.colorScheme.onSecondaryContainer)
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
                                Text(stringResource(R.string.settings_daily_reminders), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.settings_daily_reminders_body),
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
                                label      = stringResource(R.string.settings_morning),
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
                                label      = stringResource(R.string.settings_evening),
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
                                    val timeStr = formatTime(context, lastChanceMinute / 60, lastChanceMinute % 60)
                                    stringResource(R.string.settings_last_chance_on, timeStr)
                                } else {
                                    stringResource(R.string.settings_last_chance_off)
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

                Spacer(Modifier.height(24.dp))

                // ── Privacy (BUG-054, BUG-062) ───────────────────────────────
                SettingsSectionHeader(stringResource(R.string.settings_section_privacy))

                var locationInGallery by remember { mutableStateOf(PrivacyPrefs.saveLocationInGallery(context)) }
                var locationInShares by remember { mutableStateOf(PrivacyPrefs.includeLocationWhenSharing(context)) }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        PrivacyToggleRow(
                            title = stringResource(R.string.settings_location_gallery_title),
                            body = stringResource(R.string.settings_location_gallery_body),
                            checked = locationInGallery,
                            onCheckedChange = {
                                locationInGallery = it
                                PrivacyPrefs.setSaveLocationInGallery(context, it)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        PrivacyToggleRow(
                            title = stringResource(R.string.settings_location_share_title),
                            body = stringResource(R.string.settings_location_share_body),
                            checked = locationInShares,
                            onCheckedChange = {
                                locationInShares = it
                                PrivacyPrefs.setIncludeLocationWhenSharing(context, it)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                formatTime(LocalContext.current, hour, minute),
                fontSize = 13.sp,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
        TextButton(
            onClick = onChangeTap,
            enabled = enabled
        ) { Text(stringResource(R.string.settings_change)) }
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
    val options = listOf(
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system)
    )
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

// Honours the device's 12/24-hour setting (BUG-040).
private fun formatTime(context: Context, hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return formatClockTime(context, cal.time)
}
