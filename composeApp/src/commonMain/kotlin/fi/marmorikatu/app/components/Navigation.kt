package fi.marmorikatu.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.rememberMkInteractionSource

/** One entry in a [MkTabBar] or [MkNavRail]. */
data class MkTabItem(
    val key: String,
    val label: String? = null,
    val icon: ImageVector,
    val iconActive: ImageVector? = null,
    val badge: String? = null,
)

/** Size ladder for the tab bar. */
enum class MkTabSize { Md, Kid }

/**
 * Phone bottom navigation: a hairline-topped bar over the app background that
 * tints the active item teal and swaps to its filled icon.
 */
@Composable
fun MkTabBar(
    items: List<MkTabItem>,
    active: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: MkTabSize = MkTabSize.Md,
) {
    val colors = MkTheme.colors
    val iconSize = if (size == MkTabSize.Kid) 30.dp else 22.dp
    val labelSize = if (size == MkTabSize.Kid) 12.sp else 9.5.sp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.appBg)
            .drawBehind {
                // spec: 1px border-top hairline.
                drawLine(
                    color = colors.borderSubtle,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(this.size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isActive = item.key == active
            val fg = if (isActive) colors.accent else colors.inkLo
            val glyph = if (isActive) item.iconActive ?: item.icon else item.icon
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MkRadius.sm))
                    .clickable(
                        interactionSource = rememberMkInteractionSource(),
                        indication = null,
                        onClick = { onChange(item.key) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = glyph,
                        contentDescription = item.label,
                        tint = fg,
                        modifier = Modifier.size(iconSize),
                    )
                    if (!item.badge.isNullOrEmpty()) {
                        NavBadge(
                            text = item.badge,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 11.dp, y = (-3).dp),
                        )
                    }
                }
                if (item.label != null) {
                    Text(
                        text = item.label,
                        style = TextStyle(
                            fontFamily = MkTheme.type.ui,
                            fontWeight = FontWeight.Medium,
                            fontSize = labelSize,
                            letterSpacing = 0.01.em,
                            color = fg,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Tablet / kiosk left rail: a narrow surface column with a brand logo tile,
 * navigation items, and a footer slot pinned to the bottom.
 */
@Composable
fun MkNavRail(
    items: List<MkTabItem>,
    active: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    brand: String = "M",
    onBrandClick: (() -> Unit)? = null,
    brandActive: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    val colors = MkTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(MkSpacing.railWidth)
            .background(colors.surfaceCard)
            .drawBehind {
                // spec: 1px border-right hairline.
                drawLine(
                    color = colors.borderSubtle,
                    start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(top = 20.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Logo tile. When [onBrandClick] is supplied it doubles as the Home
        // button — the "M" *is* Marmorikatu — so no separate house icon is
        // needed in the rail. A brighter ring marks it active.
        Box(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .size(54.dp)
                .clip(RoundedCornerShape(MkRadius.md))
                .background(colors.accentDim)
                .border(
                    width = if (brandActive) 2.dp else 1.dp,
                    color = if (brandActive) colors.accent else colors.accentBorder,
                    shape = RoundedCornerShape(MkRadius.md),
                )
                .then(
                    if (onBrandClick != null) {
                        Modifier.clickable(
                            interactionSource = rememberMkInteractionSource(),
                            indication = null,
                            onClick = onBrandClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = brand,
                style = TextStyle(
                    fontFamily = MkTheme.type.display,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = colors.accent,
                ),
            )
        }

        // The items live in a weighted, scrollable band so the logo (above) and
        // the footer mic (below) stay pinned and visible even when many tall
        // items at the kiosk's scaled density would otherwise overflow the rail.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                val isActive = item.key == active
                val fg = if (isActive) colors.accent else colors.inkLo
                val glyph = if (isActive) item.iconActive ?: item.icon else item.icon
                Box(
                    modifier = Modifier
                        .width(84.dp)
                        .defaultMinSize(minHeight = 68.dp)
                        .clip(RoundedCornerShape(MkRadius.md))
                        .background(if (isActive) colors.accentDim else Color.Transparent)
                        .clickable(
                            interactionSource = rememberMkInteractionSource(),
                            indication = null,
                            onClick = { onChange(item.key) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = glyph,
                            contentDescription = item.label,
                            tint = fg,
                            modifier = Modifier.size(26.dp),
                        )
                        if (item.label != null) {
                            Text(
                                text = item.label,
                                style = TextStyle(
                                    fontFamily = MkTheme.type.ui,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = fg,
                                ),
                            )
                        }
                    }
                    if (!item.badge.isNullOrEmpty()) {
                        NavBadge(
                            text = item.badge,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-9).dp, y = 3.dp),
                        )
                    }
                }
            }
        }

        footer()
    }
}

/** The alarm-red count pill shared by the tab bar and rail. */
@Composable
private fun NavBadge(text: String, modifier: Modifier = Modifier) {
    val colors = MkTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
            .clip(CircleShape)
            .background(colors.statusAlarm)
            .border(2.dp, colors.appBg, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = MkTheme.type.mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 8.sp,
                color = Color.White,
            ),
        )
    }
}
