/**
 * クライアント ↔ サーバーの API 契約。
 *
 * **契約の要点:**
 *  - `batchId` は冪等性の単位。同じ値を再送しても二重計上しない。
 *  - `localDate` は**端末が確定**して送る。サーバーは日付を計算しない。
 *    タイムゾーンや夏時間の影響を避けるため。
 *  - 端末は**日付境界で区間を分割してから**送る。つまり1つの区間が
 *    複数の local_date にまたがることはない。
 *  - `schemaVersion` を必ず送る。古いアプリが残るため、サーバーは
 *    複数バージョンを受け入れる必要がある。
 */

/** 現在のスキーマ版。互換性を壊す変更のときだけ上げる。 */
export const SCHEMA_VERSION = 1;

/** 1リクエストあたりの最大イベント数。CPU時間とボディサイズを守るため。 */
export const MAX_EVENTS_PER_BATCH = 2000;

/** 受け付ける local_date の範囲（未来・遠い過去を弾く）。 */
export const MAX_FUTURE_DAYS = 2;
export const MAX_PAST_DAYS = 3650;

/** 1区間の最大長（ミリ秒）。24時間を超える区間は異常。 */
export const MAX_SEGMENT_MILLIS = 24 * 60 * 60 * 1000;

/** 取り込みリクエスト。 */
export interface IngestRequest {
  batchId: string;
  deviceId: string;
  schemaVersion: number;
  sentAt?: number;
  events: UsageEventPayload[];
}

/** サーバーが受け取る1区間。端末側 `AppUsageEvent` に対応する。 */
export interface UsageEventPayload {
  /** 端末が確定したローカル日付 "YYYY-MM-DD"。 */
  localDate: string;
  packageName: string;
  appLabel: string;
  startTime: number;
  endTime: number;
  /** Android 側の CloseReason をそのまま引き継ぐ。 */
  closeReason?: string | null;
}

/** 取り込み応答。 */
export interface IngestResponse {
  accepted: boolean;
  /** true なら「既に処理済みのバッチ」だった（再送）。 */
  duplicate: boolean;
  eventCount: number;
  summariesUpdated: number;
}

/** 日次集計の1行。 */
export interface DailySummaryRow {
  deviceId: string;
  localDate: string;
  packageName: string;
  appLabel: string;
  totalMillis: number;
  segmentCount: number;
}

/** 集計応答。 */
export interface SummaryResponse {
  from: string;
  to: string;
  /** 日ごとの合計（全端末を合算）。 */
  dailyTotals: { localDate: string; totalMillis: number }[];
  /** 期間内のアプリ別合計（降順）。 */
  appTotals: { packageName: string; appLabel: string; totalMillis: number }[];
  /** 端末別の内訳。 */
  byDevice: { deviceId: string; label: string; totalMillis: number }[];
}

/** エラー応答。 */
export interface ErrorResponse {
  error: string;
  detail?: string;
}

/**
 * 1日の内訳（`GET /api/v1/day`）。
 *
 * `/summary` は「日ごと（アプリ合算）」と「アプリごと（日合算）」しか返さず、
 * **交差した情報を持たない**。この応答がその交点を埋める。
 *
 * 1日分に固定しているのは、D1 の行読み取りと応答サイズに上限を作るため
 * （30日 × 50アプリを一度に返すと重くなる）。
 */
export interface DayResponse {
  date: string;
  /** その日の合計（全端末・全アプリ）。 */
  totalMillis: number;
  /** アプリ別（その日）。使用時間の降順。 */
  apps: {
    packageName: string;
    appLabel: string;
    totalMillis: number;
    segmentCount: number;
  }[];
  /** 端末別（その日）。 */
  byDevice: { deviceId: string; label: string; totalMillis: number }[];
}
