package os.aether.discipline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.ProviderDraft
import os.aether.discipline.data.SceneBinding
import os.aether.discipline.data.db.AiProviderConfig
import os.aether.discipline.domain.MODEL_SCENES
import os.aether.discipline.domain.describeTransportFailure
import os.aether.discipline.domain.requireProviderBase
import os.aether.discipline.domain.sceneLabel
import java.io.IOException

@Composable
fun ProviderScreen(repo: AetherRepository) {
    val providers by repo.providers.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val colors = LocalAether.current
    var editingId by remember { mutableStateOf<Long?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("DeepSeek") }
    var base by remember { mutableStateOf("https://api.deepseek.com/v1") }
    var key by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/audio/transcriptions") }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var reveal by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var bindings by remember { mutableStateOf(emptyList<SceneBinding>()) }

    fun perform(label: String, action: suspend () -> Unit) {
        if (pending != null) return
        pending = label
        feedback = ""
        failure = false
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = true
                val detail = if (error is IOException) describeTransportFailure(error) else error.message.orEmpty()
                feedback = detail.ifBlank { "操作失败，请重试。" }
            } finally {
                pending = null
            }
        }
    }

    suspend fun loadProvider(provider: AiProviderConfig?) {
        editingId = provider?.id
        name = provider?.providerName.orEmpty()
        base = provider?.baseUrl.orEmpty()
        path = provider?.transcriptionPath?.ifBlank { "/audio/transcriptions" } ?: "/audio/transcriptions"
        key = ""
        reveal = false
        fetched = null
        models = provider?.let { repo.cachedModels(it.id) }.orEmpty()
        val bound = bindings.filter { it.providerId == provider?.id }
        model = bound.firstOrNull { it.scene == "companion" }?.model ?: bound.firstOrNull()?.model.orEmpty()
    }

    fun draft() = ProviderDraft(editingId, name, base, key, path)

    fun invalidateCatalog() {
        fetched = null
        models = emptyList()
    }

    fun activate(scene: String) {
        val label = sceneLabel(scene)
        val form = draft()
        val selected = model
        val catalog = fetched
        perform("正在测试$label…") {
            editingId = repo.activateModel(form, scene, selected, catalog)
            base = requireProviderBase(form.baseUrl)
            key = ""
            reveal = false
            bindings = repo.sceneBindings()
            models = editingId?.let { repo.cachedModels(it) }.orEmpty()
            feedback = "已测试并启用$label · ${selected.trim()}"
        }
    }

    LaunchedEffect(providers) {
        bindings = repo.sceneBindings()
        if (!initialized && pending == null && providers.isNotEmpty()) {
            initialized = true
            val preferred = bindings.firstOrNull { it.scene == "companion" }?.providerId
            loadProvider(providers.firstOrNull { it.id == preferred } ?: providers.first())
        }
    }

    val idle = pending == null
    ScreenColumn {
        PageTitle("模型接入", "拉取模型 → 选择模型 → 测试并绑定")
        if (providers.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(providers, key = { it.id }) { provider ->
                    TextButton(
                        enabled = idle,
                        onClick = { perform("正在读取渠道…") { loadProvider(provider) } },
                    ) {
                        Text(
                            provider.providerName,
                            color = if (provider.id == editingId) colors.accent else colors.textSecondary,
                            fontWeight = if (provider.id == editingId) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item { TextButton(enabled = idle, onClick = { perform("正在新建渠道…") { loadProvider(null) } }) { Text("新渠道") } }
            }
        }
        GlassCard(padding = 0.dp) {
            FieldRow("渠道名称", name, { name = it }, "例如 DeepSeek", idle)
            Hairline()
            FieldRow("Base URL", base, { base = it; invalidateCatalog() }, "https://api.deepseek.com/v1", idle, KeyboardType.Uri)
            Hairline()
            FieldRow(
                "API Key", key, { key = it; invalidateCatalog() },
                if (editingId == null) "粘贴密钥" else "已保存，留空沿用", idle, KeyboardType.Password,
                masked = !reveal,
                trailing = {
                    IconButton(onClick = { reveal = !reveal }, enabled = idle) {
                        Icon(
                            if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (reveal) "隐藏密钥" else "显示密钥", tint = colors.textSecondary,
                        )
                    }
                },
            )
            Hairline()
            FieldRow("转写路径", path, { path = it }, "/audio/transcriptions", idle, KeyboardType.Uri)
        }
        PrimaryButton("一键拉取模型", enabled = idle) {
            val form = draft()
            perform("正在拉取模型列表…") {
                val catalog = repo.discoverModels(form)
                base = catalog.baseUrl
                models = catalog.models
                fetched = catalog.models
                showModels = models.isNotEmpty()
                feedback = if (models.isEmpty()) "接口返回 0 个模型。可手填模型名后测试。" else "已拉取全部 ${models.size} 个模型。选择后测试启用。"
            }
        }
        GlassCard {
            SectionTitle("选择模型")
            OutlinedTextField(
                model, { model = it }, label = { Text("模型 ID") },
                placeholder = { Text("从列表选择或手动填写") },
                enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (models.isNotEmpty()) {
                TextButton(enabled = idle, onClick = { showModels = true }) { Text("查看全部 ${models.size} 个模型") }
            }
            PrimaryButton("测试并启用搭档", enabled = idle && model.isNotBlank()) { activate("companion") }
            Quiet("测试成功后加密保存渠道与绑定。")
        }
        val status = pending ?: feedback.takeIf(String::isNotBlank)
        if (status != null) {
            val tint = if (failure) colors.danger else colors.accent
            Text(
                status, color = tint, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().background(tint.copy(alpha = 0.09f)).padding(12.dp),
            )
        }
        SectionTitle("场景绑定")
        GlassCard(padding = 0.dp) {
            MODEL_SCENES.forEachIndexed { index, scene ->
                if (index > 0) Hairline()
                val binding = bindings.firstOrNull { it.scene == scene }
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Body(sceneLabel(scene))
                        Quiet(binding?.let { "${it.providerName} · ${it.model}" } ?: "未绑定")
                    }
                    TextButton(enabled = idle && model.isNotBlank(), onClick = { activate(scene) }) { Text("测试并绑定") }
                    if (binding != null) {
                        TextButton(enabled = idle, onClick = {
                            perform("正在解绑…") {
                                repo.unbind(scene)
                                bindings = repo.sceneBindings()
                                feedback = "已解绑${sceneLabel(scene)}"
                            }
                        }) { Text("解绑") }
                    }
                }
            }
        }
        Quiet("场景测试使用生成的文本、图像或音频样本。早报未单独绑定时沿用搭档。")
        if (editingId != null) {
            TextButton(enabled = idle, onClick = { confirmDelete = true }) { Text("删除此渠道", color = colors.danger) }
        }
    }
    if (showModels) {
        ModelPicker(models, model, onDismiss = { showModels = false }) {
            model = it
            showModels = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除渠道？") },
            text = { Text("「$name」的密钥和相关场景绑定会从本机移除。") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = editingId ?: return@TextButton
                    perform("正在删除渠道…") {
                        repo.deleteProvider(id)
                        bindings = repo.sceneBindings()
                        loadProvider(providers.firstOrNull { it.id != id })
                        feedback = "已删除渠道及其场景绑定。"
                    }
                }) { Text("删除", color = colors.danger) }
            },
        )
    }
}

@Composable
private fun ModelPicker(models: List<String>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val colors = LocalAether.current
    val filtered = remember(models, query) { models.filter { it.contains(query.trim(), ignoreCase = true) } }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(Modifier.background(colors.surfaceStrong, androidx.compose.foundation.shape.RoundedCornerShape(30.dp))) {
            SectionTitle("模型列表")
            OutlinedTextField(
                query, { query = it }, label = { Text("搜索模型") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Quiet("显示 ${filtered.size} / ${models.size} 个")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(filtered, key = { it }) { id ->
                    Text(
                        id, color = if (selected == id) colors.accent else colors.textPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(id) }
                            .padding(horizontal = 6.dp, vertical = 13.dp),
                    )
                }
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun Hairline() {
    val colors = LocalAether.current
    Box(Modifier.fillMaxWidth().height(0.6.dp).background(colors.textSecondary.copy(alpha = 0.16f)))
}

@Composable
private fun FieldRow(
    label: String, value: String, onValue: (String) -> Unit, placeholder: String, enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, masked: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalAether.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (trailing == null) 14.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(74.dp))
        BasicTextField(
            value = value, onValueChange = onValue, singleLine = true, enabled = enabled,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1)
                    inner()
                }
            },
        )
        if (trailing != null) trailing()
    }
}
