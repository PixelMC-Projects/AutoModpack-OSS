package pl.skidam.automodpack_core.oss.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/** Validates the outstanding transaction and the nonce created on this exact login connection. */
public final class LoginReplyValidator {
    private LoginReplyValidator() {}
    public static JsonObject parse(byte[] bytes, int expectedId, int actualId, String requiredNonce) {
        if (expectedId == 0 || actualId != expectedId || bytes == null || bytes.length > 4096)
            throw new SecurityException("No matching outstanding login query");
        JsonObject reply = JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        if (!reply.has("schema") || reply.get("schema").getAsInt() != 1 || !reply.has("status")) throw new SecurityException("Invalid query schema");
        if (requiredNonce != null && (!reply.has("clientNonce") || !requiredNonce.equals(reply.get("clientNonce").getAsString())))
            throw new SecurityException("Query reply belongs to another login");
        return reply;
    }
}
