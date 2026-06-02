package com.colorwalk.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.colorwalk.app.domain.colorForDay

private data class Page(
    val title: String,
    val body: String,
    val icon: ImageVector? = null,
    val iconTint: Color = Color.White,
    val solidCircle: Boolean = false
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val accent = remember { colorForDay(System.currentTimeMillis()).composeColor }

    val pages = remember {
        listOf(
            Page(
                title = "A new color, every day",
                body = "Each day Hue & Seek gives you one color to hunt down in the real world. Step outside and find it.",
                solidCircle = true
            ),
            Page(
                title = "Capture & validate",
                body = "Take a photo where today's color dominates the frame. The app checks automatically — no cheating!",
                icon = Icons.Default.CameraAlt,
                iconTint = accent
            ),
            Page(
                title = "Build your streak",
                body = "One photo per day keeps your streak alive. Miss a day and it resets to zero. How far can you go?",
                icon = Icons.Default.LocalFireDepartment,
                iconTint = Color(0xFFFF6D00)
            )
        )
    }

    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val isLast = pageIndex == pages.lastIndex
    val enterAnim = remember { fadeIn(tween(280)) + slideInHorizontally(tween(280)) { it / 5 } }
    val exitAnim = remember { fadeOut(tween(200)) + slideOutHorizontally(tween(280)) { -it / 5 } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), Color(0xFF121212))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (!isLast) {
                    TextButton(onClick = onFinish) {
                        Text("Skip", color = Color.White.copy(alpha = 0.38f), fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Page content (circle + text) — single AnimatedContent so both animate together
            AnimatedContent(
                targetState = pageIndex,
                transitionSpec = { enterAnim togetherWith exitAnim },
                label = "page"
            ) { idx ->
                val p = pages[idx]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PageCircle(accent = accent, page = p, size = 164.dp)
                    Spacer(Modifier.height(48.dp))
                    Text(
                        p.title,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 32.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        p.body,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        textAlign = TextAlign.Center,
                        lineHeight = 23.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.indices.forEach { i ->
                    val active = i == pageIndex
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(if (active) 10.dp else 7.dp)
                            .background(if (active) accent else Color.White.copy(alpha = 0.25f))
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { if (isLast) onFinish() else pageIndex++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text(
                    if (isLast) "Get Started" else "Next",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PageCircle(accent: Color, page: Page, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (page.solidCircle) accent else accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        page.icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = page.iconTint,
                modifier = Modifier.size(size * 0.42f)
            )
        }
    }
}
