package com.astra.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.ui.*

private data class NavTab(val filledIcon: ImageVector, val outlinedIcon: ImageVector, val label: String)

class MainActivity : ComponentActivity() {
    private val vm: AstraViewModel by viewModels()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** "Share to ZENIQ": text goes into the message box; pictures and files are attached to the next message. */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent == null) return
        // a scheduled task's notification was tapped: run its prompt now
        intent.getStringExtra(Alarms.EXTRA_PROMPT)?.let { p -> intent.removeExtra(Alarms.EXTRA_PROMPT); if (p.isNotBlank()) vm.send(p) }
        fun nameOf(uri: android.net.Uri): String = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (_: Exception) { null } ?: (uri.lastPathSegment ?: "Shared file")
        try {
            when (intent.action) {
                android.content.Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                    val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                    if (uri != null) vm.attachShared(uri, intent.type, nameOf(uri))
                    else if (!text.isNullOrBlank()) vm.draft.value = text
                }
                android.content.Intent.ACTION_SEND_MULTIPLE -> {
                    @Suppress("DEPRECATION") val uris = intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                    uris?.take(8)?.forEach { vm.attachShared(it, contentResolver.getType(it), nameOf(it)) }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtimePerms = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                add(android.Manifest.permission.RECORD_AUDIO)
            // Android 9 and older need this to save generated app ZIPs into Documents/ZENIQApps
            if (android.os.Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
        if (runtimePerms.isNotEmpty()) requestPermissions(runtimePerms, 1)

        val navTabs = listOf(
            NavTab(Icons.Filled.Chat, Icons.Outlined.ChatBubbleOutline, "Chat"),
            NavTab(Icons.Filled.ViewInAr, Icons.Outlined.ViewInAr, "Models"),
            NavTab(Icons.Filled.Code, Icons.Outlined.Code, "Code"),
            NavTab(Icons.Filled.Settings, Icons.Outlined.Settings, "Settings")
        )

        if (savedInstanceState == null) handleShare(intent)

        setContent {
            AstraTheme(isSystemInDarkTheme()) {
                val c = LocalIos.current
                var tab by remember { mutableIntStateOf(0) }
                var showHistory by remember { mutableStateOf(false) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0

                BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
                BackHandler(enabled = showHistory && !drawerState.isOpen) { showHistory = false }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = true,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = c.bg, drawerContentColor = c.text) {
                            Sidebar(
                                vm,
                                onNewChat = {
                                    vm.newChat(); tab = 0; showHistory = false
                                    scope.launch { drawerState.close() }
                                },
                                onOpenChat = { id ->
                                    vm.openChat(id); tab = 0; showHistory = false
                                    scope.launch { drawerState.close() }
                                },
                                onShowMore = {
                                    showHistory = true
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                ) {
                Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    Box(Modifier.weight(1f)) {
                        if (showHistory) {
                            HistoryScreen(vm, onBack = { showHistory = false },
                                onOpen = { id -> vm.openChat(id); tab = 0; showHistory = false })
                        } else {
                            AnimatedContent(tab, transitionSpec = {
                                (fadeIn(tween(260)) + scaleIn(tween(260), 0.98f)) togetherWith fadeOut(tween(120))
                            }, label = "tabs") { t ->
                                when (t) {
                                    0 -> ChatScreen(vm, onOpenModels = { tab = 1 },
                                        onMenu = { scope.launch { drawerState.open() } })
                                    1 -> ModelsScreen(vm)
                                    2 -> CodeScreen(vm)
                                    else -> SettingsScreen(vm)
                                }
                            }
                        }
                    }
                    AnimatedVisibility(!ime, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.line))
                            Row(
                                Modifier.fillMaxWidth().background(c.bg)
                                    .navigationBarsPadding()
                                    .padding(top = 6.dp, bottom = 4.dp)
                            ) {
                                navTabs.forEachIndexed { i, item ->
                                    val sel = tab == i
                                    val a by animateFloatAsState(if (sel) 1f else 0.48f, tween(220), label = "ta$i")
                                    val s by animateFloatAsState(if (sel) 1.1f else 1f, IosSpring, label = "ts$i")
                                    Column(
                                        Modifier.weight(1f).pressScale { tab = i; showHistory = false },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = if (sel) item.filledIcon else item.outlinedIcon,
                                            contentDescription = item.label,
                                            tint = if (sel) c.accent else c.sub,
                                            modifier = Modifier.size(24.dp).alpha(a)
                                                .graphicsLayer { scaleX = s; scaleY = s }
                                        )
                                        Text(item.label, fontSize = 10.sp, color = if (sel) c.accent else c.sub)
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
