package app.meineschule.expressive.ui.nav

import androidx.compose.ui.unit.IntOffset
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
import android.net.Uri
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.DeepLink
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

    var shared by remember { mutableStateOf<DeepLink.Share?>(null) }
    val pending by app.pendingLink.collectAsState()
    LaunchedEffect(pending) {
        val l = pending ?: return@LaunchedEffect; app.pendingLink.value = null
        when (l) {
            is DeepLink.Chat -> nav.navigate("chat/${l.id}")
            is DeepLink.Profile -> nav.navigate("person?u=${Uri.encode(l.link.userId)}&n=${Uri.encode(l.link.name)}" + (l.link.server?.let { "&b=${Uri.encode(it)}" } ?: "") + (l.link.school?.let { "&sc=${Uri.encode(it)}" } ?: "") + (l.link.schoolName?.let { "&sn=${Uri.encode(it)}" } ?: ""))
            is DeepLink.Section -> nav.navigate(l.route) { launchSingleTop = true }
            is DeepLink.Share -> { shared = l; nav.navigate("share") }
        }
    }

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
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(); val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>(); val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>(); val slideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        NavHost(
            nav, startDestination = Dest.Home.route,
            enterTransition = { fadeIn(effects) + slideInHorizontally(slideSpec) { it / 12 } + scaleIn(spatial, .96f) },
            exitTransition = { fadeOut(fastEffects) + scaleOut(spatial, .98f) },
            popEnterTransition = { fadeIn(effects) + scaleIn(spatial, .98f) },
            popExitTransition = { fadeOut(fastEffects) + slideOutHorizontally(slideSpec) { it / 12 } },
        ) {
            composable(Dest.Home.route) { HomeScreen(nav) }
            composable(Dest.Plan.route) { ScheduleScreen() }
            composable(Dest.Chats.route) { ChatsScreen(nav) }
            composable("chat/{id}", listOf(navArgument("id") { type = NavType.StringType })) { ChatScreen(it.arguments!!.getString("id")!!, nav) }
            composable("newchat?edit={edit}&prefill={prefill}", listOf(
                navArgument("edit") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("prefill") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { e -> NewChatScreen(nav, e.arguments?.getString("edit"), e.arguments?.getString("prefill")?.split('\n')?.filter { it.isNotBlank() }.orEmpty()) }
            composable("person?u={u}&n={n}&b={b}&sc={sc}&sn={sn}&k={k}", listOf("u", "n", "b", "sc", "sn", "k").map { k -> navArgument(k) { type = NavType.StringType; nullable = true; defaultValue = null } }) { e ->
                val a = e.arguments!!; PersonScreen(a.getString("u").orEmpty(), a.getString("n").orEmpty(), a.getString("b"), a.getString("sc"), a.getString("sn"), nav, a.getString("k"))
            }
            composable("debuglog") { DebugLogScreen(nav) }
            composable("share") { ShareScreen(shared?.text, shared?.uris.orEmpty(), nav) }
            composable(Dest.Calendar.route) { CalendarScreen() }
            composable(Dest.More.route) { MoreScreen(nav) }
            composable("media") { MediaScreen(nav) }
            composable("media/{id}") { MediaDetailScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
            composable("hip") { HipScreen(onBack = { nav.popBackStack() }) }
            composable("care") { CareScreen(onBack = { nav.popBackStack() }) }
            composable("lost") { LostScreen(onBack = { nav.popBackStack() }) }
            composable("protocols") { ProtocolsScreen(nav) }
            composable("protocol/{id}") { ProtocolDetailScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
            composable("student/{id}") { StudentScreen(it.arguments!!.getString("id")!!, nav) }
            composable("profile") { ProfileScreen(nav) }
            composable("notifications") { NotificationsScreen(nav) }
            composable("links") { LinksScreen(onBack = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, nav = nav) }
        }
    }
}
