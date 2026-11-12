package com.team2207.roboroute.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.team2207.roboroute.R
import com.team2207.roboroute.aoa.NotificationManager
import com.team2207.roboroute.datastore.ActionType
import com.team2207.roboroute.datastore.CustomButton
import com.team2207.roboroute.ui.action.FIELD_HEIGHT_METERS
import com.team2207.roboroute.ui.action.FIELD_WIDTH_METERS
import com.team2207.roboroute.ui.action.RobotVisual
import com.team2207.roboroute.ui.components.FullScreenImage
import com.team2207.roboroute.ui.settings.ActionEditorSheet
import com.team2207.roboroute.ui.theme.returnPrimaryColor
import com.team2207.roboroute.ui.theme.returnSecondaryColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.team2207.roboroute.datastore.Pose2d as ProtoPose2d
import com.team2207.roboroute.ui.action.Action as UIAction

@Composable
fun MainView(
    onNavigateToSettings: () -> Unit,
    onEditPose: (Double, Double, Double, Double, Double) -> Unit,
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val activeNotification by NotificationManager.current.collectAsState()
    var activeDrawingPoints by remember { mutableStateOf(listOf<Offset>()) }
    val scope = rememberCoroutineScope()
    val routeAlpha = remember { Animatable(1f) }
    var fadeJob by remember { mutableStateOf<Job?>(null) }
    val isEditing by viewModel.isEditing.collectAsState()
    val layout by viewModel.layout.collectAsState()
    val appData by viewModel.appData.collectAsState()
    val livePose by viewModel.livePose.collectAsState()
    val isPoseValid by viewModel.isPoseValid.collectAsState()
    val isRedAlliance by viewModel.isRedAlliance.collectAsState()
    val isAoaConnected by viewModel.isAoaConnected.collectAsState()

    // Absorb back on the root screen so an accidental left/right edge swipe can't close
    // the app. The bottom/home gesture still works. Dialogs and sheets register their own
    // back handlers, which take priority while visible, so they still dismiss normally.
    BackHandler {}

    var draftPose by remember { mutableStateOf<UIAction.PoseSelection?>(null) }
    // rememberSaveable so the edited action survives navigating to the pose selector and back.
    var editingActionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }
    // rememberSaveable so the pending button survives navigating to the pose selector and back.
    var createForButtonId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    // Bumped on every fresh open so a new edit/create session starts with a blank form
    // while the same session still restores typed text after a trip to the pose selector.
    var editSession by rememberSaveable { mutableStateOf(0) }
    var createSession by rememberSaveable { mutableStateOf(0) }

    val editingAction: UIAction? =
        editingActionId?.let { id ->
            appData.actionsList
                .firstOrNull { it.id == id }
                ?.let { action ->
                    when (action.actionType) {
                        ActionType.PATHPLANNER ->
                            UIAction.PathPlanner(action.id, action.name, action.pathName)
                        ActionType.POSE ->
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

    LaunchedEffect(navController.currentBackStackEntry) {
        val savedState = navController.currentBackStackEntry?.savedStateHandle
        val x = savedState?.get<Double>("pose_x")
        val y = savedState?.get<Double>("pose_y")
        val r = savedState?.get<Double>("pose_r")

        if (x != null && y != null && r != null) {
            draftPose = UIAction.PoseSelection(id = editingActionId ?: 0, name = "", x = x, y = y, r = r)
            if (createForButtonId != null) {
                showCreateSheet = true
            } else {
                showEditSheet = true
            }
            savedState.remove<Double>("pose_x")
            savedState.remove<Double>("pose_y")
            savedState.remove<Double>("pose_r")
        }
    }

    val toProtoAction: (UIAction) -> com.team2207.roboroute.datastore.Action =
        { uiAction ->
            com.team2207.roboroute.datastore.Action
                .newBuilder()
                .apply {
                    name =
                        when (uiAction) {
                            is UIAction.PathPlanner -> uiAction.name
                            is UIAction.PoseSelection -> uiAction.name
                        }
                    when (uiAction) {
                        is UIAction.PathPlanner -> {
                            actionType = ActionType.PATHPLANNER
                            pathName = uiAction.pathName
                        }
                        is UIAction.PoseSelection -> {
                            actionType = ActionType.POSE
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
                        if (uiAction.actionId != 0) {
                            uiAction.actionId
                        } else {
                            (appData.actionsList.maxOfOrNull { it.id } ?: 0) + 1
                        }
                }.build()
        }

    Scaffold(
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            val maxWidthPx = constraints.maxWidth.toFloat()
            val maxHeightPx = constraints.maxHeight.toFloat()

            val painter = painterResource(id = R.drawable.field_2026)
            val imgSize = painter.intrinsicSize
            val scale = min(maxWidthPx / imgSize.width, maxHeightPx / imgSize.height)
            val density = LocalDensity.current.density
            val fitWidthDp = (imgSize.width * scale / density).dp
            val fitHeightDp = (imgSize.height * scale / density).dp
            val fitWidthPx = imgSize.width * scale
            val fitHeightPx = imgSize.height * scale

            val isFlipped = isRedAlliance
            val flipAngle = if (isFlipped) 180f else 0f

            // Separate px-per-meter per axis so coordinates map onto the drawn image exactly.
            // Portrait image: field X (long, 16.54175 m) runs vertically, field Y (short, 8.0137 m) horizontally.
            val pxPerMeterX = (fitHeightPx / FIELD_WIDTH_METERS).toFloat()
            val pxPerMeterY = (fitWidthPx / FIELD_HEIGHT_METERS).toFloat()

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = flipAngle },
            ) {
                FullScreenImage(
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                modifier =
                    Modifier
                        .size(fitWidthDp, fitHeightDp)
                        .align(Alignment.Center)
                        .pointerInput(isEditing) {
                            // Drawing is only active if we aren't in Edit Mode
                            if (!isEditing) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        fadeJob?.cancel()
                                        scope.launch { routeAlpha.snapTo(1f) }
                                        activeDrawingPoints = listOf(offset)
                                    },
                                    onDragEnd = {
                                        val route =
                                            processRawRoute(
                                                activeDrawingPoints,
                                                pxPerMeterX,
                                                pxPerMeterY,
                                                fitWidthPx,
                                                fitHeightPx,
                                                isFlipped = isRedAlliance,
                                            )
                                        viewModel.executeRoute(route)

                                        fadeJob =
                                            scope.launch {
                                                delay(10000)
                                                routeAlpha.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(durationMillis = 500),
                                                )
                                                activeDrawingPoints = emptyList() // Clear line after send and animate
                                                routeAlpha.snapTo(1f) // Reset alpha for next drawing
                                            }
                                    },
                                    onDragCancel = { activeDrawingPoints = emptyList() },
                                ) { change, _ ->
                                    change.consume()
                                    activeDrawingPoints = activeDrawingPoints + change.position
                                }
                            }
                        },
            ) {
                val primaryColor = returnPrimaryColor()
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = routeAlpha.value },
                ) {
                    if (activeDrawingPoints.size > 1) {
                        val route =
                            Path().apply {
                                moveTo(activeDrawingPoints[0].x, activeDrawingPoints[0].y)
                                activeDrawingPoints.forEach { lineTo(it.x, it.y) }
                            }
                        drawPath(
                            path = route,
                            color = primaryColor,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
                if (isPoseValid) {
                    val xMeter = if (isFlipped) FIELD_WIDTH_METERS - livePose.x else livePose.x
                    val yMeter = if (isFlipped) FIELD_HEIGHT_METERS - livePose.y else livePose.y

                    // Unflipped, the blue-wall origin (0,0) sits at the bottom-right of the
                    // portrait image, +X toward the red wall (up), +Y toward the left border.
                    // X (long) -> Vertical offset, Y (short) -> Horizontal offset.
                    val xOffsetDp = -(xMeter * pxPerMeterX / density).dp
                    val yOffsetDp = -(yMeter * pxPerMeterY / density).dp

                    val robotWidthDp = (appData.robotWidth * pxPerMeterY / density).dp
                    val robotLengthDp = (appData.robotLength * pxPerMeterX / density).dp

                    RobotVisual(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(yOffsetDp + (robotWidthDp / 2), xOffsetDp + (robotLengthDp / 2))
                                .graphicsLayer {
                                    rotationZ =
                                        Math
                                            .toDegrees(
                                                livePose.rotation + (if (isFlipped) Math.PI else 0.0),
                                            ).toFloat()
                                },
                        showControls = false,
                        robotWidth = robotWidthDp,
                        robotLength = robotLengthDp,
                    )
                }

                layout.buttonsList.forEach { button ->
                    androidx.compose.runtime.key(button.id) {
                        CircularButton(
                            button = button,
                            isEditing = isEditing,
                            onUpdate = { x, y, r, actionId ->
                                viewModel.updateButton(button.id, x, y, r, actionId)
                            },
                            onDelete = { viewModel.deleteButton(button.id) },
                            onCreateNewAction = { buttonId ->
                                createForButtonId = buttonId
                                createSession += 1
                                showCreateSheet = true
                            },
                            onEditAction = { actionId ->
                                editingActionId = actionId
                                editSession += 1
                                showEditSheet = true
                            },
                            actions = appData.actionsList,
                            maxWidth = maxWidthPx,
                            maxHeight = maxHeightPx,
                            onExecute = { action -> viewModel.executeAction(action) },
                            flipped = isFlipped,
                        )
                    }
                }
            }

            if (isEditing) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                ) {
                    IconButton(
                        onClick = { viewModel.setEditing(false) },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = returnPrimaryColor(),
                                contentColor = returnSecondaryColor(),
                            ),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Done")
                    }
                    IconButton(
                        onClick = { viewModel.addButton() },
                        modifier = Modifier.padding(top = 8.dp),
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = returnPrimaryColor(),
                                contentColor = returnSecondaryColor(),
                            ),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Button")
                    }
                }
            } else {
                ConnectionIndicator(
                    isConnected = isAoaConnected,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                )
                MenuButton(
                    onSettingsClick = onNavigateToSettings,
                    onEditLayoutClick = { viewModel.setEditing(true) },
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 30.dp),
                )
            }

            ActionEditorSheet(
                onSaveAction = { uiAction ->
                    viewModel.saveAction(toProtoAction(uiAction))
                    draftPose = null
                    editingActionId = null
                },
                onEditPose = { x, y, r -> onEditPose(x, y, r, appData.robotWidth, appData.robotLength) },
                showSheetInitial = showEditSheet,
                onSheetVisibilityChange = { visible ->
                    showEditSheet = visible
                    editingActionId = null
                    if (!visible) {
                        draftPose = null
                    }
                },
                currentPose = draftPose,
                initialAction = editingAction,
                formKey = editSession,
            )

            ActionEditorSheet(
                onSaveAction = { uiAction ->
                    val protoAction = toProtoAction(uiAction)
                    viewModel.saveAction(protoAction)
                    createForButtonId?.let { buttonId ->
                        layout.buttonsList.firstOrNull { it.id == buttonId }?.let { button ->
                            viewModel.updateButton(buttonId, button.x, button.y, button.radius, protoAction.id)
                        }
                    }
                    createForButtonId = null
                    draftPose = null
                },
                onEditPose = { x, y, r -> onEditPose(x, y, r, appData.robotWidth, appData.robotLength) },
                showSheetInitial = showCreateSheet,
                onSheetVisibilityChange = { visible ->
                    showCreateSheet = visible
                    if (!visible) {
                        createForButtonId = null
                        draftPose = null
                    }
                },
                currentPose = draftPose,
                initialAction = null,
                formKey = createSession,
            )

            activeNotification?.let { activeNotif ->
                NotificationView(
                    title = activeNotif.title,
                    description = activeNotif.description,
                    level = activeNotif.level,
                    durationMs = activeNotif.durationMs,
                    onDismiss = { NotificationManager.dismiss() },
                )
            }
        }
    }
}

@Composable
fun ConnectionIndicator(
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Color(0xFF4CAF50) else Color.Red),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = (if (isConnected) "Connected" else "Disconnected"),
            color = returnSecondaryColor(),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun CircularButton(
    button: CustomButton,
    isEditing: Boolean,
    onUpdate: (Float, Float, Float, Int) -> Unit,
    onDelete: () -> Unit,
    onCreateNewAction: (Int) -> Unit,
    onEditAction: (Int) -> Unit,
    actions: List<com.team2207.roboroute.datastore.Action>,
    maxWidth: Float,
    maxHeight: Float,
    onExecute: (com.team2207.roboroute.datastore.Action) -> Unit,
    flipped: Boolean,
) {
    var showActionDialog by remember { mutableStateOf(false) }
    val assignedAction = actions.find { it.id == button.actionId }
    val currentActionId by rememberUpdatedState(button.actionId)
    val density = LocalDensity.current

    // Stored coords are always in the "unflipped" reference frame. When the field is
    // flipped the button is mirrored onto the screen and mirrored back before saving.
    val toDisplay = { v: Float -> if (flipped) 1f - v else v }
    val toRaw = { v: Float -> if (flipped) 1f - v else v }

    var localX by remember { mutableStateOf(toDisplay(button.x)) }
    var localY by remember { mutableStateOf(toDisplay(button.y)) }
    var localRadius by remember { mutableStateOf(button.radius) }
    var isInteracting by remember { mutableStateOf(false) }

    // Not keyed on isInteracting: on release the last commit flowing back already matches
    // the dragged state, so re-syncing then would snap the button back to a stale value
    // (the "bounce"). External changes are still picked up whenever not interacting.
    LaunchedEffect(button.x, button.y, button.radius) {
        if (!isInteracting) {
            localX = toDisplay(button.x)
            localY = toDisplay(button.y)
            localRadius = button.radius
        }
    }

    val primaryColor = returnPrimaryColor()
    val secondaryColor = returnSecondaryColor()

    Box(
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        (localX * maxWidth - localRadius).roundToInt(),
                        (localY * maxHeight - localRadius).roundToInt(),
                    )
                }.size(with(density) { (localRadius * 2).toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        if (isEditing) {
                            primaryColor.copy(alpha = 0.5f)
                        } else {
                            primaryColor
                        },
                    ).border(2.dp, secondaryColor, CircleShape)
                    .pointerInput(button.id, isEditing) {
                        if (isEditing) {
                            detectDragGestures(
                                onDragStart = { isInteracting = true },
                                onDragEnd = { isInteracting = false },
                                onDragCancel = { isInteracting = false },
                            ) { change, dragAmount ->
                                change.consume()
                                localX = (localX + dragAmount.x / maxWidth).coerceIn(0f, 1f)
                                localY = (localY + dragAmount.y / maxHeight).coerceIn(0f, 1f)
                                onUpdate(toRaw(localX), toRaw(localY), localRadius, currentActionId)
                            }
                        }
                    }.clickable {
                        if (isEditing) {
                            showActionDialog = true
                        } else {
                            println("Executing action: ${assignedAction?.name}")
                            assignedAction?.let { onExecute(it) }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = assignedAction?.name ?: if (isEditing) "+" else "",
                color = Color.White,
                fontSize = with(density) { (localRadius / 3).toSp() },
                fontWeight = FontWeight.Bold,
            )
        }

        if (isEditing) {
            // Resize handles centered on the bounding square's corners. Sized relative to the
            // button radius so they clear the circle's outline instead of sitting on its line.
            val handleSize =
                with(density) {
                    (max(14f, min(localRadius * 0.45f, 26f))).toDp()
                }
            val handleOffset = handleSize / 2f
            listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).forEach { alignment ->
                Box(
                    modifier =
                        Modifier
                            .align(alignment)
                            .offset(
                                x =
                                    if (alignment == Alignment.TopStart || alignment == Alignment.BottomStart) {
                                        -handleOffset
                                    } else {
                                        handleOffset
                                    },
                                y =
                                    if (alignment == Alignment.TopStart || alignment == Alignment.TopEnd) {
                                        -handleOffset
                                    } else {
                                        handleOffset
                                    },
                            ).size(handleSize)
                            .clip(CircleShape)
                            .background(secondaryColor)
                            .border(1.dp, primaryColor, CircleShape)
                            .pointerInput(button.id, alignment) {
                                detectDragGestures(
                                    onDragStart = { isInteracting = true },
                                    onDragEnd = { isInteracting = false },
                                    onDragCancel = { isInteracting = false },
                                ) { change, dragAmount ->
                                    change.consume()
                                    // Corner-aware resizing. Track the dominant drag axis at
                                    // full speed so the handle stays locked to the square's
                                    // corner instead of sliding along the circle's diagonal.
                                    val factorX = if (alignment == Alignment.TopStart || alignment == Alignment.BottomStart) -1 else 1
                                    val factorY = if (alignment == Alignment.TopStart || alignment == Alignment.TopEnd) -1 else 1
                                    val deltaRadius =
                                        if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                            dragAmount.x * factorX
                                        } else {
                                            dragAmount.y * factorY
                                        }
                                    localRadius = (localRadius + deltaRadius).coerceIn(40f, 600f)
                                    onUpdate(toRaw(localX), toRaw(localY), localRadius, currentActionId)
                                }
                            },
                )
            }
        }
    }

    if (showActionDialog && isEditing) {
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text("Configure Button") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Select Action:", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.size(8.dp))

                    if (actions.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            Text("No actions available. Create one first!", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(actions) { action ->
                                val actionLabel =
                                    when {
                                        action.name.isNotBlank() -> action.name
                                        action.actionType == ActionType.POSE ->
                                            "Pose Selection: (${"%.2f".format(action.pose.x)}, " +
                                                "${"%.2f".format(action.pose.y)}, ${"%.2f".format(action.pose.rotation)} rad)"
                                        else -> "Unnamed"
                                    }
                                ListItem(
                                    headlineContent = { Text(actionLabel) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    trailingContent = {
                                        IconButton(
                                            onClick = {
                                                showActionDialog = false
                                                onEditAction(action.id)
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit ${action.name}",
                                                tint = returnPrimaryColor(),
                                            )
                                        }
                                    },
                                    modifier =
                                        Modifier.clickable {
                                            onUpdate(button.x, button.y, button.radius, action.id)
                                            showActionDialog = false
                                        },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.size(16.dp))
                    Button(
                        onClick = {
                            showActionDialog = false
                            onCreateNewAction(button.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create New Action")
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onDelete()
                            showActionDialog = false
                        },
                    ) {
                        Text("Delete Button", color = Color.Red)
                    }
                    TextButton(onClick = { showActionDialog = false }) {
                        Text("Close")
                    }
                }
            },
        )
    }
}

@Composable
fun MenuButton(
    onSettingsClick: () -> Unit,
    onEditLayoutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = returnPrimaryColor(),
                    contentColor = returnSecondaryColor(),
                ),
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TextButton(
                onClick = {
                    expanded = false
                    onEditLayoutClick()
                },
            ) {
                Text("Edit layout", color = returnPrimaryColor())
            }
            TextButton(
                onClick = {
                    expanded = false
                    onSettingsClick()
                },
            ) {
                Text("Settings", color = returnPrimaryColor())
            }
        }
    }
}

fun processRawRoute(
    rawPoints: List<Offset>,
    pxPerMeterX: Float,
    pxPerMeterY: Float,
    fitWidthPx: Float,
    fitHeightPx: Float,
    isFlipped: Boolean,
): List<ProtoPose2d> {
    if (rawPoints.size < 2) return emptyList()

    val waypoints = mutableListOf<ProtoPose2d>()
    val spacingMeters = 0.5
    var distanceAccumulator = 0.0

    // Helper: Convert Pixels to Meters with alliance flipping support
    fun toMeters(offset: Offset): Offset {
        val unflippedX = (fitHeightPx - offset.y) / pxPerMeterX
        val unflippedY = (fitWidthPx - offset.x) / pxPerMeterY

        return if (isFlipped) {
            Offset(
                x = (FIELD_WIDTH_METERS - unflippedX).toFloat(),
                y = (FIELD_HEIGHT_METERS - unflippedY).toFloat(),
            )
        } else {
            Offset(x = unflippedX, y = unflippedY)
        }
    }

    // Add the very first point
    val start = toMeters(rawPoints[0])
    waypoints.add(
        ProtoPose2d
            .newBuilder()
            .setX(start.x.toDouble())
            .setY(start.y.toDouble())
            .setRotation(0.0)
            .build(),
    )

    // Walk through every touch segment
    for (i in 0 until rawPoints.size - 1) {
        val p1 = toMeters(rawPoints[i])
        val p2 = toMeters(rawPoints[i + 1])

        val segmentVector = p2 - p1
        val segmentLength = segmentVector.getDistance()
        if (segmentLength == 0f) continue

        val angle = kotlin.math.atan2(segmentVector.y.toDouble(), segmentVector.x.toDouble())
        var remaining = segmentLength

        // If this segment is long enough to include one or more 0.5m waypoints
        while (distanceAccumulator + remaining >= spacingMeters) {
            val needed = (spacingMeters - distanceAccumulator).toFloat()
            val ratio = needed / remaining

            // Calculate exact meter position
            val pointMeters =
                Offset(
                    p1.x + (p2.x - p1.x) * (1 - remaining / segmentLength + ratio),
                    p1.y + (p2.y - p1.y) * (1 - remaining / segmentLength + ratio),
                )

            waypoints.add(
                ProtoPose2d
                    .newBuilder()
                    .setX(pointMeters.x.toDouble())
                    .setY(pointMeters.y.toDouble())
                    .setRotation(angle)
                    .build(),
            )

            distanceAccumulator = 0.0
            remaining -= needed
        }
        distanceAccumulator += remaining
    }
    return waypoints
}
