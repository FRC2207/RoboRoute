package com.team2207.roboroute.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.team2207.roboroute.aoa.AoaSubscribeTrigger
import com.team2207.roboroute.datastore.Action
import com.team2207.roboroute.datastore.ActionRepository
import com.team2207.roboroute.datastore.ActionType
import com.team2207.roboroute.datastore.AppData
import com.team2207.roboroute.datastore.ButtonLayout
import com.team2207.roboroute.datastore.CustomButton
import com.team2207.roboroute.datastore.LayoutRepository
import com.team2207.roboroute.datastore.Pose2d
import com.team2207.roboroute.model.ActionExport
import com.team2207.roboroute.model.BackupModel
import com.team2207.roboroute.model.ButtonExport
import com.team2207.roboroute.model.PoseExport
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class SettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val actionRepository = ActionRepository(application)
    private val layoutRepository = LayoutRepository(application)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    val appData: StateFlow<AppData> =
        actionRepository.appDataFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppData.getDefaultInstance(),
        )

    fun saveAction(action: Action) {
        viewModelScope.launch {
            actionRepository.addAction(action)
        }
    }

    fun deleteAction(actionId: Int) {
        viewModelScope.launch {
            actionRepository.deleteAction(actionId)
            // Clear any buttons that referenced the deleted action so they aren't left dangling.
            layoutRepository.updateLayout { builder ->
                for (index in builder.buttonsList.indices) {
                    val button = builder.getButtons(index)
                    if (button.actionId == actionId) {
                        builder.setButtons(index, button.toBuilder().setActionId(0).build())
                    }
                }
            }
        }
    }

    fun updateNtPath(path: String) {
        viewModelScope.launch {
            actionRepository.updateNtPath(path)
        }
    }

    fun updateRobotDimensions(
        width: Double,
        length: Double,
    ) {
        viewModelScope.launch {
            actionRepository.updateRobotDimensions(width, length)
        }
    }

    fun manualSubscribe() {
        AoaSubscribeTrigger.trigger()
    }

    fun exportData(
        context: Context,
        uri: Uri,
    ) {
        viewModelScope.launch {
            val currentActions = actionRepository.appDataFlow.first()
            val currentLayout = layoutRepository.layoutFlow.first()

            val backup =
                BackupModel(
                    actions =
                        currentActions.actionsList.map { action ->
                            ActionExport(
                                id = action.id,
                                name = action.name,
                                type = action.actionType.name,
                                pose = if (action.hasPose()) PoseExport(action.pose.x, action.pose.y, action.pose.rotation) else null,
                                pathName = if (action.pathName.isNotEmpty()) action.pathName else null,
                                ntKey = if (action.ntKey.isNotEmpty()) action.ntKey else null,
                                ntData = if (action.ntData.isNotEmpty()) action.ntData else null,
                            )
                        },
                    buttons =
                        currentLayout.buttonsList.map { button ->
                            ButtonExport(
                                id = button.id,
                                x = button.x,
                                y = button.y,
                                radius = button.radius,
                                actionId = button.actionId,
                            )
                        },
                )

            val json = gson.toJson(backup)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(json)
                }
            }
        }
    }

    fun importData(
        context: Context,
        uri: Uri,
    ) {
        viewModelScope.launch {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        val backup = gson.fromJson(reader, BackupModel::class.java)

                        // Map back to Proto
                        val actionsProto =
                            AppData
                                .newBuilder()
                                .apply {
                                    backup.actions.forEach { act ->
                                        addActions(
                                            Action.newBuilder().apply {
                                                id = act.id
                                                name = act.name
                                                actionType = ActionType.valueOf(act.type)
                                                act.pose?.let { p ->
                                                    pose =
                                                        Pose2d
                                                            .newBuilder()
                                                            .setX(p.x)
                                                            .setY(p.y)
                                                            .setRotation(p.rotation)
                                                            .build()
                                                }
                                                act.pathName?.let { pathName = it }
                                                act.ntKey?.let { ntKey = it }
                                                act.ntData?.let { ntData = it }
                                            },
                                        )
                                    }
                                }.build()

                        val layoutProto =
                            ButtonLayout
                                .newBuilder()
                                .apply {
                                    backup.buttons.forEach { btn ->
                                        addButtons(
                                            CustomButton.newBuilder().apply {
                                                id = btn.id
                                                x = btn.x
                                                y = btn.y
                                                radius = btn.radius
                                                actionId = btn.actionId
                                            },
                                        )
                                    }
                                }.build()

                        actionRepository.replaceAll(actionsProto)
                        layoutRepository.replaceAll(layoutProto)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
