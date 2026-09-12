package com.selfkaizen.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.selfkaizen.app.collector.UsageCollectorWorker
import com.selfkaizen.app.data.CollectionKeys
import com.selfkaizen.app.data.DatabaseProvider
import com.selfkaizen.app.data.SettingsRepository
import com.selfkaizen.app.data.DailySummary
import com.selfkaizen.app.rules.RuleEngine
import com.selfkaizen.app.rules.RuleSettings
import com.selfkaizen.app.rules.UsageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ランキング1行分。 */
data class AppUsageRow(
    val packageName: String,
    val label: String,
    val millis: Long
)

/** 7日間チャートの1本分。 */
data class DayUsageRow(
    val date: LocalDate,
    val millis: Long,
    /** その日 上限を超えたか（履歴として赤くする）。 */
    val exceeded: Boolean
)

/**
 * ダッシュボードの表示状態。
 *
 * すべて Room の `daily_summary`（生イベントから再生成可能なキャッシュ）と
 * `collection_state` から導出する。**表示のための計算は保存しない。**
 */
data class DashboardState(
    val loading: Boolean = true,
    val todayMillis: Long = 0L,
    val limitMillis: Long = 0L,
    val status: UsageStatus = UsageStatus.WITHIN_LIMIT,
    /**
     * ランキングが表示している日のアプリ別。
     *
     * **既定は今日。** 7日チャートの棒をタップするとその日に切り替わる
     * （日 × アプリの交点を見るための唯一の入口）。
     */
    val apps: List<AppUsageRow> = emptyList(),
    /** ランキングが表示している日。null = 今日。 */
    val selectedDate: LocalDate? = null,
    /** [selectedDate] の合計。ランキング見出しに出す。 */
    val selectedMillis: Long = 0L,
    /** [selectedDate] が上限を超えていたか（履歴の赤表示用）。 */
    val selectedExceeded: Boolean = false,
    /** 今日を含む直近7日。古い順。 */
    val days: List<DayUsageRow> = emptyList(),
    /** 直近7日の平均（今日を含まない）。 */
    val averageMillis: Long = 0L,
    /** 前回の収集完了時刻。null = 未収集。 */
    val lastCollectedAt: Long? = null,
    val now: Long = System.currentTimeMillis()
) {
    /** 今日を表示しているか。 */
    val isTodaySelected: Boolean get() = selectedDate == null
}

/**
 * ダッシュボードのデータ読み込み。
 *
 * 設計上の注意（`design/SPEC.md`）:
 *  - **アプリは違反を判定しない。** 使用量を示すだけ。判断は本人が行う。
 *  - 上限超過の判定のみ `RuleEngine.status()` を使う（保存はしない）
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = DatabaseProvider.get(app).usageDao()
    private val settingsRepo = SettingsRepository(dao)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /** パッケージ名 → 表示名。PackageManager の呼び出しを繰り返さないためのキャッシュ。 */
    private val labelCache = mutableMapOf<String, String>()

    /** ランキングが表示している日。null = 今日。 */
    private var selectedDate: LocalDate? = null

    /** すでに反映済みの収集実行ID。同じ実行で二重に再読込しないため。 */
    private val seenRunIds = mutableSetOf<UUID>()

    init {
        // 収集の完了を監視する。
        //
        // 必須の配線: アプリ起動時に collectNow() が走るが、それは非同期であり
        // UI の初回読み込みより後に完了する。この監視が無いと
        // 「開いた瞬間は空、戻ってくると表示される」という状態になる
        // （エミュレータ検証で実際に発生した）。
        observeWork(UsageCollectorWorker.IMMEDIATE_WORK_NAME)
        observeWork(UsageCollectorWorker.PERIODIC_WORK_NAME)
    }

    /**
     * 直近データを読み直す。
     *
     * アプリ起動時・収集完了時・画面復帰時に呼ぶ。
     */
    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            _state.value = loaded
        }
    }

    /** 収集が成功で終わるたびに読み直す。 */
    private fun observeWork(name: String) {
        viewModelScope.launch {
            runCatching {
                WorkManager.getInstance(getApplication())
                    .getWorkInfosForUniqueWorkFlow(name)
                    .collect { infos ->
                        infos.forEach { info ->
                            if (info.state == WorkInfo.State.SUCCEEDED &&
                                seenRunIds.add(info.id)
                            ) {
                                refresh()
                            }
                        }
                    }
            }
        }
    }

    private suspend fun load(): DashboardState {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // 設定画面で保存された永続値を使う（未保存なら既定値）。
        val settings = settingsRepo.load()

        val todaySummaries = dao.summariesForDate(today.toString())
        val todayTotal = todaySummaries.sumOf { it.totalMillis }

        // 直近7日（今日を含む）。データが無い日も0として並べる。
        val days = (6 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val total = if (back == 0) {
                todayTotal
            } else {
                dao.summariesForDate(date.toString()).sumOf { it.totalMillis }
            }
            DayUsageRow(
                date = date,
                millis = total,
                exceeded = settings.dailyLimitEnabled && total > settings.dailyLimitMillis
            )
        }

        // 平均は今日を除く（今日はまだ途中のため）
        val past = days.dropLast(1).filter { it.millis > 0 }
        val average = if (past.isEmpty()) 0L else past.sumOf { it.millis } / past.size

        val lastCollected = dao.getState(CollectionKeys.LAST_COLLECTED_AT)?.toLongOrNull()

        // ---- ランキングが表示する日（既定は今日） ----
        //
        // 7日チャートの棒をタップすると、その日の内訳に切り替わる。
        // **日 × アプリの交点を見る唯一の入口。**
        // SPEC の3要素を増やさずに交差情報を見せるための方法。
        val selected = selectedDate
        val selectedSummaries = if (selected == null || selected == today) {
            todaySummaries
        } else {
            dao.summariesForDate(selected.toString())
        }
        val selectedTotal = if (selected == null || selected == today) {
            todayTotal
        } else {
            selectedSummaries.sumOf { it.totalMillis }
        }

        return DashboardState(
            loading = false,
            todayMillis = todayTotal,
            limitMillis = settings.dailyLimitMillis,
            status = RuleEngine.status(todayTotal, settings),
            apps = topApps(selectedSummaries),
            selectedDate = selected,
            selectedMillis = selectedTotal,
            selectedExceeded = settings.dailyLimitEnabled &&
                selectedTotal > settings.dailyLimitMillis,
            days = days,
            averageMillis = average,
            lastCollectedAt = lastCollected,
            now = System.currentTimeMillis()
        )
    }

    /**
     * ランキングに表示する日を切り替える。
     *
     * `null` で今日に戻る。**同じ日をもう一度タップした場合も今日に戻す**
     * （選択を解除する自然な操作）。
     */
    fun selectDate(date: LocalDate?) {
        val next = if (date != null && date == selectedDate) null else date
        if (next == selectedDate) return
        selectedDate = next
        refresh()
    }

    /**
     * 上位5アプリ＋「その他」に集約する。
     *
     * SPEC のランキングは6行（上位5＋Others）。行数を一定に保つことで
     * 「アプリが増えても画面が壊れない」ようにする。
     *
     * **自分自身は除外する。** ダッシュボードを開いた時間が
     * ランキングに現れるのは自己言及的でノイズになるため。
     */
    private fun topApps(summaries: List<DailySummary>, topN: Int = 5): List<AppUsageRow> {
        val self = getApplication<Application>().packageName
        val sorted = summaries
            .filter { it.totalMillis > 0 && it.packageName != self }
            .sortedByDescending { it.totalMillis }

        val head = sorted.take(topN).map {
            AppUsageRow(it.packageName, resolveLabel(it.packageName), it.totalMillis)
        }
        val rest = sorted.drop(topN)
        if (rest.isEmpty()) return head

        // 表示名は strings.xml の label_others を使うため、ここでは空にしておく
        val others = AppUsageRow(
            packageName = OTHERS_KEY,
            label = "",
            millis = rest.sumOf { it.totalMillis }
        )
        return head + others
    }

    /**
     * パッケージ名から表示名を解決する。
     *
     * 収集側は `appLabel` にパッケージ名を保存し、**表示名は UI 側で解決する**
     * 設計になっている（`UsageCollector` のコメント参照）。
     * アンインストール済みなどで解決できない場合はパッケージ名をそのまま使う。
     */
    private fun resolveLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = runCatching {
            val pm = getApplication<Application>().packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        labelCache[pkg] = label
        return label
    }

    companion object {
        /** 「その他」行を識別するための擬似パッケージ名。 */
        const val OTHERS_KEY = "__others__"
    }
}
