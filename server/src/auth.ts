import type { Database, DeviceRow } from "./db.ts";

/**
 * 端末認証。
 *
 * **平文トークンは保存しない。** SHA-256 の hex のみを `device.token_hash` に置き、
 * 認証時は受け取ったトークンをハッシュして照合する。
 * 1つの共有パスワードにしないのは、漏洩時に全端末が危険になるため。
 */

/** トークンを SHA-256 の hex にする。 */
export async function hashToken(token: string): Promise<string> {
  const data = new TextEncoder().encode(token);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** 推測困難なトークンを作る（32バイト = 64桁 hex）。 */
export function generateToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** `Authorization: Bearer <token>` からトークンを取り出す。 */
export function parseBearer(header: string | null): string | null {
  if (!header) return null;
  const m = /^Bearer\s+(.+)$/i.exec(header.trim());
  return m ? m[1].trim() : null;
}

/** 認証する。失敗したら null。 */
export async function authenticate(db: Database, token: string): Promise<DeviceRow | null> {
  const hash = await hashToken(token);
  const row = await db
    .prepare(`SELECT * FROM device WHERE token_hash = ? AND revoked = 0`)
    .bind(hash)
    .first<DeviceRow>();
  return row ?? null;
}

/** 端末を登録する（管理者操作）。 */
export async function registerDevice(
  db: Database,
  label: string,
  now: number
): Promise<{ deviceId: string; token: string }> {
  const deviceId = crypto.randomUUID();
  const token = generateToken();
  const tokenHash = await hashToken(token);
  await db
    .prepare(
      `INSERT INTO device (id, token_hash, label, created_at, last_seen_at, revoked)
       VALUES (?, ?, ?, ?, NULL, 0)`
    )
    .bind(deviceId, tokenHash, label, now)
    .run();
  return { deviceId, token };
}
