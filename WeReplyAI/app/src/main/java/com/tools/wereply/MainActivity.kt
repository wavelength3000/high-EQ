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

    var manualTick by remember { mutableIntStateOf(0) }

    val items = remember(tick, mode, manualTick) { SetupGuide.items(ctx, mode) }
    val doneCount = items.count { it.done }
    val allDone = doneCount == items.size
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
                        Text("$doneCount / ${items.size}", style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(
                        progress = { if (items.isEmpty()) 0f else doneCount.toFloat() / items.size },
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
                            "1. 微信里长按对方那条消息，选「复制」\n" +
                                "2. 点屏幕边上的悬浮球，等一两秒\n" +
                                "3. 挑一条，点它就复制好了\n" +
                                "4. 回微信输入框长按，粘贴，发送",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "球可以拖到任何位置。换人聊天时点面板上的「换个人聊」清掉上下文，" +
                                "不然 AI 会把上一个人的话当成这个人说的。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "全程是你自己在复制粘贴，微信那边看不到任何异常，没有封号风险。",
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
                            Prefs.MODE_NOTIFICATION to "通知模式（自动，风险低）",
                            Prefs.MODE_ACCESSIBILITY to "无障碍模式（自动，风险高）"
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
                            if (mode == Prefs.MODE_ACCESSIBILITY) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(autoClickSend, { autoClickSend = it; Prefs.autoClickSend = it })
                                    Spacer(Modifier.width(10.dp)); Text("填完顺手点发送")
                                }
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
