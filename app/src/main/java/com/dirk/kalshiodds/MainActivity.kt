package com.dirk.kalshiodds

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.ui.OddsScreen
import com.dirk.kalshiodds.ui.OddsViewModel
import com.dirk.kalshiodds.ui.SettingsScreen
import com.dirk.kalshiodds.ui.SettingsViewModel
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val oddsViewModel: OddsViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* in-app recent-signals list still works if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalNotifier.ensureChannels(this)
        lifecycleScope.launch {
            KalshiOddsApp.from(this@MainActivity).container.preferences.settings
                .map { it.notificationsEnabled || it.liveSignalsEnabled }
                .distinctUntilChanged()
                .collect { wantsNotify ->
                    if (wantsNotify) requestNotificationPermission()
                }
        }
        enableEdgeToEdge()
        setContent {
            KalshiOddsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf("odds") }
                    if (screen == "settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { screen = "odds" }
                        )
                    } else {
                        OddsScreen(
                            viewModel = oddsViewModel,
                            onOpenSettings = { screen = "settings" }
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
