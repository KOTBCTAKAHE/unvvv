package com.unvvv.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.unvvv.app.record.RecordService
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RecordService.ensure(this)
        handle(intent)
        setContent { AppTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val t = i?.getIntExtra(EXTRA_TAB, -1) ?: -1
        if (t >= 0) openTab.value = t
    }

    companion object {
        const val EXTRA_TAB = "tab"
        val openTab = MutableStateFlow<Int?>(null)
    }
}

sealed interface Route {
    data object Home : Route
    data class Live(val camId: String) : Route
    data object Scan : Route
    data class Play(val camId: String, val path: String) : Route
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var recordsFilter by remember { mutableStateOf<String?>(null) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val push: (Route) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    val requestedTab by MainActivity.openTab.collectAsState()
    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            tab = it
            stack.clear()
            stack.add(Route.Home)
            MainActivity.openTab.value = null
        }
    }

    val navItems = remember {
        listOf(
            NavItem(0, "Камеры", Icons.Default.Videocam),
            NavItem(1, "Записи", Icons.Default.VideoLibrary),
            NavItem(3, "Лица", Icons.Default.Face),
            NavItem(2, "Настройки", Icons.Default.Settings),
        )
    }

    CompositionLocalProvider(LocalVideoActive provides active) {
      AppBackground {
        when (val r = stack.last()) {
            Route.Home -> Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { FloatingNavBar(navItems, tab) { tab = it } },
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                    when (tab) {
                        0 -> CamerasScreen(onOpen = { push(Route.Live(it)) }, onScan = { push(Route.Scan) })
                        1 -> RecordsScreen(
                            filter = recordsFilter,
                            onFilter = { recordsFilter = it },
                            onPlay = { c, p -> push(Route.Play(c, p)) },
                        )
                        3 -> FacesScreen()
                        else -> SettingsScreen()
                    }
                }
            }

            is Route.Live -> LiveScreen(
                camId = r.camId,
                onBack = pop,
                onSwitch = { id -> stack[stack.lastIndex] = Route.Live(id) },
                onRecords = {
                    recordsFilter = r.camId
                    tab = 1
                    stack.clear()
                    stack.add(Route.Home)
                },
            )

            Route.Scan -> ScanScreen(onBack = pop)
            is Route.Play -> PlayerScreen(camId = r.camId, path = r.path, onBack = pop)
        }
      }
    }
}
