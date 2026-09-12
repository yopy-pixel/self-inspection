import type { NormalizedEvent } from "./validate.ts";
import type { DailySummaryRow, DayResponse, SummaryResponse } from "./types.ts";

/**
 * DB 操作。
 *
 * Cloudflare D1 に依存しすぎないよう、**必要な最小のインターフェース**を
 * 自分で定義している。これによりローカル（`node:sqlite`）で同じ SQL を
 * そのままテストできる。テスト用アダプタは `test/d1-adapter.ts` を参照。
 */

export interface PreparedStatement {
  bind(...values: unknown[]): PreparedStatement;
  all<T = unknown>(): Promise<{ results: T[] }>;
  run(): Promise<{ meta: { changes: number } }>;
  first<T = unknown>(): Promise<T | null>;
}

export interface Database {
  prepare(sql: string): PreparedStatement;
  batch(statements: PreparedStatement[]): Promise<unknown[]>;
}

/** 端末。 */
export interface DeviceRow {
  id: string;
  token_hash: string;
  label: string;
  created_at: number;
  last_seen_at: number | null;
  revoked: number;
}

/**
 * 表示名を選ぶ SQL 式。
 *
 * **`MAX(app_label)` だけでは不十分。** `MAX` は辞書順で最大の文字列を選ぶため、
 * 同じアプリの行に古いパッケージ名が1つでも残っていると
 * （小文字始まりのパッケージ名は、大文字始まりの表示名より大きい）、
 * **その日の表示がまるごとパッケージ名に負ける。**
 * 実際にこれで「表示名に直したのに直らない」が起きた。
 *
 * 「パッケージ名と違う値」＝端末が解決できた表示名なので、それを優先する。
 * 1つも無ければ解決できていないので、パッケージ名を返す（正直なフォールバック）。
 *
 * 参照するクエリは `package_name` 列が見えている必要がある。
 */
const LABEL_EXPR = `COALESCE(
                  MAX(CASE WHEN app_label <> package_name THEN app_label END),
                  MAX(app_label)
                )`;

/**
 * バッチを取り込む。**冪等**。
 *
 * 同じ `batchId` を再送しても二重計上しない:
 *  `ingest_batch` の PRIMARY KEY により2回目の INSERT が無視され、
 *  その場合はイベント挿入も集計再計算も行わずに戻る。
 *
 * 集計は**書き込み時**に再計算する。読み取り時に大量走査すると
 * D1 の行読み取り課金と Workers の CPU 制限に直撃するため。
 */
export async function ingestBatch(
  db: Database,
  deviceId: string,
  batchId: string,
  events: NormalizedEvent[],
  now: number
): Promise<{ duplicate: boolean; inserted: number; summariesUpdated: number }> {
  // 1. バッチを登録する。既にあれば changes = 0。
  const batchInsert = await db
    .prepare(
      `INSERT OR IGNORE INTO ingest_batch (id, device_id, received_at, event_count)
       VALUES (?, ?, ?, ?)`
    )
    .bind(batchId, deviceId, now, events.length)
    .run();

  await db
    .prepare(`UPDATE device SET last_seen_at = ? WHERE id = ?`)
    .bind(now, deviceId)
    .run();

  if (batchInsert.meta.changes === 0) {
    // 再送。ここで戻らないと二重計上になる。
    return { duplicate: true, inserted: 0, summariesUpdated: 0 };
  }

  // 2. イベントを入れる。
  //
  //    `INSERT OR IGNORE` では**後から確定した endTime を反映できない**。
  //    端末側では未確定（endTime = null）の区間は送らないが、
  //    閉じ方の推定が後で更新される可能性があるため UPSERT にする。
  //
  //    WHERE 句で「実際に値が変わるときだけ」更新する。
  //    これにより、同じ内容の再送では changes = 0 のまま（冪等）。
  const insertEvent = db.prepare(
    `INSERT INTO usage_event
       (device_id, batch_id, local_date, package_name, app_label, start_time, end_time, close_reason)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (device_id, package_name, start_time) DO UPDATE SET
       end_time     = excluded.end_time,
       close_reason = excluded.close_reason,
       app_label    = excluded.app_label,
       batch_id     = excluded.batch_id
     WHERE usage_event.end_time IS NOT excluded.end_time
        OR usage_event.close_reason IS NOT excluded.close_reason
        OR usage_event.app_label IS NOT excluded.app_label`
  );

  const statements = events.map((e) =>
    insertEvent
      .bind(
        deviceId,
        batchId,
        e.localDate,
        e.packageName,
        e.appLabel,
        e.startTime,
        e.endTime,
        e.closeReason
      )
  );

  let inserted = 0;
  if (statements.length > 0) {
    const results = (await db.batch(statements)) as { meta?: { changes?: number } }[];
    inserted = results.reduce((sum, r) => sum + (r?.meta?.changes ?? 0), 0);
  }

  // 3. 影響した日付の集計を**再計算**する（加算ではない）。
  //    再計算なので、再送や部分的な取り込みがあっても値がずれない。
  const dates = [...new Set(events.map((e) => e.localDate))].sort();
  for (const date of dates) {
    await db
      .prepare(`DELETE FROM daily_summary WHERE device_id = ? AND local_date = ?`)
      .bind(deviceId, date)
      .run();
    await db
      .prepare(
        `INSERT INTO daily_summary
           (device_id, local_date, package_name, app_label, total_millis, segment_count)
         SELECT device_id, local_date, package_name,
                COALESCE(
                  MAX(CASE WHEN app_label <> package_name THEN app_label END),
                  MAX(app_label)
                ),
                SUM(end_time - start_time),
                COUNT(*)
         FROM usage_event
         WHERE device_id = ? AND local_date = ?
         GROUP BY device_id, local_date, package_name`
      )
      .bind(deviceId, date)
      .run();
  }

  return { duplicate: false, inserted, summariesUpdated: dates.length };
}

/** 期間内の集計を返す（全端末を合算）。 */
export async function querySummary(
  db: Database,
  from: string,
  to: string
): Promise<SummaryResponse> {
  const daily = await db
    .prepare(
      `SELECT local_date AS localDate, SUM(total_millis) AS totalMillis
       FROM daily_summary
       WHERE local_date >= ? AND local_date <= ?
       GROUP BY local_date
       ORDER BY local_date`
    )
    .bind(from, to)
    .all<{ localDate: string; totalMillis: number }>();

  const apps = await db
    .prepare(
      `SELECT package_name AS packageName,
              COALESCE(
                  MAX(CASE WHEN app_label <> package_name THEN app_label END),
                  MAX(app_label)
                ) AS appLabel,
              SUM(total_millis) AS totalMillis
       FROM daily_summary
       WHERE local_date >= ? AND local_date <= ?
       GROUP BY package_name
       ORDER BY totalMillis DESC
       LIMIT 50`
    )
    .bind(from, to)
    .all<{ packageName: string; appLabel: string; totalMillis: number }>();

  const devices = await db
    .prepare(
      `SELECT s.device_id AS deviceId,
              COALESCE(d.label, s.device_id) AS label,
              SUM(s.total_millis) AS totalMillis
       FROM daily_summary s
       LEFT JOIN device d ON d.id = s.device_id
       WHERE s.local_date >= ? AND s.local_date <= ?
       GROUP BY s.device_id
       ORDER BY totalMillis DESC`
    )
    .bind(from, to)
    .all<{ deviceId: string; label: string; totalMillis: number }>();

  return {
    from,
    to,
    dailyTotals: daily.results,
    appTotals: apps.results,
    byDevice: devices.results,
  };
}

/** 端末別・日別の生の集計行（デバッグ用）。 */
export async function queryDailySummary(
  db: Database,
  from: string,
  to: string
): Promise<DailySummaryRow[]> {
  const r = await db
    .prepare(
      `SELECT device_id AS deviceId, local_date AS localDate,
              package_name AS packageName, app_label AS appLabel,
              total_millis AS totalMillis, segment_count AS segmentCount
       FROM daily_summary
       WHERE local_date >= ? AND local_date <= ?
       ORDER BY local_date, totalMillis DESC`
    )
    .bind(from, to)
    .all<DailySummaryRow>();
  return r.results;
}

/**
 * **1日分の内訳**（日 × アプリの交点）。
 *
 * `/summary` は片方の軸しか返さないため、この関数が交点を埋める。
 * 端末は合算し（画面の見方に合わせる）、アプリ別と端末別を返す。
 *
 * 対象を1日に固定しているのは、**D1 の行読み取りと応答サイズに上限を作る**ため。
 * 期間を指定できるようにすると、30日 × 50アプリのような重い応答になりうる。
 */
export async function queryDay(db: Database, date: string): Promise<DayResponse> {
  const apps = await db
    .prepare(
      `SELECT package_name AS packageName,
              COALESCE(
                  MAX(CASE WHEN app_label <> package_name THEN app_label END),
                  MAX(app_label)
                ) AS appLabel,
              SUM(total_millis) AS totalMillis,
              SUM(segment_count) AS segmentCount
       FROM daily_summary
       WHERE local_date = ?
       GROUP BY package_name
       ORDER BY totalMillis DESC`
    )
    .bind(date)
    .all<{
      packageName: string;
      appLabel: string;
      totalMillis: number;
      segmentCount: number;
    }>();

  const devices = await db
    .prepare(
      `SELECT s.device_id AS deviceId,
              COALESCE(d.label, s.device_id) AS label,
              SUM(s.total_millis) AS totalMillis
       FROM daily_summary s
       LEFT JOIN device d ON d.id = s.device_id
       WHERE s.local_date = ?
       GROUP BY s.device_id
       ORDER BY totalMillis DESC`
    )
    .bind(date)
    .all<{ deviceId: string; label: string; totalMillis: number }>();

  return {
    date,
    totalMillis: apps.results.reduce((sum, a) => sum + a.totalMillis, 0),
    apps: apps.results,
    byDevice: devices.results,
  };
}
