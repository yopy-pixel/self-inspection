#!/usr/bin/env node
/**
 * セキュリティ検証 + モンキーテスト。
 *
 * **動いているサーバーに対して実行する。** 異常入力・境界値・連打を投げて、
 * サーバーが壊れないこと・情報を漏らさないことを確認する。
 *
 * 使い方:
 *   # ターミナル1
 *   cd server && npm run dev
 *   # ターミナル2
 *   cd server && npm run probe
 *
 * 本番に対して実行する場合:
 *   PROBE_URL=https://self-kaizen.xxx.workers.dev \
 *   PROBE_ADMIN_TOKEN=<ADMIN_TOKEN> \
 *   npm run probe
 *
 * 注意: 本番に対して実行するとテストデータが残る。
 *       `label` に `probe-` を付けるので、後で削除できる。
 */

const BASE = (process.env.PROBE_URL ?? "http://localhost:8787").replace(/\/+$/, "");
const ADMIN = process.env.PROBE_ADMIN_TOKEN ?? "local-admin-token";

// ---------------------------------------------------------------------------
// 結果の記録
// ---------------------------------------------------------------------------

const results = [];
let currentGroup = "";

function group(name) {
  currentGroup = name;
  results.push({ kind: "group", name });
}

/**
 * @param {string} name      何を確認したか
 * @param {boolean} ok       期待どおりだったか
 * @param {string} detail    実際の値
 */
function check(name, ok, detail) {
  results.push({ kind: "check", group: currentGroup, name, ok, detail });
}

// ---------------------------------------------------------------------------
// HTTP ヘルパ
// ---------------------------------------------------------------------------

async function req(method, path, { token, body, raw, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h["Authorization"] = `Bearer ${token}`;
  if (body !== undefined || raw !== undefined) {
    h["Content-Type"] = "application/json";
  }
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: h,
    body: raw !== undefined ? raw : body !== undefined ? JSON.stringify(body) : undefined,
  });
  let text = "";
  try {
    text = await res.text();
  } catch {
    /* ignore */
  }
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* ignore */
  }
  return { status: res.status, text, json };
}

// ---------------------------------------------------------------------------
// 準備
// ---------------------------------------------------------------------------

async function setup() {
  const r = await req("POST", "/api/v1/devices", {
    token: ADMIN,
    body: { label: "probe-device" },
  });
  if (r.status !== 201 || !r.json?.token) {
    console.error("端末を登録できませんでした。サーバーが起動しているか確認してください。");
    console.error(`  status=${r.status} body=${r.text.slice(0, 200)}`);
    process.exit(1);
  }
  return { deviceId: r.json.deviceId, token: r.json.token };
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

/** 最小の正常イベント。 */
function validEvent(overrides = {}) {
  const d = today();
  const base = Date.parse(`${d}T10:00:00Z`);
  return {
    localDate: d,
    packageName: "com.example.app",
    appLabel: "Example",
    startTime: base,
    endTime: base + 60000,
    closeReason: "PAUSED",
    ...overrides,
  };
}

function validBatch(deviceId, overrides = {}) {
  return {
    batchId: crypto.randomUUID(),
    deviceId,
    schemaVersion: 1,
    events: [validEvent()],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// 検証本体
// ---------------------------------------------------------------------------

async function run() {
  const { deviceId, token } = await setup();
  console.log(`対象: ${BASE}`);
  console.log(`端末: ${deviceId}\n`);

  // ========================================================================
  group("A. 認証");
  // ========================================================================
  {
    const r1 = await req("GET", "/api/v1/summary");
    check("認証ヘッダ無しは 401", r1.status === 401, `HTTP ${r1.status}`);

    const r2 = await req("GET", "/api/v1/summary", { token: "wrong-token" });
    check("誤ったトークンは 401", r2.status === 401, `HTTP ${r2.status}`);

    const r3 = await req("GET", "/api/v1/summary", {
      headers: { Authorization: "Bearer" },
    });
    check("'Bearer' のみは 401", r3.status === 401, `HTTP ${r3.status}`);

    const r4 = await req("GET", "/api/v1/summary", {
      headers: { Authorization: "Basic abcdef" },
    });
    check("Basic 認証は 401", r4.status === 401, `HTTP ${r4.status}`);

    const r5 = await req("GET", "/api/v1/summary", {
      headers: { Authorization: "Bearer   " + token + "   " },
    });
    check("前後の空白は許容される", r5.status === 200, `HTTP ${r5.status}`);

    // 端末IDとトークンの不一致
    const r6 = await req("POST", "/api/v1/ingest", {
      token,
      body: validBatch(crypto.randomUUID()),
    });
    check("端末IDの不一致は 403", r6.status === 403, `HTTP ${r6.status}`);

    // 別端末のトークンで他人の deviceId を指定
    const other = await req("POST", "/api/v1/devices", {
      token: ADMIN,
      body: { label: "probe-device-2" },
    });
    const r7 = await req("POST", "/api/v1/ingest", {
      token: other.json.token,
      body: validBatch(deviceId),
    });
    check("他端末のIDを指定しても 403", r7.status === 403, `HTTP ${r7.status}`);
  }

  // ========================================================================
  group("B. 入力検証（モンキー）");
  // ========================================================================
  {
    const cases = [
      ["壊れた JSON", { raw: "{not json" }, 400],
      ["空ボディ", { raw: "" }, 400],
      ["JSON 配列（オブジェクトでない）", { raw: "[]" }, 400],
      ["null", { raw: "null" }, 400],
      ["batchId 欠落", { body: { deviceId, schemaVersion: 1, events: [] } }, 400],
      ["deviceId 欠落", { body: { batchId: "x", schemaVersion: 1, events: [] } }, 400],
      ["schemaVersion 欠落", { body: { batchId: "x", deviceId, events: [] } }, 400],
      ["events 欠落", { body: { batchId: "x", deviceId, schemaVersion: 1 } }, 400],
      ["events が配列でない", { body: { batchId: "x", deviceId, schemaVersion: 1, events: "no" } }, 400],
      ["batchId が数値", { body: { batchId: 123, deviceId, schemaVersion: 1, events: [] } }, 400],
      ["schemaVersion が文字列", { body: { batchId: "x", deviceId, schemaVersion: "1", events: [] } }, 400],
      ["schemaVersion が新しすぎる", { body: { batchId: "x", deviceId, schemaVersion: 99, events: [] } }, 400],
      ["schemaVersion が 0", { body: { batchId: "x", deviceId, schemaVersion: 0, events: [] } }, 400],
    ];

    for (const [name, opts, expected] of cases) {
      const r = await req("POST", "/api/v1/ingest", { token, ...opts });
      check(name, r.status === expected, `HTTP ${r.status}（期待 ${expected}）`);
    }

    // batchId が長すぎる
    const longId = "a".repeat(500);
    const rLong = await req("POST", "/api/v1/ingest", {
      token,
      body: { batchId: longId, deviceId, schemaVersion: 1, events: [] },
    });
    check("batchId 500文字は拒否", rLong.status === 400, `HTTP ${rLong.status}`);

    // events が多すぎる
    const many = Array.from({ length: 2100 }, () => validEvent());
    const rMany = await req("POST", "/api/v1/ingest", {
      token,
      body: { batchId: crypto.randomUUID(), deviceId, schemaVersion: 1, events: many },
    });
    check("events 2100件は拒否", rMany.status === 400, `HTTP ${rMany.status}`);
  }

  // ========================================================================
  group("C. 不正なイベントの扱い");
  // ========================================================================
  {
    // 構造は正しいが、個々のイベントが不正な場合。
    // 「1件の不具合で同期全体が止まらない」設計なので、
    // 不正イベントはスキップされ、応答の rejected に載る。
    const bad = [
      ["負の長さ", validEvent({ startTime: 2000, endTime: 1000 })],
      ["開始=終了（0秒）", validEvent({ startTime: 5000, endTime: 5000 })],
      ["遠い未来", validEvent({ startTime: Date.parse("2099-01-01T00:00:00Z"), endTime: Date.parse("2099-01-01T01:00:00Z"), localDate: "2099-01-01" })],
      ["過去すぎる", validEvent({ startTime: Date.parse("1970-01-01T00:00:00Z"), endTime: Date.parse("1970-01-01T01:00:00Z"), localDate: "1970-01-01" })],
      ["localDate の書式が不正", validEvent({ localDate: "2026/09/12" })],
      ["localDate が存在しない日", validEvent({ localDate: "2026-02-30" })],
      ["packageName が空", validEvent({ packageName: "" })],
      ["packageName が長すぎる", validEvent({ packageName: "a".repeat(500) })],
      ["endTime が null", validEvent({ endTime: null })],
      ["startTime が文字列", validEvent({ startTime: "abc" })],
    ];

    const r = await req("POST", "/api/v1/ingest", {
      token,
      body: {
        batchId: crypto.randomUUID(),
        deviceId,
        schemaVersion: 1,
        events: bad.map(([, e]) => e),
      },
    });

    const ok = (r.status === 202 || r.status === 200) && Array.isArray(r.json?.rejected);
    check(
      "構造が正しければ 202 で、不正イベントは rejected に載る",
      ok,
      `HTTP ${r.status} rejected=${r.json?.rejected?.length ?? "なし"}/${bad.length}`
    );

    if (ok) {
      const reasons = r.json.rejected.map((x) => x.reason).join(", ");
      console.log(`    拒否理由: ${reasons}`);
    }
  }

  // ========================================================================
  group("D. インジェクション");
  // ========================================================================
  {
    const injections = [
      ["SQL（DROP）", "'; DROP TABLE usage_event; --"],
      ["SQL（UNION）", "' UNION SELECT * FROM device --"],
      ["パストラバーサル", "../../../etc/passwd"],
      ["制御文字", "app\u0000name"],
      ["改行", "app\nname"],
      ["巨大 Unicode", "𠮷".repeat(50)],
      ["絵文字", "📱app"],
    ];

    const events = injections.map(([, pkg]) => validEvent({ packageName: pkg }));
    const r = await req("POST", "/api/v1/ingest", {
      token,
      body: { batchId: crypto.randomUUID(), deviceId, schemaVersion: 1, events },
    });

    // 受理されても拒否されてもよい。**クラッシュしないこと**が要件。
    check(
      "インジェクション文字列でクラッシュしない",
      r.status === 202 || r.status === 200 || r.status === 400,
      `HTTP ${r.status}`
    );

    // テーブルが生きているか確認（DROP されていたら落ちる）
    const s = await req("GET", "/api/v1/summary", { token });
    check("SQL インジェクション後も DB は正常", s.status === 200, `HTTP ${s.status}`);
  }

  // ========================================================================
  group("E. 冪等性");
  // ========================================================================
  {
    const batch = validBatch(deviceId, {
      events: [validEvent({ packageName: "com.probe.idempotent" })],
    });

    const first = await req("POST", "/api/v1/ingest", { token, body: batch });
    check("初回は 202", first.status === 202, `HTTP ${first.status}`);

    const second = await req("POST", "/api/v1/ingest", { token, body: batch });
    check(
      "同じ batchId の再送は 200 duplicate",
      second.status === 200 && second.json?.duplicate === true,
      `HTTP ${second.status} duplicate=${second.json?.duplicate}`
    );

    const third = await req("POST", "/api/v1/ingest", { token, body: batch });
    check("3回目も 200 duplicate", third.status === 200, `HTTP ${third.status}`);
  }

  // ========================================================================
  group("F. 集計クエリの境界");
  // ========================================================================
  {
    const cases = [
      ["from > to", "?from=2026-09-12&to=2026-09-01", 400],
      ["from の書式不正", "?from=20260901&to=2026-09-12", 400],
      ["to の書式不正", "?from=2026-09-01&to=09-12-2026", 400],
      ["存在しない日", "?from=2026-02-30&to=2026-02-30", 400],
      ["正常", `?from=${today()}&to=${today()}`, 200],
      ["同一日", `?from=${today()}&to=${today()}`, 200],
    ];
    for (const [name, qs, expected] of cases) {
      const r = await req("GET", `/api/v1/summary${qs}`, { token });
      check(name, r.status === expected, `HTTP ${r.status}（期待 ${expected}）`);
    }

    // 極端に広い範囲
    const wide = await req("GET", "/api/v1/summary?from=1900-01-01&to=2999-12-31", { token });
    check(
      "極端に広い範囲でもクラッシュしない",
      wide.status === 200 || wide.status === 400,
      `HTTP ${wide.status}`
    );
  }

  // ========================================================================
  group("G. ルーティング");
  // ========================================================================
  {
    const paths = [
      ["未知のパス", "GET", "/api/v1/nonexistent", 404],
      ["パストラバーサル", "GET", "/api/v1/../../../etc/passwd", 404],
      ["ルート", "GET", "/", 404],
      ["誤ったメソッド（GET /ingest）", "GET", "/api/v1/ingest", 404],
      ["誤ったメソッド（POST /summary）", "POST", "/api/v1/summary", 404],
      ["末尾スラッシュは許容", "GET", "/healthz/", 200],
    ];
    for (const [name, method, path, expected] of paths) {
      const r = await req(method, path, { token });
      check(name, r.status === expected, `HTTP ${r.status}（期待 ${expected}）`);
    }
  }

  // ========================================================================
  group("H. 連打・同時実行");
  // ========================================================================
  {
    // 同じバッチを10並列で送る → 二重計上しないこと
    const batch = validBatch(deviceId, {
      events: [validEvent({ packageName: "com.probe.concurrent" })],
    });
    const responses = await Promise.all(
      Array.from({ length: 10 }, () => req("POST", "/api/v1/ingest", { token, body: batch }))
    );
    const accepted = responses.filter((r) => r.status === 202).length;
    const duplicate = responses.filter((r) => r.status === 200).length;

    check(
      "10並列の同一バッチで二重計上しない",
      accepted === 1 && duplicate === 9,
      `202が${accepted}件 / 200が${duplicate}件`
    );

    // /healthz を50連打
    const health = await Promise.all(
      Array.from({ length: 50 }, () => req("GET", "/healthz"))
    );
    check(
      "/healthz を50連打しても全て 200",
      health.every((r) => r.status === 200),
      `成功 ${health.filter((r) => r.status === 200).length}/50`
    );
  }

  // ========================================================================
  group("I. 情報漏洩");
  // ========================================================================
  {
    const s = await req("GET", "/api/v1/summary", { token });
    const body = s.text;

    check(
      "応答にトークンハッシュが含まれない",
      !body.includes("token_hash") && !/\\b[0-9a-f]{64}\\b/.test(body),
      body.slice(0, 80).replace(/\n/g, " ")
    );

    const bad = await req("POST", "/api/v1/ingest", { token, raw: "{broken" });
    const leaksStack =
      bad.text.includes("at ") && bad.text.includes(".ts:");
    check(
      "エラー応答にスタックトレースが含まれない",
      !leaksStack,
      bad.text.slice(0, 80)
    );

    check(
      "エラー応答に内部パスが含まれない",
      !bad.text.includes("/Users/") && !bad.text.includes("node_modules"),
      bad.text.slice(0, 80)
    );
  }

  return { deviceId };
}

// ---------------------------------------------------------------------------
// 出力
// ---------------------------------------------------------------------------

function report() {
  let pass = 0;
  let fail = 0;

  console.log("\n" + "=".repeat(72));
  for (const r of results) {
    if (r.kind === "group") {
      console.log(`\n${r.name}`);
      console.log("-".repeat(72));
    } else {
      const mark = r.ok ? "  OK  " : " FAIL ";
      if (r.ok) pass++;
      else fail++;
      console.log(`[${mark}] ${r.name}`);
      console.log(`          ${r.detail}`);
    }
  }

  console.log("\n" + "=".repeat(72));
  console.log(`結果: ${pass} 件成功 / ${fail} 件失敗`);
  console.log("=".repeat(72));
  return fail;
}

run()
  .then(() => {
    const failed = report();
    process.exit(failed > 0 ? 1 : 0);
  })
  .catch((e) => {
    console.error("\n検証中に例外が発生しました:", e);
    report();
    process.exit(1);
  });
