package os.aether.discipline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import os.aether.discipline.data.AetherRepository

private data class ChartRow(val date: String, val weight: Float?, val late: Boolean?, val calories: Int?, val trained: Boolean)

@Composable
fun TrendsScreen(repo: AetherRepository) {
    val payload by produceState<String?>(null, repo) { value = repo.analyticsPayload(14) }
    ScreenColumn {
        PageTitle("趋势复盘", "最近 14 天的体重、作息、饮食与训练")
        val data = payload
        if (data == null) Quiet("正在读取记录…") else GlassCard { AnalyticsBlock(data) }
    }
}

@Composable
fun AnalyticsBlock(payload: String) {
    val parsed = remember(payload) { parseAnalytics(payload) }
    if (parsed.rows.none { it.weight != null || it.late != null || it.calories != null || it.trained }) {
        Quiet("这段时间还没有记录。")
        return
    }
    val colors = LocalAether.current
    Column {
        Quiet(parsed.note)
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)) {
            val weights = parsed.rows.mapNotNull { it.weight }
            val min = (weights.minOrNull() ?: 0f) - 0.4f
            val max = (weights.maxOrNull() ?: 0f) + 0.4f
            val margin = 5.dp.toPx()
            val stepX = if (parsed.rows.size == 1) 0f else (size.width - margin * 2) / (parsed.rows.size - 1)
            val maxCal = parsed.rows.maxOf { it.calories ?: 0 }.coerceAtLeast(1)
            parsed.rows.forEachIndexed { index, row ->
                val x = margin + stepX * index
                val h = size.height * ((row.calories ?: 0) / maxCal.toFloat()) * 0.35f
                if (h > 0f) drawLine(colors.textSecondary.copy(alpha = 0.45f), Offset(x, size.height), Offset(x, size.height - h), 6f)
            }
            val path = Path()
            var hasWeight = false
            parsed.rows.forEachIndexed { index, row ->
                val x = margin + stepX * index
                val weight = row.weight
                val y = if (weight == null) margin else margin + (1f - (weight - min) / (max - min)) * (size.height - margin * 2)
                if (weight != null) {
                    if (!hasWeight) path.moveTo(x, y) else path.lineTo(x, y)
                    hasWeight = true
                    drawCircle(colors.accent, 2.5.dp.toPx(), Offset(x, y))
                }
                if (row.late == true) drawCircle(colors.danger, 4.dp.toPx(), Offset(x, y))
            }
            drawPath(path, colors.accent, style = Stroke(3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            listOf("日期", "体重", "作息", "热量", "训练").forEach { Quiet(it, Modifier.weight(1f)) }
        }
        parsed.rows.takeLast(7).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Quiet(row.date, Modifier.weight(1f))
                Body((row.weight?.let { "%.1f".format(it) } ?: "—"), Modifier.weight(1f))
                Quiet(when (row.late) { true -> "越线"; false -> "按时"; null -> "—" }, Modifier.weight(1f))
                Quiet(row.calories?.toString() ?: "—", Modifier.weight(1f))
                Quiet(if (row.trained) "练了" else "—", Modifier.weight(1f))
            }
        }
    }
}

private fun parseAnalytics(payload: String): Parsed {
    val root = runCatching { JSONObject(payload) }.getOrNull() ?: return Parsed(emptyList(), "")
    val rowsJson = root.optJSONArray("rows")
    val rows = buildList {
        if (rowsJson != null) {
            for (i in 0 until rowsJson.length()) {
                val o = rowsJson.optJSONObject(i) ?: continue
                add(ChartRow(
                    o.optString("date"), o.optDouble("weight").toFloat().takeIf { it.isFinite() },
                    o.optBoolean("late").takeIf { o.optBoolean("sleepRecorded", o.optBoolean("late")) },
                    o.optInt("calories").takeIf { o.optBoolean("dietRecorded", o.optInt("calories") > 0) },
                    o.optBoolean("trained"),
                ))
            }
        }
    }
    return Parsed(rows, root.optString("note"))
}

private data class Parsed(val rows: List<ChartRow>, val note: String)
