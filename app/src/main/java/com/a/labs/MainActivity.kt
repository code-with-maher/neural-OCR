package com.a.labs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// 1. تعريف مسارات التطبيق (Routes) بشكل آمن لمنع الأخطاء الإملائية
sealed class Screen(val route: String) {
    object Library : Screen("library_screen")
    object Settings : Screen("settings_screen")
    // مسار القراءة يأخذ "معرف الكتاب" كمتغير لكي يعرف أي كتاب يفتح
    object Reader : Screen("reader_screen/{bookId}") {
        fun createRoute(bookId: String) = "reader_screen/$bookId"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تفعيل واجهة الحافة للحافة (Edge-to-Edge) ليكون التطبيق عصرياً ويغطي الشاشة بالكامل
        enableEdgeToEdge() 
        
        setContent {
            // استخدام ثيم الماتيريال 3 (Material 3) كأساس
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // استدعاء نظام الملاحة والتنقل
                    MainAppNavigation()
                }
            }
        }
    }
}

// 2. محرك التنقل المركزي (The Navigator)
@Composable
fun MainAppNavigation() {
    // المتحكم الرئيسي في التنقل (يحفظ الـ Backstack تلقائياً)
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Library.route // شاشة البداية هي المكتبة كما طلبت
    ) {
        // شاشة المكتبة
        composable(Screen.Library.route) {
            // سنقوم بإنشاء هذا الملف لاحقاً
            // LibraryScreen(navController = navController)
        }

        // شاشة الإعدادات
        composable(Screen.Settings.route) {
            // سنقوم بإنشاء هذا الملف لاحقاً
            // SettingsScreen(navController = navController)
        }

        // شاشة القراءة (تستقبل معرف الكتاب الذي تم الضغط عليه)
        composable(Screen.Reader.route) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
            // سنقوم بإنشاء هذا الملف لاحقاً
            // ReaderScreen(navController = navController, bookId = bookId)
        }
    }
}
