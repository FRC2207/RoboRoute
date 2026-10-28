package com.team2207.roboroute.aoa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

object AoaConnectionState {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }
}

object AoaConnection {
    val isConnected: Boolean
        get() = AoaConnectionState.isConnected.value

    suspend fun sendJson(
        outputStream: OutputStream?,
        json: String,
    ) {
        val stream = outputStream ?: return
        val formatted = if (json.endsWith("\n")) json else "$json\n"
        withContext(Dispatchers.IO) {
            try {
                val bytes = formatted.toByteArray()
                stream.write(bytes)
                stream.flush()
            } catch (e: IOException) {
                SerialLogManager.addLog("AOA: Write Error: ${e.message}")
            }
        }
    }
}
