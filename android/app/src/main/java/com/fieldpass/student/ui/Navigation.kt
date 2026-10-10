package com.fieldpass.student.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fieldpass.student.AppContainer
import com.fieldpass.student.activities.ActivityDetailScreen
import com.fieldpass.student.activities.ActivityDetailViewModel
import com.fieldpass.student.auth.LoginScreen
import com.fieldpass.student.auth.LoginViewModel
import com.fieldpass.student.capture.AttendanceCaptureScreen
import com.fieldpass.student.capture.AttendanceCaptureViewModel
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.ui.home.HomeScreen
import android.os.SystemClock
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private const val KEY_OPEN_PHOTO = "open_photo_tab"

private object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val DETAIL = "activity/{id}"
    const val CAPTURE = "capture/{id}/{action}"
    fun detail(id: String) = "activity/$id"
    fun capture(id: String, action: Action) = "capture/$id/${action.name}"
}

/** Navigation for a signed-in or signed-out student. [signedIn] switches the start destination. */
@Composable
fun AppNavigation(
    container: AppContainer,
    signedIn: Boolean,
    sessionExpired: Boolean,
    onSignedIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController, startDestination = if (signedIn) Routes.HOME else Routes.LOGIN, modifier = modifier) {
        composable(Routes.LOGIN) {
            val vm: LoginViewModel = viewModel(factory = remember { viewModelFactory { initializer { LoginViewModel(container.repository, container.settings) } } })
            LoginScreen(vm, sessionExpired, onSignedIn)
        }
        composable(Routes.HOME) { entry ->
            val openPhoto by entry.savedStateHandle.getStateFlow(KEY_OPEN_PHOTO, false).collectAsStateWithLifecycle()
            HomeScreen(
                container = container,
                openPhotoRequested = openPhoto,
                onOpenPhotoHandled = { entry.savedStateHandle[KEY_OPEN_PHOTO] = false },
                onOpenActivity = { navController.navigate(Routes.detail(it)) },
                onSignOut = onSignOut,
            )
        }
        composable(Routes.DETAIL, listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = requireNotNull(entry.arguments?.getString("id"))
            val vm: ActivityDetailViewModel = viewModel(
                key = "detail-$id",
                factory = viewModelFactory { initializer { ActivityDetailViewModel(id, container.repository) } },
            )
            DetailWithRefresh(vm, navController)
        }
        composable(
            Routes.CAPTURE,
            listOf(navArgument("id") { type = NavType.StringType }, navArgument("action") { type = NavType.StringType }),
        ) { entry ->
            val id = requireNotNull(entry.arguments?.getString("id"))
            val action = Action.valueOf(requireNotNull(entry.arguments?.getString("action")))
            val vm: AttendanceCaptureViewModel = viewModel(
                key = "capture-$id-${action.name}",
                factory = viewModelFactory {
                    initializer {
                        AttendanceCaptureViewModel(id, action, container.repository, container.location, SystemClock::elapsedRealtime)
                    }
                },
            )
            AttendanceCaptureScreen(vm, onFinished = { navController.popBackStack() })
        }
    }
}

@Composable
private fun DetailWithRefresh(vm: ActivityDetailViewModel, navController: NavHostController) {
    RefreshOnReturn(vm::refresh)
    ActivityDetailScreen(
        vm,
        onAction = { activityId, action -> navController.navigate(Routes.capture(activityId, action)) },
        onOpenPhoto = {
            navController.previousBackStackEntry?.savedStateHandle?.set(KEY_OPEN_PHOTO, true)
            navController.popBackStack()
        },
    )
}
