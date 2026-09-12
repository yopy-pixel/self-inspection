# デプロイ手順書（Cloudflare Workers + D1）

**対象:** `server/`（集約サーバー）
**状態:** ✅ **デプロイ済み（2026-09-14）**

| 項目 | 値 |
|---|---|
| URL | `https://self-kaizen.yoshitashou.workers.dev` |
| Worker 名 | `self-kaizen` |
| D1 | `self-kaizen`（`e5cc48b5-cf15-4a5e-b9ef-3d17587e1b74`、APAC） |
| Version ID | `029eeab4-db31-4c9c-b987-620f1587e846`（ペアコード：ブラウザ・端末） |

**接続情報（Android アプリに入力する値）** は `server/.env.local` に保存されている
（`.gitignore` の `server/.env.*` で除外済み）。

このドキュメントの手順を上から実行すればデプロイできる。
**「ここは私（AI）ができない」箇所を明示**している。

---

## 0. 現在の到達点

| 項目 | 状態 |
|---|---|
| サーバー実装 | **完了** |
| 依存関係の脆弱性 | **0件**（wrangler 4 / workers-types 5 に更新済み） |
| 型検査 `npm run typecheck` | **0エラー** |
| サーバーテスト `npm test` | **98件パス** |
| デプロイのドライラン | **成功**（14.03 KiB / gzip 4.20 KiB） |
| D1 マイグレーション（ローカル） | **成功**（9コマンド） |
| Android ↔ サーバー契約テスト | **実通信で成功** |
| `.gitignore` | **作成済み** |
| **Cloudflare アカウント** | ✅ 取得済み（`Yoshitashou@gmail.com`） |
| **D1 の `database_id`** | ✅ 設定済み（`wrangler.toml` に反映） |
| **本番シークレット** | ✅ 設定済み（`ADMIN_TOKEN`） |
| **本番マイグレーション** | ✅ 適用済み（`0001` `0002_browser_session` `0003_pair_code_kind`） |

> **AI が代行できたのは、あなたが `wrangler login` で OAuth を通した後のみ。**
> 認証だけはアカウントに紐づくため代行できない。

---

## 0.5 デプロイ方法は2つある（git連携 / CLI）

**どちらでも同じ Worker ができる。** ただし**自動化される範囲が違う。**

### 方法A: git連携（Workers Builds）— push で自動デプロイ

Cloudflare のダッシュボードで GitHub リポジトリを接続する方式。
`main` に push するたびに自動でビルド・デプロイされる。

**⚠️ `Root directory` は接続画面には無い。**

公式ドキュメント（Workers Builds > Configuration）で確認した事実:

> Build settings can be found by navigating to **Settings** > **Build** within your Worker.

つまり **Worker を作成した後**に現れる設定。リポジトリを接続する画面には
`Root directory` の欄が無いため、そこで探しても見つからない。

**手順:**

1. **Workers & Pages → Create → Connect to Git** でリポジトリとブランチを選ぶ
   （この時点では Root directory を指定できない）
2. Worker が作成される。**最初のビルドは失敗するか、PR が作られる**（下記の注意を参照）
3. **その Worker の Settings → Build** を開く
4. **`Root directory` に `server` を入力して保存**
5. ビルドを再実行（または push し直す）

**設定値:**

| 項目 | 値 | 場所 |
|---|---|---|
| Git repository | `yopy-pixel/self-inspection` | 接続画面 |
| Git branch | `main` | 接続画面 |
| **Root directory** | **`server`** | **Settings → Build**（作成後） |
| Build command | `npm install` | Settings → Build |
| Deploy command | `npx wrangler deploy`（既定） | Settings → Build |

> **`Root directory` を設定しないと自動構成が走る。**
> 公式ドキュメントより:
> 「If your repository does not have a Wrangler configuration file, the deploy command
> (`wrangler deploy`) will trigger **automatic project configuration**.
> This detects your framework, creates the necessary configuration, and
> **opens a pull request** for you to review.」
>
> リポジトリ直下に `wrangler.toml` が無いため（`server/` にある）、
> **フレームワーク自動検出が走って意図しない PR が作られる。**
> これが起きたら Root directory を設定して PR を閉じる。

**この方式の利点:** push するだけで反映される。ローカルに認証情報を持たなくてよい。

### 方法B: wrangler CLI — 手元から手動デプロイ

このドキュメントの §1〜§5 の手順。`npx wrangler deploy` を手元で実行する。

**利点:** 失敗したときエラーがその場で見える。初回はこちらが確実。

### ⚠️ git連携でも自動化されないもの

**ここが最も誤解しやすい点。**

| 項目 | なぜ自動化されないか | 対処 |
|---|---|---|
| **D1 の `database_id`** | `wrangler.toml` に**書いてコミット**する必要がある | 後述の手順で設定してコミット |
| **`ADMIN_TOKEN`** | シークレット。**リポジトリに置けない** | `npx wrangler secret put ADMIN_TOKEN`、またはダッシュボードの **Settings → Variables and Secrets** |
| **D1 マイグレーション** | ビルド手順に含まれない | `npx wrangler d1 migrations apply self-kaizen --remote` |

つまり **git連携にしても、初回の「D1作成・ID記入・シークレット登録・マイグレーション」は手作業**。
2回目以降のコード変更だけが自動化される。

> **⚠️「Build variables and secrets」と「Variables and Secrets」は別物。**
> 公式ドキュメントより:
> 「Build variables will **not be accessible at runtime**.
> If you would like to configure runtime variables you can do so in
> **Settings > Variables & Secrets**」
>
> `ADMIN_TOKEN` は**実行時**に必要なシークレットなので、
> `Settings → Build` 側ではなく
> **`Settings → Variables and Secrets`**（または `wrangler secret put`）に入れること。
> ここを間違えると「デプロイは成功するのに認証が通らない」という分かりにくい症状になる。

### 🚧 現状のブロッカー（2026-09-14 時点）

**`server/wrangler.toml` がプレースホルダのままコミットされている:**

```toml
database_id = "REPLACE_WITH_YOUR_DATABASE_ID"
```

この状態で push しても、**git連携のビルドは D1 バインディングを解決できず失敗する。**
（`wrangler deploy` は存在しない DB ID をエラーにする）

**したがって手順は必ず次の順序になる:**

1. `npx wrangler login`
2. `npx wrangler d1 create self-kaizen` → 出力された `database_id` を `wrangler.toml` に貼る
3. **その変更をコミットして push**（git連携を使う場合はここが必須）
4. `npx wrangler d1 migrations apply self-kaizen --remote`
5. `npx wrangler secret put ADMIN_TOKEN`
6. デプロイ（push すれば自動 / または `npx wrangler deploy`）

---

## 1. Cloudflare アカウントと wrangler のログイン

```bash
cd server

# 1) アカウント作成（ブラウザ）
#    https://dash.cloudflare.com/sign-up
#    無料プランでよい。クレジットカード不要。

# 2) ログイン（ブラウザが開く）
npx wrangler login
```

**確認:**
```bash
npx wrangler whoami
# → アカウント名と ID が表示されれば成功
```

---

## 2. D1 データベースの作成

```bash
cd server
npx wrangler d1 create self-kaizen
```

**出力例:**
```
✅ Successfully created DB 'self-kaizen'

[[d1_databases]]
binding = "DB"
database_name = "self-kaizen"
database_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"   ← これをコピー
```

**`wrangler.toml` の `database_id` を置き換える:**

```toml
[[d1_databases]]
binding = "DB"
database_name = "self-kaizen"
database_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"   # ← 実際の値
```

> 現在は `REPLACE_WITH_YOUR_DATABASE_ID` というプレースホルダが入っている。
> **置き換えないとデプロイに失敗する**（意図的にそうしている。
> 誤って他人の DB を指さないため）。

---

## 3. マイグレーションの適用

```bash
cd server

# まずローカルで確認（既に検証済みだが、再確認したい場合）
npx wrangler d1 migrations apply self-kaizen --local

# 本番へ適用
npx wrangler d1 migrations apply self-kaizen --remote
```

**確認（本番のテーブル一覧）:**
```bash
npx wrangler d1 execute self-kaizen --remote \
  --command "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';"
```

期待される出力: `device` / `ingest_batch` / `usage_event` / `daily_summary`

---

## 4. 管理者トークンの設定

端末登録に使うトークン。**これが無いと端末を追加できない。**

```bash
# 強いランダム値を生成（32バイト = 64文字の hex）
openssl rand -hex 32

# シークレットとして登録（貼り付け待ちになる）
npx wrangler secret put ADMIN_TOKEN
```

**確認:**
```bash
npx wrangler secret list
# → [{"name":"ADMIN_TOKEN","type":"secret_text"}]
```

> **重要:** `ADMIN_TOKEN` が未設定の場合、端末登録は
> **503 で無効化される**（`src/index.ts` L92-98）。
> セキュリティのため意図的にそうしている。
> 取り込み（ingest）と集計（summary）は端末トークンで動くため、
> 既に端末があるなら `ADMIN_TOKEN` 無しでも動作する。

---

## 5. デプロイ

```bash
cd server

# まずドライランで最終確認（既に成功を確認済み）
npx wrangler deploy --dry-run

# 本番へデプロイ
npx wrangler deploy
```

**出力例:**
```
Total Upload: 14.03 KiB / gzip: 4.20 KiB
Uploaded self-kaizen (x.xx sec)
Deployed self-kaizen triggers (x.xx sec)
  https://self-kaizen.<あなたのサブドメイン>.workers.dev
```

**そのURLを控える。** Android の設定画面で使う。

---

## 6. 動作確認（デプロイ直後）

> **2026-09-14 に実施済み。** 結果:
>
> | 確認 | 結果 |
> |---|---|
> | `/healthz` (https) | **200** |
> | `/healthz` (http) | **403**（平文拒否） |
> | `/api/v1/summary` 認証なし | **401** |
> | `/api/v1/summary` 誤トークン | **401** |
> | `/api/v1/summary` 端末トークン | **200**（空の集計を返す） |
>
> **注意:** `/api/v1/summary` は**端末トークン**を要求する。
> `ADMIN_TOKEN` では 401 になるのが正しい（admin は端末登録用）。


```bash
# 6-1. 死活確認
curl https://self-kaizen.<サブドメイン>.workers.dev/healthz
# → {"ok":true,"now":...}

# 6-2. HTTPS 必須の確認（http で拒否されること）
curl -s -o /dev/null -w "%{http_code}\n" http://self-kaizen.<サブドメイン>.workers.dev/healthz
# → 403（Cloudflare が http→https にリダイレクトする場合は 301 も可）

# 6-3. 認証なしは拒否されること
curl -s -o /dev/null -w "%{http_code}\n" \
  https://self-kaizen.<サブドメイン>.workers.dev/api/v1/summary
# → 401

# 6-4. 端末を1台登録する
curl -s -X POST https://self-kaizen.<サブドメイン>.workers.dev/api/v1/devices \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN の値>" \
  -d '{"label":"my-phone"}'
# → {"deviceId":"...","token":"...","label":"my-phone"}
#    ★ token はここでしか表示されない。Android の設定画面に貼る。
```

---

## 7. Android への設定

1. アプリの**設定画面**を開く
2. 以下を入力:
   - **エンドポイント**: `https://self-kaizen.<サブドメイン>.workers.dev`
   - **端末ID**: 6-4 で得た `deviceId`
   - **トークン**: 6-4 で得た `token`
3. **同期を有効化**
4. **「接続テスト」**で成功を確認

**確認（サーバー側にデータが届いたか）:**
```bash
curl -s "https://self-kaizen.<サブドメイン>.workers.dev/api/v1/summary" \
  -H "Authorization: Bearer <端末トークン>" | python3 -m json.tool
```

> **画面から入力できない端末（Xiaomi など）**では、`adb shell input` が
> `INJECT_EVENTS` で拒否されます。`DEVICE-SETUP.md` §7
> 「画面を触らずに設定を入れる（`adb` 注入・debug 限定）」を使ってください。

### ブラウザ（PC・スマホ）からのログイン

**端末トークンは手入力しない。** アプリが短命のペアコードを発行し、
ブラウザはそれを引き換える（`server/README.md` §1.6）。

1. アプリの設定 → **Pair a browser** → 8文字のコードが出る（**3分・1回限り**）
2. PC で `https://self-kaizen.<サブドメイン>.workers.dev/` を開く
3. **Pairing code** に打ち込んで `Open`

ログイン後、ダッシュボードの **Browsers** に今ログインしている端末が一覧される。

| 操作 | 結果 |
|---|---|
| `Pair a browser`（アプリ or ダッシュボード） | 新しいブラウザ用のコードを発行 |
| `Revoke`（他ブラウザの行） | **そのブラウザだけ**失効。他は生きたまま |
| `Sign out`（自分の行） | 自分のセッションをサーバー側で失効させて Cookie を消す |

- **Cookie に入るのはセッションだけ**で、端末トークンはブラウザに残りません
- 端末トークンでログインした場合も、セッションが作られて Cookie には入りません
- 旧実装の Cookie（端末トークン）は**セッションに自動昇格**します

> コードは短命・単回使用です。**発行できるのは認証済みの主体だけ**で、
> ログイン画面からは発行できません（できてしまうと、その画面を開いた誰もが
> ログインできてしまい、認証が無意味になります）。

### 端末を増やす（2台目のスマホなど）

**管理トークンも `curl` も要りません。** 同じペアコードの仕組みを使います。

1. **既存の端末**のアプリ → 設定 → **Add a device** → 8文字のコードが出る（3分・1回限り）
2. **新しい端末**のアプリ → 設定 → **Endpoint (https)** にサーバーの URL を入れ、
   **Pairing code** にコードを打ち込んで **Register**
3. `Device ID` と `Token` が自動で埋まり、同期が有効になる（**保存まで自動**）

| コードの種類 | 発行 | 引き換え先 |
|---|---|---|
| ブラウザ用（既定） | アプリ / ダッシュボード | `/`（ログイン画面） |
| 端末用（`{"for":"device"}`） | アプリ / ダッシュボード | 新しい端末のアプリ |

**用途は取り違えられません。** ブラウザ用コードで端末は登録できず（401）、
端末用コードでブラウザにはログインできません（401）。端末コードは
書き込み権限（端末トークン）を配るため、閲覧用より影響が強いからです。

> `ADMIN_TOKEN` での登録（`POST /api/v1/devices`）は**残してあります**。
> 全部の端末と全セッションを失ったときの最後の手段です。

---

## 8. デプロイ後の確認事項

| 項目 | 確認方法 |
|---|---|
| ログ | `npx wrangler tail`（リアルタイム）または Dashboard > Workers > Logs |
| D1 の中身 | `npx wrangler d1 execute self-kaizen --remote --command "SELECT COUNT(*) FROM usage_event;"` |
| 端末一覧 | Dashboard > Workers > D1 > self-kaizen > Console |
| 使用量 | Dashboard > Workers > Metrics（無料枠の消費を確認） |
| ロールバック | `npx wrangler rollback`（直前のバージョンに戻す） |

### 実測（2026-09-14・実機 Xiaomi 25010PN30G / Android 16）

初回同期が成功し、D1 に実データが入ることを確認済み。

| 項目 | 実測値 |
|---|---|
| 同期結果（logcat） | `挿入=1208 重複=0 バッチ=3` |
| 再同期（設定画面） | `挿入=0 重複=0 バッチ=1` — 冪等（重複挿入なし） |
| `/healthz` | 200 `{"ok":true,...}` |
| `/api/v1/summary`（端末トークン） | 200。`dailyTotals` が 3 日分、`appTotals` が実データで返る |
| `/api/v1/summary`（未認証 / 不正トークン） | 401 |
| `http://` でアクセス | 403（HTTPS 必須が効いている） |

---

## 9. 無料枠の目安

| 項目 | 無料枠 | この用途での見込み |
|---|---|---|
| Workers リクエスト | 100,000/日 | 15分ごとの同期 = 約96/日/端末。**余裕がある** |
| Workers CPU | 10ms/リクエスト | 集計は書き込み時のみ。**余裕がある** |
| D1 読み取り | 500万行/日 | 集計は `daily_summary` のみ参照。**余裕がある** |
| D1 書き込み | 100,000行/日 | 1同期で数十行。**余裕がある** |
| D1 容量 | 5GB | 1日数百行 × 数年でも**1GB 未満** |

> **試算の根拠:** 15分間隔 = 96回/日。1回あたり最大でも数十イベント。

---

## 10. トラブルシューティング

| 症状 | 原因と対処 |
|---|---|
| `database_id` エラー | 手順2の置き換えを忘れている |
| デプロイは成功するが 500 | `npx wrangler tail` でログを見る。マイグレーション未適用の可能性 |
| 端末登録が 503 | `ADMIN_TOKEN` 未設定（手順4） |
| 端末登録が 401 | `ADMIN_TOKEN` の値が違う |
| 取り込みが 403 `device mismatch` | 端末ID とトークンの組み合わせが不一致。手順6-4 からやり直す |
| 取り込みが 400 `schemaVersion` | アプリとサーバーのスキーマ版が不一致。`SyncClient.SCHEMA_VERSION` と `server/src/types.ts` を確認 |
| 同期が `NetworkError` | エンドポイントURLの誤り、または端末がオフライン |
| データが届かない | Android の「接続テスト」を実行。`adb logcat \| grep SyncWorker` でログ確認 |

---

## 11. 再デプロイ（コード変更時）

```bash
cd server
npm run typecheck   # 型エラーが無いことを確認
npm test            # 42件パスを確認
npx wrangler deploy
```

**スキーマを変更した場合のみ**、追加で:
```bash
npx wrangler d1 migrations apply self-kaizen --remote
```

> **マイグレーションは追記のみ。** 既存ファイルを編集すると、
> 適用済みの環境と新規環境でスキーマが食い違う。
> 必ず `0002_xxx.sql` のように新しいファイルを追加する。

---

## 12. セキュリティ上の前提（実装済み）

| 対策 | 実装箇所 |
|---|---|
| 平文 HTTP を拒否 | `src/index.ts` の `handle()` 冒頭 |
| トークンは SHA-256 ハッシュのみ保存 | `migrations/0001_init.sql` の `device.token_hash` |
| 管理者トークンのタイミング安全比較 | `src/index.ts` の `timingSafeEqual()` |
| 端末IDとトークンの不一致を拒否 | `src/index.ts` の `handleIngest()` |
| `ADMIN_TOKEN` 未設定なら登録を無効化 | `src/index.ts` の `handleRegisterDevice()` |
| 内部エラーの詳細を返さない | `src/index.ts` の catch 節 |
| 入力の構造検証 | `src/validate.ts` |

**未対応（必要になったら検討）:**
- レート制限（Cloudflare の Rate Limiting ルールで追加可能）
- 端末トークンのローテーション
- 監査ログの長期保存

---

## 13. セキュリティ検証とモンキーテスト

**再実行可能なスクリプトを用意している。**

```bash
# ターミナル1: サーバー起動
cd server && npm run dev

# ターミナル2: 検証実行
cd server && npm run probe
```

**結果（2026-09-14 実施）: 46件成功 / 0件失敗**

| 分類 | 確認した内容 |
|---|---|
| A. 認証 | ヘッダ無し・誤トークン・`Bearer` のみ・Basic・前後空白・端末ID不一致・他端末IDの指定 |
| B. 入力検証 | 壊れたJSON・空ボディ・配列・null・必須欠落・型違い・`schemaVersion` 異常・長すぎるID・2100件 |
| C. 不正イベント | 負の長さ・0秒・遠い未来・過去すぎ・不正な日付・空パッケージ名・null・型違い |
| D. インジェクション | SQL（DROP/UNION）・パストラバーサル・制御文字・改行・巨大Unicode・絵文字 |
| E. 冪等性 | 同一 `batchId` の再送が二重計上しないこと |
| F. 集計の境界 | `from > to`・書式不正・存在しない日付・閏日・極端に広い範囲 |
| G. ルーティング | 未知パス・トラバーサル・誤メソッド・末尾スラッシュ |
| H. 連打 | 同一バッチ10並列・`/healthz` 50連打 |
| I. 情報漏洩 | トークンハッシュの混入・スタックトレース・内部パス |

### 検証で見つかった問題

検出した問題は Issue で管理している（修正済み・close 済み）。
`npm run probe` を再実行すれば同じ検査ができる。

### 本番に対して実行する場合

```bash
PROBE_URL=https://self-kaizen.<サブドメイン>.workers.dev \
PROBE_ADMIN_TOKEN=<ADMIN_TOKEN> \
npm run probe
```

> **注意:** 本番に対して実行するとテストデータが残る。
> ラベルが `probe-` で始まるので、後で D1 から削除できる:
> ```bash
> npx wrangler d1 execute self-kaizen --remote \
>   --command "DELETE FROM device WHERE label LIKE 'probe-%';"
> ```

---

## 付録: Android リリースビルドについて

**現状: 署名設定が未実装。** `android/app/build.gradle.kts` に
`signingConfigs` が無いため、release APK は未署名で作られる。

**実機で使うだけなら debug APK で問題ない:**
```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**release ビルドを作る場合の手順（未実施）:**

```bash
# 1) キーストアを生成（パスワードは自分で決めて保管する）
keytool -genkeypair -v \
  -keystore android/selfkaizen-release.jks \
  -alias selfkaizen \
  -keyalg RSA -keysize 2048 -validity 10000

# 2) android/keystore.properties を作成（.gitignore 済み）
cat > android/keystore.properties <<'EOF'
storeFile=../selfkaizen-release.jks
storePassword=<1で決めたパスワード>
keyAlias=selfkaizen
keyPassword=<1で決めたパスワード>
EOF

# 3) app/build.gradle.kts に signingConfigs を追加
#    （現在この設定が無い。追加する人は他スレッドと調整すること）
```

> **`keystore.properties` と `.jks` は絶対にコミットしない。**
> `.gitignore` で除外済み。
>
> **キーストアを失うとアプリを更新できなくなる**（同じ署名でないと
> 上書きインストールできない）。安全な場所にバックアップすること。

---

## 付録: 現状で「デプロイできない」理由のまとめ

| ブロッカー | 誰が解決するか |
|---|---|
| Cloudflare アカウント | **あなた**（ブラウザ操作） |
| `database_id` の設定 | **あなた**（`wrangler d1 create` の出力を貼る） |
| `ADMIN_TOKEN` の設定 | **あなた**（`wrangler secret put`） |
| それ以外 | **完了済み** |
