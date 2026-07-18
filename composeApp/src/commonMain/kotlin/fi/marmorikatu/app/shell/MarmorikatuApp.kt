package fi.marmorikatu.app.shell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import fi.marmorikatu.app.components.MkAssistantAvatar
import fi.marmorikatu.app.components.frameOsc
import fi.marmorikatu.app.components.rememberFrameMillis
import fi.marmorikatu.app.theme.MkDot
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.core.config.AssistantGender
import fi.marmorikatu.app.theme.mkPressScale
import fi.marmorikatu.app.theme.rememberMkInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Icon
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
import fi.marmorikatu.app.components.rememberGreeting
import fi.marmorikatu.app.components.rememberWallClock
import fi.marmorikatu.app.debug.DebugScreen
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
    MkVoiceCommand(MkIcons.Lightning, "Sähkön hinta", "Paljonko sähkö maksaa nyt?"),
    // "Tehosta ilmanvaihtoa" dropped — the assistant has no ventilation-control
    // tool. "Sammuta kaikki valot" is something it can actually do (set_all_lights).
    MkVoiceCommand(MkIcons.Power, "Sammuta kaikki valot", "Sammuta kaikki valot"),
)

/**
 * The active-voice overlay: a full-screen takeover with the animated assistant
 * avatar up top and, below it, the state-specific body — quick commands while
 * listening, a thinking indicator, or the spoken transcript. Renders nothing
 * while idle. Must be placed in a fill-size [Box].
 */
@Composable
private fun BoxScope.VoiceOverlay(
    voice: VoiceState,
    voiceHint: String?,
    gender: AssistantGender,
    stream: List<VoiceStreamItem>,
    kiosk: Boolean,
    onMic: () -> Unit,
    onDismiss: () -> Unit,
    onRunCommand: (String) -> Unit,
) {
    if (voice == VoiceState.Idle) return
    val colors = MkTheme.colors

    Box(
        modifier = Modifier
            .matchParentSize()
            .background(colors.appBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val glow = Brush.radialGradient(listOf(colors.accent.copy(alpha = 0.14f), Color.Transparent))
        if (kiosk) {
            // Kiosk: two columns — avatar on the left, conversation on the right.
            Row(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxWidth(0.42f).fillMaxHeight().background(glow),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    MkAssistantAvatar(
                        state = voice,
                        gender = gender,
                        modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 520.dp).aspectRatio(320f / 310f),
                    )
                    VoiceStatePill(voice, Modifier.align(Alignment.TopStart).padding(22.dp))
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MkIconButton(icon = MkIcons.X, label = "Sulje", onClick = onDismiss, round = true)
                    }
                    VoiceOverlayContent(
                        voice, voiceHint, stream, kiosk = true, onRunCommand,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    BottomButton(voice, onMic, onDismiss)
                }
            }
        } else {
            // Phone: a vertical stack — avatar high, content, control at the bottom.
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoiceStatePill(voice)
                    MkIconButton(icon = MkIcons.X, label = "Sulje", onClick = onDismiss, round = true)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .padding(horizontal = MkSpacing.pagePad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Spacer(Modifier.height(MkSpacing.x2))
                    Box(Modifier.fillMaxWidth().background(glow), contentAlignment = Alignment.Center) {
                        MkAssistantAvatar(
                            state = voice,
                            gender = gender,
                            modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(320f / 310f),
                        )
                    }
                    Spacer(Modifier.height(MkSpacing.x3))
                    VoiceOverlayContent(
                        voice, voiceHint, stream, kiosk = false, onRunCommand,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = MkSpacing.x4, top = MkSpacing.x2),
                    contentAlignment = Alignment.Center,
                ) {
                    BottomButton(voice, onMic, onDismiss)
                }
            }
        }
    }
}

/** The state pill (KUUNTELEN / MIETIN… / PUHUN / VALMIS) with a breathing dot. */
@Composable
private fun VoiceStatePill(voice: VoiceState, modifier: Modifier = Modifier) {
    val colors = MkTheme.colors
    Row(
        // Rounded background/border applied directly (no clip) so the pill shape
        // can never crop the label text.
        modifier = modifier
            .background(colors.surfaceCard, RoundedCornerShape(MkRadius.round))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.round))
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MkDot(color = colors.accent, size = 7.dp, breathing = voice != VoiceState.Ready)
        Text(
            text = when (voice) {
                VoiceState.Listening -> "KUUNTELEN"
                VoiceState.Thinking -> "MIETIN…"
                VoiceState.Speaking -> "PUHUN"
                else -> "VALMIS"
            },
            style = MkTheme.type.readout(10),
            color = colors.accent,
            maxLines = 1,
        )
    }
}

/** The bottom control: PUHU to continue when resting, LOPETA to stop when active. */
@Composable
private fun BottomButton(voice: VoiceState, onMic: () -> Unit, onDismiss: () -> Unit) {
    if (voice == VoiceState.Ready) {
        MkVoiceButton(onClick = onMic, label = "PUHU")
    } else {
        MkVoiceButton(onClick = onDismiss, label = "LOPETA")
    }
}

/**
 * State-specific overlay body, filling the space between the avatar/bar and the
 * bottom control. Listening scrolls (equalizer + prompt + quick grid) so it
 * fits short landscape; the other states let the stream's own list scroll.
 */
@Composable
private fun VoiceOverlayContent(
    voice: VoiceState,
    voiceHint: String?,
    stream: List<VoiceStreamItem>,
    kiosk: Boolean,
    onRunCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    val hAlign = if (kiosk) Alignment.Start else Alignment.CenterHorizontally
    val tAlign = if (kiosk) TextAlign.Start else TextAlign.Center
    when (voice) {
        VoiceState.Listening -> Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = hAlign,
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            VoiceEqualizer()
            Text(
                text = voiceHint?.takeIf { it.isNotBlank() }
                    ?: "Puhu nyt — sano esimerkiksi ”laita keittiöön täydet valot”",
                style = if (kiosk) type.body.copy(fontSize = 22.sp, lineHeight = 30.sp) else type.body,
                color = colors.inkHi,
                textAlign = tAlign,
                modifier = Modifier.fillMaxWidth(),
            )
            VoiceQuickGrid(onRunCommand = onRunCommand)
        }
        VoiceState.Thinking -> Column(
            modifier = modifier,
            horizontalAlignment = hAlign,
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            ThinkingDots()
            voiceHint?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = type.readout(11), color = colors.inkLo, textAlign = tAlign)
            }
            if (stream.isNotEmpty()) VoiceStream(stream, kiosk, Modifier.weight(1f, fill = false))
        }
        // Speaking + Ready (VALMIS): the stream (its list scrolls), plus the quick
        // grid under it while resting.
        else -> Column(
            modifier = modifier,
            horizontalAlignment = hAlign,
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            VoiceStream(stream, kiosk, Modifier.weight(1f, fill = false))
            if (voice == VoiceState.Ready) VoiceQuickGrid(onRunCommand = onRunCommand)
        }
    }
}

/** The lightbulb quick-actions FAB that opens [LightsQuickSheet]. */
@Composable
private fun LightbulbFab(onClick: () -> Unit) {
    val colors = MkTheme.colors
    val interaction = rememberMkInteractionSource()
    Box(
        modifier = Modifier
            .mkPressScale(interaction, pressed = 0.94f)
            .size(56.dp)
            .clip(CircleShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderSubtle, CircleShape)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MkIcons.LightbulbFill,
            contentDescription = "Pikatoiminnot · Valot",
            tint = colors.accent,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Five bars bouncing while the assistant listens (the design's mk-eq). */
@Composable
private fun VoiceEqualizer() {
    val colors = MkTheme.colors
    val ms by rememberFrameMillis()
    Row(
        modifier = Modifier.height(26.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val periods = listOf(560f, 460f, 640f, 520f, 600f)
        val phases = listOf(0f, 90f, 180f, 60f, 240f)
        periods.forEachIndexed { i, period ->
            val h = 0.28f + 0.72f * frameOsc(ms + phases[i], period)
            Box(
                Modifier
                    .width(4.dp)
                    .height(24.dp * h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent),
            )
        }
    }
}

/** Three pulsing dots while the assistant thinks. */
@Composable
private fun ThinkingDots() {
    val colors = MkTheme.colors
    val ms by rememberFrameMillis()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = MkSpacing.x2),
    ) {
        listOf(0f, 180f, 360f).forEach { phase ->
            val a = 0.3f + 0.7f * frameOsc(ms + phase, 1100f)
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accent.copy(alpha = a)),
            )
        }
    }
}

/**
 * The conversation stream: the newest sentence/request is big and bright at the
 * top; earlier items slide down and fade stepwise (0.8 → 0.3) as new ones arrive.
 * User requests are shown quoted in the accent colour, responses in ink. Uses
 * the display face (Space Grotesk) — 18.5sp phone / 27sp kiosk for the newest.
 */
@Composable
private fun VoiceStream(items: List<VoiceStreamItem>, kiosk: Boolean, modifier: Modifier = Modifier) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    // Newest is at index 0; keep it scrolled into view as items arrive so the
    // latest sentence is always visible at the top (the list otherwise holds its
    // position, leaving the new item off-screen above the fold).
    val listState = rememberLazyListState()
    LaunchedEffect(items.firstOrNull()?.seq) {
        if (items.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (kiosk) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.seq }) { i, item ->
            val faded = if (i == 0) 1f else (0.8f - (i - 1) * 0.12f).coerceIn(0.3f, 0.8f)
            val big = i == 0
            val size = when {
                big && kiosk -> 27f
                big -> 18.5f
                kiosk -> 18f
                else -> 15f
            }
            Text(
                text = if (item.isUser) "”${item.text}”" else item.text,
                style = TextStyle(
                    fontFamily = type.display,
                    fontWeight = if (big) FontWeight.Medium else FontWeight.Normal,
                    fontSize = size.sp,
                    lineHeight = (size * 1.4f).sp,
                ),
                color = if (item.isUser) colors.accent else colors.inkHi,
                textAlign = if (kiosk) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(faded).animateItem(),
            )
        }
    }
}

/** The 2×2 quick-command grid shown while listening (design's PIKAKOMENNOT). */
@Composable
private fun VoiceQuickGrid(onRunCommand: (String) -> Unit) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "PIKAKOMENNOT",
            style = type.readout(10),
            color = colors.inkLo,
            modifier = Modifier.align(Alignment.Start),
        )
        voiceQuickCommands.chunked(2).forEach { rowCmds ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCmds.forEach { cmd ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(MkRadius.md))
                            .background(colors.surfaceRaised)
                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.md))
                            .clickable { onRunCommand(cmd.prompt) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(cmd.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Text(
                            cmd.label,
                            style = type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = colors.inkHi,
                            maxLines = 1,
                        )
                    }
                }
                if (rowCmds.size == 1) Spacer(Modifier.weight(1f))
            }
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
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
    val voiceHint by viewModel.voiceHint.collectAsState()
    val assistantGender by viewModel.assistantGender.collectAsState()
    val voiceStream by viewModel.stream.collectAsState()
    val dark by viewModel.dark.collectAsState()
    // A detail chart forces landscape and wants the whole height for the chart —
    // the app header is redundant there (Takaisin + the card's own title cover
    // it), so it collapses while a detail is open.
    val detailOpen by koinInject<UiSignals>().detailOpen.collectAsState()

    // The fold-in light quick-actions sheet, opened from the lightbulb FAB that
    // sits beside the mic on every tab.
    var showLights by remember { mutableStateOf(false) }
    if (showLights) {
        BackHandler { showLights = false }
        LightsQuickSheet(onDismiss = { showLights = false })
    }

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
                    text = tab.title ?: rememberGreeting(),
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

            // Idle, the bottom dock is the lightbulb quick-actions FAB + the mic
            // button, floating over the content rather than reserving a row.
            // Hidden in a detail view, where the chart wants every pixel.
            if (voice == VoiceState.Idle && !detailOpen) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = MkSpacing.pagePad, vertical = MkSpacing.x2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightbulbFab(onClick = { showLights = true })
                    MkVoiceButton(onClick = viewModel::onMic, size = MkVoiceSize.Lg)
                }
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
            gender = assistantGender,
            stream = voiceStream,
            kiosk = false,
            onMic = viewModel::onMic,
            onDismiss = viewModel::stopListening,
            onRunCommand = viewModel::runQuickCommand,
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
    val voiceHint by viewModel.voiceHint.collectAsState()
    val assistantGender by viewModel.assistantGender.collectAsState()
    val voiceStream by viewModel.stream.collectAsState()
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
                    Text(tab.title ?: rememberGreeting(), style = type.title, color = colors.inkHi)
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
                Text(rememberWallClock(), style = type.readout(22), color = colors.inkHi)
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
            gender = assistantGender,
            stream = voiceStream,
            kiosk = true,
            onMic = viewModel::onMic,
            onDismiss = viewModel::stopListening,
            onRunCommand = viewModel::runQuickCommand,
        )
    }
}
