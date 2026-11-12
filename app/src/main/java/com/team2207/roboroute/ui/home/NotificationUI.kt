package com.team2207.roboroute.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.team2207.roboroute.ui.theme.returnPrimaryColor
import com.team2207.roboroute.ui.theme.returnSecondaryColor
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

var notificationTitle: String = "Title"
var notificationDesc: String = "Description of the Notification"
var notificationLevel: NotificationLevel = NotificationLevel.INFO
var notificationDurationMs: Long = 3000L

enum class NotificationLevel {
    ERROR,
    WARNING,
    INFO,
}

@Preview(
    device = "spec:width=800dp,height=1280dp,dpi=240",
    showSystemUi = true,
    showBackground = true,
)
@Composable
fun NotificationView(
    modifier: Modifier = Modifier,
    id: String = "",
    title: String = notificationTitle,
    description: String = notificationDesc,
    level: NotificationLevel = notificationLevel,
    durationMs: Long = notificationDurationMs,
    onDismiss: () -> Unit = {},
) {
    var remainingMs by remember(id, title, description, level, durationMs) { mutableLongStateOf(durationMs) }

    LaunchedEffect(id, title, description, level, durationMs) {
        val startMs = System.currentTimeMillis()
        while (remainingMs > 0) {
            val elapsed = System.currentTimeMillis() - startMs
            remainingMs = (durationMs - elapsed).coerceAtLeast(0L)
            delay(16L.milliseconds)
        }
        onDismiss()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(12.dp)
                    .width(235.dp)
                    .background(Color.Gray),
        ) {
            Column(
                modifier = Modifier.padding(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val iconVector =
                        when (level) {
                            NotificationLevel.INFO -> Icons.Default.Info
                            NotificationLevel.WARNING -> Icons.Default.Warning
                            NotificationLevel.ERROR -> Icons.Default.Error
                        }
                    val iconTint =
                        when (level) {
                            NotificationLevel.INFO -> Color.White
                            NotificationLevel.WARNING -> Color.Yellow
                            NotificationLevel.ERROR -> Color.Red
                        }

                    Icon(
                        imageVector = iconVector,
                        contentDescription = level.name,
                        tint = iconTint,
                        modifier = Modifier.padding(end = 6.dp),
                    )

                    Column {
                        Text(title, color = returnSecondaryColor())
                        Text(description, color = returnSecondaryColor())
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val progress =
                    if (durationMs > 0) {
                        (remainingMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.DarkGray),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(returnPrimaryColor()),
                    )
                }
            }
        }
    }
}
