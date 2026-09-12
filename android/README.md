# SelfKaizen — Android 自己監視アプリ

自分のスマホ利用時間を記録して可視化する個人用アプリ。
**強制ブロックや通知は行わない。記録と可視化のみ。**


---

## 現在の実装状況

| Phase | 内容 | 状態 |
|---|---|---|
| 1 | プロジェクト雛形 + 権限導線 | **完了・実機検証済み** |
| 2 | UsageStats 収集ロジック（ステートマシン） | **完了・実データで精度検証済み** |
| 2 | Room スキーマ + DAO | 完了 |
| 3 | WorkManager 定期収集 | 完了（実装は15分。→ **12時間＋ローカル通知に設計変更予定**） |
| 4 | 実機でのデータ検証 | **未着手（最重要）** |
| 5 | ルールエンジン（ロジック） | **完了・テスト16件**（`rules/RuleEngine.kt`） |
| 5 | 設定画面（上限・接近閾値） | 未着手 |
| 6 | ダッシュボード | 未着手 |

---

## 何が記録されるか

**「アプリが使われた」と判定するのは、そのアクティビティが画面に表示され
フォーカスを持っている間だけ**です。実測で確認済み:

| 対象 | 記録 |
|---|---|
| 画面に表示して操作しているアプリ | **記録される** |
| 画面OFF中 / ロック中 | 記録されない |
| **バックグラウンドのアプリ（サービス・同期・プッシュ）** | **記録されない** |
| **フォアグラウンドサービス（音楽再生等）** | **記録されない** |

検証の詳細（実験手順と実測値）は `../docs/RECORDING-BEHAVIOR.md` を参照。

---

## 対応OS

| OS | 状態 |
|---|---|
| Android 16 (API 36) | **検証済み・動作確認済み**（実機のOS） |
| Android 11 (API 31) | 検証済み |
| Android 8.0 (API 26) | minSdk。実機未検証 |


**導入時の注意:** インストール後に**必ず一度アプリを開く**こと。
`adb install` だけでは「未使用アプリ」と判定され、
スタンバイバケット NEVER(50) に分類されて収集が延期される。
ランチャーから起動すると ACTIVE(10) になる。

---

## 環境

実測済みの前提:

```
macOS 26.6.2 (arm64) / JDK 17.0.10 / Android Studio インストール済み
Android SDK: ~/Library/Android/sdk  (Platform 34, Build-Tools 35.0.0)
エミュレータ: Pixel_7_Pro_API_33 (google_apis_playstore, arm64-v8a)
```

`android/local.properties` に SDK パスを設定済み（無い場合は要作成）:
```properties
sdk.dir=/Users/<あなたのユーザー名>/Library/Android/sdk
```

### ビルド構成

| 項目 | バージョン | 備考 |
|---|---|---|
| Gradle | 8.13 | wrapper 同梱 |
| AGP | 8.7.3 | |
| Kotlin | 2.0.21 | Compose プラグイン込み |
| KSP | 2.0.21-1.0.28 | Room のアノテーション処理 |
| compileSdk / targetSdk | 34 | |
| minSdk | 26 | |
| Room | 2.6.1 | |
| Compose BOM | 2024.10.01 | |

---

## テスト

```bash
cd android
./gradlew :app:testDebugUnitTest
```

### テスト結果（2026-09-14 実行）

```
SyncClientTest:             22 tests, 0 failures
FocusIntervalBuilderTest:   21 tests, 0 failures
IntervalBuildingTest:       21 tests, 0 failures
TimezoneStabilityTest:      18 tests, 0 failures
BatchBuilderTest:           17 tests, 0 failures
RuleEngineTest:             16 tests, 0 failures
DurationFormatTest:         12 tests, 0 failures
SettingsMappingTest:        11 tests, 0 failures
DailyAggregatorTest:         8 tests, 0 failures
ServerContractIntegrationTest:  2 tests, 0 failures（要: 開発サーバー起動）
合計 148 tests, 0 failures
+ instrumented test 6件（Android 11 / Android 16 の両方で成功）
```

### サーバー契約テスト

`ServerContractIntegrationTest` は**開発サーバーが起動していないとスキップ**されます。
実行するには:

```bash
# ターミナル1
cd server && node --experimental-strip-types dev-server.ts
# ターミナル2
cd android && ./gradlew :app:testDebugUnitTest
```

サーバー起動時は **148 tests / スキップ0**、未起動時は 2件スキップになります。

### 何を検証しているか

`FocusIntervalBuilder`（UsageEvents → 前面滞在区間の変換）:
- 通常の開始/終了
- **PAUSED が来る前に次の RESUMED が来た場合の暗黙クローズ**（二重計上防止）
- **合計時間が重複で膨らまないこと**
- **`END_OF_DAY` で閉じ、翌日 `CONTINUE_PREVIOUS_DAY` で再開する**（日付境界）
- **`DEVICE_SHUTDOWN` をまたぐ開区間の破棄**
- 未クローズ区間の `STALE_SWEEP` 確定
- `ACTIVITY_STOPPED` / `SCREEN_*` を無視すること（終了点は PAUSED のみ）
- `packageName` が null のイベントの無視
- **画面OFF / ロック中の時間の除外**
- **複数アクティビティを持つアプリの精度**

`DailyAggregator`（区間 → 日次集計）:
- 同一日内の集計
- **日付境界をまたぐ区間の2日への分割**
- **複数日にまたがる区間の分割と、合計が元区間長と一致すること**
- 複数アプリの個別集計
- `endTime` が null / 不正な区間の除外
- タイムゾーン指定が機能すること

### テストの検証（ミューテーションテスト）

テストが実際に機能することを確かめるため、**意図的に誤った実装を注入して
検知されるか**を確認している:

| 注入した誤実装 | 検知したテスト |
|---|---|
| `END_OF_DAY` の処理を無効化 | `END_OF_DAYで閉じ翌日のCONTINUE_PREVIOUS_DAYで再開する` |
| 暗黙クローズ（二重計上防止）を削除 | `合計時間が区間の重複で膨らまない` 他1件 |
| `DEVICE_SHUTDOWN` で破棄せず閉じてしまう | `DEVICE_SHUTDOWNをまたぐ開区間は破棄される` |
| 日付分割をやめて開始日に全計上 | `日付境界をまたぐ区間は2日に分割される` 他1件 |

4件すべてが検知された。テストは実際に機能している。

---

## エミュレータでの動作確認

```bash
export ANDROID_HOME=~/Library/Android/sdk
~/Library/Android/sdk/emulator/emulator -avd Pixel_7_Pro_API_33 -no-boot-anim &
~/Library/Android/sdk/platform-tools/adb wait-for-device

cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.selfkaizen.app/.MainActivity
```

### 権限の付与（手動）

```bash
# 許可
adb shell appops set com.selfkaizen.app android:get_usage_stats allow
# 剥奪して未許可状態を再現
adb shell appops set com.selfkaizen.app android:get_usage_stats deny
```

アプリ画面で「未許可 → 設定を開く」ボタンが出ること、
許可後に「許可済み」に変わることを確認済み。

---

## Phase 3: 定期収集

`UsageCollectorWorker`（WorkManager, 15分間隔）で自動収集する。
アプリを閉じても、端末を再起動しても継続する。

### 仕組み

| 要素 | 役割 |
|---|---|
| `UsageCollectorWorker` | 15分ごとに収集を実行（`KEEP` 戦略で多重登録を防ぐ） |
| `UsageCollector` | 読み取り → 区間構築 → 冪等保存 → 日次集計の再生成 |
| `UsageStatsReader` | `UsageStatsManager` から1日単位に分割して読み取り |
| `BootReceiver` | 再起動後にスケジュールを再登録＋即時収集 |
| `MainActivity` | 起動時にスケジュール登録＋即時収集 |

### 冪等性の担保

WorkManager は遅延・再実行・**同時実行**があり得る。
実測でも2つの Worker が同時に走る場面を確認した。

- `app_usage_event` の一意インデックス `(packageName, startTime)` ＋ `IGNORE` 挿入
- `screen_off_period` の一意インデックス `(startTime, endTime)`

実測結果: 37行 / 37 distinct キー（重複ゼロ）。

### 差分収集

`collection_state` に前回の収集時刻を保存し、そこから
安全マージン（5分）だけ遡って読み直す。
Doze で実行が数時間遅れても、その間のデータを補填できる。

### エミュレータでの検証結果（2026-09-14）

```
収集完了: events=268 intervals=31 inserted=0  summaries=2   ← 初回
収集完了: events=52  intervals=4  inserted=3  summaries=3   ← 2回目
収集完了: events=61  intervals=4  inserted=3  summaries=3   ← 3回目
```

再起動テスト:
```
BootReceiver: 再起動を検知。収集スケジュールを再登録する
収集完了: events=62 intervals=1 inserted=0 summaries=3
```

---

## 重要な設計上の注意

これらは AOSP 実ソースで確認済み。

1. **`ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` の API 28 分岐は不要。**
   AOSP で `MOVE_TO_FOREGROUND` の別名（同値1/2）と定義されているため、
   コンパイル時にインライン展開され minSdk 26 でも動作する。

2. **`END_OF_DAY`(3) / `CONTINUE_PREVIOUS_DAY`(4) は必須。**
   `@hide` のためリテラル値を使う。これを処理しないと
   深夜をまたいだ使用が欠損するか翌日へ混入する。
   `UsageStats.java` の公式集計実装が `END_OF_DAY` を
   「使用時間を確定させる点」として扱っていることを確認済み。

3. **`queryEvents` は数日分しか保持されない。**
   公式 Javadoc に「Events are only kept by the system for a few days」。
   **毎日収集を回して自前 DB に蓄積する必要がある。遡及補填はできない。**

4. **直近数分のイベントは切り捨てられる。**
   公式 Javadoc に「The last few minutes of the event log will be truncated」。
   リアルタイム表示は参考値として扱うこと。

5. **終了点は `ACTIVITY_PAUSED` のみを使う。**
   `ACTIVITY_STOPPED` / `ACTIVITY_DESTROYED` を混ぜると過大計上になる。

---

## 次の作業

1. **Phase 3**: WorkManager による15分間隔の定期収集（差分・冪等 upsert）
2. **Phase 4**: **実機でのデータ検証（最優先）**
   - 自前集計 ≒ `queryUsageStats().getTotalTimeInForeground()` の一致確認
   - 日付境界（`END_OF_DAY`）と再起動の実地検証
3. Phase 5: ルールエンジン
4. Phase 6: ダッシュボード

実機を接続する際は USB デバッグを有効にすること。
`adb devices` で認識されることを確認してから Phase 4 に進む。
