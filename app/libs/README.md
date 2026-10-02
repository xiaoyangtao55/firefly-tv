# 本目录用于放置腾讯 X5（TBS）内核 SDK

FireflyTV 的网页播放走的是腾讯 X5 内核（com.tencent.smtt.sdk.WebView），
它自带统一内核、不依赖系统 WebView，可以解决低版本电视盒子内核过旧、
加载央视/卫视播放页白屏或卡死的问题。

X5 SDK 不在 Google Maven，需要手动下载并放到这里：

1. 到腾讯浏览服务官网下载 SDK（tbs_sdk_*.jar，旧版为 .aar 也可）：
   https://x5.tencent.com/docs/access.html
2. 把下载到的文件重命名为 `tbs_sdk.jar` 并放进本目录（app/libs/）。
   注意 app/build.gradle.kts 里写的是 `implementation(files("libs/tbs_sdk.jar"))`，
   文件名必须一致，否则 sync/构建会报“找不到 tbs_sdk.jar”。
   （如果你的下载是 .aar，直接改名为 .jar 即可——X5 官方分发包本质就是 jar。）

离线设备（无外网）额外步骤：
   X5 首次启动默认会从腾讯 CDN 下载内核。离线盒子需要预置本地内核：
   把内核包（通常是一组 .so / 资源）按 TBS 文档放进 assets/tencent_class/
   或按官方“离线集成”指引配置，否则会回退到系统 WebView（退化，但不崩）。

验证是否用上 X5：
   代码里 QbSdk.initX5Environment 的 onViewInitFinished(true) 表示成功使用 X5；
   false 表示回退系统 WebView。也可在运行时用
   com.tencent.smtt.sdk.WebView 是否生效来确认。
