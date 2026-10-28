package com.team2207.roboroute.ui.action

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.team2207.roboroute.R
import com.team2207.roboroute.aoa.RobotPoseManager
import com.team2207.roboroute.ui.components.FullScreenImage
import com.team2207.roboroute.ui.theme.BlueAlliance
import com.team2207.roboroute.ui.theme.Orange
import com.team2207.roboroute.ui.theme.RedAlliance
import com.team2207.roboroute.ui.theme.returnPrimaryColor
import com.team2207.roboroute.ui.theme.returnSecondaryColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// FRC field dimensions (meters). The field image is portrait: the long axis (X) runs
// vertically and the short axis (Y) horizontally. FRC 2023-2026 "blue wall" convention:
// origin at the rightmost corner of the blue alliance wall (bottom-right of the portrait
// image), +X toward the red wall, +Y toward the left side border.
const val FIELD_WIDTH_METERS = 16.54175
const val FIELD_HEIGHT_METERS = 8.0137

@Composable
fun PoseSelectorMainView(
    onBack: () -> Unit,
    onConfirm: (Double, Double, Double) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    initialX: Double = 0.0,
    initialY: Double = 0.0,
    initialR: Double = 0.0, // RADIANS
    robotWidthMeter: Double = 0.6,
    robotLengthMeter: Double = 0.6,
) {
    var robotOffset by remember { mutableStateOf(Offset.Zero) }
    var robotRotation by remember { mutableStateOf(initialR.toFloat()) }
    var isInitialized by remember { mutableStateOf(false) }
    // Guards against a fast double-tap popping the back stack more than once.
    var isNavigating by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight

            val painter = painterResource(id = R.drawable.field_2026)
            val imgWidth = painter.intrinsicSize.width
            val imgHeight = painter.intrinsicSize.height

            val scale = min(containerWidth.value / imgWidth, containerHeight.value / imgHeight)
            val fitWidthDp = (imgWidth * scale).dp
            val fitHeightDp = (imgHeight * scale).dp

            val density = androidx.compose.ui.platform.LocalDensity.current
            val fitWidthPx = imgWidth * scale * density.density
            val fitHeightPx = imgHeight * scale * density.density

            // Pixels per meter, separate per axis so each maps onto the drawn image exactly.
            val pxPerMeterX = fitHeightPx / FIELD_WIDTH_METERS // X (long) -> vertical pixels
            val pxPerMeterY = fitWidthPx / FIELD_HEIGHT_METERS // Y (short) -> horizontal pixels

            if (!isInitialized) {
                // Pose coordinate maps to the robot's CENTRE. robotOffset is the offset of the
                // handle box centre from the field box centre; the field origin (bottom-right of
                // the portrait image) maps via the standard FRC "blue wall" convention.
                val xPx = -(initialX / FIELD_WIDTH_METERS * fitHeightPx)
                val yPx = -(initialY / FIELD_HEIGHT_METERS * fitWidthPx)
                robotOffset = Offset((fitWidthPx / 2f + yPx).toFloat(), (fitHeightPx / 2f + xPx).toFloat())
                isInitialized = true
            }

            FullScreenImage(modifier = Modifier.fillMaxSize())

            IconButton(
                onClick = {
                    if (!isNavigating) {
                        isNavigating = true
                        onBack()
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = returnPrimaryColor(),
                        contentColor = returnSecondaryColor(),
                    ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            IconButton(
                onClick = {
                    if (!isNavigating) {
                        isNavigating = true
                        val confirmX = ((fitHeightPx / 2f - robotOffset.y) / fitHeightPx) * FIELD_WIDTH_METERS
                        val confirmY = ((fitWidthPx / 2f - robotOffset.x) / fitWidthPx) * FIELD_HEIGHT_METERS
                        onConfirm(confirmX, confirmY, robotRotation.toDouble())
                        onBack()
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = returnPrimaryColor(),
                        contentColor = returnSecondaryColor(),
                    ),
            ) {
                Icon(Icons.Default.Check, contentDescription = "Confirm")
            }

            // Field Container
            Box(
                modifier =
                    Modifier
                        .size(fitWidthDp, fitHeightDp)
                        .align(Alignment.Center),
            ) {
                val robotWidthDp = (robotWidthMeter * pxPerMeterY / density.density).dp
                val robotLengthDp = (robotLengthMeter * pxPerMeterX / density.density).dp

                RobotPoseEdit(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .offset {
                                IntOffset(
                                    robotOffset.x.roundToInt(),
                                    robotOffset.y.roundToInt(),
                                )
                            },
                    rotation = -robotRotation,
                    onMove = { delta ->
                        robotOffset += delta
                    },
                    onRotate = { newRotation ->
                        // Editor angles are screen-clockwise; field heading is CCW-positive.
                        robotRotation = -newRotation
                    },
                    robotWidth = robotWidthDp,
                    robotLength = robotLengthDp,
                )
            }

            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
            ) {
                val frcX = ((fitHeightPx / 2f - robotOffset.y) / fitHeightPx) * FIELD_WIDTH_METERS
                val frcY = ((fitWidthPx / 2f - robotOffset.x) / fitWidthPx) * FIELD_HEIGHT_METERS

                Text(text = "X: ${"%.2f".format(frcX)}m", color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = "Y: ${"%.2f".format(frcY)}m", color = Color.Green, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = "R: ${"%.2f".format(robotRotation)} rad", color = Color.Blue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RobotVisual(
    modifier: Modifier = Modifier,
    showControls: Boolean = false,
    robotWidth: Dp = 60.dp,
    robotLength: Dp = 60.dp,
) {
    val extensionLength = 40.dp
    val dotSize = 16.dp
    val strokeWidth = 2.dp
    val isRedAlliance by RobotPoseManager.isRedAlliance.collectAsState()
    val primaryColor = if (isRedAlliance) RedAlliance else BlueAlliance

    val drawingHeight = if (showControls) (dotSize / 2) + extensionLength + robotLength else robotLength

    Canvas(modifier = modifier.size(width = robotWidth, height = drawingHeight)) {
        val centerX = size.width / 2f
        val robotWidthPx = robotWidth.toPx()
        val robotLengthPx = robotLength.toPx()
        val strokeWidthPx = strokeWidth.toPx()
        val extensionPx = extensionLength.toPx()
        val dotRadiusPx = (dotSize / 2f).toPx()

        val squareTopY = if (showControls) dotRadiusPx + extensionPx else 0f
        val centerY = squareTopY + (robotLengthPx / 2f)

        // Rounded body with a dark fill and a thick bumper border. In the pose selector
        // the outline switches to orange and the heading arrow is hidden.
        val borderColor = if (showControls) Orange else primaryColor
        drawRoundRect(
            color = Color(0xFF222222),
            topLeft = Offset(centerX - robotWidthPx / 2f, squareTopY),
            size = Size(robotWidthPx, robotLengthPx),
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(centerX - robotWidthPx / 2f, squareTopY),
            size = Size(robotWidthPx, robotLengthPx),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = strokeWidthPx * 1.5f),
        )

        if (!showControls) {
            // AdvantagesScope heading arrow: a white center shaft running from halfway
            // behind the centre to halfway in front, with an open (unfilled) V head.
            val arrowBackY = centerY + robotLengthPx * 0.3f
            val arrowTipY = centerY - robotLengthPx * 0.3f
            val armHalf = robotLengthPx * 0.15f
            val armBaseY = arrowTipY + armHalf
            val arrowRoute =
                Path().apply {
                    moveTo(centerX, arrowBackY)
                    lineTo(centerX, arrowTipY)
                    lineTo(centerX - armHalf, armBaseY)
                    moveTo(centerX, arrowTipY)
                    lineTo(centerX + armHalf, armBaseY)
                }
            drawPath(
                path = arrowRoute,
                color = Color.White,
                style =
                    Stroke(
                        width = strokeWidthPx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
            )
        }

        if (showControls) {
            drawLine(
                color = Orange,
                start = Offset(centerX, centerY),
                end = Offset(centerX, dotRadiusPx),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun RobotPoseEdit(
    rotation: Float,
    modifier: Modifier = Modifier,
    onMove: (Offset) -> Unit,
    onRotate: (Float) -> Unit,
    robotWidth: Dp = 60.dp,
    robotLength: Dp = 60.dp,
) {
    val extensionLength = 40.dp
    val dotSize = 16.dp

    // Rotation pivot is the handle box centre, which is also the robot's centre (same anchor
    // as the main view). The rotate handle orbits at the END of the heading line (coincident
    // with the line-end dot, like PathPlanner) so it stays locked to the arrow while turning.
    val graphicHeight = robotLength + extensionLength + dotSize / 2
    val radiusDp = extensionLength + (robotLength / 2)
    val handleBoxSize = radiusDp * 2 + dotSize * 2

    val density = androidx.compose.ui.platform.LocalDensity.current
    val radiusPx = with(density) { radiusDp.toPx() }
    val dotSizePx = with(density) { dotSize.toPx() }
    val handleBoxSizePx = with(density) { handleBoxSize.toPx() }

    var localRotation by remember { mutableStateOf(rotation) }
    var isRotating by remember { mutableStateOf(false) }
    var isMoving by remember { mutableStateOf(false) }

    LaunchedEffect(rotation) {
        if (!isRotating) {
            localRotation = rotation
        }
    }

    Box(
        modifier =
            modifier
                .size(handleBoxSize)
                .pointerInput(Unit) {
                    val centerX = handleBoxSizePx / 2f
                    val centerY = centerX

                    detectDragGestures(
                        onDragStart = { offset ->
                            val dist = (offset - Offset(centerX, centerY)).getDistance()
                            if (Math.abs(dist - radiusPx) < dotSizePx * 2f) {
                                isRotating = true
                            } else if (dist <= max(robotWidth.toPx(), robotLength.toPx()) / 2f) {
                                isMoving = true
                            }
                        },
                        onDragEnd = {
                            isRotating = false
                            isMoving = false
                        },
                        onDragCancel = {
                            isRotating = false
                            isMoving = false
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        if (isMoving) {
                            onMove(dragAmount)
                        } else if (isRotating) {
                            val touchX = change.position.x - centerX
                            val touchY = change.position.y - centerY
                            val angle = atan2(touchY.toDouble(), touchX.toDouble()).toFloat()
                            localRotation = angle + (Math.PI.toFloat() / 2f)
                            onRotate(localRotation)
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = robotWidth, height = graphicHeight)
                    .graphicsLayer {
                        rotationZ = Math.toDegrees(localRotation.toDouble()).toFloat()
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }.align(Alignment.Center),
        ) {
            RobotVisual(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .offset(y = -(extensionLength / 2 + dotSize / 4)),
                showControls = true,
                robotWidth = robotWidth,
                robotLength = robotLength,
            )
        }

        Box(
            modifier =
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(Orange),
        )

        val angleRadForHandle = localRotation.toDouble() - (Math.PI / 2.0)
        val dotOffsetX = (radiusDp.value * cos(angleRadForHandle)).dp
        val dotOffsetY = (radiusDp.value * sin(angleRadForHandle)).dp

        Box(
            modifier =
                Modifier
                    .offset(x = dotOffsetX, y = dotOffsetY)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(Orange),
        )
    }
}
