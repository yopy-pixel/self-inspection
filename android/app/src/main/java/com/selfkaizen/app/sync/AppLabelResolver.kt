package com.selfkaizen.app.sync

import android.content.Context

/**
 * パッケージ名 → 表示名。
 *
 * **なぜ端末側で解決する必要があるか。**
 * サーバー（Cloudflare Workers）には `PackageManager` が無い。
 * パッケージ名から表示名を引く手段が存在しないため、
 * **端末が解決して送るしかない。**
 *
 * これを怠ると、サーバーの画面に
 * `com.brave.browser` `com.proxima.dfm` のような
 * **パッケージ名が並び、何を使ったのか分からなくなる**（実際に起きた）。
 *
 * プライバシー上の追加は無い。パッケージ名は既に送っており、
 * 表示名はそこから端末内で導出できる情報にすぎない
 * （`docs/PRIVACY.md` §1）。
 */
fun interface LabelResolver {
    fun label(packageName: String): String
}

/**
 * `PackageManager` で表示名を解決する実装。
 *
 * 解決できない場合は**パッケージ名をそのまま返す**。
 * 空文字を返すと、サーバー側の `appLabel || packageName` が
 * 空文字を拾って何も表示されなくなるため。
 *
 * 解決できないのは主に次の場合:
 *  - アンインストール済み
 *  - パッケージ可視性の対象外（`AndroidManifest.xml` の `<queries>` 参照）
 */
class AppLabelResolver(private val context: Context) : LabelResolver {

    private val cache = HashMap<String, String>()

    override fun label(packageName: String): String =
        cache.getOrPut(packageName) { resolve(packageName) }

    private fun resolve(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
}
