package gg.thor.utils

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import gg.thor.utils.data.SettingsRepository
import gg.thor.utils.data.ThorSettings
import gg.thor.utils.ui.DashboardScreen
import gg.thor.utils.ui.EmulatorsScreen
import gg.thor.utils.ui.LauncherScreen
import gg.thor.utils.ui.RomSorterScreen
import gg.thor.utils.ui.SettingsScreen
import gg.thor.utils.ui.theme.LocalGlassColors
import gg.thor.utils.ui.theme.ThorTheme

private data class Section(val route: String, val label: String, val icon: ImageVector)

private val sections = listOf(
    Section("dashboard", "Stats", Icons.Filled.SpaceDashboard),
    Section("launcher", "Launch", Icons.Filled.Gamepad),
    Section("roms", "ROMs", Icons.Filled.FolderOpen),
    Section("emulators", "Guide", Icons.Filled.VideogameAsset),
    Section("settings", "Setup", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = SettingsRepository(applicationContext)

        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle(initialValue = ThorSettings())
            ThorTheme(
                hue = settings.accentHue,
                saturation = settings.accentSaturation,
                opacity = settings.glassOpacity,
                blurEnabled = settings.glassBlur,
            ) {
                ThorScaffold(settings, repository)
            }
        }
    }
}

@Composable
private fun ThorScaffold(settings: ThorSettings, repository: SettingsRepository) {
    val navController = rememberNavController()
    val colors = LocalGlassColors.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Box(
        Modifier
            .fillMaxSize()
            // A vertical wash rather than a flat fill: the panels need something
            // with depth behind them or the glass reads as plain grey.
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.background,
                        colors.accentDeep.copy(alpha = 0.45f),
                        colors.background,
                    ),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = colors.panelBottom) {
                    sections.forEach { section ->
                        val selected = currentDestination?.hierarchy?.any { it.route == section.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(section.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(section.icon, contentDescription = section.label) },
                            label = { Text(section.label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accentBright,
                                selectedTextColor = colors.accentBright,
                                indicatorColor = colors.accent.copy(alpha = 0.25f),
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") { DashboardScreen(settings, repository) }
                    composable("launcher") { LauncherScreen(settings, repository) }
                    composable("roms") { RomSorterScreen(settings, repository) }
                    composable("emulators") { EmulatorsScreen() }
                    composable("settings") { SettingsScreen(settings, repository) }
                }
            }
        }
    }
}
