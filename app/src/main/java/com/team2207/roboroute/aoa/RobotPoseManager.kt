package com.team2207.roboroute.aoa

import com.team2207.roboroute.datastore.Pose2d
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RobotPoseManager {
    private val _livePose =
        MutableStateFlow(
            Pose2d
                .newBuilder()
                .setX(0.0)
                .setY(0.0)
                .setRotation(0.0)
                .build(),
        )
    val livePose: StateFlow<Pose2d> = _livePose.asStateFlow()

    private val _isPoseValid = MutableStateFlow(false)
    val isPoseValid: StateFlow<Boolean> = _isPoseValid.asStateFlow()

    private val _isRedAlliance = MutableStateFlow(false)
    val isRedAlliance: StateFlow<Boolean> = _isRedAlliance.asStateFlow()

    private var lastUpdateTime = 0L

    fun updateX(x: Double) {
        updateLastTime()
        _livePose.update { current ->
            current.toBuilder().setX(x).build()
        }
    }

    fun updateY(y: Double) {
        updateLastTime()
        _livePose.update { current ->
            current.toBuilder().setY(y).build()
        }
    }

    fun updateRotation(rotation: Double) {
        updateLastTime()
        // Invert rotation: user reported clockwise in AdvantageScope is counter-clockwise in app.
        // FRC/AdvantageKit typically use CCW positive.
        // Compose graphicsLayer.rotationZ uses CW positive.
        // So if AdvantageKit gives us CW positive, we need to invert it for our logic if we want to match.
        // Wait, AdvantageKit/WPILib use CCW positive.
        // If "clockwise in AdvantageScope goes counter clockwise in app", it means our app's rotation direction is inverted relative to AdvantageScope.
        _livePose.update { current ->
            current.toBuilder().setRotation(-rotation).build()
        }
    }

    fun updateFullPose(
        x: Double,
        y: Double,
        rotation: Double,
    ) {
        updateLastTime()
        _livePose.value =
            Pose2d
                .newBuilder()
                .setX(x)
                .setY(y)
                .setRotation(-rotation) // Invert rotation
                .build()
    }

    fun updateAlliance(isRed: Boolean) {
        _isRedAlliance.value = isRed
    }

    private fun updateLastTime() {
        lastUpdateTime = System.currentTimeMillis()
        _isPoseValid.value = true
    }

    // Bot stays visible at last known position
    fun checkValidity() {
        if (lastUpdateTime == 0L) {
            _isPoseValid.value = false
        } else {
            _isPoseValid.value = true
        }
    }
}
