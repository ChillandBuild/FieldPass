package com.fieldpass.student.auth

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpass.student.BuildConfig
import com.fieldpass.student.R
import com.fieldpass.student.ui.MinTouchTarget
import com.fieldpass.student.ui.PrimaryButton
import com.fieldpass.student.ui.SecondaryButton

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    sessionExpired: Boolean,
    onSignedIn: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            viewModel.onSignInHandled()
            onSignedIn()
        }
    }
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium)
            if (sessionExpired) {
                Text(stringResource(R.string.login_session_expired), color = MaterialTheme.colorScheme.tertiary)
            }

            OutlinedTextField(
                value = state.username, onValueChange = viewModel::onUsername, singleLine = true,
                label = { Text(stringResource(R.string.login_username)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password, onValueChange = viewModel::onPassword, singleLine = true,
                label = { Text(stringResource(R.string.login_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            PrimaryButton(
                text = stringResource(if (state.isSigningIn) R.string.login_signing_in else R.string.login_sign_in),
                onClick = viewModel::signIn, enabled = state.canSignIn,
            )

            ServerSection(state, viewModel)
            Text(stringResource(R.string.login_limits), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ServerSection(state: LoginUiState, viewModel: LoginViewModel) {
    Text(stringResource(R.string.login_server_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    OutlinedTextField(
        value = state.baseUrlText, onValueChange = viewModel::onBaseUrl, singleLine = true,
        label = { Text(stringResource(R.string.login_server_heading)) },
        placeholder = { Text(stringResource(R.string.login_server_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.login_server_help), style = MaterialTheme.typography.bodySmall)
    SecondaryButton(
        text = stringResource(if (state.isTesting) R.string.login_testing else R.string.login_test_connection),
        onClick = viewModel::testConnection, enabled = !state.isTesting && !state.isSigningIn,
    )
    when (val result = state.connection) {
        ConnectionResult.Ok -> Text(stringResource(R.string.login_connection_ok), color = MaterialTheme.colorScheme.primary)
        is ConnectionResult.Failed -> Text(stringResource(result.code.messageRes), color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
    if (BuildConfig.FIXTURE_AVAILABLE) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                .toggleable(value = state.fixtureMode, role = Role.Switch, onValueChange = viewModel::onFixtureMode),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.login_fixture_switch), modifier = Modifier.weight(1f))
            Switch(checked = state.fixtureMode, onCheckedChange = null)
        }
        Text(stringResource(R.string.login_fixture_help), style = MaterialTheme.typography.bodySmall)
    }
}
