package com.fieldpass.student.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fieldpass.student.R

/** Which runtime permission a gate asks for; carries its own explanatory strings. */
enum class GatedPermission(
    val manifestPermissions: List<String>,
    val titleRes: Int,
    val rationaleRes: Int,
    val blockedRes: Int,
) {
    CAMERA(
        listOf(android.Manifest.permission.CAMERA),
        R.string.permission_camera_title, R.string.permission_camera_rationale, R.string.permission_camera_blocked,
    ),
    LOCATION(
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
        R.string.permission_location_title, R.string.permission_location_rationale, R.string.permission_location_blocked,
    ),
}

/**
 * Shows [content] once the permission is granted. Otherwise explains why it is needed, asks only
 * when the person taps Allow, and after a "don't ask again" denial points to app settings.
 */
@Composable
fun PermissionGate(permission: GatedPermission, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(isGranted(context, permission)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isGranted(context, permission) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = isGranted(context, permission)
        asked = true
        blocked = !granted && permission.manifestPermissions.none { p ->
            context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, p) } ?: false
        }
    }

    if (granted) {
        content()
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(permission.titleRes), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(if (asked && blocked) permission.blockedRes else permission.rationaleRes))
        if (asked && blocked) {
            PrimaryButton(stringResource(R.string.action_open_settings), onClick = { openAppSettings(context) })
        } else {
            PrimaryButton(
                stringResource(R.string.permission_allow),
                onClick = { launcher.launch(permission.manifestPermissions.toTypedArray()) },
            )
        }
    }
}

private fun isGranted(context: Context, permission: GatedPermission): Boolean =
    permission.manifestPermissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
