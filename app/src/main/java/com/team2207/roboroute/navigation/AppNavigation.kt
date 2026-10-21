package com.team2207.roboroute.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.team2207.roboroute.ui.action.PoseSelectorMainView
import com.team2207.roboroute.ui.home.MainView
import com.team2207.roboroute.ui.settings.SettingsView

const val HOME_SCREEN = "home"
const val SETTINGS_SCREEN = "settings"
const val POSE_SELECTOR_SCREEN = "pose_selector"

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HOME_SCREEN) {
        // Home Screen Route
        composable(HOME_SCREEN) {
            MainView(
                onNavigateToSettings = {
                    navController.navigate(SETTINGS_SCREEN)
                },
                onEditPose = { x, y, r, w, l ->
                    navController.navigate("$POSE_SELECTOR_SCREEN?x=$x&y=$y&r=$r&w=$w&l=$l")
                },
                navController = navController,
            )
        }

        // Settings Screen Route (optionally jumps straight into editing an action)
        composable(
            route = "$SETTINGS_SCREEN?editActionId={editActionId}",
            arguments =
                listOf(
                    navArgument("editActionId") {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                ),
        ) { backStackEntry ->
            val editActionId = backStackEntry.arguments?.getInt("editActionId") ?: -1
            SettingsView(
                onBack = {
                    navController.popBackStack()
                },
                onEditPose = { x, y, r, w, l ->
                    navController.navigate("$POSE_SELECTOR_SCREEN?x=$x&y=$y&r=$r&w=$w&l=$l")
                },
                navController = navController,
                requestedEditActionId = editActionId.takeIf { it >= 0 },
            )
        }

        // Pose Selector Route
        composable(
            route = "$POSE_SELECTOR_SCREEN?x={x}&y={y}&r={r}&w={w}&l={l}",
            arguments =
                listOf(
                    navArgument("x") { defaultValue = "0.0" },
                    navArgument("y") { defaultValue = "0.0" },
                    navArgument("r") { defaultValue = "0.0" },
                    navArgument("w") { defaultValue = "0.6" },
                    navArgument("l") { defaultValue = "0.6" },
                ),
        ) { backStackEntry ->
            val x = backStackEntry.arguments?.getString("x")?.toDoubleOrNull() ?: 0.0
            val y = backStackEntry.arguments?.getString("y")?.toDoubleOrNull() ?: 0.0
            val r = backStackEntry.arguments?.getString("r")?.toDoubleOrNull() ?: 0.0
            val w = backStackEntry.arguments?.getString("w")?.toDoubleOrNull() ?: 0.6
            val l = backStackEntry.arguments?.getString("l")?.toDoubleOrNull() ?: 0.6

            PoseSelectorMainView(
                onBack = {
                    navController.popBackStack()
                },
                onConfirm = { confirmX, confirmY, confirmR ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("pose_x", confirmX)
                    navController.previousBackStackEntry?.savedStateHandle?.set("pose_y", confirmY)
                    navController.previousBackStackEntry?.savedStateHandle?.set("pose_r", confirmR)
                },
                initialX = x,
                initialY = y,
                initialR = r,
                robotWidthMeter = w,
                robotLengthMeter = l,
            )
        }
    }
}
