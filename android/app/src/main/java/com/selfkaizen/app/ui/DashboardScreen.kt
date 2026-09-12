package com.selfkaizen.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfkaizen.app.R
import com.selfkaizen.app.rules.UsageStatus
import com.selfkaizen.app.ui.theme.LocalKaizenColors
import com.selfkaizen.app.ui.theme.SectionLabelStyle
import com.selfkaizen.app.ui.theme.TabularNumberStyle
import java.time.ZoneId
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton

/**
 * ダッシュボード本体。
 *
 * `design/SPEC.md` §6 のとおり **3要素**（リング / ランキング / 7日間）。
 * 勤務時間帯ルールは存在しないため、**違反セクションは無い**。
 * アプリは使用量を示すだけで、判断は本人が行う。
 */
@Composable
fun DashboardScreen(
    state: DashboardState,
    onOpenSettings: () -> Unit,
    onSelectDate: (java.time.LocalDate?) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalKaizenColors.current

    Surface(modifier = modifier.fillMaxSize(), color = c.bg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            // 設定への入口。**タブや階層は作らない**（SPEC §6）ため、
            // 画面右上に控えめな記号を1つだけ置く。
            // 主役はリングであり、設定は目立たせない。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "⚙",
                        color = c.ink3,
                        fontSize = 15.sp
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            RingHero(state)
            Spacer(Modifier.height(14.dp))
            AppRankingCard(state)
            Spacer(Modifier.height(12.dp))
            WeekCard(state, onSelectDate)
            Spacer(Modifier.height(12.dp))
            UpdatedLine(state)
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ---------------------------------------------------------------- hero

/** リング＋今日の合計＋状態。画面の主役。 */
@Composable
private fun RingHero(state: DashboardState) {
    val c = LocalKaizenColors.current
    val over = state.status == UsageStatus.EXCEEDED
    val cdRing = stringResource(R.string.cd_ring)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.label_today).uppercase(),
            style = SectionLabelStyle,
            color = c.ink3
        )
        Spacer(Modifier.height(11.dp))

        Box(contentAlignment = Alignment.Center) {
            UsageRing(
                todayMillis = state.todayMillis,
                limitMillis = state.limitMillis,
                status = state.status,
                modifier = Modifier
                    .size(RING_SIZE)
                    .semantics { contentDescription = cdRing }
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = DurationFormat.ringLabel(state.todayMillis),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.6).sp,
                    style = TabularNumberStyle,
                    color = c.ink
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(
                        R.string.label_limit,
                        DurationFormat.limit(state.limitMillis)
                    ),
                    fontSize = 12.5.sp,
                    style = TabularNumberStyle,
                    color = c.ink3
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        StateLine(state)
    }
}

/**
 * 状態行。**形・色・語の三重**で伝える（SPEC §3）。
 *
 * 超過のときだけ赤にし、字を重くする。
 * 赤はここかリングにしか出ないため、赤が出たこと自体が情報になる。
 */
@Composable
private fun StateLine(state: DashboardState) {
    val c = LocalKaizenColors.current
    val limit = state.limitMillis
    val remaining = limit - state.todayMillis

    val text: String
    val color: androidx.compose.ui.graphics.Color
    val weight: FontWeight

    when (state.status) {
        UsageStatus.DISABLED -> {
            text = stringResource(R.string.state_disabled)
            color = c.ink2
            weight = FontWeight.Medium
        }
        UsageStatus.EXCEEDED -> {
            text = stringResource(
                R.string.state_over,
                DurationFormat.long(-remaining)
            )
            color = c.danger
            weight = FontWeight.ExtraBold
        }
        else -> {
            text = stringResource(R.string.state_left, DurationFormat.long(remaining))
            color = c.ink
            weight = FontWeight.SemiBold
        }
    }

    Text(text = text, fontSize = 14.5.sp, fontWeight = weight, color = color)
}

/**
 * リング。**`stroke-dasharray` は状態ごとの固定値ではなく実データから算出する。**
 *
 * SPEC §3 の式に従う:
 *   描画角 = 360 × min(実使用 / 上限, 1)
 *
 * 超過時のみ全周の点線に切り替える（形で状態を示すため）。
 * 接近（APPROACHING）は**残り時間**で判定されるものであり、
 * 塗り率とは独立している（塗り率を接近判定に使ってはいけない）。
 */
@Composable
private fun UsageRing(
    todayMillis: Long,
    limitMillis: Long,
    status: UsageStatus,
    modifier: Modifier = Modifier
) {
    val c = LocalKaizenColors.current
    val over = status == UsageStatus.EXCEEDED

    val fraction = if (limitMillis > 0L) {
        (todayMillis.toDouble() / limitMillis.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val strokePx = RING_STROKE.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)

        if (over) {
            // 全周の点線。色は赤。地の輪も赤を薄く敷いて全体を赤く読ませる。
            drawCircle(
                color = c.danger.copy(alpha = 0.18f),
                radius = (size.minDimension - strokePx) / 2f,
                style = Stroke(width = strokePx)
            )
            val dash = RING_DASH.toPx()
            val gap = RING_GAP.toPx()
            drawArc(
                color = c.danger,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Butt,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f)
                )
            )
        } else {
            // 地の輪（全周）＋実使用率の円弧
            drawCircle(
                color = c.track,
                radius = (size.minDimension - strokePx) / 2f,
                style = Stroke(width = strokePx)
            )
            if (fraction > 0f) {
                drawArc(
                    color = c.ink,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- ranking

/** アプリ別ランキング。上位5＋Others の6行で固定。 */
@Composable
private fun AppRankingCard(state: DashboardState) {
    val c = LocalKaizenColors.current
    val apps = state.apps
    Card {
        CardHeader(
            title = stringResource(R.string.label_top_apps),
            // 今日以外を見ているときは日付を出す。
            // **「いつの話か」が分からない数字は判断の役に立たない。**
            hint = if (state.isTodaySelected) {
                stringResource(R.string.label_today).lowercase()
            } else {
                state.selectedDate?.let { DurationFormat.shortDate(it) }
            }
        )
        if (apps.isEmpty()) {
            Text(
                // **過ぎた日に「まだ収集していない」は誤り。** その日はもう来ないので、
                // 「これから収集される」と読めてしまう表現を使ってはいけない。
                // 記録が無いという事実だけを記号で出す（SPEC §1）。
                text = if (state.isTodaySelected) {
                    stringResource(R.string.label_never_collected)
                } else {
                    stringResource(R.string.widget_none)
                },
                fontSize = 12.5.sp,
                color = c.ink3
            )
            return@Card
        }

        val max = apps.maxOf { it.millis }.coerceAtLeast(1L)
        apps.forEach { row ->
            val label = if (row.packageName == DashboardViewModel.OTHERS_KEY) {
                stringResource(R.string.label_others)
            } else {
                row.label.ifBlank { row.packageName }
            }
            AppRow(label = label, millis = row.millis, max = max)
        }
    }
}

@Composable
private fun AppRow(label: String, millis: Long, max: Long) {
    val c = LocalKaizenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(74.dp),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(9.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(c.track)
        ) {
            val frac = (millis.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(c.ink2)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = DurationFormat.compact(millis),
            modifier = Modifier.width(46.dp),
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            style = TabularNumberStyle,
            color = c.ink2
        )
    }
}

// ---------------------------------------------------------------- week

/** 7日間チャート。上限を超えた日だけ赤（履歴）。 */
@Composable
private fun WeekCard(state: DashboardState, onSelectDate: (java.time.LocalDate?) -> Unit) {
    val c = LocalKaizenColors.current
    Card {
        CardHeader(
            title = stringResource(R.string.label_7days),
            hint = if (state.averageMillis > 0) {
                stringResource(
                    R.string.label_avg,
                    DurationFormat.long(state.averageMillis)
                )
            } else {
                null
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val max = state.days.maxOfOrNull { it.millis }?.coerceAtLeast(1L) ?: 1L
            state.days.forEach { day ->
                val isToday = day.date == state.days.lastOrNull()?.date
                val isSelected = state.selectedDate?.let { it == day.date } ?: isToday
                val barColor = when {
                    day.exceeded -> c.danger
                    isSelected -> c.ink
                    else -> c.ink3.copy(alpha = 0.5f)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 棒をタップしてその日の内訳に切り替える。
                        // 選択中をもう一度押すと今日に戻る（selectDate 側で処理）。
                        .clickable { onSelectDate(day.date) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(
                                ((day.millis.toFloat() / max.toFloat())
                                    .coerceIn(0f, 1f) * 52f).dp
                                    .coerceAtLeast(2.dp)
                            )
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(barColor)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = DurationFormat.dayInitial(day.date.dayOfWeek),
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) c.ink2 else c.ink3
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- footer

/**
 * 最終収集時刻（`OPEN-ISSUES` B-4）。
 *
 * **収集が黙って止まることを防ぐ**ための唯一の可観測性。
 * 12時間以上更新が無ければ警告色にする。
 */
@Composable
private fun UpdatedLine(state: DashboardState) {
    val c = LocalKaizenColors.current
    val zone = ZoneId.systemDefault()
    val last = state.lastCollectedAt

    val text: String
    val color: androidx.compose.ui.graphics.Color

    if (last == null) {
        text = stringResource(R.string.label_never_collected)
        color = c.danger
    } else {
        val stale = state.now - last > STALE_THRESHOLD_MILLIS
        text = if (stale) {
            stringResource(R.string.label_stale, DurationFormat.ago(last, state.now))
        } else {
            stringResource(R.string.label_updated, DurationFormat.clock(last, state.now, zone))
        }
        color = if (stale) c.danger else c.ink3
    }

    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        fontSize = 11.5.sp,
        textAlign = TextAlign.Center,
        style = TabularNumberStyle,
        color = color
    )
}

// ---------------------------------------------------------------- parts

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = LocalKaizenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 11.dp)
    ) {
        content()
    }
}

@Composable
private fun CardHeader(title: String, hint: String?) {
    val c = LocalKaizenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title.uppercase(),
            style = SectionLabelStyle,
            color = c.ink3
        )
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 11.sp,
                style = TabularNumberStyle,
                color = c.ink3
            )
        }
    }
}

// ---------------------------------------------------------------- const

/** SPEC §6 の実測値。リング直径 196dp。 */
private val RING_SIZE = 196.dp

/** リングの線幅。モックアップの viewBox 206 → 196dp の比率（14 × 0.9515）。 */
private val RING_STROKE = 13.3.dp

/** 超過時の点線。モックアップの dasharray "6 11" を同率で換算。 */
private val RING_DASH = 5.7.dp
private val RING_GAP = 10.5.dp

/**
 * 収集が止まっているとみなす閾値。
 * 収集は12時間間隔を予定しているため、その2倍を超えたら異常とする。
 */
private const val STALE_THRESHOLD_MILLIS = 24L * 60 * 60 * 1000
