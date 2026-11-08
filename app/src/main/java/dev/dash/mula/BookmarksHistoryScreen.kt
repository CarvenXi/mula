package dev.dash.mula

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

@Composable
fun BookmarksHistoryScreen(
    favorites: FavoritesStore,
    history: HistoryStore,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    blurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0=书签，1=历史
    var showActions by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.onSuccess { html ->
            if (html == null) Toast.makeText(context, "无法读取书签文件", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, "已导入 ${favorites.importHtml(html)} 个书签", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(favorites.exportHtml()) }
                ?: error("无法写入文件")
        }.onSuccess { Toast.makeText(context, "书签已导出", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxSize().layerBackdrop(backdrop).navigationBarsPadding()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp)
        ) {
            // 顶部双栏切换：任意时刻只显示一个页面，不再并排挤压内容。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("书签", "历史").forEachIndexed { index, label ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (selectedTab == index) MiuixTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontSize = 14.sp,
                            color = if (selectedTab == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            if (selectedTab == 0) {
                if (favorites.items.isEmpty()) {
                    EmptyPane("暂无书签")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 92.dp),
                    ) {
                        items(favorites.items.toList()) { item ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { onOpen(item.url) }.padding(12.dp)
                            ) {
                                Text(item.name, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(item.url, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Text("编辑", fontSize = 11.sp, color = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            editIndex = favorites.items.indexOf(item); name = item.name; url = item.url; showEditor = true
                                        }.padding(6.dp))
                                    Text("删除", fontSize = 11.sp, color = MiuixTheme.colorScheme.error,
                                        modifier = Modifier.clickable { favorites.remove(favorites.items.indexOf(item)) }.padding(6.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                HistoryListContent(
                    store = history,
                    onOpen = onOpen,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(56.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .progressiveTopBar(backdrop = backdrop, tint = MiuixTheme.colorScheme.surface.copy(alpha = .58f), enabled = blurEnabled)
        ) {
            SmallTopAppBar(
                title = "书签与历史",
                color = Color.Transparent,
                navigationIcon = { GlassBackButton(backdrop = backdrop, onClick = onBack) },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(onClick = { exportPicker.launch("mula-bookmarks.html") }) {
                            Text("导出", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                        }
                    } else if (history.items.isNotEmpty()) {
                        IconButton(onClick = { history.clear() }) { Icon(MiuixIcons.Delete, contentDescription = "清空历史") }
                    }
                },
                defaultWindowInsetsPadding = false,
                modifier = Modifier.statusBarsPadding(),
            )
        }
        if (selectedTab == 0) Box(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 22.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .size(54.dp).clip(CircleShape)
                .textureBlurBar(
                    backdrop = backdrop,
                    shape = CircleShape,
                    tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.62f),
                    radius = 34f,
                    noise = 0.04f,
                    highlight = top.yukonga.miuix.kmp.blur.highlight.Highlight.GlassStrokeMiddleLight,
                )
                .clickable { showActions = true },
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = 30.sp, color = Color.White) }
    }

    OverlayBottomSheet(show = showActions, title = "书签", onDismissRequest = { showActions = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("新建书签", Modifier.fillMaxWidth().clickable {
                showActions = false; editIndex = -1; name = ""; url = ""; showEditor = true
            }.padding(16.dp), color = MiuixTheme.colorScheme.onSurface)
            Text("导入书签（Chrome / Firefox / Via）", Modifier.fillMaxWidth().clickable {
                showActions = false; importPicker.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain"))
            }.padding(16.dp), color = MiuixTheme.colorScheme.onSurface)
            Text("导出书签", Modifier.fillMaxWidth().clickable {
                showActions = false; exportPicker.launch("mula-bookmarks.html")
            }.padding(16.dp), color = MiuixTheme.colorScheme.onSurface)
        }
    }
    OverlayBottomSheet(show = showEditor, title = if (editIndex >= 0) "编辑书签" else "新建书签", onDismissRequest = { showEditor = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            TextField(value = name, onValueChange = { name = it }, label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            TextField(value = url, onValueChange = { url = it }, label = "网址", singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            TextButton(text = "保存", modifier = Modifier.fillMaxWidth(), onClick = {
                if (url.isNotBlank()) {
                    if (editIndex >= 0) favorites.update(editIndex, name, url) else favorites.add(name, url)
                    showEditor = false
                } else Toast.makeText(context, "请输入网址", Toast.LENGTH_SHORT).show()
            }, colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun EmptyPane(message: String) {
    Box(Modifier.fillMaxWidth().padding(top = 34.dp), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
