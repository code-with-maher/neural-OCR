package com.a.labs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// تعريف مسارات التطبيق
sealed class Screen(val route: String) {
    object Library : Screen("library_screen")
    object Settings : Screen("settings_screen")
    object Reader : Screen("reader_screen/{bookId}") {
        fun createRoute(bookId: String) = "reader_screen/$bookId"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppNavigation()
                }
            }
        }
    }
}

@Composable
fun MainAppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Library.route
    ) {
        // شاشة المكتبة تحتوي زر ينقلك لشاشة الإعدادات الجاهزة بالكومبوز
        composable(Screen.Library.route) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("اضغط الزر للذهاب لشاشة الإعدادات")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { navController.navigate(Screen.Settings.route) }) {
                    Text("اذهب لشاشة الإعدادات")
                }
            }
        }

        // شاشة الإعدادات الموجودة فعلاً (الكومبوز الجاهزة)
        composable(Screen.Settings.route) {
            SettingsScreen(navController)
        }

        // شاشة القراءة تبقى معلقة
        composable(Screen.Reader.route) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
            // ReaderScreen(navController = navController, bookId = bookId)
        }
    }
}