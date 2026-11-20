# Aether OS

单人、纯本地的原生 Android 自律应用。界面用 Jetpack Compose，数据在 Room，闹钟用 `setAlarmClock`，锁屏主线是 Device Owner + LockTask。不带账号，源码里没有 API Key。

两个硬指标：驾考任务完成，自律天数不断链。体重、饮食、英语、训练是观察项。

## 下载安装

到 [Releases](https://github.com/1535273240sch-droid/SelfDisciplineOS/releases) 下载最新的 `AetherOS-vX.Y.Z.apk` 直接安装（仅自用签名）。推送 `v*` tag 后 GitHub Actions 会自动构建并发布。

## 更新记录

- **v2.2.0** 重构今日、学习、搭档和设置的玻璃界面，新增六主题实时预览切换。今日三项硬指标连接到实时录入、练习和训练。首页可查看最近 14 天的趋势，历史补录沿用原结算结果。
- **v2.1.4** 今日页改成「抬头 + AI 计划 + 硬项 + 四条等高入口」，底部固定为今日 / 学习 / 搭档 / 设置，整窗只在根上吃一次安全区（状态栏、挖孔、导航条、键盘）。模型接入改成一组玻璃表单：测试连接会真正请求服务商，失败时写出原因；Base URL 已带 `/v1` 或没带都能对上 `/models` 和 `/chat/completions`；拉不到列表时仍可手填模型名。测通后会给还没绑定的搭档、快速记录、早报补上默认模型，对话里采纳的安排写进今日计划。
- **v2.1.3** 界面距离修复：全局统一处理系统栏留白，内容不再顶进状态栏/挖孔下面；底部 Dock 改为进布局流，删掉各页面写死的 `bottom=108dp` 魔法数；所有页面统一 16dp 边距、12dp 间距；锁屏与早报页同样让开系统栏；界面风格、控件、文案与数据逻辑不变。清理不再使用的旧仪表盘代码。
- **v2.1.2** 签名：使用正式 keystore，开启 v1/v2/v3 签名。
- **v2.1** AI 工具链闭环、模型路由 6 场景绑定、Bento 玻璃界面。

## 构建

需要 JDK 17、Android SDK Platform 36 和 Build-Tools 36。

```bash
./gradlew :app:test :app:assembleRelease
```

仓库的 [Build 工作流](https://github.com/1535273240sch-droid/SelfDisciplineOS/actions/workflows/build.yml) 使用正式签名，运行测试并上传 `aether-apk`。本地构建输出在 `app/build/outputs/apk/release/`，未提供正式证书时使用调试签名。

## 第一次打开

1. 在「设置 → 模型路由」填渠道、Base URL 和 Key，模型名可以等拉取后再选，也可以直接手填。点「测试连接并拉取模型」。Key 用 Android Keystore 的 AES-GCM 存在本机。Base URL 写成 `https://api.deepseek.com` 或 `https://api.deepseek.com/v1` 都可以。
2. 测试成功后，搭档、快速记录和早报会自动绑到这个模型（已经绑过别的渠道则不动）。转写、拍照、云端音色仍要各自点一行绑定。
3. 在「数据与阈值」填目标体重、热量、考试日期、出发日期和 6 位紧急密码。
4. 需要补昨天的体重/作息/饮食时，用「设置 → 补录过去的数据」。补录只进趋势，不算当日达标，也不会恢复已经断掉的天数。
5. 在「Root 与锁屏」看 su、Device Owner、精确闹钟、使用情况、无障碍、悬浮窗、全部文件。路径 1 是 `dpm set-device-owner`。路径 2 会先备份 `/data/system/device_owner_2.xml` 和 `device_policies.xml`，必须输入「确认风险」才会写。
6. 题库不够时，在驾考页导入 JSON。内置题是按公开的公安部令第 163 号、第 172 号口径写的练习题，不是驾考宝典或其他商业题库。

每天 `00:05` 会自动结算前一天：三硬项（作息未越线、驾考题量、体重/饮食/作息实时入库）任一没完成就断链；App 没打开、闹钟被系统吞掉时，下次启动会按日期逐天补齐。紧急解锁会显示常驻倒计时通知，到点自动重锁。

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
