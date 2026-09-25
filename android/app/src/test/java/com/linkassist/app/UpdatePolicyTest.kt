package com.linkassist.app

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val repo = "Danjack85/LinkAssist"
    private val asset = "https://github.com/$repo/releases/download/v3.0.0/LinkAssist-3.0.0-android.apk"
    private val hash = "a".repeat(64)

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun repositoryBuildsOnlyTheExpectedApiEndpoint() {
        assertEquals("https://api.github.com/repos/$repo/releases/latest", UpdatePolicy.latestApi(" $repo "))
        listOf("https://github.com/$repo", "owner/repo/extra", "owner/..", "owner/repo?token=x", "user@host/repo").forEach {
            rejected { UpdatePolicy.repository(it) }
        }
    }

    @Test fun releaseAssetMustMatchRepositoryTagAndFilename() {
        assertEquals(asset, UpdatePolicy.assetUrl(asset, repo, "v3.0.0", "LinkAssist-3.0.0-android.apk"))
        rejected { UpdatePolicy.assetUrl(asset, "someone/else") }
        rejected { UpdatePolicy.assetUrl(asset, repo, "v2.0.0") }
        rejected { UpdatePolicy.assetUrl(asset, repo, "v3.0.0", "other.apk") }
    }

    @Test fun rejectsHttpCredentialsWrongHostPortAndTraversal() {
        listOf(
            asset.replace("https:", "http:"),
            asset.replace("github.com", "github.com.attacker.invalid"),
            asset.replace("github.com", "user:secret@github.com"),
            asset.replace("github.com", "github.com:8443"),
            asset.replace("/LinkAssist/", "/OtherRepo/"),
            asset.replace("/v3.0.0/", "/%2e%2e/"),
            asset.replace("/v3.0.0/", "/v3.0.0%2f..%2f/"),
            "$asset?token=secret", "$asset#fragment",
        ).forEach { rejected { UpdatePolicy.assetUrl(it, repo) } }
    }

    @Test fun onlyOfficialHttpsCdnRedirectsAreAllowed() {
        for (host in listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) {
            val url = "https://$host/assets/file?sig=test"
            assertEquals(url, UpdatePolicy.assetRedirect(url, repo))
            rejected { UpdatePolicy.assetUrl(url, repo) }
        }
        listOf(
            "http://release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com.attacker.invalid/file",
            "https://attacker.githubusercontent.com/file",
            "https://user@objects.githubusercontent.com/file",
            "https://api.github.com/repos/$repo/releases/latest",
        ).forEach { rejected { UpdatePolicy.assetRedirect(it, repo) } }
    }

    @Test fun validatesReleasePage() {
        assertEquals("https://github.com/$repo/releases/tag/v3.0.0", UpdatePolicy.releaseUrl("https://github.com/$repo/releases/tag/v3.0.0", repo, "v3.0.0"))
        rejected { UpdatePolicy.releaseUrl("https://github.com/other/repo/releases/tag/v3.0.0", repo, "v3.0.0") }
    }

    @Test fun validatesVersionCodeSizeAndHash() {
        UpdatePolicy.apkMetadata("3.0.0", 12, 123, hash)
        UpdatePolicy.apkMetadata("2.2", 11, 100, hash.uppercase())
        rejected { UpdatePolicy.apkMetadata("3.0.0", 0, 123, hash) }
        rejected { UpdatePolicy.apkMetadata("3.0.0", Long.MAX_VALUE, 123, hash) }
        rejected { UpdatePolicy.apkMetadata("3.0.0", 12, 0, hash) }
        rejected { UpdatePolicy.apkMetadata("3.0.0", 12, UpdatePolicy.MAX_APK_BYTES + 1, hash) }
        rejected { UpdatePolicy.apkMetadata("not-a-version", 12, 123, hash) }
        rejected { UpdatePolicy.apkMetadata("3.0.0", 12, 123, "") }
        rejected { UpdatePolicy.apkMetadata("3.0.0", 12, 123, "z".repeat(64)) }
    }
}
