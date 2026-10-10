package com.fieldpass.student

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fieldpass.student.auth.AppViewModel
import com.fieldpass.student.auth.SessionState
import com.fieldpass.student.ui.ErrorPanel
import com.fieldpass.student.ui.FieldPassTheme
import com.fieldpass.student.ui.LoadingBox
import com.fieldpass.student.ui.LocalContainer
import com.fieldpass.student.ui.AppNavigation
import com.fieldpass.student.ui.SecondaryButton

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FieldPassApp).container
        setContent {
            FieldPassTheme {
                CompositionLocalProvider(LocalContainer provides container) { Root(container) }
            }
        }
    }
}

@Composable
private fun Root(container: AppContainer) {
    val appVm: AppViewModel = viewModel(
        factory = viewModelFactory { initializer { AppViewModel(container.repository, container.session) } },
    )
    val session by appVm.state.collectAsStateWithLifecycle()
    val fixture by container.settings.fixtureMode.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (fixture) FixtureBanner()
            Box(Modifier.weight(1f).then(if (fixture) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                when (val s = session) {
                    SessionState.Checking -> LoadingBox()
                    is SessionState.CannotReachServer -> Unreachable(s, appVm)
                    is SessionState.SignedOut -> key("out") {
                        AppNavigation(container, signedIn = false, sessionExpired = s.sessionExpired, onSignedIn = appVm::onSignedIn, onSignOut = appVm::signOut)
                    }
                    SessionState.SignedIn -> key("in") {
                        AppNavigation(container, signedIn = true, sessionExpired = false, onSignedIn = appVm::onSignedIn, onSignOut = appVm::signOut)
                    }
                }
            }
        }
    }
}

@Composable
private fun FixtureBanner() {
    Box(
        Modifier.fillMaxWidth().background(Color(0xFFB00020)).windowInsetsPadding(WindowInsets.statusBars).padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.fixture_banner), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Unreachable(state: SessionState.CannotReachServer, appVm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text(stringResource(R.string.session_unreachable_title), style = MaterialTheme.typography.titleLarge)
        ErrorPanel(state.code, appVm::restore)
        SecondaryButton(stringResource(R.string.session_unreachable_sign_out), appVm::signOut)
    }
}
