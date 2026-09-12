import {
  MAX_EVENTS_PER_BATCH,
  MAX_FUTURE_DAYS,
  MAX_PAST_DAYS,
  MAX_SEGMENT_MILLIS,
  SCHEMA_VERSION,
  type IngestRequest,
  type UsageEventPayload,
} from "./types.ts";

/**
 * 検証と正規化。
 *
 * **Android / Cloudflare に依存しない純粋なロジック**にしてある。
 * ここが最も壊れやすい（誤ったデータを静かに受け入れると、
 * 後から数値が信用できなくなる）ため、単体テストで境界を固定する。
 */

/** 正規化済みの区間。 */
export interface NormalizedEvent {
  localDate: string;
  packageName: string;
  appLabel: string;
  startTime: number;
  endTime: number;
  closeReason: string | null;
}

/** 検証結果。 */
export interface BatchValidation {
  /** 構造的な誤り。1件でもあればバッチ全体を拒否する（400）。 */
  structuralErrors: string[];
  /** 受け入れる区間。 */
  validEvents: NormalizedEvent[];
  /** 個別に弾いた区間と理由。バッチは受理し、応答で報告する。 */
  rejectedEvents: { index: number; reason: string }[];
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_STRING = 255;
const MAX_CLOSE_REASON = 64;

/** "YYYY-MM-DD" として妥当か（実在する日付かまで確認する）。 */
export function isValidDateString(s: unknown): s is string {
  if (typeof s !== "string" || !DATE_RE.test(s)) return false;
  const [y, m, d] = s.split("-").map(Number);
  if (m < 1 || m > 12) return false;
  // その月の日数。Date.UTC の月末溢出を利用する。
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return d >= 1 && d <= daysInMonth;
}

/** a - b を日数で返す（UTC の日付として扱う。時刻は無関係）。 */
export function daysBetween(a: string, b: string): number {
  const [ay, am, ad] = a.split("-").map(Number);
  const [by, bm, bd] = b.split("-").map(Number);
  return Math.round((Date.UTC(ay, am - 1, ad) - Date.UTC(by, bm - 1, bd)) / 86_400_000);
}

function isNonEmptyString(v: unknown, max: number): v is string {
  return typeof v === "string" && v.length > 0 && v.length <= max;
}

function isFiniteInteger(v: unknown): v is number {
  return typeof v === "number" && Number.isInteger(v) && Number.isFinite(v);
}

/**
 * バッチを検証する。
 *
 * @param raw パース済みの JSON（unknown として受け取り、自分で型を確かめる）
 * @param todayUtc "YYYY-MM-DD"。テストのために注入できるようにしている
 */
export function validateBatch(raw: unknown, todayUtc: string): BatchValidation {
  const structuralErrors: string[] = [];
  const validEvents: NormalizedEvent[] = [];
  const rejectedEvents: { index: number; reason: string }[] = [];

  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    return { structuralErrors: ["body must be a JSON object"], validEvents, rejectedEvents };
  }

  const body = raw as Partial<IngestRequest>;

  // ---- 構造的な検証 ----
  if (!isNonEmptyString(body.batchId, 128)) {
    structuralErrors.push("batchId must be a non-empty string (<=128)");
  }
  if (!isNonEmptyString(body.deviceId, 128)) {
    structuralErrors.push("deviceId must be a non-empty string (<=128)");
  }
  if (!isFiniteInteger(body.schemaVersion)) {
    structuralErrors.push("schemaVersion must be an integer");
  } else if (body.schemaVersion > SCHEMA_VERSION) {
    // 新しいアプリが古いサーバーに送っている。黙って受けると解釈を誤る。
    structuralErrors.push(
      `schemaVersion ${body.schemaVersion} is newer than supported ${SCHEMA_VERSION}`
    );
  } else if (body.schemaVersion < 1) {
    structuralErrors.push(`schemaVersion must be >= 1`);
  }
  if (!Array.isArray(body.events)) {
    structuralErrors.push("events must be an array");
    return { structuralErrors, validEvents, rejectedEvents };
  }
  if (body.events.length > MAX_EVENTS_PER_BATCH) {
    structuralErrors.push(
      `events must not exceed ${MAX_EVENTS_PER_BATCH} (got ${body.events.length})`
    );
  }

  if (structuralErrors.length > 0) {
    return { structuralErrors, validEvents, rejectedEvents };
  }

  // ---- 個別の検証 ----
  body.events.forEach((e, index) => {
    const reason = validateEvent(e, todayUtc);
    if (reason) {
      rejectedEvents.push({ index, reason });
      return;
    }
    validEvents.push(normalizeEvent(e as UsageEventPayload));
  });

  return { structuralErrors, validEvents, rejectedEvents };
}

/** 問題があれば理由を返す。無ければ null。 */
function validateEvent(e: unknown, todayUtc: string): string | null {
  if (typeof e !== "object" || e === null) return "not an object";
  const ev = e as Partial<UsageEventPayload>;

  if (!isValidDateString(ev.localDate)) return "localDate must be a valid YYYY-MM-DD";
  const age = daysBetween(ev.localDate, todayUtc);
  if (age > MAX_FUTURE_DAYS) return `localDate is too far in the future (${ev.localDate})`;
  if (age < -MAX_PAST_DAYS) return `localDate is too old (${ev.localDate})`;

  if (!isNonEmptyString(ev.packageName, MAX_STRING)) return "packageName is required";
  if (typeof ev.appLabel !== "string" || ev.appLabel.length > MAX_STRING) {
    return "appLabel must be a string (<=255)";
  }
  if (!isFiniteInteger(ev.startTime) || ev.startTime < 0) {
    return "startTime must be a non-negative integer";
  }
  if (!isFiniteInteger(ev.endTime) || ev.endTime < 0) {
    return "endTime must be a non-negative integer";
  }
  if (ev.endTime < ev.startTime) {
    // 実データで時刻の逆行が確認されている（別スレッドの記録）。
    // 負の区間をそのまま入れると合計が狂うため弾く。
    return "endTime must be >= startTime";
  }
  if (ev.endTime - ev.startTime > MAX_SEGMENT_MILLIS) {
    return "segment exceeds 24 hours";
  }
  if (
    ev.closeReason !== undefined &&
    ev.closeReason !== null &&
    (typeof ev.closeReason !== "string" || ev.closeReason.length > MAX_CLOSE_REASON)
  ) {
    return "closeReason must be a string (<=64) or null";
  }
  return null;
}

function normalizeEvent(e: UsageEventPayload): NormalizedEvent {
  return {
    localDate: e.localDate,
    packageName: e.packageName.trim(),
    appLabel: (e.appLabel ?? "").trim().slice(0, MAX_STRING),
    startTime: e.startTime,
    endTime: e.endTime,
    closeReason: e.closeReason == null ? null : e.closeReason,
  };
}
