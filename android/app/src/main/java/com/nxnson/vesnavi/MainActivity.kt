package com.nxnson.vesnavi

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.nxnson.vesnavi.ui.AppRoot
import com.nxnson.vesnavi.ui.VesNaviTheme

class MainActivity : ComponentActivity() {
    private val model get() = (application as VesNaviApp).model

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.permissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(includeBluetooth = false)
        setContent {
            val keepOn = model.keepScreenOn
            LaunchedEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            VesNaviTheme {
                AppRoot(model, requestBluetooth = { requestPermissions(includeBluetooth = true) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.foregroundChanged(true)
    }

    override fun onStop() {
        super.onStop()
        model.foregroundChanged(false)
    }

    /** Location (and notifications) on launch; Bluetooth only when the rider goes to connect the scooter. */
    private fun requestPermissions(includeBluetooth: Boolean) {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (includeBluetooth) wanted += model.ble.requiredPermissions()
        permissionLauncher.launch(wanted.distinct().toTypedArray())
    }
}
