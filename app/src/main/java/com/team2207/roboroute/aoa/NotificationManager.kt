package com.team2207.roboroute.aoa

import com.team2207.roboroute.ui.home.NotificationLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ActiveNotification(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val level: NotificationLevel,
    val durationMs: Long,
)

object NotificationManager {
    private val _current = MutableStateFlow<ActiveNotification?>(null)
    val current: StateFlow<ActiveNotification?> = _current.asStateFlow()

    fun postNotification(
        title: String,
        description: String,
        level: NotificationLevel,
        durationMs: Long,
    ) {
        _current.value =
            ActiveNotification(
                title = title,
                description = description,
                level = level,
                durationMs = durationMs,
            )
    }

    fun dismiss() {
        _current.value = null
    }
}
