package os.aether.discipline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.db.CustomLearningModule
import os.aether.discipline.news.CryptoBoard

@Composable
fun StudyScreen(repo: AetherRepository, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var modules by remember { mutableStateOf(emptyList<CustomLearningModule>()) }
    var board by remember { mutableStateOf(CryptoBoard(emptyList(), "—", emptyList())) }
    var showNew by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("3") }
    var template by remember { mutableStateOf("DAILY_QUOTA") }
    LaunchedEffect(Unit) {
        repo.observeToday().collect { modules = repo.modules() }
    }
    LaunchedEffect(Unit) {
        board = runCatching { repo.refreshCrypto() }.getOrDefault(board)
    }
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                PageTitle("学习", "练习、训练与每日目标")
            }
            AccentButton(if (editing) "完成" else "编辑模块") { editing = !editing }
        }
        if (editing) {
            GlassCard {
                Quiet("选择一个模块计入今日的学习硬指标。")
                AccentButton("新建学习模块") { showNew = true }
            }
        }
        if (showNew) {
            GlassCard {
                OutlinedTextField(title, { title = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(target, { target = it.filter(Char::isDigit) }, label = { Text("每日目标") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccentButton(if (template == "DAILY_QUOTA") "· 定量" else "定量") { template = "DAILY_QUOTA" }
                    AccentButton(if (template == "COUNTDOWN_SPRINT") "· 倒计时" else "倒计时") { template = "COUNTDOWN_SPRINT" }
                    AccentButton(if (template == "CRYPTO_TRACKER") "· 行情" else "行情") { template = "CRYPTO_TRACKER" }
                }
                AccentButton("添加") {
                    scope.launch {
                        repo.addModule(title, target.toIntOrNull() ?: 3, template)
                        modules = repo.modules()
                        showNew = false
                        title = ""
                    }
                }
            }
        }
        modules.filter { editing || !it.isArchived }.forEach { module ->
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(module.title)
                        if (module.countsTowardsStreak && !module.isArchived) Quiet("今日学习硬指标")
                        Quiet(module.subtitle)
                    }
                }
                if (module.templateType == "CRYPTO_TRACKER" || module.moduleId == "crypto") {
                    if (board.quotes.isEmpty()) Quiet("行情还没回来") else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            board.quotes.forEach { quote ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(LocalAether.current.accentSoft).padding(8.dp)) {
                                    Quiet(quote.pair)
                                    Text(quote.price, color = LocalAether.current.textPrimary, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFeatureSettings = "tnum"))
                                    Quiet(if (quote.changePct >= 0) "+${"%.2f".format(quote.changePct)}%" else "${"%.2f".format(quote.changePct)}%")
                                }
                            }
                        }
                    }
                    Quiet("恐慌贪婪 ${board.fearGreed}")
                    board.headlines.forEach { Quiet(it.take(90)) }
                }
                val ratio = (module.currentValue / module.targetValue.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                Metric("${module.currentValue} / ${module.targetValue} ${module.unit}", size = 20)
                BoxTrack(ratio)
                if (!module.isArchived) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (module.moduleId) {
                            "driving" -> AccentButton("进入练习") { nav.navigate("driving") }
                            "english" -> AccentButton("进入跟读") { nav.navigate("english") }
                            "fitness" -> AccentButton("打开训练") { nav.navigate("workout") }
                            else -> if (module.currentValue < module.targetValue) {
                                AccentButton(if (module.templateType == "CRYPTO_TRACKER") "记录一次复盘" else "记录一次") {
                                    scope.launch { repo.bumpModule(module.moduleId, 1) }
                                }
                            }
                        }
                    }
                }
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        AccentButton("上移") { scope.launch { repo.moveModule(module.moduleId, -1); modules = repo.modules() } }
                        AccentButton("下移") { scope.launch { repo.moveModule(module.moduleId, 1); modules = repo.modules() } }
                        if (!module.countsTowardsStreak && !module.isArchived) {
                            AccentButton("设为硬指标") {
                                scope.launch { repo.setHardModule(module.moduleId) }
                            }
                        }
                        AccentButton(if (module.isArchived) "恢复" else "归档") {
                            scope.launch { repo.setArchived(module.moduleId, !module.isArchived); modules = repo.modules() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxTrack(ratio: Float) {
    val colors = LocalAether.current
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp).clip(RoundedCornerShape(99.dp)).background(colors.textSecondary.copy(0.15f)),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(ratio).height(6.dp).background(colors.accent))
    }
}
