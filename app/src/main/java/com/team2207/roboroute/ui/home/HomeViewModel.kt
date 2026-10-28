package com.team2207.roboroute.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.team2207.roboroute.aoa.AoaActionSender
import com.team2207.roboroute.aoa.AoaConnectionState
import com.team2207.roboroute.aoa.RobotPoseManager
import com.team2207.roboroute.datastore.ActionRepository
import com.team2207.roboroute.datastore.ActionType
import com.team2207.roboroute.datastore.AppData
import com.team2207.roboroute.datastore.ButtonLayout
import com.team2207.roboroute.datastore.CustomButton
import com.team2207.roboroute.datastore.LayoutRepository
import com.team2207.roboroute.datastore.Pose2d
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val layoutRepository = LayoutRepository(application)
    private val actionRepository = ActionRepository(application)

    val livePose: StateFlow<Pose2d> = RobotPoseManager.livePose
    val isPoseValid: StateFlow<Boolean> = RobotPoseManager.isPoseValid
    val isRedAlliance: StateFlow<Boolean> = RobotPoseManager.isRedAlliance
    val isAoaConnected: StateFlow<Boolean> = AoaConnectionState.isConnected

    init {
        viewModelScope.launch {
            while (true) {
                RobotPoseManager.checkValidity()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    val layout: StateFlow<ButtonLayout> =
        layoutRepository.layoutFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ButtonLayout.getDefaultInstance(),
        )

    val appData: StateFlow<AppData> =
        actionRepository.appDataFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppData.getDefaultInstance(),
        )

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    fun setEditing(editing: Boolean) {
        _isEditing.value = editing
    }

    fun addButton() {
        viewModelScope.launch {
            layoutRepository.updateLayout { builder ->
                val newId = (builder.buttonsList.maxOfOrNull { it.id } ?: 0) + 1
                builder.addButtons(
                    CustomButton
                        .newBuilder()
                        .setId(newId)
                        .setX(0.5f)
                        .setY(0.5f)
                        .setRadius(100f)
                        .build(),
                )
            }
        }
    }

    fun updateButton(
        buttonId: Int,
        x: Float,
        y: Float,
        radius: Float,
        actionId: Int,
    ) {
        viewModelScope.launch {
            layoutRepository.updateLayout { builder ->
                val index = builder.buttonsList.indexOfFirst { it.id == buttonId }
                if (index != -1) {
                    builder.setButtons(
                        index,
                        builder
                            .getButtons(index)
                            .toBuilder()
                            .setX(x)
                            .setY(y)
                            .setRadius(radius)
                            .setActionId(actionId)
                            .build(),
                    )
                }
            }
        }
    }

    fun deleteButton(buttonId: Int) {
        viewModelScope.launch {
            layoutRepository.updateLayout { builder ->
                val index = builder.buttonsList.indexOfFirst { it.id == buttonId }
                if (index != -1) {
                    builder.removeButtons(index)
                }
            }
        }
    }

    fun executeAction(action: com.team2207.roboroute.datastore.Action) {
        if (action.actionType == ActionType.POSE) {
            viewModelScope.launch {
                AoaActionSender.runPose(action.pose)
            }
        }
    }

    fun executeRoute(route: List<com.team2207.roboroute.datastore.Pose2d>) {
        viewModelScope.launch {
            AoaActionSender.runRoute(route)
        }
    }

    fun saveAction(action: com.team2207.roboroute.datastore.Action) {
        viewModelScope.launch {
            actionRepository.addAction(action)
        }
    }
}
