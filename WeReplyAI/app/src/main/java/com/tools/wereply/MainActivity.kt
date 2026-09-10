package com.tools.wereply

import android.content.Intent
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

    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SuggestionNotifier.ensureChannels(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            MaterialTheme {
                Screen(
                    tick = refreshTick.intValue,
                    onStartBall = { startBall() },
                    onStopBall = { stopBall() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    private fun startBall() {
        startForegroundService(Intent(this, ClipboardBallService::class.java))
        refreshTick.intValue++
    }

    private fun stopBall() {
        startService(
            Intent(this, ClipboardBallService::class.java)
                .setAction(ClipboardBallService.ACTION_STOP)
        )
        refreshTick.intValue++
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Screen(tick: Int, onStartBall: () -> Unit, onStopBall: () -> Unit) {

    val ctx = LocalContext.current

    var mode by remember { mutableStateOf(Prefs.mode) }
    var showAdvanced by remember { mutableStateOf(Prefs.showAdvanced) }
    var enabled by remember { mutableStateOf(Prefs.enabled) }
    var autoSend by remember { mutableStateOf(Prefs.autoSend) }
    var watchWx by remember { mutableStateOf(Prefs.watchWeChat) }
    var watchDy by remember { mutableStateOf(Prefs.watchDouyin) }
    var baseUrl by remember { mutableStateOf(Prefs.baseUrl) }
    var apiKey by remember { mutableStateOf(Prefs.apiKey) }
    var model by remember { mutableStateOf(Prefs.model) }
    var persona by remember { mutableStateOf(Prefs.persona) }
    var myNick by remember { mutableStateOf(Prefs.myNickname) }
    var tones by remember { mutableStateOf(Prefs.selectedTones) }
    var customTone by remember { mutableStateOf(Prefs.customTone) }
    var useBank by remember { mutableStateOf(Prefs.useReplyBank) }
    var whitelistAll by remember { mutableStateOf(Prefs.whitelistAll) }
    var whitelist by remember { mutableStateOf(Prefs.whitelist.joinToString("\n")) }
    var quietStart by remember { mutableStateOf(Prefs.quietStartHour.toString()) }
    var quietEnd by remember { mutableStateOf(Prefs.quietEndHour.toString()) }
    var minDelay by remember { mutableStateOf(Prefs.minDelaySec.toString()) }
    var maxDelay by remember { mutableStateOf(Prefs.maxDelaySec.toString()) }
    var perHour by remember { mutableStateOf(Prefs.maxPerHour.toString()) }

    var manualTick by remember { mutableIntStateOf(0) }

    val items = remember(tick, mode, manualTick) { SetupGuide.items(ctx, mode) }
    val required = items.filter { !it.optional }
    val doneCount = required.count { it.done }
    val allDone = doneCount == required.size
    val ballRunning = remember(tick) { ClipboardBallService.running }
    val isClipboard = mode == Prefs.MODE_CLIPBOARD

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
                        Text("$doneCount / ${required.size}", style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(
                        progress = { if (required.isEmpty()) 0f else doneCount.toFloat() / required.size },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                    Text(
                        if (allDone) "都齐了。" else "返回本页会自动重新检测，不用手动刷新。",
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
                                item.title + if (item.optional) "（可选）" else "",
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
                                    onClick = { Prefs.setConfirmed(mk, true); manualTick++ },
                                    modifier = Modifier.padding(start = 18.dp)
                                ) { Text("我按上面弄好了") }
                            }
                        } else {
                            val mk = item.manualKey
                            if (mk != null) {
                                TextButton(
                                    onClick = { Prefs.setConfirmed(mk, false); manualTick++ },
                                    modifier = Modifier.padding(start = 18.dp)
                                ) { Text("重新标记为未完成") }
                            }
                        }
                    }
                }
            }

            // ---------------- 悬浮球（剪贴板模式） ----------------
            if (isClipboard) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("悬浮球", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (ballRunning) "运行中" else "未启动",
                                modifier = Modifier.weight(1f)
                            )
                            if (ballRunning) {
                                OutlinedButton(onStopBall) { Text("关闭") }
                            } else {
                                Button(onStartBall, enabled = allDone) { Text("启动悬浮球") }
                            }
                        }
                        if (!allDone) {
                            Text(
                                "准备工作没弄完，启动了球也出不来。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        HorizontalDivider()
                        Text("怎么用", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "轻点球 —— 读剪贴板，直接出候选\n" +
                                "  1. 微信里长按对方消息选「复制」\n" +
                                "  2. 轻点球，挑一条，自动复制\n" +
                                "  3. 回输入框长按粘贴，发送",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "长按球 —— 显示剪贴板对话\n" +
                                "  长按球把剪贴板里的内容读出来，列成一条条对话，\n" +
                                "  每条点一下切换「对方 / 我」，改对了点「生成回复」。\n" +
                                "  复制一条长按一次，就能一条条攒起来。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "面板里每条点一下能切换「对方 / 我」，长按删掉，" +
                                "整体判反了点「角色对调」。换人聊天点「清空」。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "球可以拖到任何位置，拖动不会误触发长按。",
                            style = MaterialTheme.typography.bodySmall
                        )

                        HorizontalDivider()
                        OutlinedTextField(
                            value = myNick,
                            onValueChange = { myNick = it; Prefs.myNickname = it },
                            label = { Text("你自己的微信昵称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "整段对话贴进来时，靠它分清哪几条是你说的。填了最准，" +
                                "轻点球就能一路生成到底；不填也能用 —— 默认把整段最后说话的那个" +
                                "当成对方（你本来就是在回他），判反了面板上点「角色对调」。",
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (ballRunning) {
                            HorizontalDivider()
                            Text(
                                "球老是自己消失的话，去关一下电池优化和厂商的自启动限制。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ SetupGuide.jump(ctx, "battery") }, Modifier.weight(1f)) {
                                    Text("电池优化")
                                }
                                OutlinedButton({ SetupGuide.jump(ctx, "autostart") }, Modifier.weight(1f)) {
                                    Text("自启动")
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- AI 接口 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI 接口", style = MaterialTheme.typography.titleMedium)
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

            // ---------------- 人设 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("人设", style = MaterialTheme.typography.titleMedium)
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

            // ---------------- 表达风格 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("表达风格", style = MaterialTheme.typography.titleMedium)
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

                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(useBank, { useBank = it; Prefs.useReplyBank = it })
                        Spacer(Modifier.width(10.dp))
                        Text("内置段子库")
                    }
                    Text(
                        "内置一批全网经典素材：土味情话、舔狗日记式自嘲、高情商话术。" +
                            "生成时喂给 AI，贴切时它会借用或化用；对方认真或生气时不会用。" +
                            "想加段子改 ReplyBank.kt。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (Tone.CUSTOM.id in tones) {
                        OutlinedTextField(
                            value = customTone,
                            onValueChange = { customTone = it; Prefs.customTone = it },
                            label = { Text("自定义风格怎么写") },
                            minLines = 2, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ---------------- 高级模式（折叠） ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "高级模式",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton({ val n = !showAdvanced; showAdvanced = n; Prefs.showAdvanced = n }) {
                            Text(if (showAdvanced) "收起" else "展开")
                        }
                    }
                    Text(
                        "自动监听微信消息，不用手动复制。要多开好几个权限，也有封号风险。" +
                            "剪贴板模式够用就别碰这里。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (showAdvanced) {
                        HorizontalDivider()
                        Text("工作模式", style = MaterialTheme.typography.titleSmall)
                        listOf(
                            Prefs.MODE_CLIPBOARD to "剪贴板悬浮球（2 个权限，零风险）",
                            Prefs.MODE_NOTIFICATION to "通知模式（自动，风险低）"
                        ).forEach { (m, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = mode == m,
                                    onClick = { mode = m; Prefs.mode = m }
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        if (!isClipboard) {
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(watchWx, { watchWx = it; Prefs.watchWeChat = it })
                                Spacer(Modifier.width(10.dp)); Text("监听微信")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(watchDy, { watchDy = it; Prefs.watchDouyin = it })
                                Spacer(Modifier.width(10.dp)); Text("监听抖音")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(autoSend, { autoSend = it; Prefs.autoSend = it })
                                Spacer(Modifier.width(10.dp)); Text("全自动直接发")
                            }
                            if (Tone.SHARP.id in tones) {
                                Text(
                                    "尖酸风格不参与全自动，永远只推给你自己点。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            HorizontalDivider()
                            Text("回复谁", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(whitelistAll, { whitelistAll = it; Prefs.whitelistAll = it })
                                Spacer(Modifier.width(10.dp)); Text("所有单聊都处理")
                            }
                            OutlinedTextField(
                                value = whitelist,
                                onValueChange = {
                                    whitelist = it
                                    Prefs.whitelist = it.lines().map(String::trim)
                                        .filter(String::isNotBlank).toSet()
                                },
                                label = { Text("白名单昵称，一行一个") },
                                minLines = 3, modifier = Modifier.fillMaxWidth(),
                                enabled = !whitelistAll
                            )

                            HorizontalDivider()
                            Text("节流", style = MaterialTheme.typography.titleSmall)
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

                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(enabled, { enabled = it; Prefs.enabled = it })
                                Spacer(Modifier.width(10.dp))
                                Text("自动监听总开关", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }

            // ---------------- 拦截说明 ----------------
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("关于内容限制", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "关键词拦截已经全部去掉了。候选只做两件事：非空、不超过 120 字。" +
                            "内容上不设限，该冷就冷、该呛就呛，由你自己挑。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "只有一条留在 prompt 里：不主动引导对方打赏、转账、充值、投资。" +
                            "对方自己提钱会正常回应，只是不顺势索要。这条不拦候选，" +
                            "你不会遇到「这条怎么没了」的情况。",
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
