# android/ — 极简伴侣 App

## 文件结构

```
android/
├── build.gradle.kts          # 项目级构建
├── settings.gradle.kts       # 项目设置
├── gradle.properties
└── app/
    ├── build.gradle.kts      # App 模块构建（OkHttp + Gson，零 UI 库）
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/supagent/collector/
            ├── MainActivity.kt      # 唯一 Activity：配置服务器地址 + 启动按钮
            └── CollectorService.kt  # 核心：前台 Service，60s 轮询 UsageStatsManager
```

## 编译

```bash
cd android
./gradlew assembleDebug
# APK 产出: app/build/outputs/apk/debug/app-debug.apk
```

## 安装到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 工作原理

```
CollectorService (前台 Service)
    │  每 60 秒读取 UsageStatsManager
    │  找到当前前台 App 包名
    │  分类: entertainment / social / productivity / other
    ▼
HTTP POST http://<电脑IP>:8080/api/phone/usage
    │  { timestamp, package_name, app_name, usage_seconds, category }
    ▼
SupAgent 服务器
    → SQLite 存储
    → EntertainmentDetector 检测连续娱乐
    → ReminderSystem 触发提醒
    → 微信推送
```
