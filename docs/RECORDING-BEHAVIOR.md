# 何が記録されて、何が記録されないか

**検証日:** 2026-09-14
**検証環境:** Android 16 (API 36) エミュレータ
**目的:** 「バックグラウンドで動いているものは記録されないか」を実測で確定させる

---

## 結論

| 対象 | 記録されるか |
|---|---|
| 画面に表示され、操作しているアプリ | **記録される** |
| 前面にあるが画面OFF | **記録されない** |
| ロック画面表示中 | **記録されない** |
| **バックグラウンドで動くアプリ（サービス・同期・プッシュ）** | **記録されない** |
| **フォアグラウンドサービス（音楽再生・ダウンロード等）** | **記録されない** |
| 自アプリがバックグラウンドで収集を実行すること | **動作する**（これ自体は「使用時間」ではない） |

**「アプリが使われた」と判定するのは、そのアプリのアクティビティが
画面に表示されフォーカスを持っている間だけ**です。

---

## 実験A: バックグラウンドで動くアプリは記録されない

### 手順

エミュレータで何も操作せずに収集を実行した。
この時点で以下がバックグラウンドで稼働していた:

- `com.google.android.gms`（Google Play Services）
- `com.google.android.googlequicksearchbox`（Googleアプリ）
- `com.google.android.apps.messaging`（メッセージ）
- `com.google.android.as`（Android System Intelligence）
- `com.android.systemui`
- `com.google.android.networkstack.tethering`

### 結果

```
収集完了: events=280 intervals=2 inserted=2 swept=0
```

**280件のイベントを読んだのに、記録されたのは2件だけ:**

| パッケージ | 記録 |
|---|---|
| `com.google.android.googlesdksetup` | 0秒（一瞬のアクティビティ） |
| `com.selfkaizen.app` | 未クローズ（自アプリが前面） |

**バックグラウンドで動いていた6アプリは、1つも記録されていない。**

---

## 実験C: 前面10秒 → 背面20秒

### 手順

1. 設定アプリを前面に出す
2. 10秒待機
3. HOME キーで背面に送る
4. 20秒待機
5. 収集

### 結果

```
com.android.settings                  | 12秒 | 22:48:57 | PAUSED
com.google.android.apps.nexuslauncher | 28秒 | 22:49:09 | PAUSED
```

- 設定アプリは **22:48:57 → 22:49:09 の12秒**で終了
- 22:49:09 は **HOME キーを押した時刻**と一致
- **背面にいた20秒は記録されていない**

---

## 実験D: 前面のまま画面OFF 20秒

### 手順

1. 設定アプリを前面に出す
2. 電源キーで画面OFF
3. 20秒待機
4. 電源キーで画面ON
5. 収集

### 結果

```
com.android.settings |  3秒 | 22:49:58   ← 画面OFFの前
com.android.settings | 12秒 | 22:50:22   ← 画面ONの後

screen_off_period | 22:50:02 → 22:50:22 | 20秒   ← 除外された
```

**画面OFF中の20秒は使用時間に含まれていない。**
区間は画面OFFの時点で2つに分断されている。

### OS自身が PAUSED を出している

`dumpsys usagestats` で実イベントを確認した:

```
22:50:02  type=SCREEN_NON_INTERACTIVE  package=android
22:50:02  type=ACTIVITY_PAUSED         package=com.android.settings   ← 区間が閉じる
22:50:02  type=ACTIVITY_STOPPED        package=com.android.settings

22:50:22  type=SCREEN_INTERACTIVE      package=android
22:50:22  type=ACTIVITY_RESUMED        package=com.android.settings   ← 再開
```

**画面OFF時に OS が `ACTIVITY_PAUSED` を発行している。**

---

## なぜバックグラウンドが記録されないのか（実装）

本アプリは `UsageEvents` の以下のイベント**だけ**を使う:

| イベント | 扱い |
|---|---|
| `ACTIVITY_RESUMED` | 使用開始（画面に表示されフォーカスを持った） |
| `ACTIVITY_PAUSED` | 使用終了 |
| `END_OF_DAY` / `CONTINUE_PREVIOUS_DAY` | 日付境界 |
| `DEVICE_SHUTDOWN` / `DEVICE_STARTUP` | 再起動 |
| `SCREEN_NON_INTERACTIVE` / `KEYGUARD_SHOWN` | 除外期間の開始 |
| `SCREEN_INTERACTIVE` / `KEYGUARD_HIDDEN` | 除外期間の終了 |
| `ACTIVITY_STOPPED` / `ACTIVITY_DESTROYED` | **無視**（終了点に使うと過大計上） |
| **`FOREGROUND_SERVICE_START` / `STOP`** | **無視**（使用時間ではない） |
| `STANDBY_BUCKET_CHANGED` 等 | 無視 |

**`ACTIVITY_*` はアクティビティのライフサイクルに対応する。**
バックグラウンドのサービスはアクティビティを持たないため、
そもそも `ACTIVITY_RESUMED` が発行されない。

フォアグラウンドサービス（音楽再生など）は
`FOREGROUND_SERVICE_START` を発行するが、これは**意図的に無視**している。
画面を見ていない音楽再生を「アプリの使用」に数えるのは不自然なため。

---

## 二重の安全機構

画面OFFを除外する仕組みは2つある:

1. **OSが `ACTIVITY_PAUSED` を発行する**（一次機構）
   画面OFF時にアクティビティが一時停止するため、区間が自然に閉じる。
2. **`SCREEN_NON_INTERACTIVE` を記録して差し引く**（二次機構）
   OSが `PAUSED` を出さないケースや、
   `PAUSED` を取りこぼした場合の安全網。

集計時（`subtractScreenOff`）に両方を適用するため、
片方が欠けても過大計上しない。

> **なぜ両方必要か:** エミュレータでの実データ検証で、
> `SCREEN_NON_INTERACTIVE` を無視した実装が
> 実測で**約3000倍**の過大計上を起こすことを確認している。

---

## 記録されないもの（まとめ）

- バックグラウンドサービス（同期・プッシュ通知・位置情報など）
- フォアグラウンドサービス（音楽再生・ファイル転送など）
- 画面OFF中の時間
- ロック画面表示中の時間
- 他アプリのバックグラウンド動作

## 記録されるもの

- 画面に表示され、操作しているアプリの時間
- 動画視聴など、画面を眺めている時間（タッチ操作がなくても、
  アクティビティが前面にあれば記録される）

> **補足:** 「操作していないが画面を見ている」時間は記録される。
> これは意図した挙動であり、動画視聴や読書も「使用」に含めるため。
> タッチ操作の有無で分けたい場合は `USER_INTERACTION` イベントを使う拡張が考えられる。

---

## 検証に使ったコマンド

```bash
# バックグラウンドで動いているサービス一覧
adb shell dumpsys activity services | grep "^  \* ServiceRecord"

# 実イベントの生ログ（何が発行されているか直接見る）
adb shell dumpsys usagestats | grep -E "type=(ACTIVITY_|SCREEN_|KEYGUARD_)"

# イベント種別の集計
adb shell dumpsys usagestats | grep -oE "type=[A-Z_]+" | sort | uniq -c | sort -rn

# 記録結果の確認
adb shell "sqlite3 /data/data/com.selfkaizen.app/databases/selfkaizen.db \
  \"SELECT packageName, (endTime-startTime)/1000, closeReason FROM app_usage_event ORDER BY startTime;\""
```
