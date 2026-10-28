package com.team2207.roboroute.aoa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SerialLogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val formattedLog = "[$timestamp] $message"
        val currentList = _logs.value.toMutableList()
        currentList.add(0, formattedLog) // Newest first
        if (currentList.size > 1000) {
            currentList.removeAt(currentList.size - 1)
        }
        _logs.value = currentList
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
