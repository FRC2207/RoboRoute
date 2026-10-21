package com.team2207.roboroute.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.team2207.roboroute.serial.SerialLogManager
import com.team2207.roboroute.ui.action.ActionCreationScreen
import com.team2207.roboroute.ui.theme.returnPrimaryColor
import com.team2207.roboroute.ui.theme.returnSecondaryColor
import com.team2207.roboroute.datastore.Action as ProtoAction
import com.team2207.roboroute.datastore.ActionType as ProtoActionType
import com.team2207.roboroute.datastore.Pose2d as ProtoPose2d
import com.team2207.roboroute.ui.action.Action as UIAction

@Composable
fun SettingsView(
    onBack: () -> Unit,
    onEditPose: (Double, Double, Double, Double, Double) -> Unit,
    navController: NavController,
    modifier: Modifier = Modifier,
    requestedEditActionId: Int? = null,
    viewModel: SettingsViewModel = viewModel(),
) {
    val appData by viewModel.appData.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Local state to avoid cursor jumping. Prefilled from the saved value and kept in sync
    // whenever the saved value changes externally, but never while the field is being edited.
    var localNtPath by remember { mutableStateOf(appData.ntPath) }
    var ntPathFocused by remember { mutableStateOf(false) }
    LaunchedEffect(appData.ntPath) {
        if (!ntPathFocused) {
            localNtPath = appData.ntPath
        }
    }
    var localWidth by remember(appData.robotWidth) { mutableStateOf(appData.robotWidth.toString()) }
    var localLength by remember(appData.robotLength) { mutableStateOf(appData.robotLength.toString()) }

    var draftPose by remember { mutableStateOf<UIAction.PoseSelection?>(null) }
    // rememberSaveable so the edited action survives navigating to the pose selector and back.
    var editingActionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var showLogSheet by remember { mutableStateOf(false) }
    var actionToDelete by remember { mutableStateOf<ProtoAction?>(null) }

    val editingAction: UIAction? =
        editingActionId?.let { id ->
            appData.actionsList
                .firstOrNull { it.id == id }
                ?.let { action ->
                    when (action.actionType) {
                        ProtoActionType.PATHPLANNER ->
                            UIAction.PathPlanner(action.id, action.name, action.pathName)
                        ProtoActionType.POSE ->
                            UIAction.PoseSelection(
                                action.id,
                                action.name,
                                action.pose.x,
                                action.pose.y,
                                action.pose.rotation,
                            )
                        else -> null
                    }
                }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri?.let { viewModel.exportData(context, it) }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let { viewModel.importData(context, it) }
        }

    var pendingNavEdit by remember(requestedEditActionId) { mutableStateOf(requestedEditActionId) }

    LaunchedEffect(pendingNavEdit, appData.actionsList) {
        val id = pendingNavEdit
        if (id != null && appData.actionsList.any { it.id == id }) {
            editingActionId = id
            draftPose = null
            showSheet = true
            pendingNavEdit = null
        }
    }

    LaunchedEffect(navController.currentBackStackEntry) {
        val savedState = navController.currentBackStackEntry?.savedStateHandle
        val x = savedState?.get<Double>("pose_x")
        val y = savedState?.get<Double>("pose_y")
        val r = savedState?.get<Double>("pose_r")

        if (x != null && y != null && r != null) {
            draftPose = UIAction.PoseSelection(id = editingActionId ?: 0, name = "", x = x, y = y, r = r)
            showSheet = true
            savedState.remove<Double>("pose_x")
            savedState.remove<Double>("pose_y")
            savedState.remove<Double>("pose_r")
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 30.dp, top = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = returnPrimaryColor(),
                            contentColor = returnSecondaryColor(),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate Back",
                    )
                }

                Text(
                    text = "Settings",
                    modifier = Modifier.padding(start = 16.dp),
                    color = returnPrimaryColor(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = 70.dp)
                        .padding(horizontal = 16.dp),
            ) {
                OutlinedTextField(
                    value = localNtPath,
                    onValueChange = { localNtPath = it },
                    label = { Text("NetworkTables Robot Pose Path") },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                ntPathFocused = focusState.isFocused
                                // Commit once the field is clicked off of or the keyboard is dismissed,
                                // so the subscribe request only fires when the path is final.
                                if (!focusState.isFocused && localNtPath != appData.ntPath) {
                                    viewModel.updateNtPath(localNtPath)
                                }
                            },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )

                Spacer(modifier = Modifier.padding(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = localWidth,
                        onValueChange = {
                            localWidth = it
                            it.toDoubleOrNull()?.let { w -> viewModel.updateRobotDimensions(w, appData.robotLength) }
                        },
                        label = { Text("Robot Width (m)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = localLength,
                        onValueChange = {
                            localLength = it
                            it.toDoubleOrNull()?.let { l -> viewModel.updateRobotDimensions(appData.robotWidth, l) }
                        },
                        label = { Text("Robot Length (m)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                Spacer(modifier = Modifier.padding(8.dp))

                ActionEditorSheet(
                    onSaveAction = { uiAction ->
                        val protoAction =
                            ProtoAction
                                .newBuilder()
                                .apply {
                                    name =
                                        when (uiAction) {
                                            is UIAction.PathPlanner -> uiAction.name
                                            is UIAction.PoseSelection -> uiAction.name
                                        }
                                    when (uiAction) {
                                        is UIAction.PathPlanner -> {
                                            actionType = ProtoActionType.PATHPLANNER
                                            pathName = uiAction.pathName
                                        }
                                        is UIAction.PoseSelection -> {
                                            actionType = ProtoActionType.POSE
                                            pose =
                                                ProtoPose2d
                                                    .newBuilder()
                                                    .apply {
                                                        x = uiAction.x
                                                        y = uiAction.y
                                                        rotation = uiAction.r
                                                    }.build()
                                        }
                                    }
                                    id =
                                        if (uiAction.actionId !=
                                            0
                                        ) {
                                            uiAction.actionId
                                        } else {
                                            (appData.actionsList.maxOfOrNull { it.id } ?: 0) + 1
                                        }
                                }.build()
                        viewModel.saveAction(protoAction)
                        draftPose = null
                        editingActionId = null
                    },
                    onEditPose = { x, y, r -> onEditPose(x, y, r, appData.robotWidth, appData.robotLength) },
                    showSheetInitial = showSheet,
                    onSheetVisibilityChange = {
                        showSheet = it
                        editingActionId = null
                        if (!it) {
                            draftPose = null
                        }
                    },
                    currentPose = draftPose,
                    initialAction = editingAction,
                )

                Spacer(modifier = Modifier.padding(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { exportLauncher.launch("roboroute_backup.json") },
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonColors(
                                containerColor = returnPrimaryColor(),
                                contentColor = returnSecondaryColor(),
                                disabledContainerColor = Color.Gray,
                                disabledContentColor = Color.White,
                            ),
                    ) {
                        Text("Export JSON")
                    }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonColors(
                                containerColor = returnPrimaryColor(),
                                contentColor = returnSecondaryColor(),
                                disabledContainerColor = Color.Gray,
                                disabledContentColor = Color.White,
                            ),
                    ) {
                        Text("Import JSON")
                    }
                }

                Spacer(modifier = Modifier.padding(4.dp))

                Button(
                    onClick = { showLogSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonColors(
                            containerColor = returnPrimaryColor(),
                            contentColor = returnSecondaryColor(),
                            disabledContainerColor = Color.Gray,
                            disabledContentColor = Color.White,
                        ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("View Logs")
                }

                Spacer(modifier = Modifier.padding(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Saved Actions", fontWeight = FontWeight.Bold, color = returnPrimaryColor())
                    IconButton(
                        onClick = {
                            editingActionId = null
                            draftPose = null
                            showSheet = true
                        },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = returnPrimaryColor(),
                                contentColor = returnSecondaryColor(),
                            ),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create New Action")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(appData.actionsList) { action ->
                        ListItem(
                            headlineContent = { Text(action.name) },
                            supportingContent = {
                                Text(
                                    when (action.actionType) {
                                        ProtoActionType.PATHPLANNER -> "Path: ${action.pathName}"
                                        ProtoActionType.POSE -> "Pose Selection: (${"%.2f".format(
                                            action.pose.x,
                                        )}, ${"%.2f".format(action.pose.y)}, ${"%.2f".format(action.pose.rotation)} rad)"
                                        else -> "Legacy/Unknown"
                                    },
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { actionToDelete = action }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete ${action.name}",
                                        tint = returnPrimaryColor(),
                                    )
                                }
                            },
                            modifier =
                                Modifier.clickable {
                                    editingActionId = action.id
                                    showSheet = true
                                },
                        )
                    }
                }
            }
        }
    }

    actionToDelete?.let { action ->
        AlertDialog(
            onDismissRequest = { actionToDelete = null },
            title = { Text("Delete Action") },
            text = { Text("Are you sure you want to delete this action? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAction(action.id)
                        if (editingActionId == action.id) {
                            editingActionId = null
                            draftPose = null
                            showSheet = false
                        }
                        actionToDelete = null
                    },
                ) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { actionToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showLogSheet) {
        SerialLogSheet(
            onDismiss = { showLogSheet = false },
            onManualSubscribe = { viewModel.manualSubscribe() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialLogSheet(
    onDismiss: () -> Unit,
    onManualSubscribe: () -> Unit,
) {
    val logs by SerialLogManager.logs.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.8f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Logs", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row {
                    IconButton(onClick = onManualSubscribe, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Manual Subscribe")
                    }
                    Button(onClick = { SerialLogManager.clearLogs() }) {
                        Text("Clear")
                    }
                }
            }
            Spacer(modifier = Modifier.padding(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(logs) { log ->
                    Text(
                        text = log,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionEditorSheet(
    onSaveAction: (UIAction) -> Unit,
    onEditPose: (Double, Double, Double) -> Unit,
    showSheetInitial: Boolean = false,
    onSheetVisibilityChange: (Boolean) -> Unit = {},
    currentPose: UIAction.PoseSelection? = null,
    initialAction: UIAction? = null,
    // See ActionCreationScreen.formKey: identifies the edit session so the typed form
    // survives a trip to the pose selector but resets on a fresh open.
    formKey: Any? = null,
) {
    var showBottomSheet by remember(showSheetInitial) { mutableStateOf(showSheetInitial) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(showSheetInitial) {
        showBottomSheet = showSheetInitial
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
                onSheetVisibilityChange(false)
            },
            sheetState = sheetState,
        ) {
            ActionCreationScreen(
                onDismiss = {
                    showBottomSheet = false
                    onSheetVisibilityChange(false)
                },
                onSaveAction = { action ->
                    onSaveAction(action)
                    showBottomSheet = false
                    onSheetVisibilityChange(false)
                },
                onEditPose = {
                    val currentX = currentPose?.x ?: (initialAction as? UIAction.PoseSelection)?.x ?: 0.0
                    val currentY = currentPose?.y ?: (initialAction as? UIAction.PoseSelection)?.y ?: 0.0
                    val currentR = currentPose?.r ?: (initialAction as? UIAction.PoseSelection)?.r ?: 0.0
                    onEditPose(currentX, currentY, currentR)
                },
                currentPose = currentPose,
                initialAction = initialAction,
                formKey = formKey,
            )
        }
    }
}
