package com.dirk.kalshiarb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiarb.ui.ArbApp
import com.dirk.kalshiarb.ui.ArbHunterTheme
import com.dirk.kalshiarb.ui.ArbViewModel

class MainActivity : ComponentActivity() {

    private val vm: ArbViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            ArbHunterTheme(light = state.lightTheme) {
                ArbApp(state = state, vm = vm)
            }
        }
    }
}
