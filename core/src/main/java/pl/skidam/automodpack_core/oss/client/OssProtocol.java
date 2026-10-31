package pl.skidam.automodpack_core.oss.client;

import com.google.gson.*;
import pl.skidam.automodpack_core.oss.PackTrust;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Binds every server-signed metadata packet to this connection and authenticated account. */
public final class OssProtocol {
    private OssProtocol() {}
    public record Offer(String releaseId, long sequence, URI manifestUrl, URI manifestTransportUrl, String manifestSha256, UUID account, long expiresAt) {}
    public static JsonObject signedMessage(PackTrust trust, byte[] envelope, String nonce, int queryId) throws IOException {
        try {
            if (envelope.length > 1_048_576) throw new IOException("Oversized identity message");
            JsonObject object = JsonParser.parseString(new String(trust.verifyServerEnvelope(envelope), StandardCharsets.UTF_8)).getAsJsonObject();
            if (number(object,"schema") != 1 || !trust.packId().equals(string(object,"packId")) ||
                    !nonce.equals(string(object,"clientNonce")) || number(object,"queryId") != queryId) throw new IOException("Identity/session binding failed");
            return object;
        } catch (RuntimeException error) { throw new IOException("Malformed identity message", error); }
    }
    public static Offer offer(PackTrust trust, JsonObject object, UUID localAccount, String localName, long now) throws IOException {
        try {
            UUID authenticated = UUID.fromString(string(object,"playerUuid"));
            long issued = number(object,"issuedAt"), expires = number(object,"queryExpiresAt");
            long urlExpires = number(object,"urlExpiresAt");
            if (urlExpires <= now || urlExpires > now + 300) throw new IOException("Invalid short download URL lifetime");
            JsonElement online = object.get("onlineAuthenticated");
            if (online == null || !online.isJsonPrimitive() || !online.getAsJsonPrimitive().isBoolean() || !online.getAsBoolean() || !authenticated.equals(localAccount) ||
                    !localName.equals(string(object,"playerName")) || issued > now + 30 || expires < now ||
                    expires < issued || expires - issued > 180 || !"minecraft-login".equals(string(object,"authorizationTransport")))
                throw new IOException("Account authentication or authorization lifetime failed");
            String release = string(object,"releaseId");
            URI canonical = URI.create(string(object,"manifestUrl"));
            trust.validateManifestUrl(canonical, release);
            URI transport = URI.create(string(object,"manifestTransportUrl"));
            trust.validateTransportUrl(canonical, transport);
            long sequence = number(object,"sequence");
            if (!release.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || sequence < trust.minimumSequence()) throw new IOException("Invalid active release");
            String hash = string(object,"manifestSha256");
            if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid active manifest hash");
            return new Offer(release, sequence, canonical, transport, hash, authenticated, expires);
        } catch (RuntimeException error) { throw new IOException("Malformed authorization offer", error); }
    }
    public static String string(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid metadata field");
        return value.getAsString();
    }
    public static long number(JsonObject object, String key) throws IOException {
        try {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.toString().matches("-?[0-9]+")) throw new IOException("Invalid metadata number");
            return Long.parseLong(value.toString());
        } catch (NumberFormatException error) { throw new IOException("Invalid metadata number", error); }
    }
}
