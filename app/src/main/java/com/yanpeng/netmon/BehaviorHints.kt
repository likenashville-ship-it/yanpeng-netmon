package com.yanpeng.netmon

object BehaviorHints {

    fun forApp(
        packageName: String,
        appName: String,
        rx: Long,
        tx: Long,
        remotes: List<RemoteConn>,
        route: RouteMode
    ): String {
        val pkg = packageName.lowercase()
        val name = appName.lowercase()
        val total = rx + tx
        val hints = mutableListOf<String>()

        // Route-first for overseas
        when (route) {
            RouteMode.PROXY -> hints += "经代理访问（多半出海）"
            RouteMode.VPN_APP -> hints += "VPN/代理核心：节点与本地转发"
            RouteMode.DIRECT -> { /* filled by content */ }
            RouteMode.UNKNOWN -> { }
        }

        // Package / name heuristics
        when {
            match(pkg, name, "douyin", "aweme", "抖音", "tiktok") ->
                hints += if (rx > tx * 3) "短视频 CDN 预加载/播放" else "短视频相关联网"
            match(pkg, name, "kuaishou", "gifshow", "快手") ->
                hints += "短视频 CDN"
            match(pkg, name, "bilibili", "bili", "电视") && name.contains("bili") ->
                hints += "视频流媒体"
            match(pkg, name, "iqiyi", "youku", "tencent.qqlive", "video") ->
                hints += "视频播放/缓冲"
            match(pkg, name, "market", "appstore", "play.store", "packageinstaller", "应用商店", "updater") ||
                pkg.contains("xiaomi.market") || pkg.contains("miui.package") ->
                hints += if (rx > tx * 5) "应用商店下载/更新包" else "应用商店检查更新"
            match(pkg, name, "mm", "wechat", "微信", "com.tencent.mm") ->
                hints += "微信 IM 长连/收发"
            match(pkg, name, "mobileqq", "qq", "tim") && (pkg.contains("tencent") || name.contains("qq")) ->
                hints += "QQ 长连保活"
            match(pkg, name, "alibaba.android.rimet", "dingtalk", "钉钉") ->
                hints += "钉钉 IM/办公"
            match(pkg, name, "feishu", "lark", "飞书") ->
                hints += "飞书 IM/办公"
            match(pkg, name, "ximalaya", "喜马拉雅") ->
                hints += "音频收听/缓存"
            match(pkg, name, "red", "xhs", "xiaohongshu", "小红书") ->
                hints += "社区内容浏览"
            match(pkg, name, "taobao", "tmall", "闲鱼", "pinduoduo", "yangkeduo", "jingdong", "jd.") ->
                hints += "电商浏览/推送"
            match(pkg, name, "alipay", "支付宝") ->
                hints += "支付/金融保活"
            match(pkg, name, "amap", "gaode", "baidu.map", "maps", "高德", "地图") ->
                hints += "地图/导航定位"
            match(pkg, name, "nlp", "location", "定位") ->
                hints += "定位/网络位置"
            match(pkg, name, "push", "mipush", "getui", "umeng", "jg.android", "推送") ->
                hints += "推送心跳"
            match(pkg, name, "ezviz", "ys7", "萤石", "mihome", "mijia", "米家", "smart") ->
                hints += "IoT/摄像头心跳或媒体"
            match(pkg, name, "chrome", "browser", "uc.", "webview", "浏览器") ->
                hints += if (route == RouteMode.PROXY) "浏览器经代理上网" else "浏览器网页流量"
            match(pkg, name, "twitter", "x.android", "com.x.", "tweet") || pkg == "com.twitter.android" ->
                hints += "社交客户端（多经代理）"
            match(pkg, name, "gmail", "gm.", "google.android.gms", "vending", "play.games") ->
                hints += "Google 服务/商店"
            match(pkg, name, "immigration", "移民", "出入境", "ncia", "gov.cn") ->
                hints += "政务/证照相关下载"
            match(pkg, name, "zlb", "浙里办", "浙政", "gov") ->
                hints += "政务 App 联网"
            match(pkg, name, "wallpaper", "壁纸") ->
                hints += "壁纸资源下载"
            match(pkg, name, "netguard", "clash", "v2ray", "singbox", "shadowsocks") ->
                hints += "代理内核与连接复用"
        }

        // Port-based
        val ports = remotes.map { it.port }.toSet()
        when {
            ports.any { it == 14000 || it in 14000..14010 } ->
                if (hints.none { it.contains("QQ") }) hints += "检测到 QQ 长连口"
            ports.any { it == 443 } && remotes.size >= 8 && rx > 500_000 ->
                if (hints.none { it.contains("CDN") || it.contains("视频") }) hints += "多路 HTTPS，像媒体拉取"
            ports.any { it in setOf(7890, 7891, 1080, 45107) } ->
                if (hints.none { it.contains("代理") }) hints += "连本机代理端口"
        }

        // Traffic shape
        if (total > 0) {
            when {
                rx > tx * 10 && rx > 1_000_000 ->
                    if (hints.none { it.contains("下载") || it.contains("CDN") || it.contains("播放") })
                        hints += "下行远大于上行，像下载/播放"
                tx > rx * 3 && tx > 200_000 ->
                    hints += "上行偏多，像上传/同步"
                total < 50_000 && remotes.isNotEmpty() ->
                    if (hints.none { it.contains("心跳") || it.contains("保活") || it.contains("长连") })
                        hints += "小流量保活/心跳"
            }
        }

        if (hints.isEmpty()) {
            return when {
                remotes.isNotEmpty() -> "有活跃连接，用途不明（仅供参考）"
                total > 0 -> "有流量，无可见连接详情"
                else -> "暂无活跃迹象"
            }
        }
        return hints.distinct().take(3).joinToString("；")
    }

    private fun match(pkg: String, name: String, vararg keys: String): Boolean {
        for (k in keys) {
            val key = k.lowercase()
            if (pkg.contains(key) || name.contains(key)) return true
        }
        return false
    }
}
