package com.colorwalk.app.ui.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.colorwalk.app.MainActivity
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.colorForDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ColorWalkWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getInstance(context).photoDao()
        val streak = withContext(Dispatchers.IO) {
            StreakCalculator.compute(dao.getRecentPhotoDates())
        }
        val walkColor = colorForDay(System.currentTimeMillis())
        val circleColor = walkColor.composeColor

        // Widget is always dark-themed — pass the same color for day and night
        val bgColor = ColorProvider(day = Color(0xFF121212), night = Color(0xFF121212))
        val circleColorProvider = ColorProvider(day = circleColor, night = circleColor)
        val whiteText = ColorProvider(day = Color.White, night = Color.White)
        val dimText = ColorProvider(day = Color.White.copy(alpha = 0.7f), night = Color.White.copy(alpha = 0.7f))

        provideContent {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(bgColor)
                    .cornerRadius(16.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    // Today's color circle
                    Box(
                        modifier = GlanceModifier
                            .size(48.dp)
                            .background(circleColorProvider)
                            .cornerRadius(24.dp)
                    ) {}
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        walkColor.name,
                        style = TextStyle(
                            color = whiteText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        "🔥 $streak day${if (streak != 1) "s" else ""}",
                        style = TextStyle(
                            color = dimText,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }
    }

    companion object {
        suspend fun requestUpdate(context: Context) {
            GlanceAppWidgetManager(context)
                .getGlanceIds(ColorWalkWidget::class.java)
                .forEach { ColorWalkWidget().update(context, it) }
        }
    }
}

class ColorWalkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ColorWalkWidget()
}
