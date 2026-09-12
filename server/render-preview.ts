/**
 * 画面プレビューの生成。
 *
 * **本物のコードパスを通す**（`ingestBatch` → `querySummary` → `renderDashboard`）。
 * モックアップではなく、実際にサーバーが返す HTML をファイルに書き出す。
 *
 * 使い方:
 *   node --experimental-strip-types render-preview.ts
 *   → preview/dashboard.html と preview/login.html ができる
 *
 * サーバーを起動し続けなくても画面を確認できる。
 * （静的ファイルなので「Sign out」は動かない。見た目専用）
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { createTestDb, seedDevice } from "./test/d1-adapter.ts";
import {
  ingestBatch,
  queryDailySummary,
  queryDay,
  querySummary,
  type Database,
} from "./src/db.ts";
import { hashToken } from "./src/auth.ts";
import { renderDashboard, renderDay, renderLogin } from "./src/dashboard.ts";
import type { NormalizedEvent } from "./src/validate.ts";

const here = dirname(fileURLToPath(import.meta.url));
const schema = readFileSync(join(here, "migrations", "0001_init.sql"), "utf8");

const PHONE = "11111111-1111-1111-1111-111111111111";
const LAPTOP = "22222222-2222-2222-2222-222222222222";

/** スマホのアプリ。上位ほど使用時間が長い。 */
const PHONE_APPS: { pkg: string; label: string; weight: number }[] = [
  { pkg: "com.google.android.youtube", label: "YouTube", weight: 0.30 },
  { pkg: "com.android.chrome", label: "Chrome", weight: 0.19 },
  { pkg: "jp.naver.line.android", label: "LINE", weight: 0.16 },
  { pkg: "com.instagram.android", label: "Instagram", weight: 0.13 },
  { pkg: "com.twitter.android", label: "X", weight: 0.10 },
  { pkg: "com.google.android.apps.maps", label: "Maps", weight: 0.07 },
  { pkg: "com.google.android.gm", label: "Gmail", weight: 0.05 },
];

/**
 * PC のアプリ。**スマホとは別のアプリ**にする。
 * 同じアプリ名を両方に入れると「合算」の意味が分かりにくくなるため。
 */
const PC_APPS: { pkg: string; label: string; weight: number }[] = [
  { pkg: "com.microsoft.VSCode", label: "Code", weight: 0.55 },
  { pkg: "com.google.Chrome", label: "Chrome (PC)", weight: 0.30 },
  { pkg: "com.tinyspeck.slackmacgap", label: "Slack", weight: 0.15 },
];

/** 日ごとのスマホ利用（分）。添字0が6日前、末尾が今日。今日はまだ途中。 */
const PHONE_MINUTES = [186, 142, 231, 118, 268, 157, 222];

/** 日ごとの PC 利用（分）。PC は使わない日もある（0）。 */
const PC_MINUTES = [0, 96, 0, 74, 0, 128, 81];

function ev(
  localDate: string,
  pkg: string,
  label: string,
  start: number,
  end: number
): NormalizedEvent {
  return {
    localDate,
    packageName: pkg,
    appLabel: label,
    startTime: start,
    endTime: end,
    closeReason: "PAUSED",
  };
}

/** 決定的な擬似乱数（実行のたびに同じ絵になるように）。 */
function makeRandom(seed: number) {
  let s = seed;
  return () => {
    s = (s * 1103515245 + 12345) % 2147483648;
    return s / 2147483648;
  };
}

function isoDate(daysAgo: number): string {
  return new Date(Date.now() - daysAgo * 86_400_000).toISOString().slice(0, 10);
}

/**
 * 1日分の区間を組み立てる。
 *
 * 重みで予算を配分し、各区間を時系列に並べる。
 * `startOffset` は端末ごとにずらして、同時刻に重ならないようにする
 * （重なるとサーバー側の合算が不自然になる）。
 */
function buildDay(
  date: string,
  apps: { pkg: string; label: string; weight: number }[],
  budgetMinutes: number,
  rand: () => number,
  startOffsetMillis: number
): NormalizedEvent[] {
  if (budgetMinutes <= 0) return [];

  const budget = budgetMinutes * 60_000;
  const out: NormalizedEvent[] = [];
  let cursor = startOffsetMillis;

  for (const app of apps) {
    // ±15% の揺らぎを入れて、機械的に見えないようにする
    const jitter = 0.85 + rand() * 0.3;
    const total = Math.floor(budget * app.weight * jitter);
    if (total < 60_000) continue;

    // **1〜4回に分けて使う。** 現実の使い方は「開いて閉じて、また後で開く」
    // の繰り返しなので、1アプリ1セッションでは `segmentCount` が
    // 常に 1 になり、日別ページの「n×」が意味を持たない。
    const sessions = 1 + Math.floor(rand() * 3.99);
    const per = Math.floor(total / sessions);

    for (let i = 0; i < sessions; i++) {
      // 最後のセッションで端数を吸収し、合計を total に一致させる
      const use = i === sessions - 1 ? total - per * (sessions - 1) : per;
      if (use < 60_000) continue;
      out.push(ev(date, app.pkg, app.label, cursor, cursor + use));
      // 使った後の休憩（次に開くまで）
      cursor += use + Math.floor(rand() * 25 * 60_000) + 60_000;
    }
  }
  return out;
}

async function main() {
  const db: Database = createTestDb(schema);
  await seedDevice(db, PHONE, await hashToken("preview-token"), "Pixel 7");
  await seedDevice(db, LAPTOP, await hashToken("preview-token-2"), "MacBook Pro");

  const rand = makeRandom(42);
  const dayBase = Date.UTC(2026, 0, 1, 8, 0, 0); // 表示には影響しない固定値

  for (let daysAgo = 6; daysAgo >= 0; daysAgo--) {
    const idx = 6 - daysAgo;
    const date = isoDate(daysAgo);

    // **日ごとに時刻をずらす。** 同じ startTime を別の日に使うと、
    // サーバーの一意制約 (device_id, package_name, start_time) が
    // 正しく重複として弾いてしまい、プレビューの数値がおかしくなる。
    // 実際の epoch millis は日をまたげば必ず異なる値になる。
    const dayShift = daysAgo * 86_400_000;

    // スマホ: 朝から
    await ingestBatch(
      db,
      PHONE,
      `preview-${date}-phone`,
      buildDay(date, PHONE_APPS, PHONE_MINUTES[idx], rand, dayBase + dayShift),
      Date.now()
    );

    // PC: 昼過ぎから（時刻をずらして重ならないようにする）
    await ingestBatch(
      db,
      LAPTOP,
      `preview-${date}-pc`,
      buildDay(date, PC_APPS, PC_MINUTES[idx], rand, dayBase + dayShift + 5 * 3_600_000),
      Date.now()
    );
  }

  const today = isoDate(0);
  const from = isoDate(6);
  const summary = await querySummary(db, from, today);

  const outDir = join(here, "preview");
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, "dashboard.html"), renderDashboard(summary, today), "utf8");
  writeFileSync(join(outDir, "login.html"), renderLogin(), "utf8");

  // 日別ページ（メイン画面の日付をタップして開く先）。
  // **日 × アプリの交点**がここで初めて見える。
  const day = await queryDay(db, today);
  writeFileSync(join(outDir, "day.html"), renderDay(day), "utf8");

  console.log("生成しました: preview/dashboard.html / day.html / login.html");
  console.log(`  from=${from} to=${today}`);
  console.log(
    `  日数=${summary.dailyTotals.length} アプリ=${summary.appTotals.length} 端末=${summary.byDevice.length}`
  );

  // **DB には「日 × アプリ」の粒度で積んである**ことの確認。
  // 上の画面はそれを集約して表示しているだけ（片方の軸が落ちる）。
  await printDailyAppMatrix(db, from, today);
}

/** 日 × アプリのマトリクスを表示する（保存粒度の確認用）。 */
async function printDailyAppMatrix(db: Database, from: string, to: string) {
  const rows = await queryDailySummary(db, from, to);

  // 端末は合算する（画面と同じ見方に合わせる）
  const byDay = new Map<string, Map<string, number>>();
  for (const r of rows) {
    const day = byDay.get(r.localDate) ?? new Map<string, number>();
    day.set(r.appLabel, (day.get(r.appLabel) ?? 0) + r.totalMillis);
    byDay.set(r.localDate, day);
  }

  const labels = [...new Set(rows.map((r) => r.appLabel))];
  const width = Math.max(...labels.map((l) => l.length), 4);

  console.log(`\n=== DB の中身: 日 × アプリ（${from} 〜 ${to}） ===`);
  console.log(`${"date".padEnd(12)}${labels.map((l) => l.padStart(width + 2)).join("")}`);
  for (const [date, apps] of [...byDay.entries()].sort()) {
    const cells = labels.map((l) => {
      const v = apps.get(l) ?? 0;
      return (v > 0 ? `${Math.round(v / 60000)}m` : "·").padStart(width + 2);
    });
    console.log(`${date.padEnd(12)}${cells.join("")}`);
  }
  console.log(`\n行数=${rows.length}（1行 = 1端末 × 1日 × 1アプリ）`);
}

await main();
