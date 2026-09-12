# 実機で動かす手順

対象: Android 実機（minSdk 26 = Android 8.0 以上）
アプリID: `com.selfkaizen.app`

> エミュレータではなく**実機**で動かすための手順。
> サーバーへのデプロイは `DEPLOYMENT.md` を参照。

---

## 0. 先に知っておくこと

**このアプリは「インストールするだけ」では動きません。**
OS の特別な許可が2つ必要で、どちらも**手動**です。

| 必要なもの | なぜ必要か | 自動化 |
|---|---|---|
| **使用状況へのアクセス** | アプリ別の使用時間を読むため。ランタイム権限ダイアログでは**取れない** | `adb` で代用可 |
| **電池最適化からの除外** | 15分ごとの収集が OS に止められないようにする | **手動のみ** |

特に2つ目を忘れると、**数日後にデータが止まります**。
最初は動いて見えるので気づきにくいのが厄介な点です。

---

## 1. USB デバッグを有効にする

実機側の操作:

1. **設定 → 端末情報 → ビルド番号** を**7回**タップ
   → 「開発者向けオプションが有効になりました」と出る
2. **設定 → システム → 開発者向けオプション** を開く
3. **USB デバッグ** を ON
4. （推奨）**USB 経由のアプリのインストール**も ON
   ※ 一部端末（Xiaomi 等）は別途「USB 経由でのインストール」の許可が必要

> **Xiaomi / Redmi の場合:** 「開発者向けオプション」に加えて
> **設定 → パスワードとセキュリティ → プライバシー** のあたりに
> 「USB デバッグ（セキュリティ設定）」があり、そこでも ON が必要なことがあります。
> また Xiaomi は **MI アカウントの挿入とネットワーク接続**を要求します。

---

## 2. 接続を確認する

USB ケーブルで Mac に接続（**充電専用ケーブルでは認識しません**。データ転送対応のものが必要）。

```bash
cd android

# adb にパスを通す（毎回必要なし）
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

# 認識確認
adb devices -l
```

**期待する出力:**
```
List of devices attached
XXXXXXXX    device product:... model:... device:...
```

- **何も出ない** → ケーブル / USBデバッグ / 端末側の「この PC を許可しますか？」ダイアログを確認
- **`unauthorized` と出る** → 端末側のダイアログで「許可」を押す。出ない場合は
  `adb kill-server && adb start-server` してから再接続
- **`offline`** → ケーブルを抜き差し

---

## 3. ビルドしてインストールする

```bash
cd android

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **debug APK で問題ありません。** release ビルドは署名設定が未実装のため、
> 実機で自分で使うだけなら debug で十分です（`DEPLOYMENT.md` の付録を参照）。

**既にインストール済みで更新する場合**も `-r` で上書きできます。
**データは保持されます**（アンインストールすると消えます）。

---

## 4. 使用状況へのアクセスを許可する（必須）

```bash
adb shell appops set com.selfkaizen.app android:get_usage_stats allow

# 確認（allow と出れば成功）
adb shell cmd appops get com.selfkaizen.app android:get_usage_stats
```

**または端末側で手動:**

1. アプリを起動すると「Usage access: not granted」と表示される
2. **Open settings** をタップ
3. **使用状況へのアクセス** → **SelfKaizen** → **許可**
4. アプリに戻ると「Usage access: granted」に変わる

> 端末によっては設定画面へ直接飛べず、設定トップに落ちることがあります。
> その場合は **設定 → アプリ → 特別なアクセス → 使用状況へのアクセス** から辿ってください。

---

## 5. 電池最適化から除外する（必須・忘れやすい）

**これをしないと、数日後に収集が止まります。**

### 標準的な Android

1. **設定 → アプリ → SelfKaizen → バッテリー**
2. **制限なし / 最適化しない** を選ぶ

### メーカー別（重要）

| メーカー | 追加で必要な操作 |
|---|---|
| **Xiaomi / Redmi** | 設定 → アプリ → SelfKaizen → **バッテリーセーバー: 制限なし**、さらに **自動起動** を ON |
| **Samsung** | 設定 → バッテリー → バックグラウンド使用制限 → **アプリをスリープさせない** に追加 |
| **OPPO / Realme** | 設定 → バッテリー → **アプリの省電力** → SelfKaizen を許可、**自動起動** を ON |
| **Huawei** | 設定 → バッテリー → **アプリ起動管理** → 手動管理 → 3つすべて ON |
| **Pixel / 素の Android** | 上記の標準手順のみで通常は十分 |

> **アプリ側に「除外を要求する」ボタンはありません。**
> Play ポリシー上、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を直接要求するのは
> 原則禁止されているためです（個人用途なら使えるが、方針として入れていない）。
> 設定画面から手動で除外してください。

---

## 6. 動作確認

### 6-1. すぐに確認できること

```bash
# アプリを起動（初回収集が走る）
adb shell am start -n com.selfkaizen.app/.MainActivity
```

アプリの画面で確認:
- **リング**に今日の合計が出ている
- **最終更新**（画面下部）が現在時刻に近い
  → `updated 14:05` のように出れば収集が動いている

### 6-2. 収集が実際に走ったか（DBを直接見る）

```bash
adb shell "run-as com.selfkaizen.app ls databases/"
```

### 6-3. 数分使ってから再確認

端末で数分アプリを操作 → アプリに戻る → リングの数字が増えていれば正常。

### 6-4. 最初の検証項目（`PLAN-android.md` Phase 4 より）

| 項目 | 方法 |
|---|---|
| アプリ別使用時間が公式値と合うか | 端末の **設定 → デジタルウェルビーイング** と画面の数値を比較 |
| 画面OFF中の時間を計上していないか | 画面を消して数分放置 → 数字が増えていないこと |
| 日付境界をまたぐ分割 | 深夜0時をまたいで使う（`DailyAggregator` の検証） |

> **`END_OF_DAY` を探す必要はありません。** この値は `queryEvents()` に
> 公開されないことが AOSP 実装で確定しています（`PLAN-android.md` の訂正を参照）。

---

## 7. サーバー同期を使う場合（任意）

**アプリは同期なしでも完全に動きます。** データは端末内に留まります。

本番は `DEPLOYMENT.md` の手順で Cloudflare にデプロイし、
**HTTPS のエンドポイント**を設定画面に入力してください。

### デプロイせずに試す（ローカルサーバー + `adb reverse`）

実機から `localhost` は「端末自身」を指すため、そのままでは Mac のサーバーに届きません。
`adb reverse` でポートを転送すると届きます。

```bash
# 1) Mac 側で開発サーバーを起動
cd server
npm run dev          # = node --experimental-strip-types dev-server.ts

# 2) 実機の localhost:8787 を Mac の 8787 に転送
adb reverse tcp:8787 tcp:8787
```

アプリの設定画面で:
- **Endpoint (https)** … `http://localhost:8787`
  ※ debug ビルドのみ平文が `localhost` に限り許可されています。
     **release ビルドでは平文禁止**なので、この方法は debug 専用です
- **Device ID** / **Token** … サーバーの登録 API で得た値
- **Sync to server** を ON → **Test connection** で成功を確認

> `adb reverse` は **USB 接続を外すと無効**になります。

### PC のブラウザから見る（ペアコード）

**端末トークンを手入力する必要はありません。**
アプリの設定 → **Pair a browser** → 出た8文字を PC のログイン画面に入力します
（3分・1回限り）。詳しくは `DEPLOYMENT.md` §7「ブラウザ（PC・スマホ）からのログイン」。

ログインしたブラウザはサーバーの **Browsers** に並び、**1台ずつ失効**できます
（`Revoke`）。複数の PC・スマホを前提にしているので、台数に制限はありません。

### 画面を触らずに設定を入れる（`adb` 注入・debug 限定）

Xiaomi の MIUI/HyperOS は `adb shell input tap` / `text` を
`INJECT_EVENTS` 権限の欠如として拒否します。実機で手入力する代わりに、
**起動 Intent の extra で同期設定を渡せます。**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
TOKEN=...                       # 端末トークン（管理トークンではない）

"$ADB" shell am force-stop com.selfkaizen.app
"$ADB" shell am start -n com.selfkaizen.app/.MainActivity \
  --es endpoint "https://self-kaizen.yoshitashou.workers.dev" \
  --es deviceId "<deviceId>" \
  --es token    "$TOKEN" \
  --ez enableSync true
```

成功すると logcat に次が出ます（**トークンは出ません**）:

```
MainActivity: adb から同期設定を保存した (endpoint=https://...)
SyncWorker:   同期結果: 挿入=1208 重複=0 バッチ=3
```

```bash
"$ADB" logcat -d -s MainActivity:* SyncWorker:* | tail
```

仕様と安全策:

| 項目 | 挙動 |
| --- | --- |
| 有効になるビルド | **debug のみ**（`FLAG_DEBUGGABLE` を実行時に判定。release では即 return） |
| 上書き範囲 | 渡した extra だけ。渡さなかった項目は既存値を保持 |
| 不完全な設定 | endpoint / deviceId / token / enabled が揃わなければ**保存しない** |
| ログ | endpoint のみ。**token と deviceId は出さない** |
| 同期の起動 | この関数では投げない。直後の通常の配線（`isConfigured` → `syncNow`）が実行する |

> コマンドラインにトークンが残るため、共有端末のシェル履歴では
> `history -c` するか、`TOKEN=$(security find-generic-password ...)` のように
> 変数経由で渡してください。

> **注意:** debug ビルドではこの Activity が exported のため、
> 他アプリからも同じ extra を送れば設定を書き換えられます。
> 検証用の割り切りで、release ではこの経路ごと無効になります。

---

## 8. トラブルシューティング

| 症状 | 原因と対処 |
|---|---|
| `adb devices` に何も出ない | ケーブルが充電専用 / USBデバッグが OFF / 端末側の許可ダイアログ未応答 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 署名が違う版が入っている。`adb uninstall com.selfkaizen.app` してから再インストール（**データは消える**） |
| `INSTALL_FAILED_USER_RESTRICTED` | Xiaomi 等。開発者向けオプションの「USB 経由でのインストール」を ON |
| 画面が「Usage access: not granted」のまま | 手順4を実施。設定画面で許可してもアプリに戻るまで反映されないことがある（アプリを再起動） |
| リングが `0m` のまま | 使用状況アクセスが未許可 / 収集前。アプリを再起動すると初回収集が走る |
| **数日後にデータが止まった** | **手順5（電池最適化の除外）漏れ**。最終更新が古い日付になっていればこれ |
| 同期が失敗する | エンドポイントが `https://` か / トークンが正しいか / `error: endpoint must be https://` は平文禁止によるもの |

---

## 9. アンインストールとデータ

```bash
# データを残して更新
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 完全に消す（データも消える）
adb uninstall com.selfkaizen.app
```

> **データのバックアップ手段は現状ありません**（エクスポート未実装）。
> アンインストール・端末紛失でデータは失われます。
> サーバー同期を有効にしていればサーバー側に残ります。
> 詳細は `OPEN-ISSUES.md` の B-2 / B-3 を参照。
