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
import {
  SESSION_REFRESH_AFTER_MS,
  authenticateSession,
  createSession,
  listSessions,
  mintPairCode,
  normalizePairCode,
  pruneSessions,
  redeemPairCode,
  revokeSession,
  sessionLabelFromUA,
  touchSession,
  type PairKind,
} from "./session.ts";

/**
 * Cloudflare Worker のエントリポイント。
 *
 * ルーティング:
 *   GET  /healthz              … 死活確認（認証不要）
 *   GET  /                     … 集計画面（ブラウザセッション）
 *   POST /dashboard            … ログイン・失効・ペアコード発行
 *   GET  /day                  … 日別の内訳（ブラウザセッション）
 *   POST /api/v1/devices       … 端末登録（管理者トークンが必要）
 *   POST /api/v1/devices/claim … 端末登録（ペアコード。未認証でよい）
 *   POST /api/v1/pair          … ペアコード発行（端末トークン or セッション）
 *   POST /api/v1/ingest        … 取り込み（端末トークン）
 *   GET  /api/v1/summary       … 集計（端末トークン）
 *   GET  /api/v1/day           … 日別の内訳（端末トークン）
 *
 * 認証は2種類ある。**混ぜない。**
 *  - 端末トークン … 端末（アプリ）が同期に使う。Cookie には入れない。
 *  - セッション   … ブラウザごとに1つ。1台ずつ失効できる。
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
      return await handleDayPage(request, env, url, now);
    }

    if (request.method === "POST" && path === "/api/v1/devices") {
      return await handleRegisterDevice(request, env, now);
    }

    // ペアコードの発行。**認証済みの主体だけ**が発行できる。
    if (request.method === "POST" && path === "/api/v1/pair") {
      return await handlePair(request, env, now);
    }

    // 新しい端末が自分自身を登録する（コードが資格情報）。
    if (request.method === "POST" && path === "/api/v1/devices/claim") {
      return await handleClaimDevice(request, env, now);
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
 * Cookie が無ければログイン画面を返す。
 */
async function handleDashboard(request: Request, env: AppEnv, now: number): Promise<Response> {
  const auth = await resolveBrowserAuth(request, env, now);
  if (!auth) return rejectBrowser(request, "Session expired");

  // `now` を受け取るのは、実時刻に依存させないため（テストの決定性）。
  const today = new Date(now).toISOString().slice(0, 10);
  const from = new Date(now - 6 * 86_400_000).toISOString().slice(0, 10);

  return html(
    renderDashboard(await querySummary(env.DB, from, today), today, {
      sessions: await listSessions(env.DB, now),
      currentSessionId: auth.sessionId,
    }),
    auth.setCookie ? { headers: { "Set-Cookie": auth.setCookie } } : {}
  );
}

/** ブラウザの認証結果。 */
interface BrowserAuth {
  sessionId: string;
  /**
   * Cookie を差し替える必要があるときだけ入る。
   *  - 旧 Cookie（端末トークン）からの昇格
   *  - セッションの延長
   */
  setCookie?: string;
}

/**
 * ブラウザの Cookie を解決する。
 *
 * 1. セッショントークンとして照合する（通常）。
 * 2. 一致しなければ**端末トークンとして照合し、セッションに昇格させる**。
 *    これは Cookie に端末トークンを入れていた旧実装からの移行措置で、
 *    利用者を突然ログアウトさせないためにある。
 *    （昇格後はブラウザに端末トークンは残らない。）
 */
async function resolveBrowserAuth(
  request: Request,
  env: AppEnv,
  now: number
): Promise<BrowserAuth | null> {
  const cookie = tokenFromCookie(request.headers.get("Cookie"));
  if (!cookie) return null;

  const session = await authenticateSession(env.DB, cookie, now);
  if (session) {
    // 延長は1日1回まで（毎リクエスト書くと D1 の書き込みが無駄に増える）。
    const stale = now - (session.last_seen_at ?? 0) > SESSION_REFRESH_AFTER_MS;
    if (stale) await touchSession(env.DB, session.id, now);
    return { sessionId: session.id, setCookie: stale ? buildCookie(cookie) : undefined };
  }

  const device = await authenticate(env.DB, cookie);
  if (!device) return null;

  const promoted = await createSession(
    env.DB,
    {
      label: sessionLabelFromUA(request.headers.get("User-Agent")),
      origin: "legacy",
      deviceId: device.id,
    },
    now
  );
  return { sessionId: promoted.id, setCookie: buildCookie(promoted.token) };
}

/**
 * 認証できないブラウザへの応答。
 *
 * Cookie を持っている（＝期限切れ・失効済み）なら消してログイン画面に戻す。
 * 持っていないなら、ただの初回アクセスなので何も消さない。
 */
function rejectBrowser(request: Request, message: string): Response {
  const had = tokenFromCookie(request.headers.get("Cookie")) !== null;
  return html(renderLogin(had ? message : undefined), {
    headers: had ? { "Set-Cookie": clearCookie() } : {},
  });
}

/**
 * ログインとログイン後の操作（すべて POST）。
 *
 *  - `pair=<code>`  … ペアコードを引き換えてセッションを作る
 *  - `token=<t>`    … 端末トークンでセッションを作る（初回・予備）
 *  - `newpair=1`    … 新しいブラウザ用のコードを発行して画面に出す
 *  - `revoke=<id>`  … 指定セッションを失効させる
 *  - `logout=1`     … 自分のセッションを失効させて Cookie を消す
 */
async function handleDashboardAuth(
  request: Request,
  env: AppEnv,
  now: number
): Promise<Response> {
  const form = new URLSearchParams(await request.text());
  const auth = await resolveBrowserAuth(request, env, now);
  const label = sessionLabelFromUA(request.headers.get("User-Agent"));

  // ---- ログアウト（自分のセッションを失効させる） ----
  if (form.get("logout") === "1") {
    if (auth) await revokeSession(env.DB, auth.sessionId, now);
    return redirect("/", { "Set-Cookie": clearCookie() });
  }

  // ---- 他のセッションの失効 ----
  const revokeId = form.get("revoke");
  if (revokeId) {
    if (!auth) return rejectBrowser(request, "Session expired");
    await revokeSession(env.DB, revokeId, now);
    // 自分自身を消した場合はログイン画面へ戻す。
    if (revokeId === auth.sessionId) {
      return redirect("/", { "Set-Cookie": clearCookie() });
    }
    return redirect("/");
  }

  // ---- 未ログイン: コードかトークンでセッションを作る ----
  if (!auth) {
    const code = form.get("pair") ?? "";
    const token = form.get("token")?.trim() ?? "";

    if (normalizePairCode(code).length > 0) {
      const redeemed = await redeemPairCode(env.DB, code, now);
      if (!redeemed.ok) {
        // **理由を区別しない**（総当たりの手がかりを与えない）。
        return html(renderLogin("Code rejected"), { status: 401 });
      }
      const created = await createSession(
        env.DB,
        {
          label,
          origin: "pair",
          deviceId: redeemed.deviceId,
          originSessionId: redeemed.sessionId,
        },
        now
      );
      return redirect("/", { "Set-Cookie": buildCookie(created.token) });
    }

    if (!token) {
      return html(renderLogin("Enter a pairing code or a device token"), { status: 400 });
    }

    const device = await authenticate(env.DB, token);
    if (!device) {
      return html(renderLogin("Token rejected"), { status: 401 });
    }
    const created = await createSession(
      env.DB,
      { label, origin: "token", deviceId: device.id },
      now
    );
    return redirect("/", { "Set-Cookie": buildCookie(created.token) });
  }

  // ---- ログイン済み: 新しいブラウザ用のコードを発行 ----
  if (form.get("newpair") === "1") {
    await pruneSessions(env.DB, now);
    const { code, expiresAt } = await mintPairCode(
      env.DB,
      { sessionId: auth.sessionId },
      now
    );

    const today = new Date(now).toISOString().slice(0, 10);
    const from = new Date(now - 6 * 86_400_000).toISOString().slice(0, 10);
    return html(
      renderDashboard(await querySummary(env.DB, from, today), today, {
        sessions: await listSessions(env.DB, now),
        currentSessionId: auth.sessionId,
        pairCode: { code, expiresAt },
      }),
      auth.setCookie ? { headers: { "Set-Cookie": auth.setCookie } } : {}
    );
  }

  return redirect("/");
}

/**
 * 日別の内訳ページ（ブラウザ用）。
 *
 * メイン画面の日付をタップして開く。
 * **認証はメイン画面と同じ**（Cookie）。
 */
async function handleDayPage(
  request: Request,
  env: AppEnv,
  url: URL,
  now: number
): Promise<Response> {
  const auth = await resolveBrowserAuth(request, env, now);
  if (!auth) return rejectBrowser(request, "Session expired");

  const date = url.searchParams.get("date") ?? "";
  if (!isValidDateString(date)) {
    return html(renderLogin("Invalid date"), { status: 400 });
  }

  return html(renderDay(await queryDay(env.DB, date)), {
    headers: auth.setCookie ? { "Set-Cookie": auth.setCookie } : {},
  });
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
// ペアコードの発行
// ---------------------------------------------------------------------------

/**
 * ペアコードを発行する。
 *
 * **認証済みの主体だけ**が発行できる:
 *  - 端末トークン（アプリ）… `Authorization: Bearer <token>`
 *  - 既存のブラウザセッション … Cookie
 *
 * 未認証で発行できると、ログイン画面を開いた誰もがログインできてしまい、
 * 認証が丸ごと無意味になる。
 *
 * body の `{"for":"device"}` で**端末登録用**のコードになる（既定はブラウザ用）。
 * 用途は引き換え時に照合するので、取り違えは通らない。
 */
async function handlePair(request: Request, env: AppEnv, now: number): Promise<Response> {
  let deviceId: string | null = null;
  let sessionId: string | null = null;

  const bearer = parseBearer(request.headers.get("Authorization"));
  if (bearer) {
    const device = await authenticate(env.DB, bearer);
    if (!device) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);
    deviceId = device.id;
  } else {
    const auth = await resolveBrowserAuth(request, env, now);
    if (!auth) return json({ error: "unauthorized" } satisfies ErrorResponse, 401);
    sessionId = auth.sessionId;
  }

  let kind: PairKind = "browser";
  try {
    const body = (await request.json()) as { for?: unknown };
    if (body?.for === "device") kind = "device";
  } catch {
    // body 無しはブラウザ用（既定）。
  }

  const { code, expiresAt } = await mintPairCode(env.DB, { deviceId, sessionId }, now, kind);
  return json({
    code,
    kind,
    expiresAt,
    expiresInSeconds: Math.max(0, Math.floor((expiresAt - now) / 1000)),
  });
}

// ---------------------------------------------------------------------------
// 端末をペアコードで登録する
// ---------------------------------------------------------------------------

/**
 * 新しい端末が自分自身を登録する。
 *
 * **未認証でよい。** コード自体が資格情報で、3分・1回限り。
 * 発行には認証が要るので、勝手に端末を増やすことはできない。
 *
 * これがあると、端末を1台増やすのに `ADMIN_TOKEN` を取り出して
 * `curl` を叩く必要がなくなる。管理トークンは
 * 「全部の端末とセッションを失ったとき」の最後の手段として残す。
 */
async function handleClaimDevice(
  request: Request,
  env: AppEnv,
  now: number
): Promise<Response> {
  let body: { code?: unknown; label?: unknown };
  try {
    body = (await request.json()) as { code?: unknown; label?: unknown };
  } catch {
    return json({ error: "invalid JSON" } satisfies ErrorResponse, 400);
  }

  const rawCode = typeof body.code === "string" ? body.code : "";
  if (normalizePairCode(rawCode).length === 0) {
    return json({ error: "code is required" } satisfies ErrorResponse, 400);
  }

  // **先に引き換える。** 使用済みにするのはコード側の責務で、
  // 同時アクセスでも二重に端末が増えない。
  const redeemed = await redeemPairCode(env.DB, rawCode, now, "device");
  if (!redeemed.ok) {
    // 理由は返さない（総当たりの手がかりを与えない）。
    return json({ error: "code rejected" } satisfies ErrorResponse, 401);
  }

  let label = "device";
  if (typeof body.label === "string" && body.label.length > 0) {
    label = body.label.slice(0, 64);
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
