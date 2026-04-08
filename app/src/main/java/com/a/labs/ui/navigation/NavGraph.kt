package com.a.labs.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.a.labs.ui.ViewModelFactory
import com.a.labs.ui.library.LibraryScreen
import com.a.labs.ui.library.LibraryViewModel
import com.a.labs.ui.reader.ReaderScreen
import com.a.labs.ui.reader.ReaderViewModel
import com.a.labs.ui.settings.LogsScreen
import com.a.labs.ui.settings.SettingsScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    factory: ViewModelFactory,
    readerFactory: ViewModelFactory
) {
    NavHost(
        navController = navController,
        startDestination = "library"
    ) {
        composable("library") {
            val viewModel: LibraryViewModel = viewModel(factory = factory)
            LibraryScreen(navController, viewModel)
        }

        composable("settings") {
            SettingsScreen(navController)
        }

        composable("logs") {
            LogsScreen(navController)
        }

        composable(
            route = "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
            val viewModel: ReaderViewModel = viewModel(factory = readerFactory)
            ReaderScreen(navController, viewModel, bookId)
        }
    }
}