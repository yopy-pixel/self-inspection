# SelfKaizen 集約サーバー

Android（将来は PC）の使用時間データを集約するサーバー。
**Cloudflare Workers + D1（サーバーレス SQLite）** を前提にしている。


---

## 0. 現状

| 項目 | 状態 |
|---|---|
| ローカルテスト | **98件 / 全パス** |
| 実装 | ingest（冪等・UPSERT） / summary / day / healthz / 端末登録 / **ペアコード（ブラウザ・端末）** |
| **HTTPS 強制** | **実装済み**（平文 `http:` を 403 で拒否。`docs/PRIVACY.md` §3.1） |
| **Android 側の送信実装** | **実装済み**（`sync/SyncClient.kt`。契約テスト済み） |
| **サーバー↔クライアント実通信** | **検証済み**（下記 §1.5） |
| **デプロイ** | **実施済み**（`docs/DEPLOYMENT.md` §0 に URL と ID） |
| **ブラウザ用の画面** | **実装済み**（`GET /`・セッション認証・読み取り専用） |
| **ブラウザごとのセッション** | **実装済み**（一覧・個別失効。複数の PC・スマホを想定） |

> **依存ゼロでテストできる。** `node:sqlite`（Node 22 内蔵）を使うため
> `npm install` すら不要。D1 は SQLite なので**同じ SQL** がローカルで動く。
> 実行時の依存（`dependencies`）も**ゼロのまま**にしてある。

### 1.6 ブラウザのログイン（ペアコード）

**端末トークン（64桁）を手入力させない。** 認証済みのアプリが短命・単回使用の
コードを発行し、新しいブラウザはそれを引き換える。

```
アプリ                         サーバー                     新しいPCのブラウザ
  |-- POST /api/v1/pair ------->|                              |
  |    (端末トークン)            |  コードを発行（3分・単回）    |
  |<-- {code:"W0VR8XEE"} --------|                              |
  |                                                             |
  |  画面にコードを表示 ---------> 人がPCで入力 ---------------> |
  |                              |<-- POST /dashboard pair=CODE -|
  |                              |--- Set-Cookie: sk_token=... -->|
```

| 経路 | 認証 | 備考 |
|---|---|---|
| `POST /api/v1/pair` | 端末トークン（Bearer）または セッション Cookie | 未認証では **401**。ここが要 |
| `POST /dashboard` `pair=<code>` | 不要（コード自体が資格情報） | セッションを作って Cookie を返す |
| `POST /dashboard` `token=<t>` | 不要 | 初回・アプリが使えないときの予備 |
| `POST /dashboard` `newpair=1` | セッション | ログイン済みブラウザから追加のコードを発行 |
| `POST /dashboard` `revoke=<id>` | セッション | 指定セッションを失効 |
| `POST /dashboard` `logout=1` | セッション | 自分のセッションを失効して Cookie を消す |

### 1.7 端末の追加も同じ仕組みでやる

**管理トークン（`ADMIN_TOKEN`）と `curl` は要らない。**
`{"for":"device"}` で発行したコードを、新しい端末が引き換える。

```
既存の端末（アプリ）              サーバー                  新しい端末
  |-- POST /api/v1/pair ---------->|                          |
  |    {"for":"device"}            |  端末用コード（3分・単回） |
  |<-- {code, kind:"device"} ------|                          |
  |                                |<-- POST /api/v1/devices/claim
  |                                |    {code, label}          |
  |                                |--- {deviceId, token} ---->|  保存する
```

| 経路 | 認証 | 備考 |
|---|---|---|
| `POST /api/v1/pair` `{"for":"device"}` | 端末トークン or セッション | 未認証では **401** |
| `POST /api/v1/devices/claim` | **不要**（コードが資格情報） | `device` 行を作り、`deviceId` とトークンを返す |
| `POST /api/v1/devices` | `ADMIN_TOKEN` | **残す**。全端末と全セッションを失ったときの最後の手段 |

**用途（`kind`）は引き換え時に照合する。**

- ブラウザ用のコード → 端末登録に使えない（401）
- 端末用のコード → ブラウザのログインに使えない（401）

端末コードは**書き込み権限（端末トークン）を配る**ので、閲覧用の
ブラウザコードより影響が強い。混ざらないようにしてある。

**端末トークンとセッションは別物。** Cookie に入るのはセッションだけで、
端末トークンはブラウザに残らない。だから

- PC を増やす → コードを1つ発行するだけ
- 1台だけ止める → その行を `Revoke`（他は生きたまま）
- 本当に失効する → サーバー側で無効化される（Cookie を消すだけでは終わらない）

旧実装の Cookie（端末トークンそのもの）は**セッションに自動昇格**する。
変更で全ブラウザが突然ログアウトするのを避けるため。

### 1.5 サーバー ↔ クライアントの実通信を検証する

Cloudflare アカウントが無くても、**実際の HTTP 通信**で契約を検証できる。

```bash
# ターミナル1: ローカルサーバーを起動
cd server
node --experimental-strip-types dev-server.ts
#   → deviceId と token が表示される

# ターミナル2: Android のコードから実通信で検証
cd android
./gradlew :app:testDebugUnitTest
#   ServerContractIntegrationTest が実行される
```

`ServerContractIntegrationTest` は **Android のコード**
（`BatchBuilder` → `SyncClient` → `UrlConnectionTransport`）でリクエストを組み立て、
動いているサーバーへ送り、以下を検証する:

1. 端末登録 → トークン発行
2. **日付境界をまたぐ区間**を送信（23:30–翌00:30 → 2件に分割）→ 202
3. 同じバッチを**再送** → 200（重複・二重計上しない）
4. 集計を取得し、**分割後の数値が正しい**ことを確認
5. 誤ったトークン → 401

> **サーバーが起動していない場合はスキップする**（失敗にはしない）。
> エンドポイントは `SELF_KAIZEN_TEST_ENDPOINT` で変更できる。

> テストデータの日付は**実行時の今日から動的に**決めること。
> サーバーは `MAX_FUTURE_DAYS` / `MAX_PAST_DAYS` で範囲外を弾くため、
> 固定日付を書くと実行日によって失敗する。

---

## セキュリティ検証 + モンキーテスト

異常入力・境界値・連打を投げて、壊れないこと・情報を漏らさないことを確認する。
**サーバーを起動した状態で実行する。**

```bash
npm run dev     # ターミナル1
npm run probe   # ターミナル2
```

**2026-09-14 時点: 46件成功 / 0件失敗。**

本番に対して実行する場合:
```bash
PROBE_URL=https://self-kaizen.<サブドメイン>.workers.dev \
PROBE_ADMIN_TOKEN=<ADMIN_TOKEN> \
npm run probe
```

詳細は `docs/DEPLOYMENT.md` §13 を参照。

---

## 1. ローカルでテストする（アカウント不要）

```bash
cd server
node --test --experimental-strip-types test/server.test.ts
```

`66 tests / 66 pass` になれば正常。

> `--experimental-strip-types` は Node 22 の機能。TypeScript をそのまま実行する。
> **制約: パラメータプロパティ（constructor の `private readonly x`）が使えない。**
> そのため `test/d1-adapter.ts` は明示的なフィールド代入をしている。

### 何を検証しているか

| 分類 | 主な内容 |
|---|---|
| **冪等性** | 同じ `batchId` の再送で二重計上しない／別バッチでも同じ区間は増えない |
| **集計** | アプリ別・日別・端末別に正しく分かれる／期間外は返らない |
| **検証** | 構造エラーは400、個別の不正イベントはスキップして報告 |
| **境界** | `endTime < startTime` を弾く／24時間超を弾く／実在しない日付を弾く |
| **HTTP** | 401 / 403 / 400 / 404 / 202（新規）/ 200（再送）/ 201（端末登録） |

### テストの検証（ミューテーションテスト）

テストが実際に機能することを確かめるため、**意図的に誤った実装を注入して
検知されるか**を確認している:

| 注入した誤実装 | 検知 |
|---|---|
| 再送チェックを削除（二重計上する） | ✅ 2件が検知 |
| 集計の再計算をやめる | ✅ 8件が検知 |

---

## 2. デプロイ手順（Cloudflare アカウントが必要）

```bash
cd server
npm install

# 1. Cloudflare にログイン
npx wrangler login

# 2. D1 データベースを作成
npx wrangler d1 create self-kaizen
#   → 出力された database_id を wrangler.toml に貼る

# 3. スキーマを適用
npx wrangler d1 migrations apply self-kaizen --remote

# 4. 端末登録用の管理者トークンを設定（自分で決めた長い文字列）
npx wrangler secret put ADMIN_TOKEN

# 5. デプロイ
npx wrangler deploy
#   → https://self-kaizen.<your-subdomain>.workers.dev
```

### 端末を登録する（トークン発行）

```bash
curl -X POST https://self-kaizen.<sub>.workers.dev/api/v1/devices \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"label":"my phone"}'
# → {"deviceId":"...","token":"..."}   ← トークンはここでしか出ない
```

**返った `token` と `deviceId` を Android アプリに設定する。**

> **注意:** トークンはサーバーに**ハッシュのみ**保存される。
> 紛失したら再発行するしかない（平文は復元できない）。

### 動作確認

```bash
curl https://self-kaizen.<sub>.workers.dev/healthz
# → {"ok":true,"now":...}
```

---

## 3. API 契約

### POST /api/v1/ingest — 取り込み

```
Authorization: Bearer <device_token>
Content-Type: application/json
```

```jsonc
{
  "batchId": "uuid",        // 冪等性の単位。クライアントが生成
  "deviceId": "uuid",       // トークンの端末と一致すること（不一致は403）
  "schemaVersion": 1,
  "sentAt": 1757700000000,  // 任意
  "events": [
    {
      "localDate": "2026-09-14",   // ★端末が確定した日付
      "packageName": "com.google.android.youtube",
      "appLabel": "YouTube",
      "startTime": 1757696400000,  // epoch millis
      "endTime":   1757696530000,
      "closeReason": "PAUSED"      // 任意
    }
  ]
}
```

応答:

| 状況 | ステータス | 意味 |
|---|---|---|
| 新規取り込み | **202** | `{accepted, duplicate:false, eventCount, summariesUpdated}` |
| 再送 | **200** | `duplicate:true`。**二重計上しない** |
| 構造的な誤り | **400** | `{error, detail}` |
| 認証失敗 | **401** | |
| deviceId 不一致 | **403** | |

### GET /api/v1/summary — 集計

```
GET /api/v1/summary?from=2026-09-08&to=2026-09-14
Authorization: Bearer <device_token>
```

```jsonc
{
  "from": "2026-09-08",
  "to": "2026-09-14",
  "dailyTotals": [{ "localDate": "2026-09-14", "totalMillis": 13320000 }],
  "appTotals":   [{ "packageName": "...", "appLabel": "YouTube", "totalMillis": 4320000 }],
  "byDevice":    [{ "deviceId": "...", "label": "my phone", "totalMillis": 13320000 }]
}
```

### GET /api/v1/day — 1日の内訳

```
GET /api/v1/day?date=2026-09-14
Authorization: Bearer <device_token>
```

```jsonc
{
  "date": "2026-09-14",
  "totalMillis": 15540000,
  "apps": [
    { "packageName": "...", "appLabel": "YouTube", "totalMillis": 4500000, "segmentCount": 12 }
  ],
  "byDevice": [{ "deviceId": "...", "label": "my phone", "totalMillis": 9000000 }]
}
```

**`/summary` との違い:** `/summary` は「日ごと（アプリ合算）」と
「アプリごと（日合算）」しか返さず、**交差した情報を持たない**。
この端点がその交点（日 × アプリ）を埋める。

**1日分に固定している。** 期間を指定できるようにすると、
30日 × 50アプリのような重い応答になりうるため（D1 の行読み取りと
応答サイズに上限を作る）。

### GET /healthz — 死活確認（認証不要）

---

## 4. クライアント側（Android）が守るべきこと

**これが契約の核心。** 守らないと数値がずれる。

1. **`localDate` は端末が確定して送る。**
   サーバーは日付を計算しない。タイムゾーン・夏時間・旅行時の扱いを
   正しく知っているのは端末だけ。

2. **日付境界で区間を分割してから送る。**
   1つの区間が複数の `localDate` にまたがってはいけない。
   Android 側の `DailyAggregator` が既にこの分割ロジックを持っている
   （`startOfNextDay()`）ので、それを再利用する。

3. **`batchId` は再送で変えない。**
   同じデータを再送するときは同じ `batchId` を使う。
   変えると二重計上する。

4. **送信失敗してもローカル記録は消さない。**
   成功応答を受け取ってから「送信済み」にする。

5. **1バッチは 2000 イベント以下**（`MAX_EVENTS_PER_BATCH`）。
   超えると 400 になる。超えそうなら分割する。

### 送信頻度

- アプリ起動時
- 12時間ごとのバックグラウンド処理のついで
- **UI のクリティカルパスに置かない**（表示は常にローカルの Room を読む）

---

## 5. 設計上の決定

| 決定 | 理由 |
|---|---|
| **`ingest_batch` の PK で冪等化** | モバイルは必ず再送する。これが無いと二重計上 |
| **`localDate` を端末が送る** | サーバーで日付を切るとタイムゾーンでずれる |
| **集計は書き込み時に再計算** | 読み取り時の大量走査は D1 の行読み取り課金と CPU 制限に直撃 |
| **`UNIQUE(device_id, package_name, start_time)`** | 区間の同一性。再送で増やさない |
| **トークンは SHA-256 のみ保存** | 平文保存は漏洩時に全端末が危険 |
| **端末ごとのトークン** | 共有パスワードにしない |
| **`schemaVersion` を必須** | 古いアプリが残るため複数版を受け入れる必要がある |
| **構造エラーは400 / 個別エラーはスキップ** | 構造的な誤りは表面化させつつ、1件の不正データで同期を止めない |
| **`deviceId` とトークンの一致を検証** | 取り違えると別端末のデータとして保存される |

### 既知の前提

**区間の同一性は `(device_id, package_name, start_time)`。**
同じ `startTime` を持つ区間は、**別の日であっても**重複として扱われる。
実データの `startTime` は epoch millis なので日をまたげば必ず異なる値になり、
この状況は実データでは起きない。テストで明示している。

---

## 6. 未実装・未決定

### 未実装

| 項目 | 備考 |
|---|---|
| ~~Android 側の送信実装~~ | **実装済み**（`android/.../sync/`） |
| ~~ダッシュボード配信~~ | **実装済み**（`GET /` ブラウザ用の読み取り専用画面） |
| 違反（上限超過）のサーバー側判定 | 現状は端末内で判定。サーバーは集計のみ |
| データのエクスポート | ロックイン回避のため |
| レート制限 | 個人用途のため未実装 |
| 同期設定の UI | 現在は `SyncSettingsStore` に直接保存するのみ（画面未実装） |

### 未決定

1. **データ主権** — 使用履歴を Cloudflare に置くことの許容
2. **無料枠の確認** — 実運用での消費量の測定
3. 認証方式 — 自前端末トークン（実装済み）か Cloudflare Access か
4. ダッシュボードの配信方法

> **デプロイ前に #1 と #2 を決めること。** #1 は価値判断、
> #2 は無料枠の数値（Workers の CPU 10ms が実際に足りるか）。
