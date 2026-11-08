package dev.adan.simpletpa;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

final class UpdateChecker {
    static final String REPOSITORY = "https://github.com/godblessmerica/SimpleTPA";
    private static final URI ENDPOINT = URI.create("https://api.github.com/repos/godblessmerica/SimpleTPA/releases/latest");
    record Release(String version, String url) {}

    static Optional<Release> check(String current) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "SimpleTPA/" + current)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode());
            if (response.body().length() > 1_048_576) throw new IOException("Release response too large");
            return newerRelease(response.body(), current);
        }
    }

    static Optional<Release> newerRelease(String json, String current) {
        var release = JsonParser.parseString(json).getAsJsonObject();
        if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) return Optional.empty();
        String tag = release.get("tag_name").getAsString();
        SemanticVersion latest = SemanticVersion.parse(tag);
        if (latest.compareTo(SemanticVersion.parse(current)) <= 0 || !latest.prerelease().isEmpty()) return Optional.empty();
        String url = release.get("html_url").getAsString();
        if (!url.startsWith(REPOSITORY + "/releases/tag/")) throw new IllegalArgumentException("Unexpected release URL");
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Unexpected release URL");
        }
        return Optional.of(new Release(tag.replaceFirst("^[vV]", ""), url));
    }
}
