package com.aura.launcher

import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TextShadow = Shadow(Color(0x80000000), Offset(0f, 2f), 8f)

// ============================================================================
//  Drawer gesture state: progress 0 (closed) .. 1 (fully open), driven by finger
// ============================================================================

@Stable
class DrawerState(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    var heightPx: Float = 1f
    private var job: Job? = null

    val isOpen: Boolean get() = progress > 0.01f

    /** [deltaPx] uses touch sign: negative = finger moving up = opening. */
    fun drag(deltaPx: Float) {
        job?.cancel()
        progress = (progress - deltaPx / heightPx).coerceIn(0f, 1f)
    }

    fun settle(velocityY: Float = 0f) {
        val target = when {
            velocityY < -700f -> 1f
            velocityY > 700f -> 0f
            progress > 0.5f -> 1f
            else -> 0f
        }
        animateTo(target)
    }

    fun animateTo(target: Float) {
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
            ) { value, _ -> progress = value }
        }
    }

    fun reset() {
        job?.cancel()
        progress = 0f
    }
}

@Stable
class AppActions(
    val onOpen: (AppEntry) -> Unit,
    val onPin: (AppEntry) -> Unit,
    val onDock: (AppEntry) -> Unit,
    val onInfo: (AppEntry) -> Unit,
    val onUninstall: (AppEntry) -> Unit,
)

// ============================================================================
//  Root
// ============================================================================

@Composable
fun LauncherRoot(vm: LauncherViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val apps by vm.apps.collectAsStateWithLifecycle()
    val dock by vm.dock.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val dockKeys by vm.dockKeys.collectAsStateWithLifecycle()
    val pinnedKeys by vm.pinnedKeys.collectAsStateWithLifecycle()

    val drawer = remember { DrawerState(scope) }

    val actions = remember(vm) {
        AppActions(
            onOpen = { app ->
                vm.launch(app)
                // Reset the drawer once the app's launch animation has covered the screen.
                scope.launch { delay(450); drawer.reset() }
            },
            onPin = { app ->
                if (!vm.togglePinned(app.key)) {
                    Toast.makeText(context, "Home screen is full", Toast.LENGTH_SHORT).show()
                }
            },
            onDock = { app ->
                if (!vm.toggleDock(app.key)) {
                    Toast.makeText(context, "Dock is full", Toast.LENGTH_SHORT).show()
                }
            },
            onInfo = { vm.showInfo(it) },
            onUninstall = { vm.uninstall(it) },
        )
    }

    // Home button while already home -> close drawer
    LaunchedEffect(vm) {
        vm.homePresses.collect { if (drawer.isOpen) drawer.animateTo(0f) }
    }
    // A launcher must never "go back" out of itself
    BackHandler { if (drawer.isOpen) drawer.animateTo(0f) }

    val dockSet = remember(dockKeys) { dockKeys.toSet() }
    val pinnedSet = remember(pinnedKeys) { pinnedKeys.toSet() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        SideEffect { drawer.heightPx = heightPx }
        val drawerVisible by remember { derivedStateOf { drawer.progress > 0.001f } }

        val homeDrag = rememberDraggableState { drawer.drag(it) }

        HomeScreen(
            pinned = pinned,
            dock = dock,
            pinnedKeys = pinnedSet,
            dockKeys = dockSet,
            actions = actions,
            onSearch = { drawer.animateTo(1f) },
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    state = homeDrag,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> drawer.settle(velocity) },
                )
                .graphicsLayer {
                    val p = drawer.progress
                    alpha = 1f - p
                    scaleX = 1f - 0.06f * p
                    scaleY = 1f - 0.06f * p
                },
        )

        if (drawerVisible) {
            AppDrawer(
                apps = apps,
                pinnedKeys = pinnedSet,
                dockKeys = dockSet,
                actions = actions,
                drawer = drawer,
            )
        }
    }
}

// ============================================================================
//  Home screen
// ============================================================================

@Composable
private fun HomeScreen(
    pinned: List<AppEntry>,
    dock: List<AppEntry>,
    pinnedKeys: Set<String>,
    dockKeys: Set<String>,
    actions: AppActions,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // Soft scrims so white text stays readable on any wallpaper
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color(0x73000000),
                            0.35f to Color(0x14000000),
                            0.65f to Color(0x1A000000),
                            1f to Color(0xA6000000),
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(36.dp))
            ClockWidget(Modifier.padding(start = 16.dp))
            Spacer(Modifier.height(18.dp))
            SearchPill(onSearch, Modifier.padding(horizontal = 16.dp))

            Spacer(Modifier.weight(1f))

            if (pinned.isEmpty()) {
                Text(
                    "Swipe up for all apps\nLong-press any app to pin it here",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Color(0xB3FFFFFF),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            } else {
                pinned.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { app ->
                            AppIcon(
                                app = app,
                                isPinned = app.key in pinnedKeys,
                                isDocked = app.key in dockKeys,
                                actions = actions,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Dock(dock, pinnedKeys, dockKeys, actions)

            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0x66FFFFFF))
            )
        }
    }
}

@Composable
private fun ClockWidget(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            val untilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(untilNextMinute + 50)
        }
    }

    val is24 = DateFormat.is24HourFormat(context)
    val time = remember(now, is24) {
        now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm", Locale.getDefault()))
    }
    val date = remember(now) {
        now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
    }

    Column(modifier) {
        Text(
            text = time,
            color = Color.White,
            fontSize = 76.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-2).sp,
            style = TextStyle(shadow = TextShadow),
        )
        Text(
            text = date,
            color = Color(0xE6FFFFFF),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(shadow = TextShadow),
        )
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x40000000))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Search apps", color = Color(0xB3FFFFFF), fontSize = 15.sp)
    }
}

@Composable
private fun Dock(
    dock: List<AppEntry>,
    pinnedKeys: Set<String>,
    dockKeys: Set<String>,
    actions: AppActions,
) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x59000000))
            .border(1.dp, Color(0x1FFFFFFF), shape)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .heightIn(min = 62.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (dock.isEmpty()) {
            Text(
                "Long-press an app, then \"Add to dock\"",
                color = Color(0xB3FFFFFF),
                fontSize = 13.sp,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                dock.forEach { app ->
                    AppIcon(
                        app = app,
                        isPinned = app.key in pinnedKeys,
                        isDocked = true,
                        actions = actions,
                        showLabel = false,
                        iconSize = 54.dp,
                    )
                }
            }
        }
    }
}

// ============================================================================
//  App drawer
// ============================================================================

private fun drawerNestedScroll(drawer: DrawerState) = object : NestedScrollConnection {
    // Finger moving up while the drawer isn't fully open: grow the drawer first.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && drawer.progress < 1f) {
            drawer.drag(available.y)
            Offset(0f, available.y)
        } else Offset.Zero

    // Grid is already at the top and finger keeps moving down: shrink the drawer.
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f) {
            drawer.drag(available.y)
            Offset(0f, available.y)
        } else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (drawer.progress > 0.001f && drawer.progress < 0.999f) {
            drawer.settle(available.y)
            available
        } else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (drawer.progress > 0.001f && drawer.progress < 0.999f) {
            drawer.settle(available.y)
            available
        } else Velocity.Zero
}

@Composable
private fun AppDrawer(
    apps: List<AppEntry>,
    pinnedKeys: Set<String>,
    dockKeys: Set<String>,
    actions: AppActions,
    drawer: DrawerState,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
    }
    val connection = remember(drawer) { drawerNestedScroll(drawer) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val handleDrag = rememberDraggableState { drawer.drag(it) }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = (1f - drawer.progress) * drawer.heightPx
                shape = RoundedCornerShape((28f * (1f - drawer.progress)).dp)
                clip = true
            }
            .background(Brush.verticalGradient(listOf(Color(0xF2141419), Color(0xF20A0A0E))))
            .pointerInput(Unit) { detectTapGestures { } } // swallow taps so they don't reach the home layer
            .statusBarsPadding()
            .imePadding()
    ) {
        // Grab handle: drag down to dismiss
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .draggable(
                    state = handleDrag,
                    orientation = Orientation.Vertical,
                    onDragStopped = { drawer.settle(it) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0x66FFFFFF))
            )
        }

        SearchField(
            query = query,
            onChange = { query = it },
            onGo = { filtered.firstOrNull()?.let(actions.onOpen) },
        )

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No apps found", color = Color(0x99FFFFFF), fontSize = 15.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(connection),
                contentPadding = PaddingValues(
                    start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp + navBottom
                ),
            ) {
                items(filtered, key = { it.key }) { app ->
                    AppIcon(
                        app = app,
                        isPinned = app.key in pinnedKeys,
                        isDocked = app.key in dockKeys,
                        actions = actions,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onGo: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x26FFFFFF))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search apps", color = Color(0x99FFFFFF), fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
    }
}

// ============================================================================
//  App icon (tap = open, long-press = menu, springy press feedback)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppIcon(
    app: AppEntry,
    isPinned: Boolean,
    isDocked: Boolean,
    actions: AppActions,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    iconSize: Dp = 58.dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "iconPress",
    )
    val haptic = LocalHapticFeedback.current

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { actions.onOpen(app) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                modifier = Modifier.size(iconSize),
            )
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = app.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(iconSize + 12.dp),
                    style = TextStyle(shadow = TextShadow),
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (isPinned) "Remove from home" else "Add to home") },
                onClick = { menuOpen = false; actions.onPin(app) },
            )
            DropdownMenuItem(
                text = { Text(if (isDocked) "Remove from dock" else "Add to dock") },
                onClick = { menuOpen = false; actions.onDock(app) },
            )
            DropdownMenuItem(
                text = { Text("App info") },
                onClick = { menuOpen = false; actions.onInfo(app) },
            )
            DropdownMenuItem(
                text = { Text("Uninstall") },
                onClick = { menuOpen = false; actions.onUninstall(app) },
            )
        }
    }
}
