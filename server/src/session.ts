import type { Database } from "./db.ts";
import { generateToken, hashToken } from "./auth.ts";

/**
 * ブラウザセッションとペアコード。
 *
 * **端末トークンとセッショントークンを分ける。** 端末トークンは端末（アプリ）が
 * 同期に使う機械用の資格情報で、ブラウザには渡さない。ブラウザには
 * 1ブラウザにつき1つの独立したセッションを発行する。
 *
 * これで「PC を1台増やす」「スマホのブラウザだけ止める」ができる。
 * （旧実装は Cookie に端末トークンを入れていたため、どちらもできなかった。）
 *
 * **平文は一切保存しない。** セッショントークンもペアコードも SHA-256 のみ。
 */

/** ペアコードの有効期間。短いほど安全（総当たりの窓が狭くなる）。 */
export const PAIR_CODE_TTL_MS = 3 * 60_000;

/** セッションの有効期間。使用のたびに延びる（sliding）。 */
export const SESSION_TTL_MS = 30 * 86_400_000;

/**
 * セッションの延長と書き込みを間引く間隔。
 *
 * 毎リクエスト書くと D1 の書き込みが無駄に増える。1日1回で十分
 * （30日有効のうち、最大でも1日分しか目減りしない）。
 */
export const SESSION_REFRESH_AFTER_MS = 86_400_000;

/**
 * ペアコードの文字種。
 *
 * Crockford Base32（`I` `L` `O` `U` を除く）。**見間違えやすい文字を避ける**ため。
 * 0 と O、1 と I/L を取り違えても [normalizePairCode] が吸収する。
 */
const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** ペアコードの長さ。32^8 = 40bit なので総当たりは現実的でない。 */
const CODE_LENGTH = 8;

/** ブラウザセッションの1行。 */
export interface SessionRow {
  id: string;
  token_hash: string;
  label: string;
  origin: string;
  device_id: string | null;
  origin_session_id: string | null;
  created_at: number;
  last_seen_at: number | null;
  expires_at: number;
  revoked_at: number | null;
}

/**
 * ペアコードを作る（表示用の平文）。
 *
 * 32 は 256 を割り切るので `% 32` に偏りは無い（棄却サンプリング不要）。
 */
export function generatePairCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  return [...bytes].map((b) => CODE_ALPHABET[b % 32]).join("");
}

/**
 * 入力されたコードを正規化する。
 *
 * 小文字・空白・ハイフンを許し、**紛らわしい文字を寄せる**
 * （`O`→`0`、`I`/`L`→`1`）。人が書き写す前提のコードなので、
 * 1文字の取り違えで失敗させない。
 */
export function normalizePairCode(input: string): string {
  return input
    .toUpperCase()
    .replace(/O/g, "0")
    .replace(/[IL]/g, "1")
    .split("")
    .filter((c) => CODE_ALPHABET.includes(c))
    .join("");
}

/** ペアコードの用途。 */
export type PairKind = "browser" | "device";

/**
 * ペアコードを発行する。
 *
 * 発行できるのは**認証済みの主体だけ**（端末トークンを持つアプリ、または
 * ログイン済みのブラウザ）。未認証の画面で発行すると、その画面を開いた
 * 誰もがログインできてしまい、認証が意味を失う。
 *
 * [kind] は用途。`browser` は閲覧セッション、`device` は端末トークンを配る。
 * **引き換え側で一致を確認する**ので、取り違えは通らない。
 */
export async function mintPairCode(
  db: Database,
  source: { deviceId?: string | null; sessionId?: string | null },
  now: number,
  kind: PairKind = "browser"
): Promise<{ code: string; expiresAt: number }> {
  const code = generatePairCode();
  const codeHash = await hashToken(code);
  const expiresAt = now + PAIR_CODE_TTL_MS;

  await db
    .prepare(
      `INSERT INTO pair_code (code_hash, device_id, session_id, created_at, expires_at, used_at, kind)
       VALUES (?, ?, ?, ?, ?, NULL, ?)`
    )
    .bind(codeHash, source.deviceId ?? null, source.sessionId ?? null, now, expiresAt, kind)
    .run();

  // 期限切れの掃除。**発行時にまとめて**行う（読み取り経路を軽く保つ）。
  await db
    .prepare(`DELETE FROM pair_code WHERE expires_at < ?`)
    .bind(now - 86_400_000)
    .run();

  return { code, expiresAt };
}

/** 引き換えの結果。 */
export type RedeemResult =
  | { ok: true; deviceId: string | null; sessionId: string | null }
  | { ok: false; reason: "not_found" | "expired" | "used" | "wrong_kind" };

/**
 * ペアコードを引き換える。**単回使用。**
 *
 * 「見つからない」「期限切れ」「使用済み」「用途違い」を区別して返すが、
 * HTTP の応答では区別しない（総当たりの手がかりを与えないため）。
 */
export async function redeemPairCode(
  db: Database,
  input: string,
  now: number,
  kind: PairKind = "browser"
): Promise<RedeemResult> {
  const code = normalizePairCode(input);
  if (code.length !== CODE_LENGTH) return { ok: false, reason: "not_found" };

  const hash = await hashToken(code);
  const row = await db
    .prepare(`SELECT * FROM pair_code WHERE code_hash = ?`)
    .bind(hash)
    .first<{
      device_id: string | null;
      session_id: string | null;
      expires_at: number;
      used_at: number | null;
      kind: string;
    }>();

  if (!row) return { ok: false, reason: "not_found" };
  if (row.used_at !== null) return { ok: false, reason: "used" };
  if (row.expires_at <= now) return { ok: false, reason: "expired" };
  if (row.kind !== kind) return { ok: false, reason: "wrong_kind" };

  // **使用済みにするのが先。** 逆にすると同時アクセスで二重に使われる。
  const marked = await db
    .prepare(`UPDATE pair_code SET used_at = ? WHERE code_hash = ? AND used_at IS NULL`)
    .bind(now, hash)
    .run();
  if (marked.meta.changes === 0) return { ok: false, reason: "used" };

  return { ok: true, deviceId: row.device_id, sessionId: row.session_id };
}

/** セッションを作る。**平文トークンはここでしか存在しない。** */
export async function createSession(
  db: Database,
  opts: {
    label: string;
    origin: "pair" | "token" | "legacy";
    deviceId?: string | null;
    originSessionId?: string | null;
  },
  now: number
): Promise<{ id: string; token: string; expiresAt: number }> {
  const token = generateToken();
  const tokenHash = await hashToken(token);
  const id = crypto.randomUUID();
  const expiresAt = now + SESSION_TTL_MS;

  await db
    .prepare(
      `INSERT INTO browser_session
         (id, token_hash, label, origin, device_id, origin_session_id,
          created_at, last_seen_at, expires_at, revoked_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`
    )
    .bind(
      id,
      tokenHash,
      opts.label.slice(0, 64),
      opts.origin,
      opts.deviceId ?? null,
      opts.originSessionId ?? null,
      now,
      now,
      expiresAt
    )
    .run();

  return { id, token, expiresAt };
}

/**
 * セッショントークンで認証する。
 *
 * 失効・期限切れは null（呼び出し側は Cookie を消してログイン画面に戻す）。
 */
export async function authenticateSession(
  db: Database,
  token: string,
  now: number
): Promise<SessionRow | null> {
  const hash = await hashToken(token);
  const row = await db
    .prepare(
      `SELECT * FROM browser_session
       WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?`
    )
    .bind(hash, now)
    .first<SessionRow>();
  return row ?? null;
}

/**
 * セッションの延長。
 *
 * 書き込みを減らすため、**1日以上経ってから**呼ぶ（呼び出し側で判定する）。
 */
export async function touchSession(
  db: Database,
  id: string,
  now: number
): Promise<number> {
  const expiresAt = now + SESSION_TTL_MS;
  await db
    .prepare(`UPDATE browser_session SET last_seen_at = ?, expires_at = ? WHERE id = ?`)
    .bind(now, expiresAt, id)
    .run();
  return expiresAt;
}

/** 有効なセッションの一覧（新しい順）。 */
export async function listSessions(
  db: Database,
  now: number
): Promise<SessionRow[]> {
  const r = await db
    .prepare(
      `SELECT * FROM browser_session
       WHERE revoked_at IS NULL AND expires_at > ?
       ORDER BY COALESCE(last_seen_at, created_at) DESC
       LIMIT 50`
    )
    .bind(now)
    .all<SessionRow>();
  return r.results;
}

/**
 * セッションを失効させる。
 *
 * **これが本当のサインアウト。** Cookie を消すだけでは、控えられた
 * トークンがサーバー側で生き続ける。
 */
export async function revokeSession(
  db: Database,
  id: string,
  now: number
): Promise<boolean> {
  const r = await db
    .prepare(`UPDATE browser_session SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`)
    .bind(now, id)
    .run();
  return r.meta.changes > 0;
}

/** 失効済み・期限切れの古い行を片付ける（発行時に呼ぶ）。 */
export async function pruneSessions(db: Database, now: number): Promise<void> {
  await db
    .prepare(
      `DELETE FROM browser_session
       WHERE expires_at < ? OR (revoked_at IS NOT NULL AND revoked_at < ?)`
    )
    .bind(now, now - 7 * 86_400_000)
    .run();
}

/**
 * User-Agent から表示名を作る。
 *
 * 複数の PC・スマホを見分けるための**最低限の**情報。UA は偽装できるので
 * 認証には使わない（表示だけ）。
 */
export function sessionLabelFromUA(ua: string | null): string {
  if (!ua) return "unknown device";
  // コマンドラインから叩かれた場合（動作確認など）は OS を名乗らない。
  // 「curl on unknown OS」のような無意味な表示にしない。
  if (/^curl\//i.test(ua.trim())) return "curl";

  // 順序が重要: Edge/Opera は Chrome を含み、Android は Linux を含む。
  const os = /iPhone|iPad|iPod/.test(ua)
    ? "iOS"
    : /Android/.test(ua)
      ? "Android"
      : /Mac OS X|Macintosh/.test(ua)
        ? "macOS"
        : /Windows/.test(ua)
          ? "Windows"
          : /Linux/.test(ua)
            ? "Linux"
            : "unknown OS";

  const browser = /Edg\//.test(ua)
    ? "Edge"
    : /OPR\//.test(ua)
      ? "Opera"
      : /Firefox\//.test(ua)
        ? "Firefox"
        : /Chrome\//.test(ua)
          ? "Chrome"
          : /Safari\//.test(ua)
            ? "Safari"
            : "browser";

  return `${browser} on ${os}`;
}
