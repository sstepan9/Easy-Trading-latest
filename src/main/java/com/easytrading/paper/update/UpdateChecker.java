package com.easytrading.paper.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the public Modrinth API without blocking the server thread. */
public final class UpdateChecker {
    public static final String PROJECT_PAGE = "https://modrinth.com/plugin/easy-trading";
    private static final URI VERSIONS_URI = URI.create(
            "https://api.modrinth.com/v2/project/ps74UrHn/version?include_changelog=false");
    private static final Pattern VERSION_NUMBER = Pattern.compile("\\d+");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CompletableFuture<Optional<Release>> findNewerRelease(String installedVersion) {
        HttpRequest request = HttpRequest.newBuilder(VERSIONS_URI)
                .header("Accept", "application/json")
                .header("User-Agent", "EasyTrading/" + installedVersion + " (update-checker)")
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Modrinth returned HTTP " + response.statusCode());
            }
            return parseNewerRelease(response.body(), installedVersion);
        });
    }

    private static Optional<Release> parseNewerRelease(String responseBody, String installedVersion) {
        JsonArray versions = JsonParser.parseString(responseBody).getAsJsonArray();
        List<Release> newer = new ArrayList<>();
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) continue;
            JsonObject version = element.getAsJsonObject();
            String versionNumber = stringValue(version, "version_number");
            if (versionNumber == null || !"release".equals(stringValue(version, "version_type"))
                    || !"listed".equals(stringValue(version, "status"))
                    || compareVersions(versionNumber, installedVersion) <= 0) continue;
            newer.add(new Release(versionNumber, PROJECT_PAGE));
        }
        return newer.stream().max(Comparator.comparing(Release::version, UpdateChecker::compareVersions));
    }

    private static String stringValue(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    /** Numeric comparison supports EasyTrading versions, including 03 versus 10. */
    static int compareVersions(String left, String right) {
        List<Integer> leftParts = numericParts(left);
        List<Integer> rightParts = numericParts(right);
        int maxLength = Math.max(leftParts.size(), rightParts.size());
        for (int i = 0; i < maxLength; i++) {
            int leftPart = i < leftParts.size() ? leftParts.get(i) : 0;
            int rightPart = i < rightParts.size() ? rightParts.get(i) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static List<Integer> numericParts(String version) {
        List<Integer> parts = new ArrayList<>();
        Matcher matcher = VERSION_NUMBER.matcher(version == null ? "" : version);
        while (matcher.find()) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                parts.add(Integer.MAX_VALUE);
            }
        }
        return parts;
    }

    public record Release(String version, String pageUrl) {}
}
