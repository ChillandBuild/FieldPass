package com.fieldpass.student.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import com.fieldpass.student.ui.RefreshOnReturn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fieldpass.student.AppContainer
import com.fieldpass.student.R
import com.fieldpass.student.activities.ActivitiesScreen
import com.fieldpass.student.activities.ActivitiesViewModel
import com.fieldpass.student.enrollment.EnrollmentScreen
import com.fieldpass.student.enrollment.EnrollmentViewModel
import com.fieldpass.student.history.HistoryScreen
import com.fieldpass.student.history.HistoryViewModel
import com.fieldpass.student.ui.BrandMark

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    ACTIVITIES(R.string.tab_activities, Icons.Filled.DateRange),
    HISTORY(R.string.tab_history, Icons.Filled.Menu),
    PHOTO(R.string.tab_photo, Icons.Filled.AccountCircle),
}

/** Signed-in home: bottom tabs for activities, history and the enrolment photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenActivity: (String) -> Unit,
    onSignOut: () -> Unit,
    openPhotoRequested: Boolean = false,
    onOpenPhotoHandled: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openPhotoRequested) {
        if (openPhotoRequested) {
            selected = Tab.PHOTO.ordinal
            onOpenPhotoHandled()
        }
    }
    val repo = container.repository
    val activities: ActivitiesViewModel = viewModel(factory = viewModelFactory { initializer { ActivitiesViewModel(repo) } })
    val history: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(repo) } })
    val enrollment: EnrollmentViewModel = viewModel(factory = viewModelFactory { initializer { EnrollmentViewModel(repo) } })

    RefreshOnReturn {
        activities.refresh()
        history.refresh()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrandMark(size = 36.dp)
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = { TextButton(onClick = onSignOut) { Text(stringResource(R.string.action_sign_out)) } },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Tab.entries.forEachIndexed { index, tab ->
                    val label = stringResource(tab.labelRes)
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val body = Modifier.padding(padding)
        when (Tab.entries[selected]) {
            Tab.ACTIVITIES -> ActivitiesScreen(activities, onOpenActivity, onOpenPhoto = { selected = Tab.PHOTO.ordinal }, modifier = body)
            Tab.HISTORY -> HistoryScreen(history, body)
            Tab.PHOTO -> EnrollmentScreen(enrollment, body)
        }
    }
}
