# Aether OS

单人、纯本地的原生 Android 自律应用。界面用 Jetpack Compose，数据在 Room，闹钟用 `setAlarmClock`，锁屏主线是 Device Owner + LockTask。不带账号，源码里没有 API Key。

两个硬指标：驾考任务完成，自律天数不断链。体重、饮食、英语、训练是观察项。

## 构建

需要 JDK 17、Android SDK Platform 36 和 Build-Tools 36。

```bash
./gradlew :app:test :app:assembleRelease
```

可安装包在 `app/build/outputs/apk/release/`。Release 用调试证书签名，方便直接装到自己的手机。

## 第一次打开

1. 在「设置 → 模型路由」填 Base URL、Key 和转写路径。Key 用 Android Keystore 的 AES-GCM 存在本机。
2. 按场景绑定：搭档、速记、转写、视觉、早报、云端音色。
3. 在「数据与阈值」填目标体重、热量、考试日期、出发日期和 6 位紧急密码。
4. 在「Root 与锁屏」看 su、Device Owner、精确闹钟、使用情况、无障碍、悬浮窗、全部文件。路径 1 是 `dpm set-device-owner`。路径 2 会先备份 `/data/system/device_owner_2.xml` 和 `device_policies.xml`，必须输入「确认风险」才会写。
5. 题库不够时，在驾考页导入 JSON。内置题是按公开的公安部令第 163 号、第 172 号口径写的练习题，不是驾考宝典或其他商业题库。

每天静默快照会写到 `/sdcard/Download/DSH-Go/backup/`，只留最近 14 份。界面上没有导出按钮。

## 这台电脑没做的真机项

策划书里的 5 项必须在一加 Ace 5 Ultra（ColorOS 16 / Android 16）上确认，这台构建机没有连着那部手机：

- Device Owner 能否激活
- `setStatusBarDisabled` 和 LockTask 能否挡住下拉、Home、最近任务
- `com.oplus.ttsaccessibilityengine` 的中文和美式英语是否能听
- `setAlarmClock` 在 00:00 / 08:00 的误差是否不超过 60 秒
- 使用情况权限下，遮罩离开前台是否记为逃逸

## 题库格式

JSON 数组。字段可以用 `questionId`、`subject`（1 或 4）、`questionType`、`content`、`options`、`correctAnswer`、`officialExplanation`、`ruleVersion`、`highYield`。
