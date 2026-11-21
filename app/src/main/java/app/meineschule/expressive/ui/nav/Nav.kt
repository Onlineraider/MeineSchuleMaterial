package app.meineschule.expressive.ui.nav

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.ui.screens.*

enum class Dest(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector) {
    Home("home", "Start", Icons.Outlined.Home, Icons.Rounded.Home),
    Plan("plan", "Plan", Icons.Outlined.CalendarViewWeek, Icons.Rounded.CalendarViewWeek),
    Chats("chats", "Chats", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    Calendar("calendar", "Kalender", Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth),
    More("more", "Mehr", Icons.Outlined.Apps, Icons.Rounded.Apps),
}

/**
 * Adaptive Navigation: Bottom-Navigation-Bar auf Smartphones, Navigation Rail auf Tablets/Faltgeräten,
 * Drawer auf großen Bildschirmen (NavigationSuiteScaffold). Auf Detailseiten blendet sie sich aus.
 */
@Composable
fun MainShell() {
    val nav = rememberNavController(); val app = LocalApp.current
    val entry by nav.currentBackStackEntryAsState(); val route = entry?.destination?.route
    val top = Dest.entries.firstOrNull { it.route == route }
    val state by app.session.state.collectAsState()
    val school by app.session.school.collectAsState()

    val layout = if (top != null) NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()) else NavigationSuiteType.None
    NavigationSuiteScaffold(
        layoutType = layout,
        navigationSuiteItems = {
            Dest.entries.forEach { d ->
                item(
                    selected = top == d, onClick = { nav.navigate(d.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                    label = { Text(d.label) },
                    icon = {
                        val badge = when (d) { Dest.Chats -> state?.unreadChatMessageCount?.takeIf { it > 0 }?.toString(); Dest.Calendar -> state?.notConfirmedCalendar?.size?.takeIf { it > 0 }?.let { "" }; else -> null }
                        BadgedBox(badge = { if (badge != null) { if (badge.isEmpty()) Badge() else Badge { Text(badge) } } }) { Icon(if (top == d) d.selected else d.icon, d.label) }
                    },
                )
            }
        },
    ) {
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(); val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        NavHost(
            nav, startDestination = Dest.Home.route,
            enterTransition = { fadeIn(effects) + slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 12 } + scaleIn(spatial, .96f) },
            exitTransition = { fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(spatial, .98f) },
            popEnterTransition = { fadeIn(effects) + scaleIn(spatial, .98f) },
            popExitTransition = { fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + slideOutHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 12 } },
        ) {
            composable(Dest.Home.route) { HomeScreen(nav) }
            composable(Dest.Plan.route) { ScheduleScreen() }
            composable(Dest.Chats.route) { ChatsScreen(onOpen = { nav.navigate("chat/$it") }) }
            composable("chat/{id}", listOf(navArgument("id") { type = NavType.StringType })) { ChatScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
            composable(Dest.Calendar.route) { CalendarScreen() }
            composable(Dest.More.route) { MoreScreen(nav) }
            composable("media") { MediaScreen(nav) }
            composable("media/{id}") { MediaDetailScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
            composable("hip") { HipScreen(onBack = { nav.popBackStack() }) }
            composable("absence") { AbsenceScreen(onBack = { nav.popBackStack() }) }
            composable("care") { CareScreen(onBack = { nav.popBackStack() }) }
            composable("lost") { LostScreen(onBack = { nav.popBackStack() }) }
            composable("protocols") { ProtocolsScreen(nav) }
            composable("protocol/{id}") { ProtocolDetailScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
            composable("student/{id}") { StudentScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
            composable("profile") { ProfileScreen(nav) }
            composable("notifications") { NotificationsScreen(nav) }
            composable("links") { LinksScreen(onBack = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
