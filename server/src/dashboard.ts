import type { SessionRow } from "./session.ts";
import type { DayResponse, SummaryResponse } from "./types.ts";

/**
 * サーバー側の画面（読み取り専用）。
 *
 * **アプリのダッシュボードとは役割が違う。**
 *  - アプリ = スマホで日常的に見る（リング・ウィジェット）
 *  - この画面 = **PCのブラウザから確認する用**。「データが届いているか」を
 *    目で見るためにある（curl で JSON を読むより速い）
 *
 * 設計方針はアプリと揃える: モノクロ、赤は使わない、記号と数字を優先。
 * 外部CDNは使わない（1ファイルで完結）。
 */

const COOKIE_NAME = "sk_token";

/** Cookie からトークンを取り出す。 */
export function tokenFromCookie(header: string | null): string | null {
  if (!header) return null;
  for (const part of header.split(";")) {
    const [k, ...rest] = part.trim().split("=");
    if (k === COOKIE_NAME) {
      const v = rest.join("=");
      return v.length > 0 ? decodeURIComponent(v) : null;
    }
  }
  return null;
}

/**
 * Cookie を組み立てる。
 *
 * **中身はセッショントークン**（端末トークンではない）。ブラウザごとに独立して
 * 発行され、個別に失効できる。端末トークンをブラウザに置かないのは、
 * 1台のブラウザを止めるために端末すべてを止める羽目になるため。
 *
 * **`HttpOnly` を付ける**（JavaScript から読めないようにする）。
 * `Secure` は HTTPS 必須。`SameSite=Strict` で CSRF を防ぐ。
 * トークンを URL に置かないのは、**URL はログに残る**ため。
 */
export function buildCookie(sessionToken: string): string {
  return `${COOKIE_NAME}=${encodeURIComponent(sessionToken)}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=2592000`;
}

/** ログアウト用（即時失効）。 */
export function clearCookie(): string {
  return `${COOKIE_NAME}=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0`;
}

/** トークン入力画面。 */
export function renderLogin(error?: string): string {
  return page(
    "SelfKaizen",
    `
    <h1>SelfKaizen</h1>
    <p class="sub">Sign in this browser</p>
    <form method="POST" action="/dashboard">
      <label for="pair">Pairing code</label>
      <input id="pair" name="pair" autocomplete="off" autocapitalize="characters"
             spellcheck="false" placeholder="8 characters from the app">
      <p class="hint">Settings → Pair a browser. Valid for 3 minutes, single use.</p>

      <label for="token">Device token</label>
      <input id="token" name="token" type="password" autocomplete="off"
             placeholder="paste the token from device registration">
      <p class="hint">Only for the first browser, or if the app is unavailable.</p>

      <button type="submit">Open</button>
    </form>
    ${error ? `<p class="err">${esc(error)}</p>` : ""}
    <p class="foot">A session is created per browser. Each one can be revoked
    separately, and the device token is never stored in the browser.</p>
    `
  );
}

/** 集計画面に渡す、ブラウザ側の状態。 */
export interface DashboardView {
  /** 有効なセッション（新しい順）。 */
  sessions: SessionRow[];
  /** いま見ているブラウザのセッション ID。 */
  currentSessionId: string;
  /** いま発行したペアコード（発行直後のみ）。 */
  pairCode?: { code: string; expiresAt: number };
  /** エラー表示（コードが無効だった等）。 */
  error?: string;
}

/** 集計画面。 */
export function renderDashboard(
  summary: SummaryResponse,
  today: string,
  view: DashboardView
): string {
  const todayTotal = summary.dailyTotals.find((d) => d.localDate === today)?.totalMillis ?? 0;
  const maxDay = Math.max(1, ...summary.dailyTotals.map((d) => d.totalMillis));
  const maxApp = Math.max(1, ...summary.appTotals.map((a) => a.totalMillis));

  const days = summary.dailyTotals
    .map(
      (d) => `
      <a class="row link" href="/day?date=${encodeURIComponent(d.localDate)}">
        <span class="k">${esc(d.localDate)}</span>
        <span class="bar"><i style="width:${pct(d.totalMillis, maxDay)}%"></i></span>
        <span class="v">${fmt(d.totalMillis)}</span>
      </a>`
    )
    .join("");

  const apps = summary.appTotals
    .slice(0, 12)
    .map(
      (a) => `
      <div class="row">
        <span class="k">${esc(a.appLabel || a.packageName)}</span>
        <span class="bar"><i style="width:${pct(a.totalMillis, maxApp)}%"></i></span>
        <span class="v">${fmt(a.totalMillis)}</span>
      </div>`
    )
    .join("");

  const devices = summary.byDevice
    .map((d) => `<li>${esc(d.label)} — ${fmt(d.totalMillis)}</li>`)
    .join("");

  return page(
    "SelfKaizen",
    `
    <h1>SelfKaizen</h1>
    <p class="sub">${esc(summary.from)} → ${esc(summary.to)}</p>

    <section>
      <h2>Today</h2>
      <div class="big">${fmt(todayTotal)}</div>
    </section>

    <section>
      <h2>Daily</h2>
      ${days || `<p class="empty">no data</p>`}
    </section>

    <section>
      <h2>Top apps</h2>
      ${apps || `<p class="empty">no data</p>`}
    </section>

    <section>
      <h2>Devices</h2>
      <ul class="plain">${devices || "<li>none</li>"}</ul>
    </section>

    <section>
      <h2>Browsers</h2>
      ${sessionList(view)}

      <form method="POST" action="/dashboard" class="inline-form">
        <input type="hidden" name="newpair" value="1">
        <button type="submit">Pair a browser</button>
      </form>
      ${view.pairCode ? pairCodeBlock(view.pairCode) : ""}
      ${view.error ? `<p class="err">${esc(view.error)}</p>` : ""}
    </section>

    <p class="foot">Read-only. The limit lives on the phone, not here.</p>
    `
  );
}

/**
 * セッション一覧。
 *
 * **ブラウザごとに1行。** 複数の PC・スマホを区別して、1台ずつ止められる。
 * 「Sign out」は自分の行では Cookie も消す（ログイン画面に戻る）。
 */
function sessionList(view: DashboardView): string {
  if (view.sessions.length === 0) return `<p class="empty">no sessions</p>`;

  return `<ul class="sessions">${view.sessions
    .map((s) => {
      const current = s.id === view.currentSessionId;
      const last = s.last_seen_at ? fmtDateTime(s.last_seen_at) : "never used";
      return `
      <li>
        <span class="s-label">${esc(s.label)}${current ? ` <b>· this browser</b>` : ""}</span>
        <span class="s-meta">added ${fmtDateTime(s.created_at)} · last used ${last}</span>
        <form method="POST" action="/dashboard" class="inline-form">
          <input type="hidden" name="revoke" value="${esc(s.id)}">
          <button type="submit" class="link-btn">${current ? "Sign out" : "Revoke"}</button>
        </form>
      </li>`;
    })
    .join("")}</ul>`;
}

/** 発行直後のペアコード。**この画面を見られたら終わりなので短命。** */
function pairCodeBlock(pair: { code: string; expiresAt: number }): string {
  return `
  <div class="code">
    <div class="code-value">${esc(pair.code)}</div>
    <p class="hint">Enter it on the other browser. Single use, expires
    ${esc(fmtClock(pair.expiresAt))}.</p>
  </div>`;
}

/** `2026-09-13 01:05`。UTC で出す（ブラウザの地域差で誤解させないため）。 */
export function fmtDateTime(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(
    d.getUTCHours()
  )}:${p(d.getUTCMinutes())}`;
}

/** `01:05:42`（UTC）。 */
export function fmtClock(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`;
}

/**
 * 1日の内訳（`GET /day`）。
 *
 * メイン画面の日付をタップすると開く。
 * **「日 × アプリ」の交点**をここで初めて見せる
 * （メイン画面は片方の軸しか出せない）。
 */
export function renderDay(day: DayResponse): string {
  const maxApp = Math.max(1, ...day.apps.map((a) => a.totalMillis));

  const apps = day.apps
    .map(
      (a) => `
      <div class="row">
        <span class="k">${esc(a.appLabel || a.packageName)}</span>
        <span class="bar"><i style="width:${pct(a.totalMillis, maxApp)}%"></i></span>
        <span class="v">${fmt(a.totalMillis)}</span>
        <span class="n">${a.segmentCount}×</span>
      </div>`
    )
    .join("");

  const devices = day.byDevice
    .map((d) => `<li>${esc(d.label)} — ${fmt(d.totalMillis)}</li>`)
    .join("");

  return page(
    `SelfKaizen — ${day.date}`,
    `
    <a class="back" href="/">← Daily</a>
    <h1>${esc(day.date)}</h1>
    <div class="big">${fmt(day.totalMillis)}</div>

    <section>
      <h2>Apps</h2>
      ${apps || `<p class="empty">no data</p>`}
    </section>

    <section>
      <h2>Devices</h2>
      <ul class="plain">${devices || "<li>none</li>"}</ul>
    </section>

    <p class="foot">${day.apps.length} apps · "n×" is how many times the app was recorded.</p>
    `
  );
}

// ---------------------------------------------------------------------------

/** 0除算を避けた割合（%）。 */
function pct(value: number, max: number): number {
  if (max <= 0) return 0;
  return Math.round((value / max) * 1000) / 10;
}

/** ミリ秒を `3h 42m` にする。アプリの表記と揃える。 */
export function fmt(millis: number): string {
  const total = Math.max(0, Math.floor(millis / 60000));
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h > 0 ? `${h}h ${String(m).padStart(2, "0")}m` : `${m}m`;
}

/** HTML エスケープ。パッケージ名やラベルは外部由来なので必須。 */
export function esc(s: string): string {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** 共通の枠。CSS はインライン（外部CDNを使わない）。 */
function page(title: string, body: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>${esc(title)}</title>
<style>
  :root {
    --bg:#F5F5F5; --surface:#FFFFFF; --ink:#000000; --ink2:#6E6E6E;
    --ink3:#A1A1A1; --line:#E4E4E4; --track:#E9E9E9; --bar:#000000;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg:#0A0A0A; --surface:#151515; --ink:#FFFFFF; --ink2:#9A9A9A;
      --ink3:#616161; --line:#252525; --track:#242424; --bar:#FFFFFF;
    }
  }
  * { box-sizing:border-box; }
  body { margin:0; padding:28px; background:var(--bg); color:var(--ink);
         font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
  h1 { font-size:20px; margin:0 0 2px; }
  h2 { font-size:10.5px; letter-spacing:1.6px; text-transform:uppercase;
       color:var(--ink3); margin:0 0 10px; font-weight:700; }
  .sub { color:var(--ink2); margin:0 0 24px; font-size:12px; }
  section { background:var(--surface); border:1px solid var(--line);
            border-radius:10px; padding:16px; margin-bottom:14px; }
  .big { font-size:34px; font-weight:700; letter-spacing:-0.5px;
         font-variant-numeric:tabular-nums; }
  .row { display:flex; align-items:center; gap:10px; margin:6px 0; }
  .k { flex:0 0 34%; color:var(--ink2); font-size:12px; overflow:hidden;
       text-overflow:ellipsis; white-space:nowrap; }
  .bar { flex:1; height:6px; background:var(--track); border-radius:3px; overflow:hidden; }
  .bar i { display:block; height:100%; background:var(--bar); border-radius:3px; }
  .v { flex:0 0 64px; text-align:right; font-size:12px;
       font-variant-numeric:tabular-nums; }
  .empty { color:var(--ink3); font-size:12px; margin:0; }
  ul.plain { list-style:none; padding:0; margin:0; color:var(--ink2); font-size:12px; }
  a.row.link { text-decoration:none; color:inherit; border-radius:6px; }
  a.row.link:hover { background:var(--track); }
  .back { display:inline-block; color:var(--ink2); text-decoration:none;
          font-size:12px; margin-bottom:14px; }
  .n { flex:0 0 34px; text-align:right; color:var(--ink3); font-size:11px;
       font-variant-numeric:tabular-nums; }
  form { display:flex; flex-direction:column; gap:10px; max-width:420px; }
  label { font-size:11px; color:var(--ink2); }
  input { padding:10px; border:1px solid var(--line); border-radius:6px;
          background:var(--surface); color:var(--ink); font-size:13px; }
  button { padding:10px 14px; border:0; border-radius:6px; background:var(--ink);
           color:var(--bg); font-size:13px; font-weight:600; cursor:pointer; }
  .err { color:#C62828; font-size:12px; }
  .foot { color:var(--ink3); font-size:11px; margin-top:20px; }
  .hint { color:var(--ink3); font-size:11px; margin:-4px 0 4px; }
  ul.sessions { list-style:none; padding:0; margin:0 0 12px; }
  ul.sessions li { display:flex; flex-wrap:wrap; align-items:baseline; gap:8px;
                   padding:7px 0; border-top:1px solid var(--line); font-size:12px; }
  ul.sessions li:first-child { border-top:0; }
  .s-label { color:var(--ink); flex:1 1 auto; }
  .s-meta { color:var(--ink3); font-size:11px;
            font-variant-numeric:tabular-nums; }
  form.inline-form { display:inline; max-width:none; }
  button.link-btn { background:none; color:var(--ink2); font-weight:500;
                    padding:2px 6px; font-size:11px; text-decoration:underline; }
  button.link-btn:hover { color:var(--ink); }
  .code { border:1px dashed var(--line); border-radius:8px; padding:12px;
          margin-top:10px; }
  .code-value { font-size:26px; font-weight:700; letter-spacing:4px;
                font-variant-numeric:tabular-nums; }
</style>
</head>
<body>${body}</body>
</html>`;
}
