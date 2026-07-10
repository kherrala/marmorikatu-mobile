package fi.marmorikatu.app.shell

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.components.MkIconButton
import fi.marmorikatu.app.components.MkIconButtonSize
import fi.marmorikatu.app.components.MkNavRail
import fi.marmorikatu.app.components.MkTabBar
import fi.marmorikatu.app.components.MkTabItem
import fi.marmorikatu.app.components.MkVoiceButton
import fi.marmorikatu.app.components.MkVoiceDock
import fi.marmorikatu.app.components.MkVoiceSize
import fi.marmorikatu.app.components.VoiceState
import fi.marmorikatu.app.debug.DebugScreen
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.screens.BussitScreen
import fi.marmorikatu.app.screens.EnergiaScreen
import fi.marmorikatu.app.screens.IlmastoScreen
import fi.marmorikatu.app.screens.KotiScreen
import fi.marmorikatu.app.screens.TapahtumatScreen
import fi.marmorikatu.app.screens.ValotScreen
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app shell from `MarmorikatuApp.dc.html`.
 *
 * The design prototype let you flip between three surfaces with a chip row;
 * on a real device the surface follows the window: a phone gets the tab-bar
 * layout, the shelf tablet gets the nav rail. Kid mode stays an explicit
 * choice a parent makes, not a size class.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MarmorikatuApp(
    widthDp: Int,
    viewModel: ShellViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }

    val surface by viewModel.surface.collectAsState()
    LaunchedEffect(widthDp) {
        if (surface != Surface.Kid) {
            viewModel.setSurface(if (widthDp >= TABLET_MIN_WIDTH_DP) Surface.Tablet else Surface.Phone)
        }
    }

    // The developer screen is still the only place that shows the transport
    // layer directly. Long-press the brand kicker to reach it.
    var showDebug by remember { mutableStateOf(false) }
    if (showDebug) {
        BackHandler { showDebug = false }
        DebugOverlay(onClose = { showDebug = false })
        return
    }

    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsSheet(
            viewModel = viewModel,
            onDismiss = { showSettings = false },
            onOpenDiagnostics = {
                showSettings = false
                showDebug = true
            },
        )
    }

    // System back returns to Koti from any other tab instead of quitting the
    // app. On Koti, deeper handlers (e.g. a KPI detail) take precedence, and
    // with nothing open the OS default (exit) applies.
    val tab by viewModel.tab.collectAsState()
    BackHandler(enabled = tab != Tab.Koti) { viewModel.setTab(Tab.Koti) }

    val openDebug = { showDebug = true }
    val openSettings = { showSettings = true }
    when (surface) {
        Surface.Phone -> PhoneSurface(viewModel, openDebug, openSettings)
        Surface.Kid -> KidSurface(viewModel)
        Surface.Tablet -> TabletSurface(viewModel, openDebug, openSettings)
    }
}

@Composable
private fun DebugOverlay(onClose: () -> Unit) {
    val colors = MkTheme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.appBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MkSpacing.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Diagnostiikka",
                style = MkTheme.type.title,
                color = colors.inkHi,
                modifier = Modifier.weight(1f),
            )
            MkIconButton(icon = MkIcons.X, label = "Sulje", onClick = onClose)
        }
        Box(modifier = Modifier.weight(1f)) { DebugScreen() }
    }
}

/** Below this the nav rail has no room to breathe. */
private const val TABLET_MIN_WIDTH_DP = 720

@Composable
private fun ScreenHost(tab: Tab, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        when (tab) {
            Tab.Koti -> KotiScreen()
            Tab.Valot -> ValotScreen()
            Tab.Ilmasto -> IlmastoScreen()
            Tab.Energia -> EnergiaScreen()
            Tab.Bussit -> BussitScreen()
            Tab.Tapahtumat -> TapahtumatScreen()
        }
    }
}

@Composable
private fun tabItems(unread: Int, rail: Boolean): List<MkTabItem> = Tab.entries.map { tab ->
    MkTabItem(
        key = tab.key,
        label = if (rail) tab.railLabel else tab.label,
        icon = tab.icon,
        iconActive = tab.iconActive,
        badge = if (tab == Tab.Tapahtumat && unread > 0) unread.toString() else null,
    )
}

// ---------------------------------------------------------------------------
// Phone
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneSurface(
    viewModel: ShellViewModel,
    openDebug: () -> Unit,
    openSettings: () -> Unit,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    val tab by viewModel.tab.collectAsState()
    val unread by viewModel.unreadCount.collectAsState()
    val voice by viewModel.voice.collectAsState()
    val voiceLine by viewModel.voiceLine.collectAsState()
    val voiceHint by viewModel.voiceHint.collectAsState()
    val dark by viewModel.dark.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.appBg)
            .statusBarsPadding(),
    ) {
        // Header: kicker + title, then the icon actions.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MkSpacing.pagePad,
                    end = MkSpacing.pagePad,
                    top = MkSpacing.x1,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Marmorikatu",
                    style = type.kicker.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.accent,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = openDebug,
                    ),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = tab.title ?: Fmt.greeting(),
                    style = type.title,
                    color = colors.inkHi,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2)) {
                MkIconButton(
                    icon = if (dark) MkIcons.Sun else MkIcons.Moon,
                    label = "Teema",
                    size = MkIconButtonSize.Lg,
                    onClick = viewModel::toggleTheme,
                )
                MkIconButton(
                    icon = MkIcons.BellFill,
                    label = "Tapahtumat",
                    size = MkIconButtonSize.Lg,
                    badge = if (unread > 0) unread.toString() else null,
                    onClick = { viewModel.setTab(Tab.Tapahtumat) },
                )
                MkIconButton(
                    icon = MkIcons.GearSix,
                    label = "Asetukset",
                    size = MkIconButtonSize.Lg,
                    onClick = openSettings,
                )
            }
        }

        // Soft edge under the header: the content should look like it passes
        // beneath the chrome rather than butting against a hard line.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(colors.appBg, colors.appBg.copy(alpha = 0f)),
                    )
                )
                .zIndex(1f)
        )

        // Each screen applies MkSpacing.pagePad itself; padding here too would
        // double it and push the header outside the content edges.
        Box(modifier = Modifier.weight(1f).offset(y = (-14).dp)) {
            // Swipe left/right to move between tabs. The climate card's own
            // room carousel consumes its horizontal drags, so it keeps working
            // inside the pager; only drags it doesn't take reach the pager.
            val tabs = remember { Tab.entries.toList() }
            val pagerState = rememberPagerState(initialPage = tab.ordinal) { tabs.size }
            LaunchedEffect(tab) {
                if (pagerState.currentPage != tab.ordinal) {
                    pagerState.animateScrollToPage(tab.ordinal)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    tabs.getOrNull(page)?.let { if (it != tab) viewModel.setTab(it) }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { tabs[it].key },
            ) { page ->
                ScreenHost(tab = tabs[page])
            }

            // Idle, the dock is just a mic button: it floats over the content
            // rather than reserving a row it does not need.
            if (voice == VoiceState.Idle) {
                MkVoiceDock(
                    onMic = viewModel::onMic,
                    state = voice,
                    hint = voiceHint,
                    transcript = voiceLine,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = MkSpacing.pagePad, vertical = MkSpacing.x2),
                )
            }
        }

        if (voice != VoiceState.Idle) {
            Box(modifier = Modifier.padding(horizontal = MkSpacing.pagePad, vertical = MkSpacing.x2)) {
                MkVoiceDock(
                    onMic = viewModel::onMic,
                    state = voice,
                    hint = voiceHint,
                    transcript = voiceLine,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        MkTabBar(
            items = tabItems(unread, rail = false),
            active = tab.key,
            onChange = { key -> Tab.entries.firstOrNull { it.key == key }?.let(viewModel::setTab) },
        )
    }
}

// ---------------------------------------------------------------------------
// Kid mode
// ---------------------------------------------------------------------------

/**
 * A deliberately small surface: a greeting, the child's own light, the shared
 * rooms, and a big voice button. No diagnostics, no settings.
 */
@Composable
private fun KidSurface(viewModel: ShellViewModel) {
    val colors = MkTheme.colors
    val type = MkTheme.type

    // ValotScreen owns a LazyColumn, so this column must NOT scroll: a lazy
    // list measured with unbounded height throws.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.appBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = MkSpacing.pagePad, vertical = MkSpacing.x3),
        verticalArrangement = Arrangement.spacedBy(MkSpacing.x4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Moi!", style = type.displayLarge, color = colors.inkHi)
                Text("Mitä haluat tehdä?", style = type.body, color = colors.inkMid)
            }
            MkIconButton(
                icon = MkIcons.X,
                label = "Poistu lapsen tilasta",
                size = MkIconButtonSize.Lg,
                onClick = viewModel::exitKidMode,
            )
        }

        // The lights the child may control, in the ordinary lights screen.
        Box(modifier = Modifier.weight(1f)) { ValotScreen() }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MkVoiceButton(
                onClick = viewModel::onMic,
                size = MkVoiceSize.Kid,
                label = "PUHU MARMORILLE",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tablet / kiosk
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletSurface(
    viewModel: ShellViewModel,
    openDebug: () -> Unit,
    openSettings: () -> Unit,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    val tab by viewModel.tab.collectAsState()
    val unread by viewModel.unreadCount.collectAsState()
    val voice by viewModel.voice.collectAsState()
    val voiceLine by viewModel.voiceLine.collectAsState()
    val voiceHint by viewModel.voiceHint.collectAsState()
    val dark by viewModel.dark.collectAsState()

    Row(modifier = Modifier.fillMaxSize().background(colors.appBg)) {
        MkNavRail(
            items = tabItems(unread, rail = true),
            active = tab.key,
            onChange = { key -> Tab.entries.firstOrNull { it.key == key }?.let(viewModel::setTab) },
            brand = "M",
            footer = {
                MkVoiceButton(onClick = viewModel::onMic, size = MkVoiceSize.Lg)
            },
        )

        Column(modifier = Modifier.weight(1f).statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.appBg)
                    .padding(horizontal = MkSpacing.pagePadTablet, vertical = MkSpacing.x4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).combinedClickable(
                        onClick = {},
                        onLongClick = openDebug,
                    ),
                ) {
                    Text("Marmorikatu", style = type.kicker, color = colors.accent)
                    Text(tab.title ?: Fmt.greeting(), style = type.title, color = colors.inkHi)
                }
                MkIconButton(
                    icon = if (dark) MkIcons.Sun else MkIcons.Moon,
                    label = "Teema",
                    size = MkIconButtonSize.Lg,
                    onClick = viewModel::toggleTheme,
                )
                Spacer(Modifier.width(MkSpacing.x2))
                MkIconButton(
                    icon = MkIcons.GearSix,
                    label = "Asetukset",
                    size = MkIconButtonSize.Lg,
                    onClick = openSettings,
                )
                Spacer(Modifier.width(MkSpacing.x3))
                Text(Fmt.now(), style = type.readout(22), color = colors.inkHi)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MkSpacing.pagePadTablet - MkSpacing.pagePad),
            ) {
                ScreenHost(tab = tab)

                if (voice == VoiceState.Idle) {
                    MkVoiceDock(
                        onMic = viewModel::onMic,
                        state = voice,
                        hint = voiceHint,
                        transcript = voiceLine,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(MkSpacing.x3),
                    )
                }
            }

            if (voice != VoiceState.Idle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MkSpacing.pagePadTablet, vertical = MkSpacing.x3),
                ) {
                    MkVoiceDock(
                        onMic = viewModel::onMic,
                        state = voice,
                        hint = voiceHint,
                        transcript = voiceLine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
