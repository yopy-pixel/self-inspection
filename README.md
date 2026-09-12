# SelfKaizen

スマートフォンの利用時間を記録して可視化する、個人用の自己監視ツール。

**記録と可視化に徹し、強制ブロックや通知は行わない。**
自分の使い方を自分で見て判断するための「鏡」であって、自分を縛る「警官」ではない。

---

## 構成

```
android/   スマホ側の収集アプリ（Kotlin / Jetpack Compose / Room）
server/    集約サーバー（Cloudflare Workers + D1）
docs/      設計・運用ドキュメント
```

| 領域 | 技術 |
|---|---|
| Android | Kotlin 2.0 / Jetpack Compose / Room / WorkManager |
| サーバー | TypeScript / Cloudflare Workers / D1 (SQLite) |
| ビルド | Gradle 8.13 / AGP 8.7 / Node.js 22 |

---

## 何をするか

Android の `UsageStatsManager` から使用状況を収集し、
アプリ別・日別の利用時間を記録する。15分ごとに自動収集し、
端末内に貯めてから、設定した場合のみサーバーへ同期する。

### 何が記録されるか

「アプリが使われた」と判定するのは、**そのアクティビティが画面に表示され
フォーカスを持っている間だけ**。

| 対象 | 記録 |
|---|---|
| 画面に表示して操作しているアプリ | **記録される** |
| 画面OFF中 / ロック中 | 記録されない |
| バックグラウンドのアプリ（サービス・同期・プッシュ） | **記録されない** |
| フォアグラウンドサービス（音楽再生など） | **記録されない** |

---

## 設計上の特徴

- **生イベントと集計を分離** — 後からルールを変えても過去を再評価できる
- **端末内で完結** — サーバーは任意。送信しなくても全機能が動く
- **冪等な書き込み** — 再実行・同時実行でも二重計上しない
- **日付は端末が確定** — タイムゾーンが変わっても過去の記録が動かない
- **実測と推定を区別** — 区間の閉じ方（`closeReason`）を保持する

---

## 使い始める

### Android

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. アプリを開く
2. 「使用状況へのアクセス」を許可する
3. 必要に応じてバッテリーを「制限なし」に設定する

詳細は [`android/README.md`](android/README.md) を参照。

### サーバー（任意）

```bash
cd server
npm install
npm test        # 44 tests
npm run dev     # ローカル開発サーバー
```

デプロイ手順は [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) を参照。

---

## テスト

```bash
cd android && ./gradlew :app:testDebugUnitTest   # 162 tests
cd server  && npm test                            # 44 tests
cd server  && npm run probe                       # セキュリティ検証 46項目
```

---

## ドキュメント

| 文書 | 内容 |
|---|---|
| [`android/README.md`](android/README.md) | アプリの構成・ビルド・テスト |
| [`server/README.md`](server/README.md) | API 仕様・デプロイ |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | 本番デプロイ手順 |
| [`docs/PRIVACY.md`](docs/PRIVACY.md) | 送信するデータと送らないデータ |
| [`docs/RECORDING-BEHAVIOR.md`](docs/RECORDING-BEHAVIOR.md) | 記録の判定基準（実測に基づく） |
| [`docs/design/SPEC.md`](docs/design/SPEC.md) | UI デザイン仕様 |

---

## ライセンス

個人用のツールです。ライセンスは定めていません。
