# 网络占用（NetMon）

Android 本地工具：查看哪些应用在用网络、大致在干什么、是否走 Clash VPN，并支持后台记录。

**包名** `com.yanpeng.netmon` · 当前版本 **1.0.4**

## 功能

- **实时**：按间隔统计各应用上下行流量（NetworkStatsManager）
- **应用名 / 图标**：解析 UID → 包名 → 中文名
- **直连 / 经 Clash**：结合 VPN UID 名单与连接特征标注路由
- **行为提示**：启发式说明（IM 保活、短视频 CDN、商店下载等）
- **详情页**：点进应用看流量与连接摘要
- **记录**：前台服务定时采样，便于回看后台谁在用网
- **顶部总览可折叠**：默认收起，方便浏览列表

## 权限

- 使用情况访问（`PACKAGE_USAGE_STATS` / 用量访问）——必开，否则看不到分应用流量
- 通知（记录服务）
- `QUERY_ALL_PACKAGES`（解析应用名）

## 构建

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 需指向本机 Android SDK（勿提交）。

## 已知限制

- 无 root 时 `/proc/net/tcp` 对三方常不可读，连接列表可能显示「连接不可读」
- 系统对三方隐藏 VPN 路由 UID 时，需用 ADB 等方式同步一次 Clash 的 UID 名单，才能稳定区分「经 Clash / 直连」
- 行为说明为启发式，不是抓包还原

## 隐私

数据仅存本机，不上云。

## License

MIT
