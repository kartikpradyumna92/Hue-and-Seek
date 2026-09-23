package com.colorwalk.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.colorwalk.app.domain.StreakCalculator
import kotlinx.coroutines.delay

/**
 * Current time in millis that changes only at each local midnight and on every
 * resume — for UI keyed to "today" (theme accent, calendar highlight) that would
 * otherwise go stale while the app stays open across midnight (BUG-036/BUG-037).
 * The resume re-read matters: delay() doesn't advance through device deep sleep.
 */
@Composable
fun rememberDayTick(): Long {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(StreakCalculator.millisUntilNextLocalMidnight() + 500L)
            }
        }
    }
    return tick
}
