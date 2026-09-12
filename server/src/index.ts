import { authenticate, parseBearer, registerDevice } from "./auth.ts";
import { ingestBatch, queryDay, querySummary, type Database } from "./db.ts";
import {
  daysBetween,
  isValidDateString,
  validateBatch,
} from "./validate.ts";
import { MAX_FUTURE_DAYS, MAX_PAST_DAYS } from "./types.ts";
import type { ErrorResponse, IngestResponse } from "./types.ts";
import {
  buildCookie,
  clearCookie,
  renderDashboard,
  renderDay,
  renderLogin,
  tokenFromCookie,
} from "./dashboard.ts";

/**
 * Cloudflare Worker のエントリポイント。
 *
 * ルーティング:
 *   GET  /healthz              … 死活確認（認証不要）
 *   POST /api/v1/devices       … 端末登録（管理者トークンが必要）
 *   POST /api/v1/ingest        … 取り込み（端末トークン）
 *   GET  /api/v1/summary       … 集計（端末トークン）
 *
 * 設計方針:
 *  - 取り込みは**冪等**。同じ batchId の再送は二重計上しない。
 *  - 構造的な誤りは 400（バグを表面化させる）。個別の不正イベントは
 *    スキップして応答で件数を報告する（1件の不具合で同期が止まらないように）。
 *  - 認証は端末ごとのトークン。読み取りは全端末分を返す（単一ユーザーのため）。
 */

export interface Env {
  DB: D1Database;
  /** 端末登録に使う管理者トークン。`wrangler secret put ADMIN_TOKEN` で設定する。 */
  ADMIN_TOKEN?: string;
}

/** D1 のバインディングを、テスト可能な最小インターフェースとして受け取る。 */
type AppEnv = { DB: Database; ADMIN_TOKEN?: string };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    return handle(request, env as unknown as AppEnv, Date.now());
  },
};

/** テストから直接呼べるようにしている。 */
export async function handle(
  request: Request,
  env: AppEnv,
  now: number
): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";

  // ---- 暗号化の必須要件: 平文 HTTP を拒否する ----
  // docs/PRIVACY.md §3.1。端末側の cleartext 禁止と**両方**で防ぐ
  // （片方だけだと設定ミスで素通りする）。
  // ローカルの開発サーバー（dev-server.ts）だけは例外とする。
  // これらのホスト名は Cloudflare には到達しないため、例外にしても安全。
  if (url.protocol !== "https:" && !isLocalHost(url.hostname)) {
    return json(
      { error: "https required", detail: "plaintext http is not accepted" },
      403
    );
  }

  try {
    if (request.method === "GET" && path === "/healthz") {
      return json({ ok: true, now });
    }

    // ---- ブラウザ用の画面（読み取り専用） ----
    if (request.method === "GET" && path === "/") {
      return await handleDashboard(request, env, now);
    }
    if (request.method === "POST" && path === "/dashboard") {
      return await handleDashboardAuth(request, env, now);
    }
    // 日別の内訳。メイン画面の日付をタップすると開く。
    if (request.method === "GET" && path === "/day") {
      return await handleDayPage(request, env, url);
    }

    if (request.method === "POST" && path === "/api/v1/devices") {
      return await handleRegisterDevice(request, env, now);
    }

    if (request.method === "POST" && path === "/api/v1/ingest") {
      return await handleIngest(request, env, now);
    }

    if (request.method === "GET" && path === "/api/v1/summary") {
      return await handleSummary(request, env, url);
    }

    // 1日の内訳（日 × アプリの交点）。1日分に固定している。
    if (request.method === "GET" && path === "/api/v1/day") {
      return await handleDay(request, env, url, now);
    }

    return json({ error: "not found" } satisfies ErrorResponse, 404);
  } catch (e) {
    // 予期しない例外。詳細は返さずログに残す（内部情報を漏らさない）。
    console.error("unhandled error", e);
    return json({ error: "internal error" } satisfies ErrorResponse, 500);
  }
}

// ---------------------------------------------------------------------------
// ブラウザ用の画面
// ---------------------------------------------------------------------------

/**
 * 集計画面。
 *
 * **トークンは Cookie から取る。** URL に置くとログに残るため。
 * Cookie が無ければトークン入力画面を返す。
 */
async function handleDashboard(request: Request, env: AppEnv, now: number): Promise<Response> {
  const cookie = tokenFromCookie(request.headers.get("Cookie"));
  if (!cookie) return html(renderLogin());

  const device = await authenticate(env.DB, cookie);
  if (!device) {
    // 失効したトークン。Cookie を消して入力し直してもらう。
    return html(renderLogin("Token rejected"), { headers: { "Set-Cookie": clearCookie() } });
  }

  // `now` を受け取るのは、実時刻に依存させないため（テストの決定性）。
  const today = new Date(now).toISOString().slice(0, 10);
  const from = new Date(now - 6 * 86_400_000).toISOString().slice(0, 10);
  const summary = await querySummary(env.DB, from, today);

  return html(renderDashboard(summary, today));
}

/** トークン入力の受け取り。 */async function handleDashboardAuth(
  request: Request,
  env: AppEnv,
  now: number
): Promise<Response> {
  const form = new URLSearchParams(await request.text());

  if (form.get("logout") === "1") {
    return redirect("/", { "Set-Cookie": clearCookie() });
  }

  const token = form.get("token")?.trim() ?? "";
  if (!token) return html(renderLogin("Token is required"), { status: 400 });

  const device = await authenticate(env.DB, token);
  if (!device) {
    // **存在するかどうかを区別しない**（総当たりの手がかりを与えない）。
    return html(renderLogin("Token rejected"), { status: 401 });
  }

  return redirect("/", { "Set-Cookie": buildCookie(token) });
}

/**
 * 日別の内訳ページ（ブラウザ用）。
 *
 * メイン画面の日付をタップして開く。
 * **認証はメイン画面と同じ**（Cookie）。
 */
async function handleDayPage(request: Request, env: AppEnv, url: URL): Promise<Response> {
  const cookie = tokenFromCookie(request.headers.get("Cookie"));
  if (!cookie) return html(renderLogin());

  const device = await authenticate(env.DB, cookie);
  if (!device) {
    return html(renderLogin("Token rejected"), { headers: { "Set-Cookie": clearCookie() } });
  }

  const date = url.searchParams.get("date") ?? "";
  if (!isValidDateString(date)) {
    return html(renderLogin("Invalid date"), { status: 400 });
  }

  return html(renderDay(await queryDay(env.DB, date)));
}

/**
 * 日別の内訳（API）。
 *
 * **1日分に固定している。** 期間を指定できるようにすると、
 * 30日 × 50アプリのような重い応答になりうるため。
 */
async function handleDay(
  request: Request,
  env: AppEnv,
  url: URL,
  now: number
): Promise<Response> {
  const token = parseBearer(request.headers.get("Authorization"));
  if (!token) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);
  const device = await authenticate(env.DB, token);
  if (!device) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);

  const date = url.searchParams.get("date") ?? "";
  if (!isValidDateString(date)) {
    return json({ error: "date must be YYYY-MM-DD" } satisfies ErrorResponse, 400);
  }

  // 未来・遠い過去は受け付けない（取り込み時の検証と同じ基準）
  const todayUtc = new Date(now).toISOString().slice(0, 10);
  const age = daysBetween(date, todayUtc);
  if (age > MAX_FUTURE_DAYS || age < -MAX_PAST_DAYS) {
    return json({ error: "date out of range" } satisfies ErrorResponse, 400);
  }

  return json(await queryDay(env.DB, date));
}

function html(body: string, opts: { status?: number; headers?: Record<string, string> } = {}) {
  return new Response(body, {
    status: opts.status ?? 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      // 認証済みの画面をキャッシュさせない。
      "cache-control": "no-store",
      ...(opts.headers ?? {}),
    },
  });
}

function redirect(location: string, headers: Record<string, string> = {}): Response {
  return new Response(null, { status: 303, headers: { location, ...headers } });
}

// ---------------------------------------------------------------------------
// 端末登録
// ---------------------------------------------------------------------------

async function handleRegisterDevice(
  request: Request,
  env: AppEnv,
  now: number
): Promise<Response> {
  if (!env.ADMIN_TOKEN) {
    // 未設定なら登録機能を無効にする（誰でも端末を追加できてしまうため）。
    return json(
      { error: "device registration is disabled", detail: "ADMIN_TOKEN is not set" },
      503
    );
  }
  const provided = parseBearer(request.headers.get("Authorization"));
  if (!provided || !timingSafeEqual(provided, env.ADMIN_TOKEN)) {
    return json({ error: "unauthorized" } satisfies ErrorResponse, 401);
  }

  let label = "device";
  try {
    const body = (await request.json()) as { label?: unknown };
    if (typeof body?.label === "string" && body.label.length > 0) {
      label = body.label.slice(0, 64);
    }
  } catch {
    // body 無しは許容する（既定のラベルを使う）
  }

  const { deviceId, token } = await registerDevice(env.DB, label, now);
  // トークンはここでしか返さない（保存はハッシュのみ）。
  return json({ deviceId, token, label }, 201);
}

// ---------------------------------------------------------------------------
// 取り込み
// ---------------------------------------------------------------------------

async function handleIngest(
  request: Request,
  env: AppEnv,
  now: number
): Promise<Response> {
  const token = parseBearer(request.headers.get("Authorization"));
  if (!token) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);

  const device = await authenticate(env.DB, token);
  if (!device) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);

  let raw: unknown;
  try {
    raw = await request.json();
  } catch {
    return json({ error: "invalid JSON" } satisfies ErrorResponse, 400);
  }

  const todayUtc = new Date(now).toISOString().slice(0, 10);
  const validation = validateBatch(raw, todayUtc);

  if (validation.structuralErrors.length > 0) {
    return json(
      { error: "invalid batch", detail: validation.structuralErrors.join("; ") },
      400
    );
  }

  const body = raw as { batchId: string; deviceId: string };

  // ボディの deviceId とトークンの端末が食い違う場合は拒否する。
  // （取り違えると別端末のデータとして保存されてしまう）
  if (body.deviceId !== device.id) {
    return json(
      { error: "device mismatch", detail: "token does not match deviceId" },
      403
    );
  }

  const result = await ingestBatch(
    env.DB,
    device.id,
    body.batchId,
    validation.validEvents,
    now
  );

  const payload: IngestResponse & { rejected?: { index: number; reason: string }[] } = {
    accepted: true,
    duplicate: result.duplicate,
    eventCount: result.inserted,
    summariesUpdated: result.summariesUpdated,
  };
  if (validation.rejectedEvents.length > 0) {
    payload.rejected = validation.rejectedEvents;
    console.warn("rejected events", validation.rejectedEvents);
  }

  // 再送は 200、新規は 202。
  return json(payload, result.duplicate ? 200 : 202);
}

// ---------------------------------------------------------------------------
// 集計
// ---------------------------------------------------------------------------

async function handleSummary(
  request: Request,
  env: AppEnv,
  url: URL
): Promise<Response> {
  const token = parseBearer(request.headers.get("Authorization"));
  if (!token) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);
  const device = await authenticate(env.DB, token);
  if (!device) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);

  const to = url.searchParams.get("to") ?? new Date().toISOString().slice(0, 10);
  const from =
    url.searchParams.get("from") ??
    // 既定は7日間
    new Date(Date.now() - 6 * 86_400_000).toISOString().slice(0, 10);

  // 書式だけでなく**実在する日付か**まで確認する。
  // 正規表現だけだと "2026-02-30" のような存在しない日付を通してしまい、
  // 集計が黙って空を返す（クライアントのバグを隠す）。
  if (!isValidDateString(from) || !isValidDateString(to)) {
    return json(
      { error: "from/to must be a valid YYYY-MM-DD" } satisfies ErrorResponse,
      400
    );
  }
  if (from > to) {
    return json({ error: "from must be <= to" } satisfies ErrorResponse, 400);
  }

  const summary = await querySummary(env.DB, from, to);
  return json(summary);
}

// ---------------------------------------------------------------------------
// ヘルパ
// ---------------------------------------------------------------------------

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

/** 長さ一定の比較（タイミング差でトークンを推測されないように）。 */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * ローカル開発用のホストか。
 *
 * これらは Cloudflare には到達しない（端末内・LAN 内で完結する）ため、
 * HTTPS 必須の例外にしても本番の安全性を損なわない。
 * Android エミュレータからホストを指す `10.0.2.2` も含む。
 */
function isLocalHost(hostname: string): boolean {
  return (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "::1" ||
    hostname === "10.0.2.2" ||
    hostname.endsWith(".local")
  );
}
