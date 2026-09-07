package com.tools.wereply

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    /** 从系统设置页返回时靠它触发权限状态重查 */
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SuggestionNotifier.ensureChannels(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent { MaterialTheme { Screen(tick = refreshTick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Screen(tick: Int) {

    val ctx = LocalContext.current

    var enabled by remember { mutableStateOf(Prefs.enabled) }
    var autoSend by remember { mutableStateOf(Prefs.autoSend) }
    var useA11y by remember { mutableStateOf(Prefs.useAccessibility) }
    var autoClickSend by remember { mutableStateOf(Prefs.autoClickSend) }
    var watchWx by remember { mutableStateOf(Prefs.watchWeChat) }
    var watchDy by remember { mutableStateOf(Prefs.watchDouyin) }
    var baseUrl by remember { mutableStateOf(Prefs.baseUrl) }
    var apiKey by remember { mutableStateOf(Prefs.apiKey) }
    var model by remember { mutableStateOf(Prefs.model) }
    var persona by remember { mutableStateOf(Prefs.persona) }
    var tones by remember { mutableStateOf(Prefs.selectedTones) }
    var customTone by remember { mutableStateOf(Prefs.customTone) }
    var whitelistAll by remember { mutableStateOf(Prefs.whitelistAll) }
    var whitelist by remember { mutableStateOf(Prefs.whitelist.joinToString("\n")) }
    var quietStart by remember { mutableStateOf(Prefs.quietStartHour.toString()) }
    var quietEnd by remember { mutableStateOf(Prefs.quietEndHour.toString()) }
    var minDelay by remember { mutableStateOf(Prefs.minDelaySec.toString()) }
    var maxDelay by remember { mutableStateOf(Prefs.maxDelaySec.toString()) }
    var perHour by remember { mutableStateOf(Prefs.maxPerHour.toString()) }

    /** 手动确认项被点过之后也要立刻刷新，所以额外挂一个本地计数 */
    var manualTick by remember { mutableIntStateOf(0) }

    val items = remember(tick, useA11y, manualTick) { SetupGuide.items(ctx, useA11y) }
    val doneCount = items.count { it.done }
    val allDone = doneCount == items.size

    Scaffold(topBar = { TopAppBar(title = { Text("私信助理") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ---------------- 准备工作 ----------------
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (allDone)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "准备工作",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text("$doneCount / ${items.size}", style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(
                        progress = { if (items.isEmpty()) 0f else doneCount.toFloat() / items.size },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                    Text(
                        if (allDone) "都齐了，可以打开总开关了。"
                        else "下面这些没弄完，程序不会正常工作。返回本页会自动重新检测。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    items.forEach { item ->
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (item.done) "✓" else "✗",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            item.jumpLabel?.let { label ->
                                Button(
                                    onClick = { SetupGuide.jump(ctx, item.key) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                ) { Text(label) }
                            }
                        }

                        // 已完成的收起来，只有没完成的才展开具体怎么点
                        if (!item.done) {
                            Text(
                                item.why,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 26.dp, top = 4.dp)
                            )
                            Text(
                                item.path,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 26.dp, top = 4.dp)
                            )
                            item.manualKey?.let { mk ->
                                TextButton(
                                    onClick = {
                                        Prefs.setConfirmed(mk, true)
                                        manualTick++
                                    },
                                    modifier = Modifier.padding(start = 18.dp)
                                ) { Text("我按上面弄好了") }
                            }
                        } else {
                            val mk = item.manualKey
                            if (mk != null) {
                                TextButton(
                                    onClick = {
                                        Prefs.setConfirmed(mk, false)
                                        manualTick++
                                    },
                                    modifier = Modifier.padding(start = 18.dp)
                                ) { Text("重新标记为未完成") }
                            }
                        }
                    }
                }
            }

            // ---------------- ① 工作模式 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("① 工作模式", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(useA11y, { useA11y = it; Prefs.useAccessibility = it })
                        Spacer(Modifier.width(10.dp))
                        Text(if (useA11y) "无障碍模式" else "通知模式")
                    }
                    Text(
                        if (useA11y)
                            "读聊天窗口里的可见消息，上下文完整，抖音也能用。聊天页会出现一个可拖动的悬浮球，" +
                                "点一下生成候选，再点一条填进输入框。代价是行为特征明显，风险比通知模式高。"
                        else
                            "只靠通知栏的快捷回复通道，不碰微信界面，最不容易被发现。" +
                                "代价是拿不到聊天历史，群聊和合并通知都抓不到。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "切换模式后上面的准备工作清单会跟着变，两种模式要开的权限不一样。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (useA11y) {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(autoClickSend, { autoClickSend = it; Prefs.autoClickSend = it })
                            Spacer(Modifier.width(10.dp))
                            Text("填完顺手点发送")
                        }
                        Text(
                            "建议关着。关掉时只把文字填进输入框，发送键你自己按 —— 留一道人工闸门，" +
                                "行为上也最不像机器。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---------------- ② AI 接口 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("② AI 接口", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it; Prefs.baseUrl = it },
                        label = { Text("接口地址（OpenAI 兼容格式）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; Prefs.apiKey = it },
                        label = { Text("API Key") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it; Prefs.model = it },
                        label = { Text("模型名") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "默认填的是 DeepSeek。通义、Kimi、智谱的兼容接口改一下地址和模型名就能用。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------------- ③ 人设 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("③ 人设", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = persona, onValueChange = { persona = it; Prefs.persona = it },
                        label = { Text("你平时怎么说话") },
                        minLines = 4, modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "写得越具体越像你。口头禅、爱用的语气词、平时几点下播，都可以写进去。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------------- ④ 表达风格 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("④ 表达风格", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "勾几种就出几条，每种风格各一条，横向对照着挑。" +
                            "只勾一种就是同一风格出 3 条不同写法。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tone.entries.forEach { t ->
                            FilterChip(
                                selected = t.id in tones,
                                onClick = {
                                    val next = if (t.id in tones) tones - t.id else tones + t.id
                                    tones = next
                                    Prefs.selectedTones = next
                                },
                                label = { Text(t.label) }
                            )
                        }
                    }
                    Tone.entries.filter { it.id in tones && it != Tone.CUSTOM }.forEach { t ->
                        Text("${t.label} · ${t.hint}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (Tone.CUSTOM.id in tones) {
                        OutlinedTextField(
                            value = customTone,
                            onValueChange = { customTone = it; Prefs.customTone = it },
                            label = { Text("自定义风格怎么写") },
                            minLines = 2, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (Tone.SHARP.id in tones) {
                        HorizontalDivider()
                        Text(
                            "尖酸这条不参与全自动。就算开了直接发，尖酸的候选也只会推给你，" +
                                "要发得自己点。让机器自己决定什么时候呛人，出事概率太高。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---------------- ⑤ 回复谁 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⑤ 回复谁", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(whitelistAll, { whitelistAll = it; Prefs.whitelistAll = it })
                        Spacer(Modifier.width(10.dp))
                        Text("所有单聊都处理")
                    }
                    OutlinedTextField(
                        value = whitelist,
                        onValueChange = {
                            whitelist = it
                            Prefs.whitelist = it.lines().map(String::trim).filter(String::isNotBlank).toSet()
                        },
                        label = { Text("白名单昵称，一行一个") },
                        minLines = 3, modifier = Modifier.fillMaxWidth(),
                        enabled = !whitelistAll
                    )
                    Text(
                        "默认白名单为空，也就是谁都不回。群聊一律跳过。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------------- ⑥ 节流 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⑥ 节流", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("免打扰起", quietStart, Modifier.weight(1f)) {
                            quietStart = it; it.toIntOrNull()?.let { v -> Prefs.quietStartHour = v }
                        }
                        NumField("免打扰止", quietEnd, Modifier.weight(1f)) {
                            quietEnd = it; it.toIntOrNull()?.let { v -> Prefs.quietEndHour = v }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("最短延迟(秒)", minDelay, Modifier.weight(1f)) {
                            minDelay = it; it.toIntOrNull()?.let { v -> Prefs.minDelaySec = v }
                        }
                        NumField("最长延迟(秒)", maxDelay, Modifier.weight(1f)) {
                            maxDelay = it; it.toIntOrNull()?.let { v -> Prefs.maxDelaySec = v }
                        }
                    }
                    NumField("每小时上限", perHour, Modifier.fillMaxWidth()) {
                        perHour = it; it.toIntOrNull()?.let { v -> Prefs.maxPerHour = v }
                    }
                }
            }

            // ---------------- ⑦ 开关 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⑦ 开关", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(watchWx, { watchWx = it; Prefs.watchWeChat = it })
                        Spacer(Modifier.width(10.dp)); Text("监听微信")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(watchDy, { watchDy = it; Prefs.watchDouyin = it })
                        Spacer(Modifier.width(10.dp)); Text("监听抖音")
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(autoSend, { autoSend = it; Prefs.autoSend = it })
                        Spacer(Modifier.width(10.dp)); Text("全自动直接发")
                    }
                    Text(
                        "建议关着。关掉时 AI 出候选，你点一下才发，既不会说错话，" +
                            "也不容易被判定为机器行为。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(enabled, { enabled = it; Prefs.enabled = it })
                        Spacer(Modifier.width(10.dp))
                        Text("总开关", style = MaterialTheme.typography.titleMedium)
                    }
                    if (enabled && !allDone) {
                        Text(
                            "准备工作还差 ${items.size - doneCount} 项，现在打开也不会工作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---------------- 拦截说明 ----------------
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("固定拦截的话题", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "涉及钱（转账、打赏、借钱、投资、平台充值）、见面约会、联系方式和地址、" +
                            "感情承诺、索要照片视频的消息，一律不生成回复，只提醒你自己看。" +
                            "另外任何风格下都拦辱骂和人身攻击。" +
                            "这些没有开关，AI 生成的内容也会再过一遍同样的规则。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
