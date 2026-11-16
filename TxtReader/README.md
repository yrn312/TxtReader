# TXT阅读器 (TxtReader)

Android 轻量 TXT 阅读器：Kotlin + Gradle KTS，minSdk 24 / targetSdk 34，无网络权限。

## 构建 APK
- GitHub：把本目录推到仓库，Actions 的 “Build APK” 完成后在 Artifacts 下载 `TxtReader-release-apk`。
- 本地：安装 JDK 17 + Android SDK（platform 34），执行 `gradle assembleRelease`（Gradle 8.7），
  或用 Android Studio 打开后 Build > Build APK(s)。
- 输出：`app/build/outputs/apk/release/app-release.apk`（使用 debug 签名，可直接安装；上架请换成自己的签名）。
