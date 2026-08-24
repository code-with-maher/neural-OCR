package com.a.labs.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.a.labs.ui.LibraryViewModelFactory
import com.a.labs.ui.ReaderViewModelFactory
import com.a.labs.ui.library.LibraryScreen
import com.a.labs.ui.library.LibraryViewModel
import com.a.labs.ui.reader.ReaderScreen
import com.a.labs.ui.reader.ReaderViewModel
import com.a.labs.ui.settings.LogsScreen
import com.a.labs.ui.settings.SettingsScreen

private val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
private const val MotionDurationMs = 350

private fun EnterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing),
        initialOffsetX = { fullWidth -> fullWidth / 3 }
    ) + fadeIn(animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing))

private fun EnterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing),
        initialOffsetX = { fullWidth -> -fullWidth / 3 }
    ) + fadeIn(animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing))

private fun ExitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing),
        targetOffsetX = { fullWidth -> -fullWidth / 3 }
    ) + fadeOut(animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing))

private fun ExitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing),
        targetOffsetX = { fullWidth -> fullWidth / 3 }
    ) + fadeOut(animationSpec = tween(MotionDurationMs, easing = EmphasizedEasing))

@Composable
fun NavGraph(
    navController: NavHostController,
    libraryFactory: LibraryViewModelFactory,
    readerFactory: ReaderViewModelFactory
) {
    NavHost(
        navController = navController,
        startDestination = "library"
    ) {
        composable(
            route = "library",
            enterTransition = { EnterFromLeft() },
            exitTransition = { ExitToLeft() },
            popEnterTransition = { EnterFromLeft() },
            popExitTransition = { ExitToRight() }
        ) {
            val viewModel: LibraryViewModel = viewModel(factory = libraryFactory)
            LibraryScreen(navController, viewModel)
        }

        composable(
            route = "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            enterTransition = { EnterFromRight() },
            exitTransition = { ExitToLeft() },
            popEnterTransition = { EnterFromLeft() },
            popExitTransition = { ExitToRight() }
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
            val viewModel: ReaderViewModel = viewModel(factory = readerFactory)
            ReaderScreen(navController, viewModel, bookId)
        }

        composable(
            route = "settings",
            enterTransition = { EnterFromRight() },
            exitTransition = { ExitToLeft() },
            popEnterTransition = { EnterFromLeft() },
            popExitTransition = { ExitToRight() }
        ) {
            SettingsScreen(navController)
        }

        composable(
            route = "logs",
            enterTransition = { EnterFromRight() },
            exitTransition = { ExitToLeft() },
            popEnterTransition = { EnterFromLeft() },
            popExitTransition = { ExitToRight() }
        ) {
            LogsScreen(navController)
        }
    }
}