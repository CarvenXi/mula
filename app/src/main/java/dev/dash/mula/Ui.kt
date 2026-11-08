package dev.dash.mula

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 统一的「图标 + 文字」小按钮（MIUIX 配色，高 32dp） */
@Composable
fun IconTextAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (primary) Color.White else MiuixTheme.colorScheme.onSurface
    val background = if (primary) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, fontSize = 12.sp, color = contentColor, maxLines = 1)
    }
}


/** 跟随 MIUIX 的 color mode 判断深色，而不是直接读系统深色。 */
@Composable
fun isAppDarkTheme(): Boolean = when (MiuixTheme.colorSchemeMode) {
    ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
    ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
    else -> isSystemInDarkTheme()
}

/** OS4 风格的柔光玻璃返回按钮。 */
@Composable
fun GlassBackButton(backdrop: top.yukonga.miuix.kmp.blur.Backdrop, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(40.dp)
            .then(
                Modifier.textureBlurBar(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(50),
                    tint = Color.White.copy(alpha = if (isAppDarkTheme()) 0.10f else 0.30f),
                    radius = 26f,
                    noise = 0.035f,
                    highlight = top.yukonga.miuix.kmp.blur.highlight.Highlight.GlassStrokeMiddleLight,
                ),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}
