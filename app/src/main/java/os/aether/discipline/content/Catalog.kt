package os.aether.discipline.content

import os.aether.discipline.data.db.DrivingQuestion
import os.aether.discipline.data.db.UserThreshold

data class StepDef(
    val category: String,
    val number: Int,
    val name: String,
    val upSets: Int,
    val upReps: Int,
) {
    val beginnerSets: Int = 1
    val beginnerReps: Int = (upReps / 4).coerceAtLeast(5)
    val midSets: Int = 2
    val midReps: Int = (upReps / 2).coerceAtLeast(8)
}

data class Phrase(val id: String, val scene: String, val english: String, val chinese: String)

data class NewsSource(
    val id: String,
    val domain: String,
    val name: String,
    val url: String,
    val kind: String,
    val referer: String? = null,
)

private fun steps(category: String, names: List<Pair<String, Pair<Int, Int>>>): List<StepDef> =
    names.mapIndexed { index, (name, gate) ->
        StepDef(category, index + 1, name, gate.first, gate.second)
    }

fun convictCatalog(): List<StepDef> = buildList {
    addAll(steps("PUSHUP", listOf(
        "墙壁俯卧撑" to (3 to 50),
        "上斜俯卧撑" to (3 to 40),
        "跪姿俯卧撑" to (3 to 30),
        "半程俯卧撑" to (2 to 25),
        "标准俯卧撑" to (2 to 20),
        "窄距俯卧撑" to (2 to 20),
        "偏重俯卧撑" to (2 to 20),
        "单臂半程俯卧撑" to (2 to 20),
        "杠杆俯卧撑" to (2 to 20),
        "单臂俯卧撑" to (1 to 10),
    )))
    addAll(steps("SQUAT", listOf(
        "肩倒立深蹲" to (3 to 50),
        "折刀深蹲" to (3 to 40),
        "支撑深蹲" to (3 to 30),
        "半深蹲" to (2 to 25),
        "标准深蹲" to (2 to 20),
        "窄距深蹲" to (2 to 20),
        "偏重深蹲" to (2 to 20),
        "单腿半程深蹲" to (2 to 20),
        "辅助单腿深蹲" to (2 to 20),
        "单腿深蹲" to (1 to 10),
    )))
    addAll(steps("PULLUP", listOf(
        "垂直引体" to (3 to 40),
        "水平引体" to (3 to 30),
        "折刀引体" to (3 to 20),
        "半程引体" to (2 to 15),
        "标准引体向上" to (2 to 10),
        "窄距引体" to (2 to 10),
        "偏重引体" to (2 to 9),
        "单臂半程引体" to (2 to 8),
        "辅助单臂引体" to (2 to 7),
        "单臂引体向上" to (1 to 6),
    )))
    addAll(steps("LEG_RAISE", listOf(
        "坐姿屈膝" to (3 to 40),
        "平卧屈膝" to (3 to 35),
        "平卧屈腿上举" to (3 to 30),
        "平卧蛙式" to (2 to 25),
        "平卧直腿上举" to (2 to 20),
        "悬垂屈膝" to (2 to 15),
        "悬垂屈腿" to (2 to 15),
        "悬垂蛙式" to (2 to 15),
        "悬垂半程直腿" to (2 to 12),
        "悬垂直腿上举" to (2 to 10),
    )))
    addAll(steps("BRIDGE", listOf(
        "短桥" to (3 to 50),
        "直桥" to (3 to 40),
        "斜桥" to (3 to 30),
        "头桥" to (2 to 25),
        "半桥" to (2 to 20),
        "标准桥" to (2 to 15),
        "靠墙下行桥" to (2 to 10),
        "靠墙上行桥" to (2 to 8),
        "合桥" to (2 to 6),
        "铁板桥" to (1 to 5),
    )))
    addAll(steps("HANDSTAND", listOf(
        "靠墙顶立" to (1 to 30),
        "乌鸦式" to (1 to 60),
        "靠墙倒立" to (1 to 120),
        "半程倒立撑" to (2 to 20),
        "倒立撑" to (2 to 15),
        "窄距倒立撑" to (2 to 12),
        "偏重倒立撑" to (2 to 10),
        "单臂半程倒立撑" to (2 to 8),
        "杠杆倒立撑" to (2 to 6),
        "单臂倒立撑" to (1 to 5),
    )))
}

fun stepOf(category: String, number: Int): StepDef =
    convictCatalog().first { it.category == category && it.number == number }

fun categoryLabel(category: String): String = when (category) {
    "PUSHUP" -> "俯卧撑"
    "SQUAT" -> "深蹲"
    "PULLUP" -> "引体向上"
    "LEG_RAISE" -> "举腿"
    "BRIDGE" -> "桥"
    "HANDSTAND" -> "倒立撑"
    else -> category
}

fun phrases(): List<Phrase> = listOf(
    Phrase("air-1", "入境", "I'm here for tourism and I'll stay for ten days.", "我来旅游，待十天。"),
    Phrase("air-2", "入境", "This is my return ticket and my hotel address.", "这是回程机票和酒店地址。"),
    Phrase("air-3", "入境", "I have nothing to declare.", "我没有需要申报的物品。"),
    Phrase("air-4", "入境", "Where is baggage claim for this flight?", "这个航班的行李提取处在哪？"),
    Phrase("air-5", "入境", "Could you point me to the taxi stand?", "出租车站怎么走？"),
    Phrase("air-6", "入境", "My bag didn't arrive. I'd like to file a claim.", "我的行李没到，我要登记。"),
    Phrase("hot-1", "酒店", "I have a reservation under this name.", "我用这个名字订了房。"),
    Phrase("hot-2", "酒店", "Could I check in a little early?", "可以提前入住吗？"),
    Phrase("hot-3", "酒店", "What time is checkout?", "退房是几点？"),
    Phrase("hot-4", "酒店", "The air conditioning isn't working.", "空调坏了。"),
    Phrase("hot-5", "酒店", "Could I get a quiet room, away from the elevator?", "能给我一间远离电梯的安静房间吗？"),
    Phrase("hot-6", "酒店", "Is breakfast included?", "含早餐吗？"),
    Phrase("eat-1", "餐厅", "A table for one, please.", "请给我一位的位子。"),
    Phrase("eat-2", "餐厅", "I'll have this, and water is fine.", "我要这个，水就行。"),
    Phrase("eat-3", "餐厅", "No onions, please.", "请不要放洋葱。"),
    Phrase("eat-4", "餐厅", "Could we split the check?", "可以分开结账吗？"),
    Phrase("eat-5", "餐厅", "Is service included, or should I leave a tip?", "服务费含了吗，还是要另给小费？"),
    Phrase("eat-6", "餐厅", "Could I get this to go?", "可以打包吗？"),
    Phrase("ride-1", "出行", "I'm at the north exit of the station.", "我在车站北口。"),
    Phrase("ride-2", "出行", "Please take me to this address.", "请送我到这个地址。"),
    Phrase("ride-3", "出行", "You can drop me off on the right.", "在右边停就行。"),
    Phrase("ride-4", "出行", "How long will it take from here?", "从这里要多久？"),
    Phrase("ride-5", "出行", "Is this the right platform for downtown?", "去市中心是这个站台吗？"),
    Phrase("ride-6", "出行", "Could you say that again more slowly?", "能再说慢一点吗？"),
    Phrase("shop-1", "购物", "I'm just looking, thanks.", "我先看看，谢谢。"),
    Phrase("shop-2", "购物", "Do you have this in a medium?", "这个有中号吗？"),
    Phrase("shop-3", "购物", "Can I try it on?", "我可以试穿吗？"),
    Phrase("shop-4", "购物", "What's your return policy?", "退货规则是什么？"),
    Phrase("shop-5", "购物", "Could I get a tax refund form?", "可以给我退税单吗？"),
    Phrase("shop-6", "购物", "I'll pay by card.", "我刷卡。"),
    Phrase("sos-1", "紧急", "I need help. Please call the police.", "我需要帮助，请报警。"),
    Phrase("sos-2", "紧急", "Where is the nearest hospital?", "最近的医院在哪？"),
    Phrase("sos-3", "紧急", "I've lost my passport.", "我的护照丢了。"),
    Phrase("sos-4", "紧急", "Can you stay with me until they arrive?", "在他们到之前你能陪着我吗？"),
    Phrase("sos-5", "紧急", "I feel dizzy and I need to sit down.", "我头晕，得坐下。"),
    Phrase("sos-6", "紧急", "This is my emergency contact.", "这是我的紧急联系人。"),
)

fun newsSources(): List<NewsSource> = listOf(
    NewsSource("ithome", "科技", "IT之家", "https://www.ithome.com/rss/", "RSS"),
    NewsSource("sspai", "科技", "少数派", "https://sspai.com/feed", "RSS"),
    NewsSource("solidot", "科技", "Solidot", "https://www.solidot.org/index.rss", "RSS"),
    NewsSource("geekpark", "科技", "极客公园", "https://www.geekpark.net/rss", "RSS"),
    NewsSource("qbitai", "AI", "量子位", "https://www.qbitai.com/feed", "RSS"),
    NewsSource("ifanr", "科技", "爱范儿", "https://www.ifanr.com/feed", "RSS"),
    NewsSource("infoq", "科技", "InfoQ 中文", "https://www.infoq.cn/feed", "RSS"),
    NewsSource("tmtpost", "科技", "钛媒体", "https://www.tmtpost.com/rss.xml", "RSS"),
    NewsSource("people-it", "科技", "人民网 IT", "http://www.people.com.cn/rss/it.xml", "RSS"),
    NewsSource("openai", "AI", "OpenAI Blog", "https://openai.com/blog/rss.xml", "RSS"),
    NewsSource("arxiv-ai", "AI", "arXiv cs.AI", "https://rss.arxiv.org/rss/cs.AI", "RSS"),
    NewsSource("techreview", "科技", "MIT Tech Review", "https://www.technologyreview.com/feed/", "RSS"),
    NewsSource("nature", "科技", "Nature", "https://www.nature.com/nature.rss", "RSS"),
    NewsSource("techcrunch", "科技", "TechCrunch", "https://techcrunch.com/feed/", "RSS"),
    NewsSource("verge", "科技", "The Verge", "https://www.theverge.com/rss/index.xml", "RSS"),
    NewsSource("hn", "科技", "Hacker News", "https://hnrss.org/frontpage", "RSS"),
    NewsSource("devto", "科技", "DEV", "https://dev.to/feed", "RSS"),
    NewsSource("wallst", "宏观", "华尔街见闻", "https://api-one.wallstcn.com/apiv1/content/lives?channel=global-channel", "JSON"),
    NewsSource("cnbc", "宏观", "CNBC", "https://www.cnbc.com/id/100003114/device/rss/rss.html", "RSS"),
    NewsSource("marketwatch", "宏观", "MarketWatch", "https://www.marketwatch.com/rss/topstories", "RSS"),
    NewsSource("fed", "宏观", "美联储", "https://www.federalreserve.gov/feeds/press_all.xml", "RSS"),
    NewsSource("ecb", "宏观", "欧洲央行", "https://www.ecb.europa.eu/rss/press.html", "RSS"),
    NewsSource("gate", "Crypto", "Gate.io BTC", "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=BTC_USDT", "JSON"),
    NewsSource("fng", "Crypto", "恐慌贪婪", "https://api.alternative.me/fng/?limit=1", "JSON"),
    NewsSource("llama", "Crypto", "DefiLlama", "https://api.llama.fi/v2/chains", "JSON"),
    NewsSource("blockchain", "Crypto", "Blockchain.info", "https://api.blockchain.info/ticker", "JSON"),
    NewsSource("panews", "Crypto", "PANews", "https://www.panewslab.com/rss.xml", "RSS"),
    NewsSource("theblock", "Crypto", "The Block", "https://www.theblock.co/rss.xml", "RSS"),
    NewsSource("cointelegraph", "Crypto", "Cointelegraph", "https://cointelegraph.com/rss", "RSS"),
    NewsSource("decrypt", "Crypto", "Decrypt", "https://decrypt.co/feed", "RSS"),
    NewsSource("blockworks", "Crypto", "Blockworks", "https://blockworks.co/feed", "RSS"),
    NewsSource("sina-fx", "外汇", "新浪美元人民币", "https://hq.sinajs.cn/list=fx_susdcny", "SINA", "https://finance.sina.com.cn"),
    NewsSource("qq-fx", "外汇", "腾讯美元人民币", "https://qt.gtimg.cn/q=whUSDCNY", "SINA"),
    NewsSource("frankfurter", "外汇", "Frankfurter", "https://api.frankfurter.app/latest?from=USD&to=CNY", "JSON"),
    NewsSource("erapi", "外汇", "ExchangeRate", "https://open.er-api.com/v6/latest/USD", "JSON"),
    NewsSource("sina-xau", "黄金", "新浪伦敦金", "https://hq.sinajs.cn/list=hf_XAU", "SINA", "https://finance.sina.com.cn"),
    NewsSource("sina-au", "黄金", "上金所 Au9999", "https://hq.sinajs.cn/list=SGE_AU9999", "SINA", "https://finance.sina.com.cn"),
    NewsSource("qq-xau", "黄金", "腾讯伦敦金", "https://qt.gtimg.cn/q=hf_XAU", "SINA"),
    NewsSource("goldapi", "黄金", "Gold API", "https://api.gold-api.com/price/XAU", "JSON"),
)

fun defaultThresholds(): List<UserThreshold> {
    val now = System.currentTimeMillis()
    val sources = newsSources().joinToString(",") { it.id }
    fun row(key: String, value: String, type: String, label: String, unit: String? = null) =
        UserThreshold(key, value, type, label, unit, now)
    return listOf(
        row("target_weight_kg", "", "FLOAT", "目标体重", "kg"),
        row("current_weight_kg", "", "FLOAT", "当前体重", "kg"),
        row("daily_calorie_limit_kcal", "", "INT", "每日热量上限", "kcal"),
        row("daily_protein_target_g", "", "FLOAT", "每日蛋白目标", "g"),
        row("daily_carbs_target_g", "", "FLOAT", "每日碳水目标", "g"),
        row("daily_fat_target_g", "", "FLOAT", "每日脂肪目标", "g"),
        row("weekday_lock_time", "00:00", "TIME", "工作日锁屏"),
        row("weekend_lock_time", "01:00", "TIME", "周末锁屏"),
        row("weekday_wake_time", "08:00", "TIME", "工作日唤醒"),
        row("weekend_wake_time", "09:00", "TIME", "周末唤醒"),
        row("weekend_wake_silent_until", "09:15", "TIME", "周末静音截止"),
        row("lock_screen_off_delay_min", "5", "INT", "遮罩熄屏延时", "分钟"),
        row("emergency_unlock_minutes", "60", "INT", "紧急通行时长", "分钟"),
        row("emergency_unlock_max_per_night", "1", "INT", "每晚解锁次数"),
        row("emergency_password_hash", "", "STRING", "紧急密码哈希"),
        row("emergency_password_salt", "", "STRING", "紧急密码盐"),
        row("daily_driving_target", "100", "INT", "每日刷题量", "题"),
        row("exam_date", "", "DATE", "驾考日期"),
        row("travel_date", "", "DATE", "自由行日期"),
        row("daily_english_sentences", "5", "INT", "每日英语句数", "句"),
        row("tts_source", "LOCAL", "STRING", "朗读来源"),
        row("tts_sample_accepted", "false", "BOOL", "样板句已确认"),
        row("news_sources_enabled_list", sources, "STRING_LIST", "早报信源"),
        row("ai_inject_exclude_privacy", "true", "BOOL", "注入时排除隐私明细"),
        row("backfill_counts_as_met", "false", "BOOL", "补录算达标"),
        row("lock_whitelist_packages", os.aether.discipline.domain.DefaultWhitelist.joinToString(","), "STRING_LIST", "紧急白名单包名"),
        row("scene_companion_provider", "", "STRING", "搭档渠道"),
        row("scene_companion_model", "", "STRING", "搭档模型"),
        row("scene_fast_provider", "", "STRING", "速记渠道"),
        row("scene_fast_model", "", "STRING", "速记模型"),
        row("scene_transcribe_provider", "", "STRING", "转写渠道"),
        row("scene_transcribe_model", "", "STRING", "转写模型"),
        row("scene_vision_provider", "", "STRING", "视觉渠道"),
        row("scene_vision_model", "", "STRING", "视觉模型"),
        row("scene_briefing_provider", "", "STRING", "早报渠道"),
        row("scene_briefing_model", "", "STRING", "早报模型"),
        row("scene_tts_provider", "", "STRING", "云端音色渠道"),
        row("scene_tts_model", "", "STRING", "云端音色模型"),
        row("scene_tts_path", "/audio/speech", "STRING", "云端 TTS 路径"),
        row("lock_strength", "UNKNOWN", "STRING", "锁屏强度"),
    )
}

private data class Q(
    val id: String,
    val subject: Int,
    val type: String,
    val content: String,
    val options: String,
    val answer: String,
    val explain: String,
    val high: Boolean,
)

fun seedQuestions(): List<DrivingQuestion> = questionRows().map { q ->
    DrivingQuestion(
        questionId = q.id,
        subject = q.subject,
        questionType = q.type,
        content = q.content,
        optionsJson = q.options,
        correctAnswer = q.answer,
        officialExplanation = q.explain,
        ruleVersion = "163-172-public-2026-11",
        manualReviewed = true,
        highYield = q.high,
    )
}

private fun questionRows(): List<Q> = listOf(
    Q("s1-01", 1, "JUDGE", "红灯亮时，禁止车辆通行。", "[\"正确\",\"错误\"]", "正确", "道路交通安全法：红灯停。已越过停止线的可继续通过，未越过的必须停。", true),
    Q("s1-02", 1, "JUDGE", "黄灯亮时，已越过停止线的车辆可以继续通行。", "[\"正确\",\"错误\"]", "正确", "黄灯表示已经接近停止线、不能安全停车的车辆可以通行。", true),
    Q("s1-03", 1, "JUDGE", "机动车行经人行横道时遇行人正在通过，应当停车让行。", "[\"正确\",\"错误\"]", "正确", "人行横道上的行人优先，不得加速通过或鸣喇叭催促。", true),
    Q("s1-04", 1, "SINGLE", "饮酒后驾驶机动车的，一次记多少分？", "[\"A. 12分\",\"B. 9分\",\"C. 6分\",\"D. 3分\"]", "A", "公安部令第163号：饮酒后驾驶机动车的，一次记12分。醉酒驾驶另依法追究刑事责任，不是只记分。", true),
    Q("s1-05", 1, "JUDGE", "醉酒驾驶机动车只记12分，不追究刑事责任。", "[\"正确\",\"错误\"]", "错误", "醉酒驾驶涉嫌危险驾驶罪，依法追究刑事责任。饮酒后驾驶才是记12分的行政记分情形。", true),
    Q("s1-06", 1, "JUDGE", "在高速公路上倒车、逆行或者穿越中央分隔带掉头的，一次记12分。", "[\"正确\",\"错误\"]", "正确", "163号令把高速公路、城市快速路上的倒车、逆行、穿越中央分隔带掉头列为一次记12分。", true),
    Q("s1-07", 1, "SINGLE", "驾驶机动车违反道路交通信号灯通行的，一次记多少分？", "[\"A. 12分\",\"B. 6分\",\"C. 3分\",\"D. 1分\"]", "B", "闯红灯属于违反信号灯通行，163号令口径为一次记6分。", true),
    Q("s1-08", 1, "SINGLE", "驾驶时拨打、接听手持电话的，一次记多少分？", "[\"A. 1分\",\"B. 3分\",\"C. 6分\",\"D. 12分\"]", "B", "妨碍安全驾驶的拨打、接听手持电话，163号令口径为一次记3分。", true),
    Q("s1-09", 1, "SINGLE", "驾驶机动车在道路上行驶，未按规定系安全带的，一次记多少分？", "[\"A. 1分\",\"B. 3分\",\"C. 6分\",\"D. 不记分\"]", "A", "163号令：未按规定系安全带，一次记1分。", true),
    Q("s1-10", 1, "JUDGE", "使用伪造、变造的机动车号牌、行驶证、驾驶证的，一次记12分。", "[\"正确\",\"错误\"]", "正确", "伪造、变造或使用其他车辆号牌、行驶证，属于一次记12分的严重情形。", true),
    Q("s1-11", 1, "JUDGE", "造成致人轻伤以上或者死亡的交通事故后逃逸，尚不构成犯罪的，一次记12分。", "[\"正确\",\"错误\"]", "正确", "尚不构成犯罪的肇事逃逸记12分；构成犯罪的依法追究刑事责任。", true),
    Q("s1-12", 1, "JUDGE", "机动车驾驶证的实习期是6个月。", "[\"正确\",\"错误\"]", "错误", "初次申领驾驶证的实习期为12个月。", true),
    Q("s1-13", 1, "JUDGE", "实习期内不得单独驾驶公共汽车、营运客车或者牵引挂车。", "[\"正确\",\"错误\"]", "正确", "实习期有准驾限制，不能单独开公共汽车、营运客车，也不能驾驶牵引挂车。", true),
    Q("s1-14", 1, "SINGLE", "没有道路中心线的城市道路，机动车最高行驶速度不得超过多少？", "[\"A. 30公里/小时\",\"B. 40公里/小时\",\"C. 50公里/小时\",\"D. 70公里/小时\"]", "A", "没有中心线的城市道路限速30公里/小时，公路为40公里/小时。", true),
    Q("s1-15", 1, "SINGLE", "没有道路中心线的公路，机动车最高行驶速度不得超过多少？", "[\"A. 30公里/小时\",\"B. 40公里/小时\",\"C. 50公里/小时\",\"D. 70公里/小时\"]", "B", "没有中心线的公路限速40公里/小时。", true),
    Q("s1-16", 1, "JUDGE", "同方向划有两条以上机动车道的，左侧为快速车道，右侧为慢速车道。", "[\"正确\",\"错误\"]", "正确", "应在左侧车道超车，超车后驶回原车道。不得长时间占用左侧快车道。", false),
    Q("s1-17", 1, "JUDGE", "机动车通过没有交通信号灯、没有交通警察指挥的交叉路口，应当减速慢行，并让右方道路的来车先行。", "[\"正确\",\"错误\"]", "正确", "没有信号控制时，让右方来车先行。转弯车辆让直行车辆先行。", true),
    Q("s1-18", 1, "JUDGE", "在车道减少的路段，遇前方机动车停车排队或者缓慢行驶的，应当每车道一辆依次交替驶入车道减少后的路口。", "[\"正确\",\"错误\"]", "正确", "交替通行，不得穿插排队。", true),
    Q("s1-19", 1, "SINGLE", "高速公路上车辆发生故障，警告标志应设在故障车来车方向多少米以外？", "[\"A. 50米\",\"B. 100米\",\"C. 150米\",\"D. 200米\"]", "C", "普通道路不少于50米，高速公路和城市快速路不少于150米。", true),
    Q("s1-20", 1, "JUDGE", "不得在高速公路上骑、轧车行道分界线，或在路肩上行驶。", "[\"正确\",\"错误\"]", "正确", "高速公路应在行车道内行驶，路肩只在紧急情况下使用。", false),
    Q("s1-21", 1, "JUDGE", "轻型牵引挂车（C6）的申请年龄为20周岁以上、70周岁以下。", "[\"正确\",\"错误\"]", "正确", "按172号令对C6年龄的口径掌握。当地车管所如有更新，以车管所当日口径为准。", true),
    Q("s1-22", 1, "JUDGE", "驾驶校车、公路客运汽车、旅游客运汽车载人超过核定人数20%以上的，一次记12分。", "[\"正确\",\"错误\"]", "正确", "校车和两类客运汽车超员20%以上记12分。其他载客汽车超员100%以上同样记12分。", true),
    Q("s1-23", 1, "JUDGE", "代替实际驾驶人接受处罚和记分、牟取经济利益的，一次记12分。", "[\"正确\",\"错误\"]", "正确", "买卖记分属于一次记12分，不能当成可以花钱消掉的小事。", true),
    Q("s1-24", 1, "SINGLE", "夜间会车，应当在距相对方向来车多少米以外改用近光灯？", "[\"A. 50米\",\"B. 100米\",\"C. 150米\",\"D. 200米\"]", "C", "夜间会车在距对向来车150米以外改用近光灯，不得用远光灯炫目。", true),
    Q("s4-01", 4, "JUDGE", "行车中突发轮胎爆裂，应握稳转向盘，松抬加速踏板，抢挂低速挡减速，尽量平稳靠边，避免紧急制动。", "[\"正确\",\"错误\"]", "正确", "爆胎时猛踩刹车或猛打方向更容易失控。先控方向，再减速。", true),
    Q("s4-02", 4, "JUDGE", "制动突然失灵时，应握稳转向，抢挂低速挡，并用驻车制动辅助减速，同时注意避让。", "[\"正确\",\"错误\"]", "正确", "不要一下拉死驻车制动。下坡失灵更要利用发动机牵阻。", true),
    Q("s4-03", 4, "JUDGE", "车辆落水后应立刻打开车门逃生。", "[\"正确\",\"错误\"]", "错误", "车外水压会把门顶住。应在水没过车窗、内外压力接近时开门或破窗，从头枕侧玻璃离开。", true),
    Q("s4-04", 4, "JUDGE", "发动机舱起火时，应迅速关闭发动机，用灭火器对准火焰根部，不要贸然掀开发动机盖。", "[\"正确\",\"错误\"]", "正确", "掀盖会灌入空气，火会突然变大。人先离开，再灭火、报警。", true),
    Q("s4-05", 4, "JUDGE", "高速公路上车辆故障后，车上人员应留在车内等待救援。", "[\"正确\",\"错误\"]", "错误", "应开启危险报警闪光灯，在来车方向150米外设置警告标志，人员迅速转移到右侧护栏以外。", true),
    Q("s4-06", 4, "JUDGE", "雾天能见度很低时，应开启雾灯、危险报警闪光灯，靠右侧低速行驶，不要以前车尾灯作为唯一参照猛跟。", "[\"正确\",\"错误\"]", "正确", "雾天最常见的事故是跟车太近和占用对向车道超车。", true),
    Q("s4-07", 4, "JUDGE", "下长坡可以挂空挡滑行，这样更省油。", "[\"正确\",\"错误\"]", "错误", "空挡滑行会失去发动机牵阻，刹车容易热衰减。下长坡应挂低挡。", true),
    Q("s4-08", 4, "JUDGE", "超车应当提前开启左转向灯，从被超车辆左侧超越，确认安全后再驶回原车道。", "[\"正确\",\"错误\"]", "正确", "不得从右侧超车，不得在对向有来车、弯道、桥梁、隧道时超车。", true),
    Q("s4-09", 4, "JUDGE", "隧道内可以短暂停靠拍照。", "[\"正确\",\"错误\"]", "错误", "隧道内禁止停车、倒车和掉头。故障时尽量驶出隧道。", true),
    Q("s4-10", 4, "JUDGE", "雨天行车应降低车速，加大跟车距离，避免急刹和急打方向。", "[\"正确\",\"错误\"]", "正确", "路面水膜会让轮胎失去抓地。进水较深的路段先观察，不要盲目通过。", false),
    Q("s4-11", 4, "JUDGE", "行经没有交通信号的路口，转弯车辆应当让直行车辆先行。", "[\"正确\",\"错误\"]", "正确", "转弯让直行，右转让左转。不要抢黄灯尾巴。", true),
    Q("s4-12", 4, "JUDGE", "遇行人横过道路未走人行横道，可以鸣喇叭后加速通过。", "[\"正确\",\"错误\"]", "错误", "即便行人违法，驾驶人也应减速避让。加速通过是科四里的典型错误。", true),
    Q("s4-13", 4, "SINGLE", "火警、救援、警务车辆执行紧急任务时，其他车辆应当怎么做？", "[\"A. 加速抢先通过\",\"B. 靠右减速让行\",\"C. 紧跟其后\",\"D. 停在路口中间\"]", "B", "警车、消防车、救护车、工程救险车执行任务时，在确保安全的前提下不受信号限制，其他车辆和行人应当让行。", true),
    Q("s4-14", 4, "JUDGE", "夜间通过急弯、坡路、拱桥、人行横道或者没有交通信号灯控制的路口时，应当交替使用远近光灯示意。", "[\"正确\",\"错误\"]", "正确", "交替远近光是示意，不是一直开着远光灯。会车仍要改近光。", false),
    Q("s4-15", 4, "JUDGE", "机动车在泥泞路段发生侧滑时，应立即向侧滑的反方向猛打转向。", "[\"正确\",\"错误\"]", "错误", "后轮侧滑时，应向侧滑的一方缓转方向，再修正，不要猛打反方向。", true),
    Q("s4-16", 4, "JUDGE", "行车中轮胎漏气、转向突然沉重或车辆跑偏，应尽快降低车速，驶离行车道。", "[\"正确\",\"错误\"]", "正确", "这是爆胎前的常见征兆，不要继续高速行驶。", false),
)
