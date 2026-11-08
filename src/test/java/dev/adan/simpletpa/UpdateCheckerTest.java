package dev.adan.simpletpa;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerTest {
    private String release(String tag, boolean prerelease, String url) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":" + prerelease + ",\"html_url\":\"" + url + "\"}";
    }

    @Test void comparesNumericallyRatherThanAlphabetically() {
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.9.0")) > 0);
        assertTrue(SemanticVersion.parse("2.0.0").compareTo(SemanticVersion.parse("1.99.99")) > 0);
        assertEquals(0, SemanticVersion.parse("v1.1.0+build.7").compareTo(SemanticVersion.parse("1.1.0")));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("latest"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.01.0"));
    }

    @Test void prereleaseOrderingFollowsSemanticVersions() {
        String[] ordered = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"};
        for (int i = 1; i < ordered.length; i++) {
            assertTrue(SemanticVersion.parse(ordered[i]).compareTo(SemanticVersion.parse(ordered[i - 1])) > 0);
        }
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.0.0-beta.01"));
    }

    @Test void onlyNewerStableReleasesProduceDownloadNotices() {
        String url = UpdateChecker.REPOSITORY + "/releases/tag/v1.10.0";
        var result = UpdateChecker.newerRelease(release("v1.10.0", false, url), "1.9.0").orElseThrow();
        assertEquals("1.10.0", result.version());
        assertEquals(url, result.url());
        assertTrue(UpdateChecker.newerRelease(release("v1.10.0", false, url), "1.10.0").isEmpty());
        assertTrue(UpdateChecker.newerRelease(release("v1.10.0", false, url), "1.11.0").isEmpty());
        assertTrue(UpdateChecker.newerRelease(release("v1.10.0", true, url), "1.9.0").isEmpty());
        assertTrue(UpdateChecker.newerRelease(release("v1.10.0-beta.1", false, url), "1.9.0").isEmpty());
    }

    @Test void malformedResponsesAndUnrelatedLinksAreRejected() {
        assertThrows(RuntimeException.class, () -> UpdateChecker.newerRelease("not json", "1.1.0"));
        assertThrows(RuntimeException.class, () -> UpdateChecker.newerRelease("{}", "1.1.0"));
        assertThrows(IllegalArgumentException.class, () -> UpdateChecker.newerRelease(release("v2.0.0", false, "https://example.com/download"), "1.1.0"));
    }
}
