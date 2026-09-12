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
 * **`HttpOnly` を付ける**（JavaScript から読めないようにする）。
 * `Secure` は HTTPS 必須。`SameSite=Strict` で CSRF を防ぐ。
 * トークンを URL に置かないのは、**URL はログに残る**ため。
 */
export function buildCookie(token: string): string {
  return `${COOKIE_NAME}=${encodeURIComponent(token)}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=2592000`;
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
    <p class="sub">Server aggregate</p>
    <form method="POST" action="/dashboard">
      <label for="token">Device token</label>
      <input id="token" name="token" type="password" autocomplete="off"
             placeholder="paste the token from device registration" required>
      <button type="submit">Open</button>
    </form>
    ${error ? `<p class="err">${esc(error)}</p>` : ""}
    <p class="foot">The token is stored in an HttpOnly cookie on this browser only.</p>
    `
  );
}

/** 集計画面。 */
export function renderDashboard(summary: SummaryResponse, today: string): string {
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

    <form method="POST" action="/dashboard" class="logout">
      <input type="hidden" name="logout" value="1">
      <button type="submit">Sign out</button>
    </form>
    <p class="foot">Read-only. The limit lives on the phone, not here.</p>
    `
  );
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
  .logout { margin-top:8px; }
  .err { color:#C62828; font-size:12px; }
  .foot { color:var(--ink3); font-size:11px; margin-top:20px; }
</style>
</head>
<body>${body}</body>
</html>`;
}
