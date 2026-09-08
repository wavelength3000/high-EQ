package com.tools.wereply

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 准备工作检查表。
 *
 * 这个 App 最大的体验坑不是功能，是「装好了但不工作」，
 * 而且十有八九是国产 ROM 把后台服务杀了。所以每一项都要能检测出真实状态，
 * 并且给到对应品牌的具体路径 —— 只丢一个「去设置」按钮，用户进去照样找不到。
 */
object SetupGuide {

    data class Item(
        val key: String,
        val title: String,
        /** 为什么需要 */
        val why: String,
        /** 具体点哪里，按品牌给 */
        val path: String,
        val done: Boolean,
        /** 有跳转就给按钮文字，没有就是 null */
        val jumpLabel: String? = null,
        /** 系统查不到状态、只能用户自己确认的项 */
        val manualKey: String? = null,
        /** 可选项，不计入完成度 */
        val optional: Boolean = false
    )

    // ---------------- 状态检测 ----------------

    /** 能不能发通知（Android 13+ 要用户点同意） */
    fun canPostNotification(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    /** 通知使用权（读别人的通知） */
    fun listenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return flat.contains(ctx.packageName)
    }

    /** 无障碍服务 */
    fun accessibilityEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val target = "${ctx.packageName}/${ChatAccessibilityService::class.java.name}"
        val short = "${ctx.packageName}/.ChatAccessibilityService"
        return flat.contains(target) || flat.contains(short)
    }

    /** 悬浮窗 */
    fun overlayEnabled(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** 有没有被排除出电池优化 */
    fun batteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // ---------------- 厂商适配 ----------------

    private val brand: String get() = Build.MANUFACTURER.lowercase()

    private val isXiaomi get() = brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    private val isHuawei get() = brand.contains("huawei") || brand.contains("honor")
    private val isOppo get() = brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")
    private val isVivo get() = brand.contains("vivo") || brand.contains("iqoo")
    private val isMeizu get() = brand.contains("meizu")
    private val isSamsung get() = brand.contains("samsung")

    /** 自启动/后台保活的路径，按品牌给不同文案 */
    fun autoStartPath(): String = when {
        isXiaomi -> "手机管家 → 应用管理 → 权限 → 自启动 → 打开本应用。\n" +
            "再进 授权管理 → 其他权限 → 本应用 → 打开「后台弹出界面」，" +
            "不开这个悬浮球弹不出来。\n" +
            "最后 设置 → 省电与电池 → 应用智能省电 → 本应用 → 无限制。"

        isHuawei -> "手机管家 → 应用启动管理 → 找到本应用 → 关掉「自动管理」，" +
            "然后自启动、关联启动、后台活动三项全部打开。"

        isOppo -> "设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台运行、允许自启动。\n" +
            "另外 设置 → 应用管理 → 本应用 → 耗电保护 → 允许完全后台行为。"

        isVivo -> "i管家 → 应用管理 → 权限管理 → 自启动 → 打开本应用。\n" +
            "再进 设置 → 电池 → 后台高耗电 → 允许本应用。"

        isMeizu -> "手机管家 → 权限管理 → 后台管理 → 本应用 → 允许后台运行。"

        isSamsung -> "设置 → 电池 → 后台使用限制 → 从不休眠的应用 → 添加本应用。"

        else -> "设置 → 应用 → 本应用 → 电池 → 选「不受限制」。"
    } + "\n\n另外：在最近任务界面下拉本应用的卡片加个锁，防止一键清理把服务干掉。"

    // ---------------- 跳转 ----------------

    private fun tryStart(ctx: Context, vararg intents: Intent): Boolean {
        intents.forEach { i ->
            runCatching {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            }
        }
        return false
    }

    private fun appDetails(ctx: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${ctx.packageName}")
    )

    private fun comp(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls))

    @SuppressLint("BatteryLife")
    fun jump(ctx: Context, key: String) {
        when (key) {
            "post" -> tryStart(
                ctx,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                appDetails(ctx)
            )

            "listener" -> tryStart(
                ctx,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                appDetails(ctx)
            )

            "a11y" -> tryStart(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

            "overlay" -> tryStart(
                ctx,
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                ),
                appDetails(ctx)
            )

            "battery" -> tryStart(
                ctx,
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                ),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetails(ctx)
            )

            // 厂商的自启动页藏得很深，组件名还随版本变，挨个试，都失败就退回应用详情页
            "autostart" -> {
                val candidates = when {
                    isXiaomi -> listOf(
                        comp("com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                        comp("com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity")
                            .putExtra("extra_pkgname", ctx.packageName)
                    )
                    isHuawei -> listOf(
                        comp("com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        comp("com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity")
                    )
                    isOppo -> listOf(
                        comp("com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        comp("com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                        comp("com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity")
                    )
                    isVivo -> listOf(
                        comp("com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                        comp("com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                    )
                    isMeizu -> listOf(
                        comp("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
                    )
                    isSamsung -> listOf(
                        comp("com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity")
                    )
                    else -> emptyList()
                }
                tryStart(ctx, *(candidates + appDetails(ctx)).toTypedArray())
            }

            else -> tryStart(ctx, appDetails(ctx))
        }
    }

    // ---------------- 清单 ----------------

    fun items(ctx: Context, mode: String): List<Item> {
        val list = mutableListOf<Item>()

        list += Item(
            key = "post",
            title = "允许发送通知",
            why = if (mode == Prefs.MODE_CLIPBOARD)
                "悬浮球靠一个前台服务托着，前台服务必须有一条常驻通知，系统才不会把它杀掉。"
            else
                "候选回复和拦截提醒都是通过通知推给你的，不给就什么都看不到。",
            path = "打开后在列表里允许「私信助理」发通知。",
            done = canPostNotification(ctx),
            jumpLabel = "去开"
        )

        when (mode) {
            Prefs.MODE_CLIPBOARD -> {
                list += Item(
                    key = "overlay",
                    title = "允许显示悬浮窗",
                    why = "悬浮球和候选面板都是浮在微信上面的，不给权限球出不来。",
                    path = "找到「显示在其他应用上层」或「悬浮窗」，打开。",
                    done = overlayEnabled(ctx),
                    jumpLabel = "去开"
                )
                if (Prefs.useScreenReader) {
                    list += Item(
                        key = "a11y",
                        title = "开启无障碍服务（读屏增强）",
                        why = "开了它就不用手动复制粘贴了：点球直接读当前聊天页，" +
                            "挑完直接填进输入框。它只在你点球那一下工作，不自动监听、不自动发送。",
                        path = "进去后找「已下载的应用」或「已安装的服务」→ 私信助理 → 打开开关。" +
                            "部分手机会弹一个吓人的风险提示，确认即可。",
                        done = accessibilityEnabled(ctx),
                        jumpLabel = "去开",
                        optional = true
                    )
                }
            }

            Prefs.MODE_ACCESSIBILITY -> {
                list += Item(
                    key = "a11y",
                    title = "开启无障碍服务",
                    why = "靠它读聊天窗口里的可见消息，不开的话悬浮球不会出现。",
                    path = "进去后找「已下载的应用」或「已安装的服务」→ 私信助理 → 打开开关。" +
                        "部分手机会弹一个吓人的风险提示，确认即可。",
                    done = accessibilityEnabled(ctx),
                    jumpLabel = "去开"
                )
                list += Item(
                    key = "overlay",
                    title = "允许显示悬浮窗",
                    why = "候选回复的面板是悬浮在微信上面的，不给权限点了没反应。",
                    path = "找到「显示在其他应用上层」或「悬浮窗」，打开。",
                    done = overlayEnabled(ctx),
                    jumpLabel = "去开"
                )
                list += batteryItem(ctx)
                list += autoStartItem()
            }

            else -> {
                list += Item(
                    key = "listener",
                    title = "开启通知使用权",
                    why = "整个通知模式都靠它读微信推来的消息，不开等于没装。",
                    path = "在列表里找到「私信助理」，打开开关，确认弹窗。",
                    done = listenerEnabled(ctx),
                    jumpLabel = "去开"
                )
                list += batteryItem(ctx)
                list += autoStartItem()
                list += Item(
                    key = "wxdetail",
                    title = "微信显示消息详情",
                    why = "不开的话通知里只有「你收到一条消息」，抓不到任何内容，功能等于废掉。",
                    path = "微信 → 我 → 设置 → 新消息通知 → 打开「通知显示消息详情」。\n" +
                        "这项系统查不到，你开完自己点一下确认。",
                    done = Prefs.confirmedWxDetail,
                    manualKey = "wxdetail"
                )
            }
        }

        return list
    }

    private fun batteryItem(ctx: Context) = Item(
        key = "battery",
        title = "关掉电池优化",
        why = "不关的话服务跑几小时就被系统冻结，表现是「用着用着就不灵了」。",
        path = "弹窗里选「允许」。如果没弹窗，在列表里把本应用切到「不受限制」。",
        done = batteryUnrestricted(ctx),
        jumpLabel = "去关"
    )

    private fun autoStartItem() = Item(
        key = "autostart",
        title = "自启动与后台保活",
        why = "国产 ROM 的重灾区。上一项只管系统层的电池优化，厂商自己那套还得单独关。",
        path = autoStartPath(),
        done = Prefs.confirmedAutoStart,
        jumpLabel = "去设置",
        manualKey = "autostart"
    )
}
