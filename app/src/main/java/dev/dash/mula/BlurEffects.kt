package dev.dash.mula

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.noiseDither
import top.yukonga.miuix.kmp.blur.progressiveBlur
import top.yukonga.miuix.kmp.blur.textureBlurEffect
import top.yukonga.miuix.kmp.blur.highlight.Highlight

/**
 * 顶栏渐进模糊：MIUIX progressiveBlur，方向自上而下。
 * 只用在纯 Compose 页面（设置 / 脚本 / 下载 / 关于），不要用在 WebView 上。
 */
@Composable
fun Modifier.progressiveTopBar(
    backdrop: Backdrop,
    shape: Shape = RectangleShape,
    tint: Color = Color.Unspecified,
    radius: Float = 30f,
    startFraction: Float = 0f,
    endFraction: Float = 1f,
    curve: Float = 0.68f,
    noise: Float = 0f,
    highlight: Highlight? = null,
    enabled: Boolean = true,
): Modifier {
    // 关闭模糊时退回纯色顶栏，避免半透明底色透出滚动内容
    if (!enabled) return background(if (tint == Color.Unspecified) Color.Transparent else tint.copy(alpha = 1f), shape)
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                progressiveBlur(
                    radius.dp.toPx(),
                    radius.dp.toPx(),
                    ProgressiveBlur(90f, startFraction, endFraction, curve),
                )
                noiseDither(noise)
            },
            highlight = { highlight },
        )
        .background(if (tint == Color.Unspecified) Color.Transparent else tint, shape)
}

/** 静态表面的纹理模糊（主页卡片等，不含 WebView） */
@Composable
fun Modifier.textureBlurBar(
    backdrop: Backdrop,
    shape: Shape = RectangleShape,
    tint: Color = Color.Unspecified,
    radius: Float = 22f,
    noise: Float = 0.06f,
    highlight: Highlight? = null,
): Modifier = this
    .drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            textureBlurEffect(
                blurRadiusX = radius.dp.toPx(),
                blurRadiusY = radius.dp.toPx(),
                noiseCoefficient = noise,
            )
        },
        highlight = { highlight },
    )
    .background(if (tint == Color.Unspecified) Color.Transparent else tint, shape)
