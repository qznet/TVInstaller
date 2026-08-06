package com.tvig.installer.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GitHubRouteManagerTest {
    private static final String RAW_URL =
            "https://raw.githubusercontent.com/owner/repo/main/config.txt";

    @Test
    public void supportedUrls_acceptGitHubFilesAndRejectUnrelatedHosts() {
        assertTrue(GitHubRouteManager.isSupportedGitHubUrl(RAW_URL));
        assertTrue(GitHubRouteManager.isSupportedGitHubUrl(
                "https://github.com/owner/repo/releases/download/v1/app.apk"));
        assertTrue(GitHubRouteManager.isSupportedGitHubUrl(
                "https://release-assets.githubusercontent.com/file.apk"));
        assertFalse(GitHubRouteManager.isSupportedGitHubUrl(
                "https://example.com/file.apk"));
    }

    @Test
    public void routeUrls_preserveTheCompleteOriginalUrl() {
        assertEquals(
                "https://proxy.gitwarp.com/raw.githubusercontent.com/owner/repo/main/config.txt",
                GitHubRouteManager.buildRouteUrl("gitwarp", RAW_URL));
        assertEquals(
                "https://gh-proxy.com/" + RAW_URL,
                GitHubRouteManager.buildRouteUrl("gh_proxy", RAW_URL));
        assertEquals(RAW_URL,
                GitHubRouteManager.buildRouteUrl("origin", RAW_URL));
    }
}
