package com.colorwalk.app.ui.components

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Date formatter for a CLDR skeleton ("MMMd", "yMMMd", …) in the user's locale order. */
fun localizedDateFormat(skeleton: String, locale: Locale = Locale.getDefault()): SimpleDateFormat =
    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

/** Clock time honouring the device's 12/24-hour setting. */
fun formatClockTime(context: Context, date: Date): String =
    DateFormat.getTimeFormat(context).format(date)
