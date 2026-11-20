package os.aether.discipline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.SceneBinding
import os.aether.discipline.data.db.AiProviderConfig
import os.aether.discipline.domain.sceneLabel

private val scenes = listOf(
    "companion" to "搭档对话",
    "fast" to "快速记录",
    "transcribe" to "语音转写",
    "vision" to "拍照识别",
    "briefing" to "早报总结",
    "tts" to "云端音色",
)

@Composable
fun ProviderScreen(repo: AetherRepository) {
    val providers by repo.providers.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val colors = LocalAether.current
    var editingId by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("DeepSeek") }
    var base by remember { mutableStateOf("https://api.deepseek.com/v1") }
    var key by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/audio/transcriptions") }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var reveal by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var bannerOk by remember { mutableStateOf<Boolean?>(null) }
    var banner by remember { mutableStateOf("") }
    var bindings by remember { mutableStateOf(emptyList<SceneBinding>()) }

    suspend fun loadProvider(provider: AiProviderConfig) {
        editingId = provider.id
        name = provider.providerName
        base = provider.baseUrl
        path = provider.transcriptionPath.ifBlank { "/audio/transcriptions" }
        key = ""
        reveal = false
        val cached = repo.cachedModels(provider.id)
        models = cached
        val bound = repo.sceneBindings()
        bindings = bound
        model = bound.firstOrNull { it.providerId == provider.id && it.scene == "companion" }?.model
            ?: bound.firstOrNull { it.providerId == provider.id }?.model
            ?: cached.firstOrNull().orEmpty()
    }

    LaunchedEffect(providers) {
        val current = repo.sceneBindings()
        bindings = current
        if (!loaded && providers.isNotEmpty()) {
            val preferred = providers.firstOrNull { provider ->
                current.any { it.providerId == provider.id && it.scene == "companion" }
            } ?: providers.first()
            loadProvider(preferred)
            loaded = true
        }
    }

    ScreenColumn(spacing = 14.dp) {
        PageTitle("模型接入", "配置服务商，再为六个场景选择模型。")
        if (providers.size > 1 || editingId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { provider ->
                    val on = provider.id == editingId
                    Text(
                        provider.providerName,
                        color = if (on) colors.accent else colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            scope.launch { loadProvider(provider) }
                        }.padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
                Text(
                    "新渠道",
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        editingId = null
                        name = ""
                        base = "https://api.deepseek.com/v1"
                        key = ""
                        path = "/audio/transcriptions"
                        model = ""
                        models = emptyList()
                        menu = false
                        banner = ""
                        bannerOk = null
                    }.padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
        GlassCard(padding = 0.dp) {
            FieldRow("渠道名称", name, { name = it }, "例如 DeepSeek")
            Hairline()
            FieldRow("Base URL", base, { base = it }, "https://api.deepseek.com/v1")
            Hairline()
            FieldRow(
                "API Key",
                key,
                { key = it },
                if (editingId == null) "粘贴 API Key" else "已保存，留空则不修改",
                secret = true,
                revealed = reveal,
                trailing = {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (reveal) "隐藏密钥" else "显示密钥",
                        tint = colors.textSecondary,
                        modifier = Modifier.clickable { reveal = !reveal }.padding(4.dp),
                    )
                },
            )
            Hairline()
            FieldRow("转写路径", path, { path = it }, "/audio/transcriptions")
            Hairline()
            FieldRow(
                "模型",
                model,
                { model = it },
                "手动输入，或拉取后选择",
                trailing = {
                    Text(
                        "›",
                        color = colors.textSecondary,
                        fontSize = 18.sp,
                        modifier = Modifier.clickable { menu = !menu }.padding(horizontal = 4.dp),
                    )
                },
            )
            if (menu && models.isNotEmpty()) {
                Hairline()
                Quiet("点选一个模型，也可以继续手打。", Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                models.take(40).forEach { id ->
                    Text(
                        id,
                        color = if (id == model.trim()) colors.accent else colors.textPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                model = id
                                menu = false
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                if (models.size > 40) Quiet("还有 ${models.size - 40} 个，直接输入模型名即可。", Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        PrimaryButton(if (busy) "正在测试…" else "测试连接并拉取模型", enabled = !busy) {
            scope.launch {
                busy = true
                banner = ""
                val result = repo.testProvider(editingId, name, base, key, path, model)
                editingId = result.providerId.takeIf { it > 0 }
                base = result.normalizedBase.ifBlank { base }
                if (result.models.isNotEmpty()) models = result.models
                if (result.chosenModel.isNotBlank()) model = result.chosenModel
                key = ""
                bindings = repo.sceneBindings()
                bannerOk = result.ok
                banner = result.message
                menu = result.models.isNotEmpty()
                busy = false
            }
        }
        if (banner.isNotBlank() && bannerOk != null) {
            val ok = bannerOk == true
            Text(
                banner,
                color = if (ok) colors.up else colors.danger,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background((if (ok) colors.up else colors.danger).copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        Quiet("场景绑定")
        GlassCard(padding = 0.dp) {
            scenes.forEachIndexed { index, (scene, label) ->
                if (index > 0) Hairline()
                val binding = bindings.firstOrNull { it.scene == scene }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val chosen = model.trim()
                        val providerId = editingId
                        if (providerId == null || chosen.isBlank()) {
                            bannerOk = false
                            banner = "先填写模型名，或测试连接后再绑定$label。"
                        } else {
                            scope.launch {
                                repo.bind(scene, providerId, chosen)
                                bindings = repo.sceneBindings()
                                bannerOk = true
                                banner = "已绑定$label · $chosen"
                            }
                        }
                    }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(
                        binding?.model ?: "未绑定",
                        color = if (binding == null) colors.accent else colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    if (binding != null) {
                        Text(
                            "解绑",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    repo.unbind(scene)
                                    bindings = repo.sceneBindings()
                                    bannerOk = true
                                    banner = "已解绑${sceneLabel(scene)}"
                                }
                            }.padding(horizontal = 8.dp),
                        )
                    }
                    Text("›", color = colors.textSecondary, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Quiet("也可手动输入模型名。密钥只加密保存在本机。")
        if (editingId != null) {
            Text(
                "删除此渠道",
                color = colors.danger,
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    val id = editingId ?: return@clickable
                    scope.launch {
                        repo.deleteProvider(id)
                        editingId = null
                        name = ""
                        key = ""
                        model = ""
                        models = emptyList()
                        bindings = repo.sceneBindings()
                        bannerOk = true
                        banner = "已删除渠道，相关场景绑定已清空"
                    }
                }.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Hairline() {
    val colors = LocalAether.current
    Box(Modifier.fillMaxWidth().height(0.6.dp).background(colors.rimBottom))
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    revealed: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalAether.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = colors.textSecondary.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                    inner()
                }
            },
        )
        if (trailing != null) trailing()
    }
}
