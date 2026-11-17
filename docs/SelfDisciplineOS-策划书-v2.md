# 个人原生安卓自律系统（Self-Discipline OS）全栈开发策划书 v2

> **文档说明（致执行开发的 AI 工程师）**
> 本 v2 为**需求冻结 + 技术前提已核实**的完整规范，取代 v1。产品定位不变：**单用户、纯本地全栈、具备 Root 最高系统权限的原生 Android 个人自律与 AI 伴侣系统**。所有功能需求一次性全量交付，**不分期、不砍功能**。
>
> v2 相对 v1 做了两类改动，逐条对照见 **第 18 节**：
> - **A 类（技术前提修正）**：v1 中若干实现手段在 Android 14/15/16 上已被平台封堵，v2 换成当前确实可行的手段。功能目标不变，落地路径变。
> - **B 类（需求补全）**：v1 未定义但执行时必然要拍板的判定规则、阈值、数据表与交互分支，v2 全部补齐。
>
> 文档中标注 **【实测】** 的结论已在目标设备上验证过；标注 **【待验证】** 的必须在写代码前于本机跑通，跑不通就走该节写明的回退方案，不得静默降级。

---

## 1. 产品愿景与核心定位

* **产品名称**：Aether OS（代号 `SelfDisciplineOS`）
* **目标用户**：单人自用，运行于**已 Root（Magisk / KernelSU 常驻型，重启后 su 仍可用）的主力 Android 旗舰机**。
* **核心定位**：零打字全语音交互 + 具备长期记忆的真人感 AI 成长搭档 + iOS 18 极致克制美学 + Root 级硬核防沉迷控制。
* **核心灵魂（The Soul）**：
  1. **有温度的硬核伙伴**：能记住用户习惯、体重变化、作息规律和薄弱点的"私人参谋兼魔鬼教官"，主动根据数据生成表格与折线图，直言不讳提出改进建议。
  2. **零摩擦全语音录入**：所有日常数据均可"一句话口述"或"随手拍照"自动识别拆分入库。
  3. **物理级作息铁律**：`08:00` 唤醒与情报早报，`00:00` 强制系统级锁机（仅支持紧急密码解锁并给予 1 小时处理窗口，超时自动重新锁死）。

### 1.1 唯一成功判据（B 类新增，最高优先级）

本系统**只有一个硬指标**：

> **驾考一次通过 + 自律天数不断链。**

其余（体重、作息达标率、英语开口能力）一律为**观察项**，不参与成败判定。所有 AI 人设话术、简报排版、破戒提示都必须把注意力导向这两个硬指标。

这条判据决定了：`自律天数（streak）`与`破戒（violation）`是系统的一等公民概念，必须有独立数据表（见 11.9–11.11），而不是从业务表现算。

---

## 2. 目标运行环境与实测基线

### 2.1 设备事实【实测】

| 项 | 值 | 证据 |
| :--- | :--- | :--- |
| 机型 | OnePlus **PLC110**（一加 Ace 5 Ultra） | `ro.product.model` |
| ROM | **ColorOS V16.1.0** 国行 | `ro.build.version.oplusrom` |
| 系统 | **Android 16 / SDK 36** | `ro.build.version.release/sdk` |
| build | `user` / `release-keys`，`PLC110_16.0.10.500(CN01)` | fingerprint |
| SoC | 天玑 9400（`ro.hardware=mt6991`），Mali GPU，8 核 3.73GHz | getprop |
| 内存 | 11371 MB | /proc/meminfo |
| App 侧显示 | 1080×2210 @ 480dpi（另有 `ro.sf.lcd_density=560`） | diagnostics + prop |
| 刷新率能力 | `persist.oplus.display.ogfr.exclusive=144`、`vrr=1` | prop（面板实际规格未在本机核实） |
| GMS | **已安装**（`com.google.android.gms` / `gsf` / `com.android.vending` 均在） | `cmd package path` |

### 2.2 对本项目有决定性影响的三条实测结论

1. **Root 形态**：用户确认为**常驻型**（Magisk / KernelSU 刷入，重启后 su 仍在）。
   > 注意：开发沙箱（Ubuntu proot）内看不到 su —— 那里 `/data/adb` 不存在、`/sys/fs/selinux` 被 bind 到空目录、`CapEff=0000000000000000`、`/system/bin/su` 不可执行。这些是 **proot mount namespace 的屏蔽效应，不代表设备无 root**。执行 AI 必须在**原生 Android 环境**（App 内 `libsu` 调用，或已开启的原生 shell）验证 su，不要在 proot 沙箱里下结论。
2. **本机无系统语音识别器**【实测】：`cmd package query-services -a android.speech.RecognitionService` 无结果；`com.google.android.as`、`com.heytap.speechassist`（小布，已装但不导出该服务）均不提供。
   → **v1 中"集成 Android 原生 `SpeechRecognizer`"这条路在本机是空的**，必须走云端转写（见 7.1）。
3. **本机 TTS 引擎只有一个**【实测】：`com.oplus.ttsaccessibilityengine/.service.TtsService`（`isDefault=true`、`exported=true`、`permission=null`）。Google TTS、讯飞、百度、`com.mediatek.ttservice` 全部未安装。
   → 该引擎是否带完整中文与美式英文音色**【待验证】**，故 7.2 规定"本机优先 + 样板句试听 + 一键切云端"。

### 2.3 构建环境事实（交付方式相关）

开发沙箱内 `gradle` / `sdkmanager` / `adb` / `aapt2` 均不存在，`ANDROID_HOME` 为空，JDK 17 在 `/usr/lib/jvm/java-17-openjdk-arm64` 但未入 PATH；可用内存仅约 2.4 GB，AGP + Compose 编译器在此量级极易 OOM。磁盘剩 55 GB 充足。`github.com` / `api.github.com` / `objects.githubusercontent.com` / `ghcr.io` 网络可达，但沙箱内**当前无任何 GitHub 凭据**（`~/.gitconfig`、`~/.netrc`、`gh hosts.yml` 均不存在，`GITHUB_TOKEN`/`GH_TOKEN` 未设置，`~/.ssh/id_ed25519` 未注册到 GitHub）。

→ 本项目**由电脑侧执行开发与构建**，本沙箱只负责策划。执行 AI 在电脑上需自备：JDK 17+、Android SDK（platform 36、build-tools）、Gradle Wrapper。若走 CI 编译，需 private 仓库且**源码中不得含任何 API Key**。

---

## 3. 技术栈与本地全栈架构

本项目**不搭建任何外部云端业务服务器**。所有数据存储、定时调度、GPS 追踪、UI 渲染与系统控制全部在手机本地完成，仅在调用大模型推理、语音转写与抓取公开信源时发起 HTTPS 请求。

* **开发语言**：Kotlin 2.0+（100% 纯原生，严禁 Flutter / React Native / WebView 跨平台方案）。
* **UI 框架**：Jetpack Compose（Material 3 底层 + 完全自定义的 iOS 18 极简视觉组件库）。
* **本地数据库**：Room（SQLite）+ Coroutines + Flow，响应式毫秒级刷新。
* **异步与后台引擎**：
  * `AlarmManager.setAlarmClock()`：**唯一**用于 `08:00` 唤醒与 `00:00` 锁屏的精确触发手段。
    > **A 类修正**：v1 用 `setExactAndAllowWhileIdle`。事实是 Doze 下该 API 每应用**每 9 分钟最多触发一次**，且 Android 14 起对 `targetSdk≥33` 的新装应用 `SCHEDULE_EXACT_ALARM` **默认拒绝**（未授权调用抛 `SecurityException`），权限被撤销时已注册闹钟全部删除；`USE_EXACT_ALARM` 虽为安装期权限但 Play 政策只允许日历/闹钟类应用声明。只有 `setAlarmClock` 不受 Doze 节流。
    > **代价（已知并接受）**：状态栏会常驻一个闹钟图标。本产品自用、不上传 Play，政策限制不适用。
  * `WorkManager`：后台静默抓取信源、题库增量同步。
  * `Foreground Service`：息屏高精度 GPS 轨迹、锁屏状态监控。
    > **A 类修正**：Android 14（targetSdk 34）起每个 FGS **必须声明 `foregroundServiceType`** 且必须有对应 `FOREGROUND_SERVICE_*` 权限，否则 `MissingForegroundServiceTypeException` / `SecurityException`；`location`/`camera`/`mic` 属 while-in-use，不能在后台创建，除非持有 `ACCESS_BACKGROUND_LOCATION`。Android 15 起 `dataSync`/`mediaProcessing` 有 24 小时内累计 6 小时超时（`onTimeout`），且 `BOOT_COMPLETED` 接收器不允许拉起 `dataSync`/`camera`/`mediaPlayback`/`phoneCall`/`mediaProjection`/`microphone`；持有 `SYSTEM_ALERT_WINDOW` 的后台启动豁免收窄为"必须已有可见 overlay 窗口"。Android 14 还把 `setOngoing(true)` 通知改为用户可滑掉（服务仍存活）。
  * Root 常驻守护（见 4.2）：对抗 ColorOS 后台冻结。
* **网络与 AI 通信**：OkHttp + Retrofit + Kotlinx Serialization，支持 SSE 流式输出。
* **Root 权限控制层**：`libsu`（topjohnwu）。
* **设备管理**：`DevicePolicyManager` + **Device Owner**（见第 8 章，本系统的锁屏地基）。

---

## 4. Root 能力清单与保活

### 4.1 逐条能力可用性（A 类修正表）

| v1 设想手段 | 实测/核实结论 | v2 采用的替代 |
| :--- | :--- | :--- |
| `settings put global policy_control immersive.full=*` 禁用状态栏下拉 | **不成立**。AOSP `PolicyControl.java` 仍读 `Settings.Global.POLICY_CONTROL`，但它**只强制沉浸（隐藏栏）**，用户下滑仍会瞬态唤出状态栏并展开通知 shade；真正禁止下拉需 `STATUS_BAR`（signature\|privileged）权限，第三方拿不到。Android 11 起大量机型失效，国产 ROM 普遍不遵循；写入还需 `WRITE_SECURE_SETTINGS`。 | **`DevicePolicyManager.setStatusBarDisabled(admin, true)`**（仅 Device Owner 可用，真屏蔽下拉）+ `setKeyguardDisabled`。 |
| `TYPE_APPLICATION_OVERLAY` 全屏遮罩 + 拦返回/近期任务 | **不成立**。① 窗口层级低于系统 bar 与 Keyguard，盖不住状态栏/导航条触摸区；② 返回手势由 SystemUI 消费，overlay 无法拦截；③ **Android 12 起 untrusted touch**：窗口被不可信 overlay 遮挡时系统直接丢弃穿透触摸（`FLAG_WINDOW_IS_OBSCURED`）；④ Android 14 起 `USE_FULL_SCREEN_INTENT` 只给通话/闹钟类应用；⑤ Android 15 把"显示在其他应用上层"纳入 sideload 的 restricted settings（开关默认灰）；⑥ ColorOS 把"显示悬浮窗""后台弹出界面"设为独立权限并会自行关闭。 | **LockTask（App Pinning）主线**：`setLockTaskPackages` + `LOCK_TASK_FEATURE_*`，隐藏 Home/近期、通知与状态栏内容不可见。overlay 仅作为**降级兜底**与倒计时胶囊载体。 |
| `dumpsys deviceidle whitelist +<pkg>` | root 下可用（Android 14 起非特权调用无效）。**但白名单只豁免 Doze**。 | 保留，同时必须处理下面三条反制。 |
| 改 `/proc/<pid>/oom_score_adj` 保活 | **可行但被系统性反制**：① Android 14 **cached-apps freezer**（进 cached 10 秒后 cgroup freeze，CPU 归零，并终止其 TCP socket）；② AMS 每次 `updateOomAdjLocked` 重算并**覆写** `oom_score_adj`，手改只在下次调度前有效，需常驻 hook；③ lmkd 与 Android 12+ **phantom process killer**（Android 15 默认阈值收紧）；④ Android 15 `force-stop` 取消全部 `PendingIntent`。 | 保活三件套：`setAlarmClock`（不依赖常驻进程）+ Device Owner（不受厂商省电冻结同等约束）+ 常驻前台服务。**不得把"进程一直活着"当作锁屏的唯一触发路径**。 |
| 无障碍服务做兜底拉起 | 可行，但 Android 13 起 sideload 应用的无障碍开关是 restricted setting（需手动"允许受限设置"，且实测会被静默回收）；Android 15 把悬浮窗、使用情况访问、设备管理器、通知监听、默认拨号/短信角色全部纳入 restricted settings。 | **用 Root 静默启用**：`su` 写 `settings put secure enabled_accessibility_services <cmp>` + `accessibility_enabled 1`，一次授权完成，绕开回收。 |

### 4.2 Root 授权流程

* 首次启动主动请求 `su` 权限并持久化授权状态；授权失败时进入"降级模式"并在设置页顶部常驻提示。
* 提供 Root 能力自检面板：逐项探测并显示 `su 可用 / DO 已激活 / 精确闹钟已授权 / 无障碍已启用 / 悬浮窗已启用 / Doze 白名单已加入`，每项带一键修复命令。

---

## 5. 视觉设计系统与 iOS 18 主题规范

### 5.1 设计核心原则（极致克制）

* **严禁过度渲染**：禁止花哨 3D 粒子、炫光霓虹渐变、复杂拟物阴影、游戏化徽章弹窗。
* **Apple iOS 18 质感复刻**：
  * **连续大圆角（Squircle）**：核心卡片 `22.dp`~`26.dp`，胶囊按钮 `CircleShape`。
  * **极细高光描边**：卡片边缘 `0.6.dp`~`1.dp` 半透明边框。
  * **物理弹簧动画**：`spring(dampingRatio = 0.82f, stiffness = 380f)` 用于所有页面切换、卡片展开、语音胶囊缩放。
  * **排版层级**：数字指标（体重、配速、刷题数、倒计时）用等宽数字 `FontFeature("tnum")`，动态变化不抖动；正文行高 `1.5x`。

### 5.2 四套内置一键切换主题（全量实现，精确色值 Token）

| 主题 | Background | SurfaceCard | Border | TextPrimary | TextSecondary | Accent |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. 银白磨砂玻璃（默认）** | `#F2F4F7` | `#CCFFFFFF`（80% 透明白 + 模糊） | `#E5E9F0` | `#111827` | `#6B7280` | `#007AFF` |
| **2. 钛金属深空灰** | `#16171A` | `#222428` | `#32353B` | `#F3F4F6` | `#9CA3AF` | `#5E9EFF` |
| **3. 羊皮纸暖白** | `#F6F3EB` | `#FCFBF7` | `#E4DEC9` | `#23211D` | `#736E63` | `#B45309` |
| **4. OLED 午夜纯黑** | `#000000` | `#0D0D0F` | `#1F1F23` | `#FFFFFF` | `#8E8E93` | `#30D158` |

* 全局实时无闪烁切换，设置页支持 4 选 1 实时预览。
* 磨砂模糊、描边、弹簧曲线做**一套通用组件实现**，四套主题只切换 token 表，不为每套单独调参。

---

## 6. 核心灵魂：语音交互中枢与 AI 搭档引擎

### 6.1 透明晶体语音交互窗口（零打字设计）

* **全局入口**：底部导航栏正中央常驻半透明磨砂晶体语音胶囊按钮（直径 `64.dp`，微凸起），点击展开全屏/半屏极简语音对话流窗口。保留极低存在感的键盘输入备用图标，**默认完全隐藏键盘**。
* **交互流**：
  1. 按住（或点击开启）语音按钮，胶囊内呈现细腻声波振幅呼吸光晕。
  2. 松手 → 云端转写 → 毫秒级文字上屏 → 立即请求 AI 搭档模型。
  3. AI 回复流式文字呈现，同时自动触发 TTS 朗读。

### 6.2 长期记忆与 System Prompt 人设

* **长期记忆库（`ai_memory_profile` 表）**：当前体重与目标体重、驾考科一/科四倒计时、美国自由行计划、囚徒健身各动作当前式数、近期睡眠达标率、AI 总结的行为模式（如"周三和周五晚上容易熬夜""晚上容易碳水超标"）。
* **注入策略**：每次对话前自动把**今日数据快照 + 最近 7 天趋势摘要 + 长期记忆档案**静默注入 System Prompt。
  > **隐私边界（B 类新增，硬性）**：注入内容**必须排除**以下两类：
  > ① **GPS 轨迹明细**——只注入聚合结论（"今天跑了 5.2km，配速 6'10\""），绝不注入 `routeGeoJson` 原始经纬度点数组；
  > ② **精确到分钟的原始时间戳**——只注入判定结论（"昨晚超过红线 20 分钟"），不注入 `00:20` 这类原文时刻。
  > 体重、饮食、训练、考试、英语数据照常全量注入（不给就无法当教官）。
* **System Prompt 核心人设约束（严禁机器人腔）**：

```text
你不是冷冰冰的AI助手，也不是只会说"好的、已为您记录、请问还有什么可以帮您"的客服机器人。
你是用户最信任的私人自律搭档、健身教练兼人生参谋。你的性格特点是：
1. 真人感与共情力：说话自然口语化、干脆利落，像认识多年的挚友兼硬核教练，能敏锐察觉用户的疲惫、借口或进步。
2. 硬核监督不纵容：当用户熬夜、漏练、暴食或逃避刷题时，不准无脑安慰，必须直接结合历史数据指出问题并给出当下可执行的补救方案；当用户达标或体重下降时，给予真诚且克制的肯定，并顺势微调下一阶段目标。
3. 主动串联数据：每次对话都要带着用户的全维度数据（体重、作息、饮食、健身、驾考、英语）进行思考，主动发现规律。
4. 一切判断以两个硬指标为最高优先：驾考一次通过、自律天数不断链。当天只要有一硬项未达标，必须在回复中点名，不得含糊。
5. 严禁使用任何格式化的客套话、免责声明或机器人自称。
```

### 6.3 一句话多维结构化入库（Function Calling / JSON Intent）

用户随口一句话，AI 必须同步完成**自然语言回复**与**后台静默 Tool Calls**：

* **示例输入**："早啊，刚空腹上秤 72.4 公斤，昨晚 12 点 20 才睡，早饭吃了两个水煮蛋和一杯黑咖啡。"
* **并发 3 个本地 Tool Call**：
  1. `record_weight(weight_kg=72.4, is_fasting=true)`
  2. `record_sleep(sleep_time="00:20", wake_time="08:00")` → 标记"轻度晚睡（超出 00:00 红线 20 分钟）"
  3. `record_diet(meal_type="BREAKFAST", items="水煮蛋x2, 黑咖啡x1", calories=155, protein_g=13.0, carbs_g=1.5)`

### 6.4 AI 不可用时的本地兜底解析（B 类新增）

AI 推理走第三方 API，欠费 / 断网 / 限流时**不得丢数据**：

* 内置**本地规则解析器**（正则 + 关键词），覆盖三类高频简单字段：
  * 体重：`空腹 72.4` / `72.4公斤` / `今早 72.4` → 写 `weight_records`，`source="VOICE_LOCAL_FALLBACK"`。
  * 作息：`12点20才睡` / `昨晚0点睡` → 写 `sleep_records` 并做红线判定。
  * 饮食：`两个水煮蛋一杯黑咖啡` → 写 `diet_records`，营养素留空标记 `needs_backfill=true`，网络恢复后由 AI 补算。
* 兜底触发时在对话流内以极轻的灰字提示"本地已记，AI 恢复后补全"，不打断用户。
* 复杂字段（多餐拆分、拍照识别、复盘分析）不做兜底，等网络恢复。

### 6.5 对话流内自动生成原生表格与折线图

用户询问（"把最近两周的体重、睡觉时间和热量对比整理出来给我看看"）或每晚定时复盘时，AI 返回本地渲染指令 `render_analytics_widget`，客户端在聊天气泡中嵌入原生 Compose 组件：

1. **iOS 18 极简对比数据表**：日期、晨起空腹体重、入睡时间（超红线标红）、热量盈亏、训练完成度。
2. **双轴联动趋势折线图（Canvas 原生绘制）**：主折线为体重变化曲线（平滑贝塞尔 + 关键节点数值标注）；叠加层为每日热量盈亏柱状图或熬夜标记点，直观揭示"熬夜/饮食与体重反弹"的因果关联，附 AI 针对性建议。

---

## 7. 语音引擎（STT / TTS）

### 7.1 STT：云端转写（A 类修正，本机无识别器）

**严禁后台静默偷听**，只允许用户点击/按住触发。

| 渠道 | 真实端点 | 协议形态 | 硬限制 | 价格 |
| :--- | :--- | :--- | :--- | :--- |
| **智谱（推荐主用）** | `POST https://open.bigmodel.cn/api/paas/v4/audio/transcriptions` | **真 OpenAI 兼容** whisper 风格 multipart（`file`/`file_base64`、`model`、`stream`、`prompt`、`hotwords`），响应 `{id, created, request_id, model, text}` 同形 | **≤25MB、≤30 秒**，`.wav`/`.mp3` | GLM-ASR 0.06 元/分钟；官方称 CER 0.0717，支持中英文 + 8 种中国方言 |
| **DeepSeek** | `POST https://api.deepseek.com/v1/tools/asr` | multipart，字段 `file`；**无 `model`/语言参数**（自动判语种） | 仅 16-bit PCM WAV 或 Ogg Opus，**≤8MiB、≤120 秒**，不支持流式 | 0.78 元/小时，限时 5 折 0.39 |
| MiniMax | `POST https://api.minimax.io/v1/speech_to_text`（国内域 `api.minimax.cn`） | multipart 形状兼容但**路径不同**；响应 `{"text","duration"}` | ≤500 秒 / ≤50MB，支持 `stream`、`verbose_json`（说话人分离+时间戳）、srt/vtt | — |

**架构要求**：

* 模型路由中心（第 8 章）的渠道配置**必须含一个「转写路径覆盖」字段**（默认 `/audio/transcriptions`，可改成 `/tools/asr`、`/speech_to_text`）。否则无法复用同一套渠道配置界面。
* **按住说话的单句时长必须限制在 30 秒内**（对齐智谱硬上限），胶囊在 25 秒时给出视觉预警、30 秒自动截断提交。
* 明确不可用项，不要写进配置模板：阿里百炼 `qwen3-asr-flash` 的"OpenAI 兼容"实为 `/compatible-mode/v1/chat/completions` + `input_audio`（官方明确 Qwen-Audio 不支持 OpenAI 兼容协议）；`paraformer`/`SenseVoice` 走 DashScope 私有异步轮询；讯飞为五步私有 REST（`/prepare`→`/upload`→`/merge`→`/getProgress`→`/getResult`）；火山 Doubao-ASR 走 Realtime WebSocket。这些若将来要支持，需各写私有客户端。
* 注意区分：智谱 `GLM-4-Voice` 是端到端语音对话模型（走 `chat/completions` + `input_audio` 且输出音频），**不是**转写渠道。

### 7.2 TTS：本机优先 + 样板句试听 + 云端兜底（B 类新增）

* **默认使用本机引擎**（目标机上唯一可用引擎为 `com.oplus.ttsaccessibilityengine`）。
* 首次进入语音中枢时，自动用本机引擎播放一段**中英混合样板句**（含一句中文搭档口吻长句 + 一句标准美式短句），下方给两个按钮：「就是这个音色」/「换成云端高保真」。
* 选云端时走渠道配置里绑定的 TTS 模型（OpenAI 兼容 `/v1/audio/tts` 或所绑渠道等价端点），此时**AI 回复文本会外发**，需在设置页明确标注这一条。
* 支持中文真人感搭档音色 + 标准美式英语发音引擎；英语跟读模块支持 `1.0x` 正常语速与 `0.75x` 慢速拆解一键切换。
* `isLanguageAvailable(ZH / EN_US)` 必须运行时探测，返回不支持时自动切云端，不得静默无声。

---

## 8. 通用 AI 模型路由中心（OpenAI 兼容协议）

1. **多服务商配置**：支持添加多组 API 渠道（自定义渠道名称、`Base URL`、`API Key`、**转写路径覆盖**），100% 兼容标准 OpenAI 格式。
2. **一键获取模型列表**：请求 `Base URL + /v1/models`，解析全部可用模型 ID 存入本地列表，支持搜索与勾选。
3. **按业务场景精细绑定**：
   * **【核心灵魂】语音搭档与晚间深度复盘**：强逻辑推理模型（如 `deepseek-chat` / `deepseek-reasoner`）。
   * **【耳朵】语音转写**：智谱 `glm-asr-2512`（主）/ DeepSeek `/v1/tools/asr`（备）。
   * **【视觉眼睛】饮食与体重秤拍照识别**：支持 `image_url` 多模态的模型（如 `glm-4v-plus` / `glm-4v-flash`）。
   * **【情报提纯】08:00 垂直新闻早报生成**：高性价比长文本总结模型。
   * **【极速解析】一句话语音多维数据提取**：低延迟响应模型。
   * **【嘴巴】TTS**：本机引擎或云端高保真（见 7.2）。
4. **凭据安全（A 类修正）**：
   * `API_KEY` 必须用 **Android Keystore（KeyMint / StrongBox）自实现 AES-GCM** 加密后存储。
   * **禁止使用 `androidx.security-crypto`**：`EncryptedSharedPreferences` / `EncryptedFile` 全部方法已标 deprecated（1.1.0-alpha07 发布说明原文："Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore"），库长期停在 alpha 且有已知崩溃问题。官方推荐直接使用 Android Keystore 自行做 AES-GCM，键值类数据迁到 **DataStore**（1.3.0 起提供加密支持）。
   * 源码仓库中**不得含任何 Key**；Key 只在运行时由用户填入本机。

---

## 9. Root 级硬核作息与防沉迷系统（整章重写）

> 本章是 v2 改动最大的部分。v1 依赖的 `policy_control` + `TYPE_APPLICATION_OVERLAY` 组合在 Android 14/15/16 上已被平台硬封堵（详见 4.1 表），**功能目标不变，实现主线换成 Device Owner + LockTask**。

### 9.1 锁屏主线：Device Owner + LockTask

**必须激活 Device Owner**（用户明确要求：必须上，不行也上）。

* **激活路径【待验证，按顺序尝试】**：
  1. `su -c "dpm set-device-owner <pkg>/<receiver>"` —— 常规 `dpm set-device-owner` 要求"设备无已登录账号且非二级用户"，root shell 下该前置检查是否可绕过**必须在真机验证**。
  2. 若 1 失败：由 root 直接写 Device Owner 持久化文件（`/data/system/device_owner_2.xml` 与 `/data/system/device_policies.xml`），随后重启 SystemServer 或整机。**此路径风险高**：格式写错可能导致设备管理员状态损坏、只能恢复出厂。执行前必须先完整备份这两个文件。
  3. 若 1、2 均失败：走第 9.6 节回退方案，并在设置页明确告知用户"当前为降级锁屏强度"。
* **激活后获得的真实拦截能力**：
  * `setStatusBarDisabled(admin, true)` —— **真正屏蔽状态栏下拉**（这是 `policy_control` 做不到的）。
  * `setLockTaskPackages(admin, 白名单)` + LockTask mode —— 锁在应用内、隐藏 Home 与近期任务、通知与状态栏内容不可见。
  * `setKeyguardDisabled`、`setCameraDisabled`、屏蔽/静默卸载应用、`LOCK_TASK_FEATURE_*` 精细控制。
* **退出保险（硬性要求）**：Device Owner 卸载时若组件未声明 `<uninstall-device-policy-admins/>`，用户**只能恢复出厂设置**才能移除。因此：
  * `DeviceAdminReceiver` 的 `device_admin.xml` **必须声明** `<uninstall-device-policy-admins/>`；
  * 设置页内置一个二次确认的**「解除设备管理员」入口**（调 `dpm.removeActiveAdmin`），把这扇门锁上而不焊死。

### 9.2 每日 `08:00` 准时唤醒

* `07:50` 后台静默完成今日信源抓取与 AI 总结。
* `08:00` 准点触发全屏晨间简报界面（伴随克制清脆的唤醒音效），一屏展示：昨晚睡眠评估、今日晨起空腹称重快捷入口、今日核心任务（100 道驾考题 + 英语 + 健身）。
* **LockTask 解除时机**：用户在简报界面点「开始今天」确认，或 **`08:30` 自动兜底解除**。不得要求用户每天手动解锁设备管理员。
* **周末规则**：周六周日推迟到 `09:00`，且**默认静音进界面**（不出声、只亮屏 + 通知），到 `09:15` 仍无交互才响。

### 9.3 每日 `00:00` 物理级强锁屏

* **锁屏时刻表**：周一至周五 `00:00:00`；周六、周日 `01:00:00`（多给一小时但不取消，避免"周末"成为整周崩口的固定缺口）。
* 触发方式：`setAlarmClock`（见 3 章）+ 由 Device Owner 侧拉起全屏锁屏 Activity；root 下可用 `su -c "am start ... --user 0"` 绕开 Android 14 对 `USE_FULL_SCREEN_INTENT` 的应用类别限制。
* 界面元素：极简时钟、今日自律复盘总结卡片、底部**【紧急工作解锁】**按钮、以及**【今晚放弃】**按钮（见 9.5）。
* **熄屏策略（B 类新增，硬性）**：遮罩显示 **5 分钟后自动熄屏**。
  > 原因：`FLAG_KEEP_SCREEN_ON` 只在应用窗口可见时生效，"整夜常亮"必须让全屏 Activity 整夜留在前台。6.1 寸 OLED 显示功耗约 50 mW@100nit 至 500 mW@1000nit，8 小时静态亮图约 0.4–4 Wh，即 4000–5000mAh 一夜掉约 **10%–30%+**；DXOMARK 实测 AOD 使待机续航从约 400h 降至约 100h。整夜固定高对比静态遮罩是典型**图像残留（烧屏）**场景，且厂商防烧屏机制（屏幕保护/像素偏移）只在系统空闲态介入，应用持有 `KEEP_SCREEN_ON` 时不会替你移动像素，反而放大风险。
* **熄屏后点亮规则**：按电源键点亮 → **自动重拉遮罩**，此种点亮**不计为逃逸**（否则用户只是看个时间就被记破戒）。

### 9.4 紧急密码解锁 + 1 小时防拖延窗口

* 点击【紧急工作解锁】→ 输入预先设定的 **6 位数字紧急密码**。
* 验证通过后解除 LockTask，同时在状态栏下方常驻一个**极简半透明悬浮倒计时胶囊**（显示"紧急通行剩余 59:59"，可拖动位置但不可关闭）。
* **60 分钟倒计时归零 → 自动重新拉起全屏遮罩并锁定**。
* **防滥用**：每晚 `00:00`（周末 `01:00`）至次日 `07:30` 期间，紧急解锁密码**仅限使用 1 次**。
* **LockTask 白名单（解锁窗口期内可开的 App）**：仅放行**应急六项** —— 电话、短信、地图、支付、微信。其余 App（尤其短视频与游戏）在解锁窗口内仍打不开。理由：否则"紧急工作解锁"会退化成"合法刷 60 分钟手机"。
* **通知策略（锁屏期间）**：仅**来电**与**系统闹钟**照常（白名单含电话与时钟），其余通知不显示（LockTask 本身即屏蔽通知内容）。

### 9.5 「今晚放弃」逃生阀与破戒判定

* 遮罩上提供【今晚放弃】按钮，无需密码。
* **后果分级（关键设计）**：
  * 按【今晚放弃】= 立即解锁 + **当晚记一次破戒 + 自律天数断链** + 次日晨间简报点名。
  * 用紧急密码解锁 = **不记破戒**（承认确有正事），只记 `unlock_events`。
  * 两者代价必须不同，否则密码会被当日常出口用。
* **为什么留逃生阀**：完全无退路时，忘一次密码就得动 root/adb 或当晚锁死；而逃生阀本身带代价（记破戒），比"忘记密码就锁死"更可能被真正用上。

### 9.6 逃逸判定与记账（B 类新增，核心机制）

**总原则：不追求物理拦死，改成"绕开就记账"。** 遮罩被划掉/绕开 → 当次记破戒、自律天数断链，次日 AI 搭档点名。

* **逃逸判定标准**：锁屏/遮罩 Activity **离开前台**即算 —— 被 `finish`、用户按 Home、或前台包名变成其他 App。
  * 实现依赖**使用情况访问（UsageStats）**权限，需引导用户手动授予；Android 15+ 该权限属 restricted setting，需"允许受限设置"。
  * 备选（若 UsageStats 拿不到）：只监听遮罩自身生命周期 + 每 5 秒探测 `ActivityManager` 前台组件。
* **重启绕过检测**：无 root 拦不住关机重启，有 root 也不便硬拦。因此**开机后读取 last boot time，若落在锁屏窗口内（`00:00`–`08:00`，周末 `01:00`–`09:00`）→ 当晚记破戒并在次日简报点名**。让代价可见，比假装拦不住更强。
* **降级回退方案**（仅当 9.1 的 DO 激活彻底失败时启用）：overlay 遮罩 + 无障碍服务兜底拉起 + 上述逃逸记账全套保留。此时"锁"变成"拦不住但全记下来"，必须在设置页顶部明确标注当前强度等级。

### 9.7 Root 底层保活

* `su` 执行 `dumpsys deviceidle whitelist +<package_name>` 加入 Doze 白名单。
* 尝试锁定 `oom_score_adj`，但**必须接受它会被 AMS 重算覆写**（4.1），因此：
  * 锁屏与唤醒的触发路径**不得依赖常驻进程存活**，一律走 `setAlarmClock` → 系统拉起；
  * 常驻前台服务只用于 GPS 运动记录与遮罩状态监控，被杀后由 `setAlarmClock` 与 `BOOT_COMPLETED` 重建（注意 Android 15 对 `BOOT_COMPLETED` 可拉起的服务类型限制）。
* 用 root 静默启用无障碍兜底服务（绕开 restricted setting 回收）。

---

## 10. 六大核心业务模块详细需求

### 10.1 体重与体态追踪（核心指标）

* **录入三选一**：语音口述（"空腹 72.1 公斤"）／**体重秤拍照识别**（拍电子秤屏幕，视觉模型读数入库）／仪表盘快捷滚轮微调。
* **数据关联**：自动计算 BMI、周均体重变化率，并与前一日入睡时间、碳水摄入量自动关联分析。

### 10.2 极简语音/拍照饮食管理

* **零食物库搜索**：砍掉"搜索食物 → 选克数"流程。
* **多模态拍照识别**：饭前随手拍一张餐盘，视觉模型识别菜品名、估算总热量(kcal)、蛋白质/碳水/脂肪(g)，用户确认或语音一句修正即入库。
* **语音速记**：按住说话直接报菜名，后台自动估算营养素并汇总今日热量盈亏进度条。

### 10.3 《囚徒健身》十式引擎 + 原生 GPS 跑步/散步

* **完整十式内置引擎**，六大系列：
  1. 俯卧撑（墙壁俯卧撑 → 单臂俯卧撑）
  2. 深蹲（肩倒立深蹲 → 单腿深蹲）
  3. 引体向上（垂直引体 → 单臂引体向上）
  4. 举腿（坐姿屈膝 → 悬垂高举腿）
  5. 桥（短桥 → 铁板桥）
  6. 倒立撑（靠墙顶立 → 单臂倒立撑）
  * 每式内置官方**初级标准、中级标准、升级标准（组数 × 次数）**。
  * 语音录入训练结果（"刚做了第五式标准俯卧撑，2 组每组 20 个"），达成升级标准时触发晋级解锁提示。
* **原生 GPS 户外跑步与散步**：
  * `FusedLocationProviderClient`（本机 GMS 已装【实测】）+ Root 前台保活；支持**户外跑步**与**户外散步（帕罗散步/日常健走）**两种模式。
  * 实时绘制 GPS 轨迹线，统计距离(km)、用时、平均配速(min/km)、步频与预估热量消耗，结束自动归档供 AI 复盘。
  * 注意声明 `foregroundServiceType="location"` + `FOREGROUND_SERVICE_LOCATION` + 运行时定位权限；若需后台持续上报另加 `ACCESS_BACKGROUND_LOCATION`。

### 10.4 驾考（科一/科四）每日百题引擎（A 类修正 + B 类新增）

**v1 的四条要求无法同时成立，v2 逐条改为可执行版本：**

| v1 原文 | 核实事实 | v2 规定 |
| :--- | :--- | :--- |
| "严格对齐 2026 最新版大纲与新规扣分标准" | **不存在这样的公开数据源。** 中国驾考题库自 **2013-01-01 起不再公开**；`122.gov.cn` 根域本机 DNS 无解析，省级子域（`hb.122.gov.cn`、`gz.122.gov.cn`）可直连但页面只有考试预约/办证业务，无题库文件或数据接口；`www.mps.gov.cn` 返回 521；12123「学法减分」仅说明"系统随机抽 20 题"，无导出。**未找到任何官方"可抓取/可再分发"许可文本**。 | 措辞改为：**以 2026-08 提取快照为基线，记分口径与年龄条款按 163 号令 / 172 号令逐条人工校订**。 |
| "彻底剔除陈旧过期题" | **没有任何社区仓库覆盖 2025 新增考点。** `AgentMystia` 自述主体为 2022.7 版，仅手工修订 163 号令记分口径与 172 号令年龄（60→63 周岁、C6 20–70），"新能源汽车/2024 便民措施等 2025 新增考点未收录"。 | 内置题库须带 `ruleVersion` 字段；对已知未覆盖的 2025 新增考点，在 App 内以「大纲待补」清单形式列出，交由用户核对当地车管所口径。 |
| "由后台 WorkManager 静默联网同步最新题库更新" | **无合法可自动增量同步的版本化数据源。** 唯一带版本号+MD5 的增量通道是驾考宝典题库 CDN `https://jk-tiku.kakamobi.cn` + `update-super` 协议，属**非授权逆向**，且 `onezion12344` 仓库 README 明确"版权归驾考宝典（北京木仓科技），禁止商业使用"。需 key 的分页 API（`mxnzp` 1000 次/天、聚合数据）**无版本号、无增量参数**。 | 后台同步改为**可选**：默认只校验内置快照版本；保留一个可配置的增量源地址槽位（留空即不联网）。 |
| "界面不设置任何繁琐的'导入题库包'按钮" | 见上，纯内置必然逐年过时。 | **破例开一个导入入口**（设置页 → 题库管理 → 导入题库文件），但保持极简：单个按钮 + 文件选择器，无向导、无格式说明页。同时 Non-Goal 相应修订（见 15.2）。 |

* **内置题库基线**（按可得性与体量排序，执行时择一）：
  * `china794/jiakao-baodian-dump`：33 个 SQLite（`car.db` 45.5MB），**225,688 题**，版本号 `202608061421`，更新 2026-08-14，仓库标 MIT（来源为逆向提取）。→ **推荐主基线**（SQLite 可直接进 Room，且带版本号）。
  * `nujgnoix/chun-jing-jia-kao`：4378 题 + 1428 图（`public/bank.json`），更新 2026-11-05；代码 MIT，但 **NOTICE 明确题库文本与图片不在 MIT 授权范围**。
  * `AgentMystia/driving-test-subject1`：科一 2115 题、714 图、解析率 99.4%，更新 2026-07-17，**无 LICENSE 文件**。
  * `doupoa/DrivingTestSubjectOne`：`q.json` 4378 条（科一 2545 + 科四 1833）/5.97MB，含 `coverUrl` 图题、`explain`、`score`，更新 2022-07-17，**MIT**。→ 合规性最好的兜底基线。
  * `LiuMashiro/...`：`Question-Bank.json` 5.97MB，2026-07-28，无 LICENSE。`GaoJiasheng/driving-quiz-app` 仅 Mock 题，**不是真题库，不得使用**。
  * HuggingFace `olivernan/chinese-driving-test`：`huggingface.co` 超时，镜像 `hf-mirror.com` 可达。
* **严禁 AI 凭空编题**（交规极其严谨，此条不变）。
* **每日 100 题智能组卷**：70% 最新考纲高频必考题 + 30% 历史错题与易混题。
* **AI 交规助教**：做错可一键"AI 拆解"，用大白话总结扣分逻辑与顺口溜记忆口诀。
* **考前排期（B 类新增）**：目标为**3 周内开考**。节奏定为 **前 10 天 100 题/天新题推进 + 后 4 天全真模考与错题回锅**（3 周 × 100 题 = 2100 题，刚好覆盖科一+科四常见量级但无模考余量，故必须腾出模考窗口）。倒计时由用户在设置页填入考试日期后自动重排。

### 10.5 美国自由行日常英语（零基础实战向）

* **场景化精选**：100% 聚焦美国自由行高频真实沟通场景 —— 机场海关入境、酒店前台办理入住、餐厅点餐与小费、Uber 打车与问路、商场购物退税、紧急情况求助。摒弃枯燥长难句和语法书。
* **标准美式发音引擎**：每日推送 5–8 句高频地道短句，内置高保真标准美式发音，支持 `1.0x` 正常语速与 `0.75x` 慢速拆解一键切换。
* **按住跟读评分**：录音 → 走 7.1 转写渠道比对目标句 → 给出相似度与逐词偏差提示。
* 用户自由行在 **3 个月内**，故本模块为第一优先级业务模块之一，不得后置。

### 10.6 `08:00` 垂直情报早报（纯干货降噪，信源清单按实测重写）

* **6 大垂直领域**：前沿科技、人工智能(AI)、全球金融宏观、加密货币(Crypto)、外汇市场(Forex)、黄金(Gold)。彻底屏蔽娱乐八卦、社会琐事与低质噪音。
* **AI 简报风格**：每天 `07:50` 自动拉取隔夜最新快讯/RSS，AI 提纯成 **8–12 条高密度早报**，每条采用【核心事实一句话】+【底层逻辑/市场影响简评】的彭博/华尔街见闻晨报风格。
* **信源可用性【实测，中国大陆出口】**：

  | 领域 | 可直连、免 key 的源 | 不可达（需代理，禁止写入默认配置） |
  | :--- | :--- | :--- |
  | 科技/AI | `ithome.com/rss/`、`sspai.com/feed`、`solidot.org/index.rss`、`geekpark.net/rss`、`qbitai.com/feed`、`ifanr.com/feed`、`infoq.cn/feed`、`tmtpost.com/rss.xml`、`people.com.cn/rss/it.xml`；境外直连可用：`openai.com/blog/rss.xml`、`rss.arxiv.org/rss/cs.AI`、`technologyreview.com/feed/`、`nature.com/nature.rss`、`techcrunch.com/feed/`、`theverge.com/rss/index.xml`、`hnrss.org/frontpage`、`dev.to/feed` | — |
  | 宏观/金融 | `api-one.wallstcn.com/apiv1/content/lives?channel=global-channel`（200 JSON，免 key）、`jin10.com/flash_newest.js`、`feed.mix.sina.com.cn/api/roll/get`、`cnbc.com/id/100003114/device/rss/rss.html`、`marketwatch.com/rss/topstories`、`federalreserve.gov/feeds/press_all.xml`、`ecb.europa.eu/rss/press.html`、`pbc.gov.cn`、`gov.cn`、`data.stats.gov.cn` | **Bloomberg（含 feeds.bloomberg.com）、Reuters、WSJ、FT 与 FT中文网、Economist、SCMP、Business Times、Straits Times、Al Jazeera、Nikkei 中文、BBC（含中文）、AP News(403)、Guardian、NYT 中文、Business Insider、Yahoo Finance(403)、Investing.com(403)** |
  | Crypto | 行情：`api.gateio.ws/v4/spot/tickers`、`api.blockchain.info/ticker`、`api.llama.fi/v2/chains`、`api.alternative.me/fng/`（恐慌贪婪指数）；媒体：`panewslab.com/rss.xml`、`theblock.co/rss.xml`、`cointelegraph.com/rss`、`decrypt.co/feed`、`blockworks.co/feed` | **Coingecko、CoinMarketCap、Binance、OKX、Bybit、Kraken、Coinbase、Bitfinex、CEX、CoinDesk、CoinGlass 全部不可达**；Odaily/ChainCatcher/BlockBeats/TechFlow 404 或 DNS 失败 |
  | 外汇 | `hq.sinajs.cn/list=fx_susdcny`（**必须带 `Referer: finance.sina.com.cn`**）、`qt.gtimg.cn/q=whUSDCNY`、`api.frankfurter.app`、`open.er-api.com/v6/latest/USD` | — |
  | 黄金 | `hq.sinajs.cn/list=hf_XAU`（伦敦金）、`hq.sinajs.cn/list=SGE_AU9999`（上海金，实测 831.50 元/克）、`qt.gtimg.cn/q=hf_XAU`、`api.gold-api.com/price/XAU`、`sge.com.cn/sjzx/mrhqsj`（HTML，需解析） | `goldprice.org` API 403、LBMA `gold.org` RSS 404 |

* **明确不可达、禁止写进默认配置的源**：`36kr.com/feed`、`jiqizhixin.com/rss`、`juejin.cn/feed`、`pingwest.com/feed` 返回 200 但**是 HTML 兜底页而非 RSS**；`huxiu` RSS 超时；`rsshub.app`、X/Twitter、Telegram、Medium、Substack、Lobsters、YouTube、Google/Google News、HuggingFace 主站均不可达。
* **覆盖度结论（如实告知用户，不得假装全覆盖）**：可完整覆盖 = 前沿科技、AI、外汇、黄金；**部分覆盖** = 宏观（国内转述 + Fed/ECB 一手 RSS，英美一手源不可达）、Crypto（行情仅剩 Gate.io/DefiLlama/blockchain.info 少数可用）。
* **实现要求**：信源清单以可配置列表存储，抓取失败的源静默跳过并在简报底部以灰字标注"N 个源今日不可达"，不得因单源失败阻塞 `08:00` 出报。

---

## 11. 本地 Room 数据库完整 Schema

执行 AI 直接基于以下实体建立 `AppDatabase`。**11.1–11.8 沿用 v1，11.9–11.13 为 v2 新增。**

### 11.1 体重与体态记录表
```kotlin
@Entity(tableName = "weight_records")
data class WeightRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,               // YYYY-MM-DD
    val timestamp: Long,
    val weightKg: Float,
    val bodyFatPercent: Float? = null,
    val isFasting: Boolean = true,
    val source: String              // "VOICE","VOICE_LOCAL_FALLBACK","SCALE_OCR","MANUAL"
)
```

### 11.2 作息与锁屏记录表
```kotlin
@Entity(tableName = "sleep_records")
data class SleepRecord(
    @PrimaryKey val date: String,            // 以起床日为准
    val actualSleepTime: String?,            // HH:mm
    val wakeUpTime: String?,
    val isLateSleep: Boolean,
    val emergencyUnlockUsed: Boolean = false,
    val emergencyUnlockDurationMin: Int = 0,
    val lockDeadline: String,                // v2 新增：当晚应锁时刻（工作日 00:00 / 周末 01:00）
    val gaveUpTonight: Boolean = false,      // v2 新增：是否按了「今晚放弃」
    val rebootedDuringLock: Boolean = false  // v2 新增：锁屏时段内是否重启过
)
```

### 11.3 饮食与热量记录表
```kotlin
@Entity(tableName = "diet_records")
data class DietRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val mealType: String,           // "BREAKFAST","LUNCH","DINNER","SNACK"
    val description: String,
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val imageUri: String? = null,
    val needsBackfill: Boolean = false   // v2 新增：本地兜底解析后待 AI 补算营养素
)
```

### 11.4 囚徒健身进度与训练日志表
```kotlin
@Entity(tableName = "calisthenics_progress")
data class CalisthenicsProgress(
    @PrimaryKey val moveCategory: String, // "PUSHUP","SQUAT","PULLUP","LEG_RAISE","BRIDGE","HANDSTAND"
    val currentStep: Int,                 // 1~10
    val currentStepName: String,
    val bestSets: Int,
    val bestReps: Int,
    val isReadyToLevelUp: Boolean
)

@Entity(tableName = "calisthenics_logs")
data class CalisthenicsLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val moveCategory: String,
    val stepNumber: Int,
    val sets: Int,
    val repsPerSet: Int,
    val notes: String? = null
)
```

### 11.5 GPS 跑步与散步轨迹记录表
```kotlin
@Entity(tableName = "gps_workouts")
data class GpsWorkout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val startTime: Long,
    val durationSec: Long,
    val workoutType: String,        // "RUN","WALK"
    val distanceMeters: Float,
    val avgPaceSecPerKm: Int,
    val caloriesBurned: Int,
    val routeGeoJson: String        // 仅本地留档，禁止注入 AI prompt（见 6.2）
)
```

### 11.6 驾考题库与错题表
```kotlin
@Entity(tableName = "driving_questions")
data class DrivingQuestion(
    @PrimaryKey val questionId: String,
    val subject: Int,               // 1 或 4
    val questionType: String,       // "SINGLE","JUDGE","MULTI"
    val content: String,
    val imageUrl: String? = null,
    val optionsJson: String,
    val correctAnswer: String,
    val officialExplanation: String,
    val ruleVersion: String,        // v2 改：替换 is2026NewRule，记录题目所依据的法规版本
    val manualReviewed: Boolean = false,  // v2 新增：是否已按 163/172 号令人工校订
    val wrongCount: Int = 0,
    val lastPracticedDate: String? = null
)

@Entity(tableName = "driving_exam_plan")
data class DrivingExamPlan(
    @PrimaryKey val id: Int = 1,
    val examDate: String,           // 用户填写的考试日期，驱动倒计时与排期
    val phase: String,              // "DAILY_100"（前10天） / "MOCK"（后4天全真模考）
    val dailyTarget: Int = 100,
    val mockPassed: Int = 0,
    val mockBestScore: Int = 0
)
```

### 11.7 AI 长期记忆档案与对话历史表
```kotlin
@Entity(tableName = "ai_memory_profile")
data class AiMemoryProfile(
    @PrimaryKey val key: String,    // "target_weight","habit_weaknesses","exam_goal","travel_plan"
    val value: String,
    val updatedAt: Long
)

@Entity(tableName = "ai_chat_messages")
data class AiChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val role: String,               // "USER","ASSISTANT"
    val textContent: String,
    val widgetType: String? = null, // null,"COMPARISON_TABLE","WEIGHT_SLEEP_CHART"
    val widgetPayloadJson: String? = null
)
```

### 11.8 通用 OpenAI 模型配置表
```kotlin
@Entity(tableName = "ai_providers")
data class AiProviderConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerName: String,
    val baseUrl: String,
    val apiKeyEncrypted: String,    // v2 改：Keystore AES-GCM 密文，禁止明文（见 8.4）
    val transcriptionPath: String = "/audio/transcriptions",  // v2 新增：路径覆盖，DeepSeek 需填 /tools/asr
    val cachedModelsJson: String
)
```

### 11.9 自律天数与达标表【新增，硬指标载体】
```kotlin
@Entity(tableName = "self_discipline_streak", indices = [Index("status")])
data class SelfDisciplineStreak(
    @PrimaryKey val date: String,           // YYYY-MM-DD
    val weightLogged: Boolean = false,      // 硬项1 组成部分
    val dietLogged: Boolean = false,
    val sleepRecorded: Boolean = false,
    val sleepOnTime: Boolean = false,       // 未超当晚 lockDeadline
    val drivingTargetMet: Boolean = false,  // 硬项2：当日题量达标
    val allThreeHardMet: Boolean = false,   // 达标判定结果
    val streakValue: Int = 0,               // 截至当日的连续天数
    val status: String                      // "MET" / "BROKEN" / "BACKFILL_ONLY" / "PENDING"
)
```

### 11.10 破戒事件表【新增】
```kotlin
@Entity(tableName = "violation_records")
data class ViolationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val timestamp: Long,
    val type: String,        // "ESCAPE_LOCK"(遮罩离开前台) / "REBOOT_DURING_LOCK"
                             // "GAVE_UP_TONIGHT"(按今晚放弃) / "LATE_SLEEP"(超红线)
                             // "DRIVING_NOT_MET" / "DATA_NOT_LOGGED"
    val detail: String? = null,
    val breaksStreak: Boolean // 是否导致断链
)
```

### 11.11 解锁事件表【新增】
```kotlin
@Entity(tableName = "unlock_events")
data class UnlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val unlockType: String,   // "EMERGENCY_PASSWORD"（不记破戒） / "GIVE_UP"（记破戒）
    val grantedAt: Long,
    val expiresAt: Long,      // grantedAt + 60min
    val usedCount: Int = 1,   // 当晚已用次数，上限 1
    val revokedAt: Long? = null
)
```

### 11.12 阈值与参数可配置表【新增，用户明确要求"设置数据入口自由修改"】
```kotlin
@Entity(tableName = "user_thresholds")
data class UserThreshold(
    @PrimaryKey val key: String,   // 见 13 章清单
    val value: String,
    val valueType: String,         // "INT","FLOAT","BOOL","TIME","DATE","STRING_LIST"
    val label: String,             // 设置页显示名
    val unit: String? = null,      // "kg","kcal","g","分钟","题"
    val updatedAt: Long
)
```

### 11.13 补录标记【新增】
`weight_records` / `diet_records` / `sleep_records` / `calisthenics_logs` / `driving_questions` 的写入路径统一带 `isBackfill: Boolean` 标记（可复用 11.9 的 `status="BACKFILL_ONLY"` 表达日级结果）。**补录数据保留、参与趋势分析，但不参与当日达标判定**（见 12.2）。

---

## 12. 判定规则（B 类新增，执行 AI 不得自行解释）

### 12.1 「达标一天」的定义 —— 三硬项

一天记为 `MET` 当且仅当以下三项**全部**成立：

1. **作息不越线**：入睡时间不晚于当晚 `lockDeadline`（工作日 `00:00`，周六周日 `01:00`）。
2. **驾考任务完成**：当日完成组卷规定的题量（前 10 天为 100 题；后 4 天为规定场数全真模考）。
3. **三项数据入库**：体重、饮食、作息三项**当日实时**均有记录。

**加分项（不卡达标）**：囚徒健身训练、GPS 跑步/散步、英语学习。

### 12.2 补录规则

* 数据**可以事后补录**（不丢记录，参与趋势与折线图）。
* 但**达标判定只看"当日实时录入"**：补录不补链，该日记为 `BACKFILL_ONLY`。
* 目的：防住"周日晚上一次性补齐上周七天"。

### 12.3 断链触发条件（任一即断）

| 触发 | 是否断链 | 说明 |
| :--- | :--- | :--- |
| 三硬项任一未达标 | **断** | 12.1 |
| 遮罩/锁屏界面离开前台（逃逸） | **断** | 9.6，记 `ESCAPE_LOCK` |
| 锁屏时段内设备重启过 | **断** | 9.6，记 `REBOOT_DURING_LOCK` |
| 按【今晚放弃】 | **断** | 9.5，记 `GAVE_UP_TONIGHT` |
| 用紧急密码解锁 60 分钟 | 不断 | 9.5，只记 `unlock_events` |
| 数据为次日补录 | 不断但记 `BACKFILL_ONLY` | 12.2 |
| 电源键点亮后自动重拉遮罩 | **不断** | 9.3，看时间不是逃逸 |

### 12.4 每周复盘

`streakValue` 与 `maxStreak` 在仪表盘顶部常驻；AI 每晚复盘与晨间简报必须点名当日断链原因（若有）。

---

## 13. 阈值与参数可配置清单（用户可在设置页自由修改）

设置页提供统一「数据与阈值」入口，下列项全部可编辑，写入 `user_thresholds`，**修改后即时影响 AI 判定与仪表盘显示**：

| key | 默认值 | 说明 |
| :--- | :--- | :--- |
| `target_weight_kg` | 用户填 | 目标体重 |
| `current_weight_kg` | 最近一次记录 | 当前体重（展示用） |
| `daily_calorie_limit_kcal` | 用户填 | 每日热量上限 |
| `daily_protein_target_g` | 用户填 | 每日蛋白目标 |
| `daily_carbs_target_g` | 用户填 | 每日碳水目标 |
| `daily_fat_target_g` | 用户填 | 每日脂肪目标 |
| `weekday_lock_time` | `00:00` | 工作日锁屏时刻 |
| `weekend_lock_time` | `01:00` | 周末锁屏时刻 |
| `weekday_wake_time` | `08:00` | 工作日唤醒时刻 |
| `weekend_wake_time` | `09:00` | 周末唤醒时刻 |
| `weekend_wake_silent_until` | `09:15` | 周末静音亮屏截止（超过才响） |
| `lock_screen_off_delay_min` | `5` | 遮罩熄屏延时 |
| `emergency_unlock_minutes` | `60` | 紧急通行时长 |
| `emergency_unlock_max_per_night` | `1` | 每晚解锁次数上限 |
| `emergency_password_hash` | 用户设 | 6 位密码（存哈希，不存明文） |
| `daily_driving_target` | `100` | 每日刷题量 |
| `exam_date` | 用户填 | 考试日期，驱动倒计时与"前10天+后4天"排期 |
| `travel_date` | 用户填 | 美国自由行日期，驱动英语模块节奏 |
| `daily_english_sentences` | `5` | 每日英语句数（范围 5–8） |
| `tts_source` | `LOCAL` | `LOCAL` / `CLOUD` |
| `tts_sample_accepted` | `false` | 样板句试听是否已确认 |
| `news_sources_enabled_list` | 10.6 清单 | 可逐条开关信源 |
| `ai_inject_exclude_privacy` | `true` | 是否执行 6.2 隐私排除 |
| `backfill_counts_as_met` | `false` | 补录是否算达标（默认否，见 12.2） |

**要求**：所有阈值修改需即时生效、无需重启；仪表盘与 AI 简报必须读取当前值而非硬编码常量。

---

## 14. 页面信息架构与导航流

采用极简 **3+1 底部导航结构**，所有二级操作均可通过语音直达或卡片点击进入：

1. **Tab 1：今日仪表盘（Today Dashboard）**
   * 顶部：日期、**当前连续自律天数 / 最长纪录**、距当晚锁屏剩余 `X 小时 Y 分`、**今日三硬项完成进度（3/3 计数）**。
   * 核心数据卡片网格（iOS 18 磨砂玻璃质感）：体重卡片（今日空腹体重 + 较昨日变化箭头 + 一键拍照识秤/语音记重）、08:00 垂直情报早报卡片、饮食热量环（已摄入/目标 kcal + 三大营养素比例 + 一键拍照识餐）、今日学习双卡（驾考 `已完成 X / 100 题` + 英语 `今日 5 句实战口语`）、今日体能双卡（囚徒健身当前式数进度条 + 户外 GPS 一键启动）。
2. **中央悬浮按钮：全语音 AI 搭档中枢（Voice AI Hub）**
   * 展开透明玻璃对话流，支持按住语音、TTS 实时朗读、对话流内嵌原生数据对比表格与双轴折线图。
3. **Tab 2：训练与学习实验室（Training & Study）**
   * 上半区：囚徒健身六艺十式晋级看板 + 原生 GPS 户外运动实时地图轨迹追踪器。
   * 下半区：驾考每日百题沉浸刷题器（含 AI 助教错题口诀拆解 + 后 4 天全真模考模式） + 美国自由行英语场景跟读器（`1.0x`/`0.75x` 美式发音 + 跟读评分）。
4. **Tab 3：偏好与系统（Settings & AI Router & Root）**
   * 主题切换器（4 套实时预览）
   * 通用 AI 模型路由中心（含转写路径覆盖、TTS 绑定、样板句试听开关）
   * **数据与阈值**入口（第 13 章全部项）
   * Root 与锁屏策略：Root 能力自检面板、Device Owner 激活状态与**「解除设备管理员」入口**、紧急密码修改、锁屏/唤醒时刻、LockTask 白名单管理、无障碍与悬浮窗授权状态
   * **题库管理 → 导入题库文件**（单个按钮 + 文件选择器，见 10.4）
   * 当前锁屏强度等级显示（`Device Owner 真拦死` / `降级：遮罩+记账`）

---

## 15. 明确排除项（Non-Goals）与修订说明

### 15.1 执行 AI 严禁自行添加以下功能

1. **禁止开发任何云端账号登录、注册或社交排行榜功能。**
2. **禁止在语音界面默认弹出全键盘**（必须以语音输入作为第一核心交互）。
3. **禁止在 UI 中暴露"备份/导出/迁移"入口**（保持极简；后台静默快照除外，见 16.1）。
4. **禁止添加游戏化徽章、连续天数奖励动画、打卡分享图**等与"极致克制"相冲的元素。
5. **禁止让 AI 生成驾考题目**（只可解释、不可出题）。

### 15.2 v1 Non-Goals 的两处修订（v2 变更，必须执行）

* **v1 Non-Goal 1「禁止开发本地数据备份、导出、导入或迁移功能」→ 修订为**：UI 一条不露（不加"导出"按钮），但**后台每天静默写一份数据库快照到 `/sdcard/Download/DSH-Go/backup/`，只保留最近 14 份**。
  > 理由：本 App 的全部价值（数月体重、作息、训练、错题、AI 长期记忆档案）只存在于**一个 Room 数据库文件**里。一次误清数据、一次 ROM 升级翻车、一次存储损坏即全量归零，且 AI"认识你多年"的记忆一起消失。成本约 10 行代码，换掉唯一不可逆风险。
* **v1 Non-Goal 2「禁止开发手动导入驾考题库包的 UI 入口」→ 取消该条**，改为设置页单个"导入题库文件"按钮。
  > 理由见 10.4：不存在合法可自动增量同步的 2026 题库源，纯内置必然逐年过时，而驾考一次通过是本系统的两个硬指标之一。

---

## 16. 数据、隐私与安全边界

### 16.1 本地存储

* 全部业务数据仅存本机 Room；每日静默快照见 15.2。
* `API_KEY` 经 Android Keystore（KeyMint/StrongBox）AES-GCM 加密存储；紧急密码只存哈希。
* **禁止使用 `androidx.security-crypto`**（已弃用，见 8.4）。

### 16.2 出境数据边界（硬性）

会离开本机、进入第三方模型服务器的内容：

* 允许：体重、饮食、训练、驾考答题、英语学习记录、作息**判定结论**、长期记忆档案、语音音频（走 7.1 转写渠道）。
* **禁止注入**：GPS 轨迹原始经纬度点数组、精确到分钟的原始时间戳（见 6.2）。
* 选云端 TTS 时，AI 回复文本会额外外发一次（见 7.2），需在设置页明确标注。

---

## 17. 交付要求与验收标准

* **交付形态**：**一次性交付最终可安装 APK**（不分期、不给中间版）。构建在电脑侧完成（见 2.3）。
* **编译目标**：`compileSdk 36`、`targetSdk 36`（Android 16），`minSdk` 建议 30+。
* **必须真机验证后才能算交付的 5 个【待验证】项**：
  1. Device Owner 能否在本机激活（9.1 三条路径）。
  2. `setStatusBarDisabled` + LockTask 在本机 ColorOS V16.1.0 上能否真正屏蔽下拉与 Home/近期。
  3. `com.oplus.ttsaccessibilityengine` 的中文与美式英文音色是否可接受（7.2 样板句试听）。
  4. `setAlarmClock` 在 ColorOS 后台冻结下能否 `00:00`/`08:00` 准点拉起（允许误差 ≤ 60 秒）。
  5. UsageStats 权限授予后逃逸判定是否可靠（9.6）。
* **验收清单（功能层）**：
  * 说一句混合意图的话，3 个 Tool Call 全部正确入库且回复自然。
  * 断网/欠费状态下说"空腹 72.4"，本地兜底成功入库。
  * `00:00` 遮罩准点出现；按 Home / 划掉遮罩 → 次日简报点名且 streak 断。
  * 遮罩 5 分钟后熄屏；电源键点亮自动重拉且不记逃逸。
  * 紧急密码解锁 → 60 分钟倒计时胶囊常驻不可关；归零自动重锁；当晚第二次密码无效。
  * 解锁窗口内只有应急六项可开，短视频/游戏打不开。
  * 锁屏时段内重启 → 记 `REBOOT_DURING_LOCK` 并断链。
  * 拍一张餐盘照，菜品+热量+三大营养素入库并可语音修正。
  * 驾考：组卷 70/30 配比正确；错题可"AI 拆解"；`exam_date` 改动后前 10 天/后 4 天排期自动重算。
  * 四套主题切换无闪烁；数字指标变化不抖动。
  * 修改 `daily_calorie_limit_kcal` 后，饮食热量环与 AI 判定立即按新值走。
  * 次日 `/sdcard/Download/DSH-Go/backup/` 出现快照，且第 15 份时最旧的被清掉。

---

## 18. 附录：v1 → v2 变更对照表

### A 类：技术前提修正（功能目标不变）

| # | v1 原文位置 | v1 主张 | v2 处置 | 依据 |
| :--- | :--- | :--- | :--- | :--- |
| A1 | §2 语音引擎 | 集成 Android 原生 `SpeechRecognizer` | **删除该选项**，改云端转写 | 本机实测无 `RecognitionService` 提供方 |
| A2 | §2 Root 层 / §6.1 | `policy_control immersive.full=*` 禁用下拉 | 改 `DevicePolicyManager.setStatusBarDisabled`（仅 DO 可用） | AOSP 只强制沉浸，下滑仍唤出 shade；`STATUS_BAR` 为 signature 权限 |
| A3 | §6.2 | `TYPE_APPLICATION_OVERLAY` 全屏遮罩拦返回/近期 | 改 **Device Owner + LockTask** 主线，overlay 降为兜底 | 窗口层级 + Android 12 untrusted touch + Android 14 FSI 限制 + Android 15 restricted settings + ColorOS 悬浮窗自关 |
| A4 | §2 后台引擎 | `setExactAndAllowWhileIdle` 做 00:00/08:00 | 改 **`setAlarmClock`** | Doze 下 9 分钟一次节流；Android 14 起 `SCHEDULE_EXACT_ALARM` 默认拒绝 |
| A5 | §6.1 | `dumpsys deviceidle whitelist` + 改 `oom_score_adj` 即保活 | 保留但**不得作为锁屏唯一触发路径** | Android 14 cached-apps freezer；AMS 覆写 oom_adj；phantom process killer；Android 15 force-stop 清 PendingIntent |
| A6 | §2 | 前台服务直接常驻 | 必须声明 `foregroundServiceType` + 对应权限；注意 Android 15 6 小时超时与 BOOT_COMPLETED 限制 | Android 14/15 FGS 行为变更 |
| A7 | §8 `AiProviderConfig.apiKey` | 明文存 Key | 改 `apiKeyEncrypted`，Keystore AES-GCM；**禁用 `androidx.security-crypto`** | 该库全 API 已 deprecated |
| A8 | §5 | 兼容 `/v1/audio/transcriptions` 即可 | 渠道配置加**「转写路径覆盖」**字段；智谱为唯一真兼容渠道，DeepSeek 走 `/v1/tools/asr` | 各家协议形态实测 |
| A9 | §7.4 | 内置 2026 大纲 + 无导入 UI + 静默增量更新 | 见 10.4 四条逐一改写；内置快照 + 人工校订 + 破例导入入口 | 官方题库 2013 年起不公开；无合法版本化增量源；社区源未覆盖 2025 新增考点 |
| A10 | §7.5 | 6 大领域信源 | 按实测重写可用/不可达清单，如实标注 Crypto 与英美宏观为部分覆盖 | Bloomberg/Reuters/WSJ/FT/Economist/Coingecko/Binance 等本机不可达 |
| A11 | §6.2 | 遮罩整夜常亮显示时钟 | 遮罩 **5 分钟后熄屏**，电源键点亮自动重拉 | 一夜掉电 10–30%+；OLED 静态高对比图像残留风险；厂商防烧屏在持有 KEEP_SCREEN_ON 时不介入 |
| A12 | §4.2 | 无障碍服务需用户手动开启 | Root 静默写入启用 | Android 13/15 sideload 应用受限设置会被静默回收 |

### B 类：需求补全（v1 未定义，v2 拍板）

| # | 补全项 | 结论 |
| :--- | :--- | :--- |
| B1 | 唯一成功判据 | **驾考一次通过 + 自律天数不断链**；体重/作息/英语降为观察项 |
| B2 | 达标一天的定义 | 三硬项：作息不越线 + 驾考任务完成 + 体重/饮食/作息当日实时入库；训练/英语/跑步为加分项 |
| B3 | 断链触发 | 三硬项未达标、逃逸、锁屏时段重启、按「今晚放弃」；密码解锁与看时间点亮不断链 |
| B4 | 补录规则 | 可补录、参与趋势，**不算达标**（防周日一次性补齐） |
| B5 | 周末作息 | 锁屏 `01:00`、唤醒 `09:00` 且默认静音、`09:15` 无交互才响；紧急解锁仍每晚 1 次不放宽 |
| B6 | 逃逸判定标准 | 遮罩 Activity **离开前台**即算（UsageStats）；辅以重启检测记账 |
| B7 | 逃生阀 | 新增【今晚放弃】按钮：记破戒断链；紧急密码解锁不记破戒 |
| B8 | LockTask 白名单 | 只放行应急六项：电话、短信、地图、支付、微信 |
| B9 | 早晨解除时机 | 简报确认或 `08:30` 自动兜底 |
| B10 | 夜间通知 | 仅来电与系统闹钟照常，其余不显示 |
| B11 | DO 退出保险 | 声明 `<uninstall-device-policy-admins/>` + 设置页内置「解除设备管理员」入口 |
| B12 | AI 不可用兜底 | 本地规则解析器覆盖体重/作息/饮食三类简单字段 |
| B13 | TTS 策略 | 本机优先 + 中英混合样板句试听 + 一键切云端 |
| B14 | 隐私排除 | GPS 轨迹明细与精确分钟级时间戳不注入 prompt |
| B15 | 数据阈值 | 新增设置页统一「数据与阈值」入口，14 章清单全部可自由修改且即时生效 |
| B16 | 新增数据表 | `self_discipline_streak`、`violation_records`、`unlock_events`、`user_thresholds`、`driving_exam_plan` |
| B17 | 考前排期 | 3 周内开考 → 前 10 天 100 题/天 + 后 4 天全真模考与错题回锅 |
| B18 | 英语模块 | 自由行在 3 个月内 → 跟读评分保留，为第一优先级业务模块 |
| B19 | 主题 | 四套全做，组件库只实现一套通用模糊/描边/弹簧，四套仅切 token |
| B20 | 备份 | UI 不露，后台每日静默快照保留 14 份 |
| B21 | 交付 | 一次性交付最终 APK；5 个【待验证】项必须真机跑通 |

---

> **致执行 AI 的最后一条**：本文档所有"禁止""必须"均为硬性。遇到本文档未覆盖的实现细节，按"极致克制 + 零打字 + 两个硬指标优先"三条原则自行裁定，不要回来问需求。
