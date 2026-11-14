package com.hsd.universal.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateLogicTest {
    private val repo = "https://github.com/SadykovIV/HSD"

    private fun release(v: String, vararg files: String, draft: Boolean = false, repository: String = repo) =
        JSONObject().put("tag_name", "v$v").put("draft", draft).put("assets", JSONArray().apply {
            for (name in files) put(JSONObject().put("name", name).put("state", "uploaded")
                .put("browser_download_url", "$repository/releases/download/v$v/$name"))
        })

    private fun page(vararg releases: JSONObject) = JSONArray(releases.toList())

    @Test fun stableReleaseContainsBothAppsAndRestoresExactUrl() {
        val feed = page(release("0.7.0", "DriveDeck-HSD-0.6.9-code338.apk", "HSD-0.7.0.apk"))
        val offered = requireNotNull(UpdateLogic.newestInPage(feed))
        assertEquals("0.7.0", offered.version)
        assertEquals("$repo/releases/download/v0.7.0/HSD-0.7.0.apk",
            UpdateLogic.Release.fromTag(offered.tag, offered.apkName)?.apkUrl)
    }

    @Test fun ignoresCompanionOnlyDraftsStaleAssetsAndForeignDownloads() {
        val incomplete = release("0.8.0", "HSD-0.8.0.apk")
        incomplete.getJSONArray("assets").getJSONObject(0).put("state", "starter")
        val forged = release("0.7.1", "HSD-0.7.1.apk", repository = "https://github.com/example/other")
        assertNull(UpdateLogic.newestInPage(page(incomplete, forged,
            release("0.7.3", "HSD-0.7.3.apk", draft = true), release("0.7.2", "HSD-0.7.1.apk"),
            release("0.7.0", "DriveDeck-HSD-0.6.9-code338.apk"))))
        assertNull(UpdateLogic.Release.fromTag("v0.7.0/../../other", "HSD-0.7.0.apk"))
        assertNull(UpdateLogic.Release.fromTag("v0.7.0", null))
        assertNull(UpdateLogic.Release.fromTag("v0.6.13-universal.2", "HSD-0.6.13-universal.2.apk"))
    }

    @Test fun ordersReleasesAcrossPagesAndUpgradesPreviousInstallations() {
        val first = UpdateLogic.newestInPage(page(release("0.7.9", "HSD-0.7.9.apk")))
        val next = UpdateLogic.newestInPage(page(release("0.7.10", "HSD-0.7.10.apk")), first)
        assertEquals("0.7.10", next?.version)
        assertSame(next, UpdateLogic.newestInPage(JSONArray(), next))
        assertTrue(UpdateLogic.isNewer("0.7.0", "0.6.13-universal.2"))
        assertFalse(UpdateLogic.isNewer("0.7.0", "0.7.0"))
        assertFalse(UpdateLogic.isNewer("0.6.12", "0.6.13-universal.2"))
        assertFalse(UpdateLogic.isNewer("latest", "0.7.0"))
        assertFalse(UpdateLogic.isNewer("0.7.0", "?"))
    }

    @Test fun optOutAndCheckIntervals() {
        val now = 100 * 3600_000L
        assertFalse(UpdateLogic.shouldCheck(false, 0, now))
        assertTrue(UpdateLogic.shouldCheck(true, 0, now))
        assertFalse(UpdateLogic.shouldCheck(true, now, now + UpdateLogic.CHECK_INTERVAL_MS - 1))
        assertTrue(UpdateLogic.shouldCheck(true, now, now + UpdateLogic.CHECK_INTERVAL_MS))
        assertTrue(UpdateLogic.shouldCheck(true, now, now - 1))
        val retry = now - UpdateLogic.CHECK_INTERVAL_MS + UpdateLogic.RETRY_MS
        assertFalse(UpdateLogic.shouldCheck(true, retry, now + UpdateLogic.RETRY_MS - 1))
        assertTrue(UpdateLogic.shouldCheck(true, retry, now + UpdateLogic.RETRY_MS))
    }

    @Test fun installedCandidateCanUpgradeToStableWithoutBeingDowngraded() {
        assertTrue(UpdateLogic.isNewer("0.8.1", "0.8.1-rc1"))
        assertTrue(UpdateLogic.isNewer("0.9.0", "0.8.1-rc12"))
        assertFalse(UpdateLogic.isNewer("0.8.0", "0.8.1-rc1"))
        assertFalse(UpdateLogic.isNewer("0.8.1", "0.8.1-rc0"))
        assertNull(UpdateLogic.newestInPage(page(release("0.8.1-rc1", "HSD-0.8.1-rc1.apk"),
            release("0.8.1", "HSD-0.8.1.apk").put("prerelease", true))))
    }
}
