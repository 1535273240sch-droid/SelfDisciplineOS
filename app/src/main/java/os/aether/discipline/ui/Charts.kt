package os.aether.discipline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class ChartRow(val date: String, val weight: Float?, val late: Boolean, val calories: Int, val trained: Boolean)

@Composable
fun AnalyticsBlock(payload: String) {
    val parsed = remember(payload) { parseAnalytics(payload) }
    if (parsed.rows.isEmpty()) return
    val colors = LocalAether.current
    Column {
        Quiet(parsed.note)
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)) {
            val weights = parsed.rows.mapNotNull { it.weight }
            if (weights.isEmpty()) return@Canvas
            val min = weights.min() - 0.4f
            val max = weights.max() + 0.4f
            val stepX = if (parsed.rows.size == 1) 0f else size.width / (parsed.rows.size - 1)
            val path = Path()
            parsed.rows.forEachIndexed { index, row ->
                val w = row.weight ?: return@forEachIndexed
                val x = stepX * index
                val y = size.height - ((w - min) / (max - min)) * size.height
                if (path.isEmpty) path.moveTo(x, y) else path.lineTo(x, y)
                if (row.late) drawCircle(colors.danger, 4.dp.toPx(), Offset(x, y))
            }
            drawPath(path, colors.accent, style = Stroke(3.dp.toPx()))
            val maxCal = parsed.rows.maxOf { it.calories }.coerceAtLeast(1)
            parsed.rows.forEachIndexed { index, row ->
                val x = stepX * index
                val h = size.height * (row.calories / maxCal.toFloat()) * 0.35f
                drawLine(colors.textSecondary.copy(alpha = 0.45f), Offset(x, size.height), Offset(x, size.height - h), 6f)
            }
        }
        parsed.rows.takeLast(7).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Quiet(row.date, Modifier.weight(1f))
                Body((row.weight?.let { "%.1f".format(it) } ?: "—"), Modifier.weight(1f))
                Body(if (row.late) "越线" else "按时", Modifier.weight(1f))
                Quiet("${row.calories}", Modifier.weight(1f))
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
                add(ChartRow(o.optString("date"), if (o.isNull("weight")) null else o.optDouble("weight").toFloat(), o.optBoolean("late"), o.optInt("calories"), o.optBoolean("trained")))
            }
        }
    }
    return Parsed(rows, root.optString("note"))
}

private data class Parsed(val rows: List<ChartRow>, val note: String)
