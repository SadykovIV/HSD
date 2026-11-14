package com.hsd.universal.update

import org.json.JSONArray

object UpdateLogic {
    const val REPO = "SadykovIV/HSD"
    const val CHECK_INTERVAL_MS = 6 * 3600_000L
    const val RETRY_MS = 3600_000L

    private val stableVersion = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
    private val prereleaseVersion = Regex("""(.+)-(?:universal\.|rc)[1-9]\d*""")

    class Release private constructor(val tag: String, val version: String, val apkName: String) {
        val apkUrl get() = "https://github.com/$REPO/releases/download/$tag/$apkName"

        companion object {
            fun fromTag(tag: String?, apkName: String?): Release? {
                if (tag == null || !tag.startsWith('v')) return null
                val version = tag.substring(1)
                if (!stableVersion.matches(version) || numbers(version) == null || apkName != "HSD-$version.apk") return null
                return Release(tag, version, apkName)
            }
        }
    }

    private fun numbers(version: String): List<Int>? = stableVersion.matchEntire(version)?.groupValues?.drop(1)?.map {
        it.toIntOrNull() ?: return null
    }

    fun isNewer(remote: String, local: String): Boolean {
        val candidate = numbers(remote) ?: return false
        val prior = prereleaseVersion.matchEntire(local)
        val installed = numbers(prior?.groupValues?.get(1) ?: local) ?: return false
        for (i in candidate.indices) {
            if (candidate[i] != installed[i]) return candidate[i] > installed[i]
        }
        return prior != null
    }

    fun newestInPage(page: JSONArray, previous: Release? = null): Release? {
        var best = previous
        for (i in 0 until page.length()) {
            val item = page.optJSONObject(i) ?: continue
            if (item.optBoolean("draft") || item.optBoolean("prerelease")) continue
            val tag = item.optString("tag_name")
            val assets = item.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("state") != "uploaded") continue
                val candidate = Release.fromTag(tag, asset.optString("name")) ?: continue
                if (asset.optString("browser_download_url") != candidate.apkUrl) continue
                if (best == null || isNewer(candidate.version, best.version)) best = candidate
            }
        }
        return best
    }

    fun shouldCheck(enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
        enabled && (nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs)
}
