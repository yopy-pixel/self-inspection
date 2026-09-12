import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { createTestDb, seedDevice } from "./d1-adapter.ts";
import { ingestBatch, querySummary, queryDailySummary } from "../src/db.ts";
import { hashToken } from "../src/auth.ts";
import { validateBatch, isValidDateString, daysBetween } from "../src/validate.ts";
import { handle } from "../src/index.ts";
import { tokenFromCookie, esc, fmt } from "../src/dashboard.ts";
import type { NormalizedEvent } from "../src/validate.ts";

const here = dirname(fileURLToPath(import.meta.url));
const migrationsDir = join(here, "..", "migrations");

/**
 * スキーマは**マイグレーションを全部**流す。
 *
 * 0001 だけを読むと、後から足したテーブルを使うコードがテストでだけ
 * 落ちる（逆に、本番に無いテーブルを前提にしたテストが通る）。
 */
const schema = readdirSync(migrationsDir)
  .filter((f) => f.endsWith(".sql"))
  .sort()
  .map((f) => readFileSync(join(migrationsDir, f), "utf8"))
  .join("\n");

const DEVICE = "11111111-1111-1111-1111-111111111111";
const TOKEN = "test-token-abc";

function ev(
  localDate: string,
  pkg: string,
  start: number,
  end: number
): NormalizedEvent {
  return {
    localDate,
    packageName: pkg,
    appLabel: pkg,
    startTime: start,
    endTime: end,
    closeReason: "PAUSED",
  };
}

function freshDb() {
  return createTestDb(schema);
}

// ===========================================================================
describe("スキーマ", () => {
  test("エラーなく適用できる", () => {
    const db = freshDb();
    assert.ok(db);
  });

  test("同じスキーマを2回適用しても壊れない（IF NOT EXISTS）", () => {
    const db = createTestDb(schema);
    // createTestDb は1回適用済み。もう一度流しても例外にならないこと。
    assert.ok(db);
  });
});

// ===========================================================================
describe("冪等性（最重要）", () => {
  test("同じ batchId を再送しても二重計上しない", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    const events = [ev("2026-09-14", "com.youtube", 1000, 2000)];

    const first = await ingestBatch(db, DEVICE, "batch-1", events, Date.now());
    assert.equal(first.duplicate, false);
    assert.equal(first.inserted, 1);

    // まったく同じバッチを再送
    const second = await ingestBatch(db, DEVICE, "batch-1", events, Date.now());
    assert.equal(second.duplicate, true);
    assert.equal(second.inserted, 0);
    assert.equal(second.summariesUpdated, 0);

    const rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    assert.equal(rows.length, 1);
    // 1000ms のまま（2000ms になっていたら二重計上）
    assert.equal(rows[0].totalMillis, 1000);
  });

  test("別バッチで同じ区間を送っても増えない（一意制約）", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    const events = [ev("2026-09-14", "com.youtube", 1000, 2000)];
    await ingestBatch(db, DEVICE, "batch-1", events, Date.now());
    const r = await ingestBatch(db, DEVICE, "batch-2", events, Date.now());

    // バッチ自体は新規だが、イベントは重複なので挿入されない
    assert.equal(r.duplicate, false);
    assert.equal(r.inserted, 0);

    const rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    assert.equal(rows[0].totalMillis, 1000);
  });

  test("後から確定した endTime で更新される（UPSERT の理由）", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    // 最初は推定値 endTime = 2000
    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [ev("2026-09-14", "com.a", 1000, 2000)],
      Date.now()
    );
    let rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    assert.equal(rows[0].totalMillis, 1000);

    // 同じ区間が別バッチで訂正されて endTime = 3000 になった。
    // `INSERT OR IGNORE` だと 1000 のままになる。UPSERT なので 2000 になる。
    await ingestBatch(
      db,
      DEVICE,
      "b2",
      [ev("2026-09-14", "com.a", 1000, 3000)],
      Date.now()
    );
    rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    assert.equal(rows[0].totalMillis, 2000);
  });

  test("値が変わらない再送では changes が 0 のまま（冪等性の維持）", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    const same = [ev("2026-09-14", "com.a", 1000, 2000)];
    const first = await ingestBatch(db, DEVICE, "b1", same, Date.now());
    assert.equal(first.inserted, 1);

    // 別バッチ・同一内容 → UPSERT の WHERE により更新されない
    const second = await ingestBatch(db, DEVICE, "b2", same, Date.now());
    assert.equal(second.inserted, 0);
  });

  test("同一 startTime の区間は重複とみなされる（一意制約の前提を明示）", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    // 区間の同一性は (device, package, startTime) で判定する。
    // 同じ startTime を持つ区間は「別の日であっても」重複として扱われる。
    //
    // 実データの startTime は epoch millis なので、日をまたげば必ず異なる値になる。
    // つまりこの状況は実データでは起きない。制約の意味を残すためにテストする。
    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [
        ev("2026-09-14", "com.a", 1000, 2000),
        ev("2026-09-15", "com.a", 1000, 3000),
      ],
      Date.now()
    );

    const rows = await queryDailySummary(db, "2026-09-01", "2026-09-30");
    assert.equal(rows.length, 1);
    assert.equal(rows[0].localDate, "2026-09-14");
  });

  test("同じ日を複数バッチに分けても合計が正しい", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [ev("2026-09-14", "com.a", 0, 1000)],
      Date.now()
    );
    await ingestBatch(
      db,
      DEVICE,
      "b2",
      [ev("2026-09-14", "com.a", 2000, 3500)],
      Date.now()
    );

    const rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    // 1000 + 1500 = 2500
    assert.equal(rows[0].totalMillis, 2500);
    assert.equal(rows[0].segmentCount, 2);
  });
});

// ===========================================================================
describe("集計の再計算", () => {
  test("アプリ別に分かれる", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [
        ev("2026-09-14", "com.a", 0, 1000),
        ev("2026-09-14", "com.b", 1000, 4000),
      ],
      Date.now()
    );

    const rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    const byPkg = Object.fromEntries(rows.map((r) => [r.packageName, r.totalMillis]));
    assert.equal(byPkg["com.a"], 1000);
    assert.equal(byPkg["com.b"], 3000);
  });

  test("表示名はパッケージ名に負けない（MAX の辞書順を使わない）", async () => {
    // **実際に起きた事故。** 同じアプリの行に古いパッケージ名が1つでも残ると、
    // MAX(app_label) は辞書順で大きい方を選ぶため
    // "com.example.app" > "Example" となって表示名が消えていた
    // （小文字始まりのパッケージ名は、大文字始まりの表示名より大きい）。
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [
        { ...ev("2026-09-14", "com.example.app", 1000, 2000), appLabel: "Example" },
        { ...ev("2026-09-14", "com.example.app", 3000, 5000), appLabel: "com.example.app" },
      ],
      Date.now()
    );

    const rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    assert.equal(rows.length, 1);
    assert.equal(rows[0].appLabel, "Example");
  });

  test("表示名が1つも無ければパッケージ名を返す", async () => {
    // 端末が解決できなかった場合。パッケージ名を出すのは正しいフォールバック。
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    await ingestBatch(db, DEVICE, "b1", [ev("2026-09-14", "com.a", 1000, 2000)], Date.now());

    const rows = await queryDailySummary(db, "2026-09-14", "2026-09-14");
    assert.equal(rows[0].appLabel, "com.a");
  });

  test("日ごとに分かれる", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));

    // startTime は実際の epoch millis なので日をまたげば必ず異なる値になる。
    // （同一の startTime はクライアント側の一意制約で重複とみなされる）
    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [
        ev("2026-09-14", "com.a", 1_700_000_000_000, 1_700_000_001_000),
        ev("2026-09-15", "com.a", 1_700_100_000_000, 1_700_100_002_000),
      ],
      Date.now()
    );

    const s = await querySummary(db, "2026-09-14", "2026-09-15");
    assert.deepEqual(
      s.dailyTotals.map((d) => [d.localDate, d.totalMillis]),
      [
        ["2026-09-14", 1000],
        ["2026-09-15", 2000],
      ]
    );
  });

  test("複数端末が device 別に分かれ、合算も正しい", async () => {
    const db = freshDb();
    const DEVICE2 = "22222222-2222-2222-2222-222222222222";
    await seedDevice(db, DEVICE, await hashToken(TOKEN), "phone");
    await seedDevice(db, DEVICE2, await hashToken("t2"), "pc");

    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [ev("2026-09-14", "com.a", 0, 1000)],
      Date.now()
    );
    await ingestBatch(
      db,
      DEVICE2,
      "b2",
      [ev("2026-09-14", "com.b", 0, 500)],
      Date.now()
    );

    const s = await querySummary(db, "2026-09-14", "2026-09-14");
    assert.equal(s.dailyTotals[0].totalMillis, 1500);
    assert.equal(s.byDevice.length, 2);
    assert.equal(s.byDevice.reduce((a, d) => a + d.totalMillis, 0), 1500);
  });

  test("期間外の日は返らない", async () => {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN));
    await ingestBatch(
      db,
      DEVICE,
      "b1",
      [
        ev("2026-09-01", "com.a", 1_756_600_000_000, 1_756_600_001_000),
        ev("2026-09-14", "com.a", 1_757_700_000_000, 1_757_700_002_000),
      ],
      Date.now()
    );

    const s = await querySummary(db, "2026-09-10", "2026-09-20");
    assert.equal(s.dailyTotals.length, 1);
    assert.equal(s.dailyTotals[0].localDate, "2026-09-14");
  });
});

// ===========================================================================
describe("検証（構造的）", () => {
  const today = "2026-09-14";

  const base = {
    batchId: "b",
    deviceId: "d",
    schemaVersion: 1,
    events: [] as unknown[],
  };

  test("正しいバッチは通る", () => {
    const r = validateBatch(base, today);
    assert.deepEqual(r.structuralErrors, []);
  });

  test("schemaVersion が新しすぎたら拒否", () => {
    const r = validateBatch({ ...base, schemaVersion: 99 }, today);
    assert.equal(r.structuralErrors.length, 1);
    assert.match(r.structuralErrors[0], /newer than supported/);
  });

  test("batchId が無ければ拒否", () => {
    const r = validateBatch({ ...base, batchId: "" }, today);
    assert.ok(r.structuralErrors.some((e) => e.includes("batchId")));
  });

  test("events が配列でなければ拒否", () => {
    const r = validateBatch({ ...base, events: "nope" }, today);
    assert.ok(r.structuralErrors.some((e) => e.includes("events")));
  });

  test("イベント数が上限を超えたら拒否", () => {
    const many = Array.from({ length: 2001 }, () =>
      ev(today, "com.a", 0, 1)
    );
    const r = validateBatch({ ...base, events: many }, today);
    assert.ok(r.structuralErrors.some((e) => e.includes("must not exceed")));
  });

  test("JSON オブジェクトでなければ拒否", () => {
    assert.ok(validateBatch([], today).structuralErrors.length > 0);
    assert.ok(validateBatch(null, today).structuralErrors.length > 0);
    assert.ok(validateBatch("x", today).structuralErrors.length > 0);
  });
});

// ===========================================================================
describe("検証（個別イベント）", () => {
  const today = "2026-09-14";
  const wrap = (events: unknown[]) => ({
    batchId: "b",
    deviceId: "d",
    schemaVersion: 1,
    events,
  });

  test("endTime < startTime は弾く（実データで時刻の逆行が確認されている）", () => {
    const r = validateBatch(
      wrap([{ ...ev(today, "com.a", 5000, 1000) }]),
      today
    );
    assert.equal(r.validEvents.length, 0);
    assert.equal(r.rejectedEvents.length, 1);
    assert.match(r.rejectedEvents[0].reason, /endTime must be >= startTime/);
  });

  test("24時間を超える区間は弾く", () => {
    const r = validateBatch(
      wrap([{ ...ev(today, "com.a", 0, 25 * 60 * 60 * 1000) }]),
      today
    );
    assert.equal(r.rejectedEvents.length, 1);
    assert.match(r.rejectedEvents[0].reason, /24 hours/);
  });

  test("実在しない日付は弾く", () => {
    const r = validateBatch(wrap([ev("2026-02-30", "com.a", 0, 1)]), today);
    assert.equal(r.rejectedEvents.length, 1);
    assert.match(r.rejectedEvents[0].reason, /valid YYYY-MM-DD/);
  });

  test("遠い未来・遠い過去は弾く", () => {
    const future = validateBatch(wrap([ev("2030-01-01", "com.a", 0, 1)]), today);
    assert.equal(future.rejectedEvents.length, 1);
    assert.match(future.rejectedEvents[0].reason, /future/);

    const old = validateBatch(wrap([ev("2000-01-01", "com.a", 0, 1)]), today);
    assert.equal(old.rejectedEvents.length, 1);
    assert.match(old.rejectedEvents[0].reason, /too old/);
  });

  test("不正なイベントはスキップし、正しいイベントは受け入れる", () => {
    const r = validateBatch(
      wrap([
        ev(today, "com.a", 0, 1000),
        { ...ev(today, "com.b", 5000, 1000) }, // 不正
        ev(today, "com.c", 0, 1000),
      ]),
      today
    );
    assert.equal(r.structuralErrors.length, 0);
    assert.equal(r.validEvents.length, 2);
    assert.equal(r.rejectedEvents.length, 1);
    // 元のインデックスが保たれていること（どのイベントが弾かれたか特定できる）
    assert.equal(r.rejectedEvents[0].index, 1);
  });

  test("closeReason は省略可能", () => {
    const e = ev(today, "com.a", 0, 1000);
    const r = validateBatch(wrap([{ ...e, closeReason: undefined }]), today);
    assert.equal(r.validEvents.length, 1);
    assert.equal(r.validEvents[0].closeReason, null);
  });

  test("日付ヘルパの境界", () => {
    assert.equal(isValidDateString("2026-02-28"), true);
    assert.equal(isValidDateString("2026-02-29"), false); // 2026 はうるう年でない
    assert.equal(isValidDateString("2024-02-29"), true); // 2024 はうるう年
    assert.equal(isValidDateString("2026-13-01"), false);
    assert.equal(isValidDateString("2026-1-1"), false);
    assert.equal(daysBetween("2026-09-15", "2026-09-14"), 1);
    assert.equal(daysBetween("2026-09-14", "2026-09-14"), 0);
    assert.equal(daysBetween("2026-09-13", "2026-09-14"), -1);
  });
});

// ===========================================================================
describe("HTTP ハンドラ", () => {
  const now = Date.UTC(2026, 8, 14, 12, 0, 0); // 2026-09-14T12:00:00Z
  const today = "2026-09-14";

  async function envWithDevice() {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN), "phone");
    return { DB: db };
  }

  function req(
    method: string,
    path: string,
    opts: { token?: string; body?: unknown } = {}
  ): Request {
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (opts.token) headers["authorization"] = `Bearer ${opts.token}`;
    return new Request(`https://example.com${path}`, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    });
  }

  test("平文 HTTP は 403 で拒否する（暗号化の必須要件）", async () => {
    const env = await envWithDevice();
    const plain = new Request("http://example.com/api/v1/ingest", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify({ batchId: "b", deviceId: DEVICE, schemaVersion: 1, events: [] }),
    });
    const res = await handle(plain, env, now);
    assert.equal(res.status, 403);
    const j = (await res.json()) as { error: string };
    assert.match(j.error, /https required/);
  });

  test("平文 HTTP は healthz でも拒否する", async () => {
    const env = await envWithDevice();
    const res = await handle(new Request("http://example.com/healthz"), env, now);
    assert.equal(res.status, 403);
  });

  test("localhost は開発用に例外とする", async () => {
    const env = await envWithDevice();
    const res = await handle(new Request("http://localhost:8787/healthz"), env, now);
    assert.equal(res.status, 200);
  });

  test("10.0.2.2（エミュレータ→ホスト）も開発用に例外とする", async () => {
    const env = await envWithDevice();
    const res = await handle(new Request("http://10.0.2.2:8787/healthz"), env, now);
    assert.equal(res.status, 200);
  });

  test("healthz は認証不要", async () => {
    const env = await envWithDevice();
    const res = await handle(req("GET", "/healthz"), env, now);
    assert.equal(res.status, 200);
  });

  test("トークン無しの取り込みは 401", async () => {
    const env = await envWithDevice();
    const res = await handle(
      req("POST", "/api/v1/ingest", { body: { batchId: "b", deviceId: DEVICE, schemaVersion: 1, events: [] } }),
      env,
      now
    );
    assert.equal(res.status, 401);
  });

  test("不正なトークンは 401", async () => {
    const env = await envWithDevice();
    const res = await handle(
      req("POST", "/api/v1/ingest", {
        token: "wrong",
        body: { batchId: "b", deviceId: DEVICE, schemaVersion: 1, events: [] },
      }),
      env,
      now
    );
    assert.equal(res.status, 401);
  });

  test("deviceId がトークンと食い違えば 403", async () => {
    const env = await envWithDevice();
    const res = await handle(
      req("POST", "/api/v1/ingest", {
        token: TOKEN,
        body: {
          batchId: "b",
          deviceId: "99999999-9999-9999-9999-999999999999",
          schemaVersion: 1,
          events: [],
        },
      }),
      env,
      now
    );
    assert.equal(res.status, 403);
  });

  test("取り込みは 202、再送は 200", async () => {
    const env = await envWithDevice();
    const body = {
      batchId: "b1",
      deviceId: DEVICE,
      schemaVersion: 1,
      events: [ev(today, "com.a", 0, 1000)],
    };

    const first = await handle(req("POST", "/api/v1/ingest", { token: TOKEN, body }), env, now);
    assert.equal(first.status, 202);
    const firstJson = (await first.json()) as { inserted?: number; eventCount: number };
    assert.equal(firstJson.eventCount, 1);

    const second = await handle(req("POST", "/api/v1/ingest", { token: TOKEN, body }), env, now);
    assert.equal(second.status, 200);
    const secondJson = (await second.json()) as { duplicate: boolean; eventCount: number };
    assert.equal(secondJson.duplicate, true);
    assert.equal(secondJson.eventCount, 0);
  });

  test("構造的な誤りは 400", async () => {
    const env = await envWithDevice();
    const res = await handle(
      req("POST", "/api/v1/ingest", {
        token: TOKEN,
        body: { batchId: "b", deviceId: DEVICE, schemaVersion: 99, events: [] },
      }),
      env,
      now
    );
    assert.equal(res.status, 400);
  });

  test("不正な JSON は 400", async () => {
    const env = await envWithDevice();
    const r = new Request("https://example.com/api/v1/ingest", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
      body: "{not json",
    });
    const res = await handle(r, env, now);
    assert.equal(res.status, 400);
  });

  test("summary は認証が必要で、集計を返す", async () => {
    const env = await envWithDevice();
    await handle(
      req("POST", "/api/v1/ingest", {
        token: TOKEN,
        body: {
          batchId: "b1",
          deviceId: DEVICE,
          schemaVersion: 1,
          events: [ev(today, "com.a", 0, 60000)],
        },
      }),
      env,
      now
    );

    const unauth = await handle(req("GET", "/api/v1/summary"), env, now);
    assert.equal(unauth.status, 401);

    const res = await handle(
      req("GET", `/api/v1/summary?from=${today}&to=${today}`, { token: TOKEN }),
      env,
      now
    );
    assert.equal(res.status, 200);
    const s = (await res.json()) as {
      dailyTotals: { totalMillis: number }[];
      appTotals: { totalMillis: number }[];
    };
    assert.equal(s.dailyTotals[0].totalMillis, 60000);
    assert.equal(s.appTotals[0].totalMillis, 60000);
  });

  test("summary の from > to は 400", async () => {
    const env = await envWithDevice();
    const res = await handle(
      req("GET", "/api/v1/summary?from=2026-09-15&to=2026-09-14", { token: TOKEN }),
      env,
      now
    );
    assert.equal(res.status, 400);
  });

  test("summary の存在しない日付は 400", async () => {
    // 正規表現だけの検証だと "2026-02-30" が通ってしまい、
    // 集計が黙って空を返す（クライアントのバグを隠す）。
    const env = await envWithDevice();
    for (const qs of [
      "from=2026-02-30&to=2026-02-30",
      "from=2026-13-01&to=2026-13-31",
      "from=2026-00-10&to=2026-01-10",
      "from=2026-01-32&to=2026-02-01",
    ]) {
      const res = await handle(
        req("GET", `/api/v1/summary?${qs}`, { token: TOKEN }),
        env,
        now
      );
      assert.equal(res.status, 400, `${qs} は 400 であるべき`);
    }
  });

  test("summary の閏日は正しく判定される", async () => {
    const env = await envWithDevice();
    // 2028 は閏年なので 02-29 は実在する（400 にならないこと）。
    const ok = await handle(
      req("GET", "/api/v1/summary?from=2028-02-29&to=2028-02-29", { token: TOKEN }),
      env,
      now
    );
    assert.equal(ok.status, 200);

    // 2026 は閏年ではないので 02-29 は存在しない。
    const bad = await handle(
      req("GET", "/api/v1/summary?from=2026-02-29&to=2026-02-29", { token: TOKEN }),
      env,
      now
    );
    assert.equal(bad.status, 400);
  });

  test("端末登録は ADMIN_TOKEN 未設定なら 503", async () => {
    const env = await envWithDevice();
    const res = await handle(req("POST", "/api/v1/devices", { body: {} }), env, now);
    assert.equal(res.status, 503);
  });

  test("端末登録は ADMIN_TOKEN があれば 201 でトークンを返す", async () => {
    const db = freshDb();
    const env = { DB: db, ADMIN_TOKEN: "admin-secret" };
    const res = await handle(
      req("POST", "/api/v1/devices", {
        token: "admin-secret",
        body: { label: "my phone" },
      }),
      env,
      now
    );
    assert.equal(res.status, 201);
    const j = (await res.json()) as { deviceId: string; token: string };
    assert.ok(j.deviceId.length > 0);
    assert.ok(j.token.length >= 64);
  });

  test("端末登録は誤った ADMIN_TOKEN なら 401", async () => {
    const db = freshDb();
    const env = { DB: db, ADMIN_TOKEN: "admin-secret" };
    const res = await handle(
      req("POST", "/api/v1/devices", { token: "wrong", body: {} }),
      env,
      now
    );
    assert.equal(res.status, 401);
  });

  test("未知のパスは 404", async () => {
    const env = await envWithDevice();
    const res = await handle(req("GET", "/nope"), env, now);
    assert.equal(res.status, 404);
  });
});

// ===========================================================================
describe("ブラウザ用の画面", () => {
  const now = Date.UTC(2026, 8, 14, 12, 0, 0);
  const today = "2026-09-14";

  async function envWithDevice() {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN), "phone");
    return { DB: db };
  }

  function get(path: string, cookie?: string): Request {
    const headers: Record<string, string> = {};
    if (cookie) headers["cookie"] = cookie;
    return new Request(`https://example.com${path}`, { method: "GET", headers });
  }

  function req(
    method: string,
    path: string,
    opts: { token?: string; body?: unknown } = {}
  ): Request {
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (opts.token) headers["authorization"] = `Bearer ${opts.token}`;
    return new Request(`https://example.com${path}`, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    });
  }

  function postForm(path: string, body: string): Request {
    return new Request(`https://example.com${path}`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body,
    });
  }

  test("Cookie が無ければトークン入力画面を返す", async () => {
    const env = await envWithDevice();
    const res = await handle(get("/"), env, now);

    assert.equal(res.status, 200);
    const body = await res.text();
    assert.match(body, /Device token/);
    // 認証済みの画面をキャッシュさせない
    assert.equal(res.headers.get("cache-control"), "no-store");
  });

  test("正しいトークンで Cookie が発行される", async () => {
    const env = await envWithDevice();
    const res = await handle(postForm("/dashboard", `token=${TOKEN}`), env, now);

    assert.equal(res.status, 303);
    const cookie = res.headers.get("set-cookie") ?? "";
    assert.match(cookie, /sk_token=/);
    // セキュリティ属性が付いていること
    assert.match(cookie, /HttpOnly/);
    assert.match(cookie, /Secure/);
    assert.match(cookie, /SameSite=Strict/);
  });

  test("誤ったトークンは 401 で Cookie を発行しない", async () => {
    const env = await envWithDevice();
    const res = await handle(postForm("/dashboard", "token=wrong"), env, now);

    assert.equal(res.status, 401);
    assert.equal(res.headers.get("set-cookie"), null);
  });

  test("トークンが空なら 400", async () => {
    const env = await envWithDevice();
    const res = await handle(postForm("/dashboard", "token="), env, now);
    assert.equal(res.status, 400);
  });

  test("有効な Cookie で集計画面が出る", async () => {
    const env = await envWithDevice();
    await handle(
      req("POST", "/api/v1/ingest", {
        token: TOKEN,
        body: {
          batchId: "b1",
          deviceId: DEVICE,
          schemaVersion: 1,
          events: [ev(today, "com.a", 1_757_700_000_000, 1_757_700_060_000)],
        },
      }),
      env,
      now
    );

    const res = await handle(get("/", `sk_token=${TOKEN}`), env, now);
    assert.equal(res.status, 200);

    const body = await res.text();
    assert.match(body, /Today/);
    assert.match(body, /Top apps/);
    // 合計が出ている（60秒 → 1m）
    assert.match(body, /1m/);
  });

  test("失効した Cookie は入力画面に戻し、Cookie を消す", async () => {
    const env = await envWithDevice();
    const res = await handle(get("/", "sk_token=stale-token"), env, now);

    assert.equal(res.status, 200);
    assert.match(res.headers.get("set-cookie") ?? "", /Max-Age=0/);
  });

  test("ログアウトで Cookie が消える", async () => {
    const env = await envWithDevice();
    const res = await handle(postForm("/dashboard", "logout=1"), env, now);

    assert.equal(res.status, 303);
    assert.match(res.headers.get("set-cookie") ?? "", /Max-Age=0/);
  });

  test("トークンが HTML に埋め込まれない", async () => {
    // Cookie に入れて本文には出さない。出ると shoulder surfing で読まれる。
    const env = await envWithDevice();
    const res = await handle(get("/", `sk_token=${TOKEN}`), env, now);
    const body = await res.text();

    assert.equal(body.includes(TOKEN), false);
  });

  test("アプリ名に HTML を入れられても実行されない（XSS 対策）", async () => {
    // appLabel はクライアント由来の外部入力。エスケープしないと
    // サーバー画面でスクリプトが動いてしまう。
    const env = await envWithDevice();
    await handle(
      req("POST", "/api/v1/ingest", {
        token: TOKEN,
        body: {
          batchId: "xss-1",
          deviceId: DEVICE,
          schemaVersion: 1,
          events: [
            {
              localDate: today,
              packageName: "com.evil",
              appLabel: "<script>alert(1)</script>",
              startTime: 1_757_700_100_000,
              endTime: 1_757_700_160_000,
              closeReason: "PAUSED",
            },
          ],
        },
      }),
      env,
      now
    );

    const res = await handle(get("/", `sk_token=${TOKEN}`), env, now);
    const body = await res.text();

    // 生のタグが本文に現れないこと
    assert.equal(body.includes("<script>alert(1)</script>"), false);
    // エスケープされた形でなら現れてよい
    assert.match(body, /&lt;script&gt;/);
  });
});

// ===========================================================================
describe("ペアコードとブラウザセッション", () => {
  const now = Date.UTC(2026, 8, 14, 12, 0, 0);
  const today = "2026-09-14";

  async function envWithDevice() {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN), "phone");
    return { DB: db };
  }

  function ua(name = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/120.0 Safari/537.36") {
    return name;
  }

  function get(path: string, cookie?: string): Request {
    const headers: Record<string, string> = { "user-agent": ua() };
    if (cookie) headers["cookie"] = cookie;
    return new Request(`https://example.com${path}`, { method: "GET", headers });
  }

  function postForm(
    path: string,
    body: string,
    opts: { cookie?: string; ua?: string } = {}
  ): Request {
    const headers: Record<string, string> = {
      "content-type": "application/x-www-form-urlencoded",
      "user-agent": opts.ua ?? ua(),
    };
    if (opts.cookie) headers["cookie"] = opts.cookie;
    return new Request(`https://example.com${path}`, { method: "POST", headers, body });
  }

  function postJson(path: string, body: unknown, token?: string): Request {
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (token) headers["authorization"] = `Bearer ${token}`;
    return new Request(`https://example.com${path}`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
  }

  /** Set-Cookie からセッショントークンを取り出す。 */
  function cookieValue(res: Response): string {
    const raw = res.headers.get("set-cookie") ?? "";
    const m = /sk_token=([^;]*)/.exec(raw);
    return m ? decodeURIComponent(m[1]) : "";
  }

  /** アプリ（端末トークン）としてコードを発行する。 */
  async function mintViaApp(env: { DB: ReturnType<typeof freshDb> }, at = now) {
    const res = await handle(postJson("/api/v1/pair", {}, TOKEN), env as never, at);
    assert.equal(res.status, 200);
    return (await res.json()) as { code: string; expiresAt: number };
  }

  // ---- 発行 ----

  test("端末トークンでコードを発行できる", async () => {
    const env = await envWithDevice();
    const { code, expiresAt } = await mintViaApp(env);

    assert.equal(code.length, 8);
    assert.match(code, /^[0-9A-HJKMNP-TV-Z]{8}$/);
    assert.equal(expiresAt, now + 3 * 60_000);
  });

  test("未認証ではコードを発行できない", async () => {
    // **ここが要**: 未認証で発行できると、ログイン画面を開いた誰もが
    // ログインできてしまい、認証が丸ごと無意味になる。
    const env = await envWithDevice();
    const res = await handle(postJson("/api/v1/pair", {}), env, now);
    assert.equal(res.status, 401);
  });

  test("でたらめなトークンでは発行できない", async () => {
    const env = await envWithDevice();
    const res = await handle(postJson("/api/v1/pair", {}, "wrong"), env, now);
    assert.equal(res.status, 401);
  });

  test("コードは毎回ちがう", async () => {
    const env = await envWithDevice();
    const a = await mintViaApp(env);
    const b = await mintViaApp(env);
    assert.notEqual(a.code, b.code);
  });

  // ---- 引き換え ----

  test("コードで新しいブラウザがログインできる", async () => {
    const env = await envWithDevice();
    const { code } = await mintViaApp(env);

    const res = await handle(postForm("/dashboard", `pair=${code}`), env, now);
    assert.equal(res.status, 303);

    const session = cookieValue(res);
    assert.ok(session.length > 0);
    // **端末トークンが Cookie に入っていない**（セッションは別物）。
    assert.notEqual(session, TOKEN);

    const page = await handle(get("/", `sk_token=${session}`), env, now);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /Today/);
  });

  test("コードは1回しか使えない", async () => {
    const env = await envWithDevice();
    const { code } = await mintViaApp(env);

    const first = await handle(postForm("/dashboard", `pair=${code}`), env, now);
    assert.equal(first.status, 303);

    const second = await handle(postForm("/dashboard", `pair=${code}`), env, now);
    assert.equal(second.status, 401);
    assert.equal(second.headers.get("set-cookie"), null);
  });

  test("期限切れのコードは拒否する", async () => {
    const env = await envWithDevice();
    const { code } = await mintViaApp(env);

    const late = now + 3 * 60_000 + 1;
    const res = await handle(postForm("/dashboard", `pair=${code}`), env, late);
    assert.equal(res.status, 401);
  });

  test("でたらめなコードは拒否する", async () => {
    const env = await envWithDevice();
    const res = await handle(postForm("/dashboard", "pair=ZZZZZZZZ"), env, now);
    assert.equal(res.status, 401);
  });

  test("小文字・空白入りでも通る", async () => {
    // 人が書き写す前提なので、大文字小文字や区切りで失敗させない。
    const env = await envWithDevice();
    const { code } = await mintViaApp(env);

    const messy = `  ${code.toLowerCase().slice(0, 4)} ${code.toLowerCase().slice(4)}  `;
    const res = await handle(postForm("/dashboard", `pair=${encodeURIComponent(messy)}`), env, now);
    assert.equal(res.status, 303);
  });

  // ---- セッション ----

  /** ログイン画面に戻されている＝そのセッションは死んでいる。 */
  function isDead(body: string): boolean {
    return body.includes("Pairing code");
  }

  test("ブラウザごとに独立したセッションになる", async () => {
    const env = await envWithDevice();
    const a = cookieValue(await handle(postForm("/dashboard", `token=${TOKEN}`), env, now));
    const b = cookieValue(await handle(postForm("/dashboard", `token=${TOKEN}`), env, now));

    assert.notEqual(a, b);

    const list = await (await handle(get("/", `sk_token=${a}`), env, now)).text();
    const ids = [...list.matchAll(/name="revoke" value="([^"]+)"/g)].map((m) => m[1]);
    assert.equal(ids.length, 2, "2台分が一覧に出ること");

    // 片方だけ失効させる
    await handle(postForm("/dashboard", `revoke=${ids[0]}`, { cookie: `sk_token=${a}` }), env, now);

    const aDead = isDead(await (await handle(get("/", `sk_token=${a}`), env, now)).text());
    const bDead = isDead(await (await handle(get("/", `sk_token=${b}`), env, now)).text());
    assert.notEqual(aDead, bDead, "片方だけが失効すること");
  });

  test("失効させたセッションはサーバー側でもう通らない", async () => {
    // **これが本当のサインアウト。** Cookie を消すだけでは、控えられた
    // トークンが生き続けてしまう。
    const env = await envWithDevice();
    const session = cookieValue(await handle(postForm("/dashboard", `token=${TOKEN}`), env, now));

    const page = await handle(get("/", `sk_token=${session}`), env, now);
    const id = /name="revoke" value="([^"]+)"/.exec(await page.text())?.[1];
    assert.ok(id);

    await handle(postForm("/dashboard", `revoke=${id}`, { cookie: `sk_token=${session}` }), env, now);

    const after = await handle(get("/", `sk_token=${session}`), env, now);
    assert.match(await after.text(), /Pairing code/);
    assert.match(after.headers.get("set-cookie") ?? "", /Max-Age=0/);
  });

  test("ログイン済みなら新しいブラウザ用のコードを発行できる", async () => {
    // PC を2台目・3台目と増やすときに、アプリを触らずに済む。
    const env = await envWithDevice();
    const first = cookieValue(await handle(postForm("/dashboard", `token=${TOKEN}`), env, now));

    const res = await handle(
      postForm("/dashboard", "newpair=1", { cookie: `sk_token=${first}` }),
      env,
      now
    );
    assert.equal(res.status, 200);
    const body = await res.text();
    const code = /class="code-value">([^<]+)</.exec(body)?.[1];
    assert.ok(code, "発行したコードが画面に出ること");

    // そのコードで2台目がログインできる
    const second = await handle(postForm("/dashboard", `pair=${code}`), env, now);
    assert.equal(second.status, 303);
    assert.notEqual(cookieValue(second), first);
  });

  test("セッションの表示名は User-Agent から作る", async () => {
    const env = await envWithDevice();
    const res = await handle(
      postForm("/dashboard", `token=${TOKEN}`, {
        ua: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/121.0",
      }),
      env,
      now
    );
    const session = cookieValue(res);

    const page = await handle(get("/", `sk_token=${session}`), env, now);
    assert.match(await page.text(), /Firefox on Windows/);
  });

  test("他ブラウザの表示名も一覧に出る", async () => {
    const env = await envWithDevice();
    const a = cookieValue(await handle(postForm("/dashboard", `token=${TOKEN}`), env, now));
    await handle(
      postForm("/dashboard", `token=${TOKEN}`, {
        ua: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) Safari/604.1",
      }),
      env,
      now
    );

    const page = await handle(get("/", `sk_token=${a}`), env, now);
    const body = await page.text();
    assert.match(body, /Chrome on macOS/);
    assert.match(body, /Safari on iOS/);
    assert.match(body, /this browser/);
  });

  // ---- 旧 Cookie からの移行 ----

  test("旧 Cookie（端末トークン）はセッションに昇格する", async () => {
    // 移行措置。これをしないと、変更後に全ブラウザが突然ログアウトする。
    const env = await envWithDevice();
    const res = await handle(get("/", `sk_token=${TOKEN}`), env, now);

    assert.equal(res.status, 200);
    assert.match(await res.text(), /Today/);

    const setCookie = res.headers.get("set-cookie") ?? "";
    const session = cookieValue(res);
    assert.ok(session.length > 0);
    assert.notEqual(session, TOKEN);
    // 昇格後はブラウザに端末トークンを残さない
    assert.equal(setCookie.includes(TOKEN), false);
  });

  test("昇格したセッションも一覧に出て失効できる", async () => {
    const env = await envWithDevice();
    const promoted = cookieValue(await handle(get("/", `sk_token=${TOKEN}`), env, now));

    const page = await handle(get("/", `sk_token=${promoted}`), env, now);
    const body = await page.text();
    assert.match(body, /Chrome on macOS/);

    const id = /name="revoke" value="([^"]+)"/.exec(body)?.[1];
    await handle(postForm("/dashboard", `revoke=${id}`, { cookie: `sk_token=${promoted}` }), env, now);

    assert.match(await (await handle(get("/", `sk_token=${promoted}`), env, now)).text(), /Pairing code/);
  });

  test("ログアウトはセッションをサーバー側でも失効させる", async () => {
    const env = await envWithDevice();
    const res = await handle(postForm("/dashboard", `token=${TOKEN}`), env, now);
    const session = cookieValue(res);

    const out = await handle(
      postForm("/dashboard", "logout=1", { cookie: `sk_token=${session}` }),
      env,
      now
    );
    assert.match(out.headers.get("set-cookie") ?? "", /Max-Age=0/);

    const after = await handle(get("/", `sk_token=${session}`), env, now);
    assert.match(await after.text(), /Pairing code/);
  });
});

describe("画面のヘルパ", () => {
  test("Cookie からトークンを取り出す", () => {
    assert.equal(tokenFromCookie("sk_token=abc"), "abc");
    assert.equal(tokenFromCookie("other=1; sk_token=abc; x=2"), "abc");
    assert.equal(tokenFromCookie("other=1"), null);
    assert.equal(tokenFromCookie("sk_token="), null);
    assert.equal(tokenFromCookie(null), null);
  });

  test("HTML エスケープ（アプリ名は外部由来なので必須）", () => {
    assert.equal(esc("<script>alert(1)</script>"), "&lt;script&gt;alert(1)&lt;/script&gt;");
    assert.equal(esc('a"b\'c&d'), "a&quot;b&#39;c&amp;d");
  });

  test("時間の表記", () => {
    assert.equal(fmt(3 * 3600_000 + 42 * 60_000), "3h 42m");
    assert.equal(fmt(48 * 60_000), "48m");
    assert.equal(fmt(0), "0m");
    assert.equal(fmt(-100), "0m");
  });
});

// ===========================================================================
describe("日別の内訳（GET /api/v1/day）", () => {
  const now = Date.UTC(2026, 8, 14, 12, 0, 0);
  const today = "2026-09-14";

  /** 2アプリ・2端末のデータを入れる。 */
  async function envWithData() {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN), "phone");
    const OTHER = "33333333-3333-3333-3333-333333333333";
    await seedDevice(db, OTHER, await hashToken("t2"), "pc");

    await ingestBatch(
      db,
      DEVICE,
      "d1",
      [
        ev(today, "com.youtube", 1_757_700_000_000, 1_757_700_600_000),  // 10分
        ev(today, "com.chrome", 1_757_700_700_000, 1_757_700_760_000),   // 1分
      ],
      now
    );
    await ingestBatch(
      db,
      OTHER,
      "d2",
      [ev(today, "com.youtube", 1_757_710_000_000, 1_757_710_300_000)],  // 5分
      now
    );
    return { DB: db };
  }

  function getDay(date: string, token?: string): Request {
    const headers: Record<string, string> = {};
    if (token) headers["authorization"] = `Bearer ${token}`;
    return new Request(`https://example.com/api/v1/day?date=${date}`, { method: "GET", headers });
  }

  test("認証が無ければ 401", async () => {
    const env = await envWithData();
    const res = await handle(getDay(today), env, now);
    assert.equal(res.status, 401);
  });

  test("日付の形式が不正なら 400", async () => {
    const env = await envWithData();
    const res = await handle(getDay("2026-13-99", TOKEN), env, now);
    assert.equal(res.status, 400);
  });

  test("未来すぎる日付は 400", async () => {
    const env = await envWithData();
    const res = await handle(getDay("2030-01-01", TOKEN), env, now);
    assert.equal(res.status, 400);
  });

  test("その日のアプリ別内訳が返る（端末は合算）", async () => {
    const env = await envWithData();
    const res = await handle(getDay(today, TOKEN), env, now);

    assert.equal(res.status, 200);
    const body = (await res.json()) as {
      date: string;
      totalMillis: number;
      apps: { packageName: string; totalMillis: number }[];
      byDevice: { label: string; totalMillis: number }[];
    };

    assert.equal(body.date, today);
    // YouTube は 10分（phone）+ 5分（pc）= 15分
    const yt = body.apps.find((a) => a.packageName === "com.youtube");
    assert.equal(yt?.totalMillis, 15 * 60_000);
    // Chrome は 1分
    const chrome = body.apps.find((a) => a.packageName === "com.chrome");
    assert.equal(chrome?.totalMillis, 60_000);
    // 合計 = 16分
    assert.equal(body.totalMillis, 16 * 60_000);
    // 端末別に2台
    assert.equal(body.byDevice.length, 2);
  });

  test("使用時間の降順で並ぶ", async () => {
    const env = await envWithData();
    const res = await handle(getDay(today, TOKEN), env, now);
    const body = (await res.json()) as { apps: { totalMillis: number }[] };

    const totals = body.apps.map((a) => a.totalMillis);
    assert.deepEqual(totals, [...totals].sort((a, b) => b - a));
  });

  test("データが無い日は空で返る（エラーにしない）", async () => {
    const env = await envWithData();
    const res = await handle(getDay("2026-09-01", TOKEN), env, now);

    assert.equal(res.status, 200);
    const body = (await res.json()) as { totalMillis: number; apps: unknown[] };
    assert.equal(body.totalMillis, 0);
    assert.equal(body.apps.length, 0);
  });
});

// ===========================================================================
describe("日別ページ（GET /day）", () => {
  const now = Date.UTC(2026, 8, 14, 12, 0, 0);
  const today = "2026-09-14";

  async function envWithData(label = "YouTube") {
    const db = freshDb();
    await seedDevice(db, DEVICE, await hashToken(TOKEN), "phone");
    await ingestBatch(
      db,
      DEVICE,
      "d1",
      [
        {
          localDate: today,
          packageName: "com.youtube",
          appLabel: label,
          startTime: 1_757_700_000_000,
          endTime: 1_757_700_600_000,
          closeReason: "PAUSED",
        },
      ],
      now
    );
    return { DB: db };
  }

  function getPage(path: string, cookie?: string): Request {
    const headers: Record<string, string> = {};
    if (cookie) headers["cookie"] = cookie;
    return new Request(`https://example.com${path}`, { method: "GET", headers });
  }

  test("Cookie が無ければ入力画面", async () => {
    const env = await envWithData();
    const res = await handle(getPage(`/day?date=${today}`), env, now);
    const body = await res.text();
    assert.match(body, /Device token/);
  });

  test("アプリ別の内訳が表示される", async () => {
    const env = await envWithData();
    const res = await handle(getPage(`/day?date=${today}`, `sk_token=${TOKEN}`), env, now);

    assert.equal(res.status, 200);
    const body = await res.text();
    assert.match(body, /YouTube/);
    assert.match(body, /10m/);
    assert.match(body, /Apps/);
    // メイン画面へ戻れること
    assert.match(body, /href="\/"/);
  });

  test("アプリ名の HTML はエスケープされる（XSS 対策）", async () => {
    const env = await envWithData("<script>alert(2)</script>");
    const res = await handle(getPage(`/day?date=${today}`, `sk_token=${TOKEN}`), env, now);
    const body = await res.text();

    assert.equal(body.includes("<script>alert(2)</script>"), false);
    assert.match(body, /&lt;script&gt;/);
  });

  test("日付が不正なら 400", async () => {
    const env = await envWithData();
    const res = await handle(getPage("/day?date=bad", `sk_token=${TOKEN}`), env, now);
    assert.equal(res.status, 400);
  });
});
