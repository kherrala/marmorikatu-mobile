package fi.marmorikatu.app.shell

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxScope
import fi.marmorikatu.app.theme.rememberMkInteractionSource
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.components.MkIconButton
import fi.marmorikatu.app.components.MkIconButtonSize
import fi.marmorikatu.app.components.MkNavRail
import fi.marmorikatu.app.components.MkTabBar
import fi.marmorikatu.app.components.MkTabItem
import fi.marmorikatu.app.components.MkVoiceButton
import fi.marmorikatu.app.components.MkVoiceCommand
import fi.marmorikatu.app.components.MkVoiceDock
import fi.marmorikatu.app.components.MkVoiceQuickCommands
import fi.marmorikatu.app.components.MkVoiceSize
import fi.marmorikatu.app.components.VoiceState
import fi.marmorikatu.app.debug.DebugScreen
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.screens.BussitScreen
import fi.marmorikatu.app.screens.KalenteriScreen
import fi.marmorikatu.app.screens.EnergiaScreen
import fi.marmorikatu.app.screens.IlmastoScreen
import fi.marmorikatu.app.screens.KotiScreen
import fi.marmorikatu.app.screens.TabletKotiDashboard
import fi.marmorikatu.app.screens.TapahtumatScreen
import fi.marmorikatu.app.screens.ValotScreen
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
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
    heightDp: Int,
    viewModel: ShellViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }

    val uiSignals: UiSignals = koinInject()
    val detailOpen by uiSignals.detailOpen.collectAsState()

    // Route a tapped notification (kind → route, set from MainActivity) to its
    // view: switch tab, and for news also flag the Koti news reader to open.
    val pendingNav by uiSignals.pendingNav.collectAsState()
    LaunchedEffect(pendingNav) {
        val route = pendingNav ?: return@LaunchedEffect
        val target = if (route == "news") Tab.Koti
            else Tab.entries.firstOrNull { it.key == route } ?: Tab.Tapahtumat
        viewModel.setTab(target)
        if (route == "news") uiSignals.openNewsReader.value = true
        uiSignals.pendingNav.value = null
    }

    val surface by viewModel.surface.collectAsState()
    LaunchedEffect(widthDp, heightDp, detailOpen) {
        if (surface != Surface.Kid) {
            // The kiosk dashboard wants a wide canvas: a genuine tablet in either
            // orientation, or any device turned landscape (a phone on its side).
            // A real tablet (short side already wide) always stays on the kiosk.
            val isTablet = minOf(widthDp, heightDp) >= TABLET_MIN_WIDTH_DP
            // While a phone shows a detail chart it forces landscape only to give
            // the chart room — keep it on the phone surface so the whole app does
            // not swap to the kiosk just because it turned wide.
            val wide = isTablet || (!detailOpen && widthDp > heightDp)
            // A detail-forced rotation recreates the activity, and while the old
            // composition disposes, [detailOpen] reads false for a moment before
            // the recreated screen re-asserts it. Sit out that transient before
            // swapping a phone to the kiosk: acting on it would dispose the
            // detail and its landscape lock, snap back to portrait, re-open the
            // detail, and oscillate between the orientations forever. The effect
            // restarts (cancelling the wait) the instant detailOpen turns true.
            if (wide && !isTablet) delay(SURFACE_SETTLE_MS)
            viewModel.setSurface(if (wide) Surface.Tablet else Surface.Phone)
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

/**
 * How long a phone must stay wide with no detail open before it swaps to the
 * kiosk surface — long enough to sit out the detailOpen=false transient of a
 * detail-forced rotation (activity recreation), short enough that a real turn
 * to landscape still feels immediate.
 */
private const val SURFACE_SETTLE_MS = 500L

@Composable
private fun ScreenHost(tab: Tab, modifier: Modifier = Modifier, phone: Boolean = false) {
    Box(modifier = modifier) {
        when (tab) {
            Tab.Koti -> KotiScreen()
            Tab.Valot -> ValotScreen()
            // Only the phone instance forces a detail chart to landscape; the
            // kiosk is already wide.
            Tab.Ilmasto -> IlmastoScreen(forceLandscapeDetail = phone)
            Tab.Energia -> EnergiaScreen(forceLandscapeDetail = phone)
            Tab.Bussit -> BussitScreen()
            Tab.Kalenteri -> KalenteriScreen()
            Tab.Tapahtumat -> TapahtumatScreen()
        }
    }
}

/**
 * The "PIKAKOMENNOT" prompts offered while the assistant listens. Tapping one
 * sends its [MkVoiceCommand.prompt] straight to the assistant. (The news glyph
 * is a stand-in — Phosphor's newspaper icon is not yet in the icon set.)
 */
private val voiceQuickCommands = listOf(
    MkVoiceCommand(MkIcons.Info, "Lue uutiset", "Lue uutiset"),
    MkVoiceCommand(MkIcons.House, "Talon yhteenveto", "Anna talon yhteenveto"),
    MkVoiceCommand(MkIcons.Moon, "Iltavalot päälle", "Laita iltavalot päälle"),
    MkVoiceCommand(MkIcons.Drop, "Onko sauna päällä?", "Onko sauna päällä?"),
)

/**
 * The active-voice overlay: a full-screen "smokescreen" scrim (dim + tap to
 * close, per the design) with the quick-command panel and dock floating above
 * it. Renders nothing while idle. Must be placed in a fill-size [Box].
 */
@Composable
private fun BoxScope.VoiceOverlay(
    voice: VoiceState,
    voiceHint: String?,
    voiceLine: String?,
    onMic: () -> Unit,
    onDismiss: () -> Unit,
    onRunCommand: (String) -> Unit,
    horizontalPad: Dp,
    bottomPad: Dp,
) {
    if (voice == VoiceState.Idle) return
    // Smokescreen: dims the dashboard and dismisses the assistant on tap.
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color(0x99080A0E))
            .clickable(
                interactionSource = rememberMkInteractionSource(),
                indication = null,
                onClick = onDismiss,
            ),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = horizontalPad)
            .padding(bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(MkSpacing.x2),
    ) {
        if (voice == VoiceState.Listening) {
            MkVoiceQuickCommands(
                commands = voiceQuickCommands,
                onRun = { onRunCommand(it.prompt) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        MkVoiceDock(
            onMic = onMic,
            state = voice,
            hint = voiceHint,
            transcript = voiceLine,
            modifier = Modifier.fillMaxWidth(),
        )
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
    // A detail chart forces landscape and wants the whole height for the chart —
    // the app header is redundant there (Takaisin + the card's own title cover
    // it), so it collapses while a detail is open.
    val detailOpen by koinInject<UiSignals>().detailOpen.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.appBg)
            .statusBarsPadding(),
    ) {
        if (!detailOpen) {
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
        }

        // Each screen applies MkSpacing.pagePad itself; padding here too would
        // double it and push the header outside the content edges. Without the
        // header the content sits flush, so the -14 tuck only applies with it.
        Box(
            modifier = Modifier
                .weight(1f)
                .offset(y = if (detailOpen) 0.dp else (-14).dp)
                // With the tab bar hidden in a detail view, the content would run
                // under the system nav bar — inset it so nothing is occluded.
                .then(if (detailOpen) Modifier.navigationBarsPadding() else Modifier),
        ) {
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
                ScreenHost(tab = tabs[page], phone = true)
            }

            // Idle, the dock is just a mic button: it floats over the content
            // rather than reserving a row it does not need. Hidden in a detail
            // view, where the chart wants every pixel.
            if (voice == VoiceState.Idle && !detailOpen) {
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

        // The tab bar is hidden while a detail chart is open — it forces landscape
        // and wants the full canvas (design).
        if (!detailOpen) {
            MkTabBar(
                // Loki/Tapahtumat is dropped from the phone bar (design): it is
                // reached via the header bell, which already carries the unread badge.
                items = tabItems(unread, rail = false).filter { it.key != Tab.Tapahtumat.key },
                active = tab.key,
                onChange = { key -> Tab.entries.firstOrNull { it.key == key }?.let(viewModel::setTab) },
            )
        }
    }

        // The active-voice UI floats above the whole surface over its scrim, so
        // it sits on top of the content and tab bar rather than in its own row.
        VoiceOverlay(
            voice = voice,
            voiceHint = voiceHint,
            voiceLine = voiceLine,
            onMic = viewModel::onMic,
            onDismiss = viewModel::stopListening,
            onRunCommand = viewModel::runQuickCommand,
            horizontalPad = MkSpacing.pagePad,
            bottomPad = MkSpacing.tabBarHeight + MkSpacing.x2,
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

    // safeDrawingPadding keeps the rail and content clear of the status bar and,
    // crucially in landscape, the side/bottom navigation bar — the app background
    // still fills edge-to-edge behind the bars.
    Box(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.appBg)
            .safeDrawingPadding(),
    ) {
        MkNavRail(
            // Koti is reached via the "M" brand tile below, and Loki/Tapahtumat
            // via the header bell (design) — both dropped from the icon list.
            items = tabItems(unread, rail = true)
                .filter { it.key != Tab.Koti.key && it.key != Tab.Tapahtumat.key },
            active = tab.key,
            onChange = { key -> Tab.entries.firstOrNull { it.key == key }?.let(viewModel::setTab) },
            brand = "M",
            onBrandClick = { viewModel.setTab(Tab.Koti) },
            brandActive = tab == Tab.Koti,
            footer = {
                MkVoiceButton(onClick = viewModel::onMic, size = MkVoiceSize.Lg)
            },
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.appBg)
                    .padding(horizontal = MkSpacing.pagePadTablet, vertical = MkSpacing.x3),
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
                    size = MkIconButtonSize.Md,
                    onClick = viewModel::toggleTheme,
                )
                Spacer(Modifier.width(MkSpacing.x2))
                MkIconButton(
                    icon = MkIcons.BellFill,
                    label = "Tapahtumat",
                    size = MkIconButtonSize.Md,
                    badge = if (unread > 0) unread.toString() else null,
                    onClick = { viewModel.setTab(Tab.Tapahtumat) },
                )
                Spacer(Modifier.width(MkSpacing.x2))
                MkIconButton(
                    icon = MkIcons.GearSix,
                    label = "Asetukset",
                    size = MkIconButtonSize.Md,
                    onClick = openSettings,
                )
                Spacer(Modifier.width(MkSpacing.x3))
                Text(Fmt.now(), style = type.readout(22), color = colors.inkHi)
            }

            // The idle mic lives in the nav-rail footer on this surface, so the
            // content area does NOT float its own mic dock (that duplicated it,
            // showing a mic on both the left rail and the bottom-right).
            Box(modifier = Modifier.weight(1f)) {
                if (tab == Tab.Koti) {
                    // The design's bespoke kiosk dashboard, not the phone screen.
                    TabletKotiDashboard()
                } else {
                    // Other tabs reuse their screens, centred on the wide canvas.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 900.dp)
                                .fillMaxSize()
                                .padding(horizontal = MkSpacing.pagePadTablet - MkSpacing.pagePad),
                        ) {
                            ScreenHost(tab = tab)
                        }
                    }
                }
            }

        }
    }

        VoiceOverlay(
            voice = voice,
            voiceHint = voiceHint,
            voiceLine = voiceLine,
            onMic = viewModel::onMic,
            onDismiss = viewModel::stopListening,
            onRunCommand = viewModel::runQuickCommand,
            horizontalPad = MkSpacing.pagePadTablet,
            bottomPad = MkSpacing.x4,
        )
    }
}
