package com.example.lastmeeting

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.lastmeeting.ui.details.MeetingDetailsScreen
import com.example.lastmeeting.ui.details.MeetingDetailsViewModel
import com.example.lastmeeting.ui.main.MainScreen
import com.example.lastmeeting.ui.main.MainViewModel
import com.example.lastmeeting.ui.settings.SettingsScreen
import com.example.lastmeeting.ui.settings.SettingsViewModel
import com.example.lastmeeting.ui.theme.LastMeetingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LastMeetingTheme {
                LastMeetingNavApp()
            }
        }
    }
}

class MeetingDetailsViewModelFactory(
    private val application: Application,
    private val meetingId: Long
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MeetingDetailsViewModel(application, meetingId) as T
    }
}

@Composable
fun LastMeetingNavApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as Application

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            val viewModel: MainViewModel = viewModel()
            MainScreen(
                viewModel = viewModel,
                onNavigateToDetails = { id ->
                    navController.navigate("details/$id")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }

        composable(
            route = "details/{meetingId}",
            arguments = listOf(navArgument("meetingId") { type = NavType.LongType })
        ) { backStackEntry ->
            val meetingId = backStackEntry.arguments?.getLong("meetingId") ?: 0L
            val viewModel: MeetingDetailsViewModel = viewModel(
                factory = MeetingDetailsViewModelFactory(app, meetingId)
            )

            MeetingDetailsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            val viewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
