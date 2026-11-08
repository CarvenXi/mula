package dev.dash.mula

import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutScreen(onBack: () -> Unit, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val kernel = webViewVersion() ?: "未知"
    val ua = remember { runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("未知") }

    val pageBackdrop = rememberLayerBackdrop()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val surface = MiuixTheme.colorScheme.surface
    val iconShape = RoundedCornerShape(26.dp)

    Box(modifier = Modifier.fillMaxSize().background(surface)) {
        // ---- 整页背景：铺满全屏的主题色渐变，供玻璃图标与顶栏采样 ----
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
            Box(modifier = Modifier.fillMaxSize().background(surface))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to MiuixTheme.colorScheme.primary.copy(alpha = 0.38f),
                            0.45f to Color.Transparent,
                            1f to MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(top = statusTop + 56.dp)
                .padding(bottom = 24.dp),
        ) {
            // ---- 头部：居中磨砂图标 + 应用名 ----
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .then(
                            if (blurEnabled) {
                                Modifier.textureBlurBar(
                                    backdrop = pageBackdrop,
                                    shape = iconShape,
                                    tint = Color.White.copy(alpha = 0.22f),
                                    radius = 42f,
                                    highlight = Highlight.GlassStrokeMiddleLight,
                                )
                            } else {
                                Modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh, iconShape)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                        modifier = Modifier.size(58.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Mula",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            SmallTitle(text = "版本")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                insideMargin = PaddingValues(0.dp),
            ) {
                AboutRow("MIUIX", "0.9.4")
                AboutRow("内核版本", kernel)
            }
            CheckUpdateButton()

            SmallTitle(text = "本机 UA")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                Text(
                    text = ua,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            SmallTitle(text = "项目")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                insideMargin = PaddingValues(0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/CarvenXi/mafari")),
                                )
                            }
                        }
                        .padding(16.dp),
                ) {
                    Text("项目地址", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "https://github.com/CarvenXi/mafari",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "Mula · 基于 MIUIX 与系统 WebView",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp + statusTop)
                .progressiveTopBar(
                    backdrop = pageBackdrop,
                    tint = MiuixTheme.colorScheme.surface.copy(alpha = 0.55f),
                    enabled = blurEnabled,
                ),
        ) {
            SmallTopAppBar(
                title = "关于",
                color = Color.Transparent,
                navigationIcon = {
                    GlassBackButton(backdrop = pageBackdrop, onClick = onBack)
                },
                defaultWindowInsetsPadding = false,
                modifier = Modifier.statusBarsPadding(),
            )
        }
    }
}

@Composable
private fun AboutRow(title: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
