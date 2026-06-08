package com.colorwalk.app.notification

import android.content.Context
import android.content.SharedPreferences
import com.colorwalk.app.ui.theme.ThemeMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JUnit tests for [NotificationPrefs].
 *
 * NotificationPrefs delegates entirely to SharedPreferences, which we mock with MockK.
 * No Android framework is needed at runtime — MockK replaces the Context/SharedPreferences
 * infrastructure, making these fast JVM unit tests.
 */
class NotificationPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    // Backing map so get/put calls are coherent within a single test
    private val boolMap = mutableMapOf<String, Boolean>()
    private val intMap = mutableMapOf<String, Int>()
    private val stringMap = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor

        // Wire editor put calls to write into the maps
        every { editor.putBoolean(any(), any()) } answers {
            boolMap[firstArg()] = secondArg(); editor
        }
        every { editor.putInt(any(), any()) } answers {
            intMap[firstArg()] = secondArg(); editor
        }
        every { editor.putString(any(), any()) } answers {
            stringMap[firstArg()] = secondArg(); editor
        }
        every { editor.apply() } returns Unit

        // Wire prefs get calls to read from the maps (falling back to supplied defaults)
        every { prefs.getBoolean(any(), any()) } answers {
            boolMap[firstArg()] ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            intMap[firstArg()] ?: secondArg()
        }
        every { prefs.getString(any(), ofType<String>()) } answers {
            stringMap[firstArg()] ?: secondArg()
        }
    }

    // ── isEnabled / setEnabled ────────────────────────────────────────────────

    @Test
    fun isEnabled_default_isTrue() {
        // "notifications_enabled" key not set — should fall back to default true
        val result = NotificationPrefs.isEnabled(context)
        assertTrue("Default notification enabled should be true", result)
    }

    @Test
    fun setEnabled_true_thenIsEnabled_returnsTrue() {
        NotificationPrefs.setEnabled(context, true)
        assertTrue(NotificationPrefs.isEnabled(context))
    }

    @Test
    fun setEnabled_false_thenIsEnabled_returnsFalse() {
        NotificationPrefs.setEnabled(context, false)
        assertFalse(NotificationPrefs.isEnabled(context))
    }

    @Test
    fun setEnabled_togglesTwice_finalValueIsCorrect() {
        NotificationPrefs.setEnabled(context, true)
        NotificationPrefs.setEnabled(context, false)
        assertFalse(NotificationPrefs.isEnabled(context))
    }

    // ── getMorningHour / setMorning ───────────────────────────────────────────

    @Test
    fun getMorningHour_default_isTen() {
        assertEquals(10, NotificationPrefs.getMorningHour(context))
    }

    @Test
    fun setMorning_hour_thenGetMorningHour_returnsStoredValue() {
        NotificationPrefs.setMorning(context, hour = 8, minute = 30)
        assertEquals(8, NotificationPrefs.getMorningHour(context))
    }

    @Test
    fun setMorning_hour_extremeValue_roundTrips() {
        NotificationPrefs.setMorning(context, hour = 23, minute = 0)
        assertEquals(23, NotificationPrefs.getMorningHour(context))
    }

    // ── getMorningMinute / setMorning ─────────────────────────────────────────

    @Test
    fun getMorningMinute_default_isZero() {
        assertEquals(0, NotificationPrefs.getMorningMinute(context))
    }

    @Test
    fun setMorning_minute_thenGetMorningMinute_returnsStoredValue() {
        NotificationPrefs.setMorning(context, hour = 9, minute = 45)
        assertEquals(45, NotificationPrefs.getMorningMinute(context))
    }

    @Test
    fun setMorning_hourAndMinute_bothRoundTrip() {
        NotificationPrefs.setMorning(context, hour = 14, minute = 15)
        assertEquals(14, NotificationPrefs.getMorningHour(context))
        assertEquals(15, NotificationPrefs.getMorningMinute(context))
    }

    @Test
    fun setMorning_calledTwice_secondCallOverridesFirst() {
        NotificationPrefs.setMorning(context, hour = 7, minute = 0)
        NotificationPrefs.setMorning(context, hour = 18, minute = 30)
        assertEquals(18, NotificationPrefs.getMorningHour(context))
        assertEquals(30, NotificationPrefs.getMorningMinute(context))
    }

    // ── getThemeMode / setThemeMode ───────────────────────────────────────────

    @Test
    fun getThemeMode_default_isSystem() {
        assertEquals(ThemeMode.SYSTEM, NotificationPrefs.getThemeMode(context))
    }

    @Test
    fun setThemeMode_dark_thenGetThemeMode_returnsDark() {
        NotificationPrefs.setThemeMode(context, ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, NotificationPrefs.getThemeMode(context))
    }

    @Test
    fun setThemeMode_light_thenGetThemeMode_returnsLight() {
        NotificationPrefs.setThemeMode(context, ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, NotificationPrefs.getThemeMode(context))
    }

    @Test
    fun setThemeMode_system_thenGetThemeMode_returnsSystem() {
        // Set to DARK first then switch back to SYSTEM
        NotificationPrefs.setThemeMode(context, ThemeMode.DARK)
        NotificationPrefs.setThemeMode(context, ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, NotificationPrefs.getThemeMode(context))
    }

    @Test
    fun getThemeMode_withInvalidStoredValue_fallsBackToSystem() {
        // Simulate a corrupt preference value
        stringMap["theme_mode"] = "INVALID_VALUE"
        val result = NotificationPrefs.getThemeMode(context)
        assertEquals(ThemeMode.SYSTEM, result)
    }
}
