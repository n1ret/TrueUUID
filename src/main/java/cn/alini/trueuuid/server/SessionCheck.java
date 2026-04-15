// java
package cn.alini.trueuuid.server;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import cn.alini.trueuuid.Trueuuid;
import cn.alini.trueuuid.config.TrueuuidConfig;

/**
 * Server-side hasJoined validation for premium auth, final UUID, and skin properties.
 */
public final class SessionCheck {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final int DEBUG_BODY_MAX_CHARS = 2048;

    public record Property(String name, String value, String signature) {}

    public record HasJoinedResult(UUID uuid, String name, List<Property> properties) {}

    private static class HasJoinedJson {
        String id; // UUID without hyphens
        String name;
        List<Prop> properties;
    }
    private static class Prop {
        String name;
        String value;
        @SerializedName("signature")
        String sig;
    }

    /**
        * Async version: non-blocking and returns CompletableFuture<Optional<HasJoinedResult>>.
     */
    public static CompletableFuture<Optional<HasJoinedResult>> hasJoinedAsync(String username, String serverId, String ip) {
        String url = TrueuuidConfig.COMMON.mojangReverseProxy.get()+"/session/minecraft/hasJoined"
                + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);

        Trueuuid.debug("Requesting Mojang validation endpoint: {}", url);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    Trueuuid.debug("Mojang response status code: {}", resp.statusCode());
                    Trueuuid.debug("Mojang response body(len={}): {}", resp.body() != null ? resp.body().length() : -1, clamp(resp.body(), DEBUG_BODY_MAX_CHARS));

                    if (resp.statusCode() != 200) {
                        Trueuuid.debug("Validation failed: non-200 status code, returning empty");
                        return Optional.<HasJoinedResult>empty();
                    }

                    HasJoinedJson dto = GSON.fromJson(resp.body(), HasJoinedJson.class);
                    if (dto == null || dto.id == null) {
                        Trueuuid.debug("JSON parse failed or UUID missing, returning empty");
                        return Optional.<HasJoinedResult>empty();
                    }

                    UUID uuid = UUID.fromString(dto.id.replaceFirst(
                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                            "$1-$2-$3-$4-$5"));

                    Trueuuid.debug("Validation succeeded: UUID={}, player={}", uuid, dto.name);

                    List<Property> props = dto.properties == null ? List.of() :
                            dto.properties.stream()
                                    .map(p -> new Property(p.name, p.value, p.sig))
                                    .toList();

                    return Optional.of(new HasJoinedResult(uuid, dto.name, props));
                })
                .exceptionally(ex -> {
                    Trueuuid.debug(ex, "Exception while communicating with Mojang or parsing response");
                    return Optional.empty();
                });
    }

    // Keep sync method for compatibility if needed, or remove it.
    public static Optional<HasJoinedResult> hasJoined(String username, String serverId, String ip) throws Exception {
        // Keep original sync implementation (or call hasJoinedAsync().get()) if needed.
        throw new UnsupportedOperationException("Synchronous hasJoined is no longer recommended; use hasJoinedAsync");
    }

    private static String clamp(String s, int maxChars) {
        if (s == null) return null;
        if (maxChars <= 0 || s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...(truncated)";
    }

    private SessionCheck() {}
}
