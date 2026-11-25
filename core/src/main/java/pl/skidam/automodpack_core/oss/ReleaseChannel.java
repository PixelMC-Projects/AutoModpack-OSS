package pl.skidam.automodpack_core.oss;

import java.io.IOException;
import java.util.Locale;

/** Signed release channel: official stable track or opt-in preview track. */
public enum ReleaseChannel {
    STABLE("stable", "正式版", "active.json"),
    PREVIEW("preview", "预览版", "active-preview.json");

    private final String id;
    private final String displayName;
    private final String activeFileName;

    ReleaseChannel(String id, String displayName, String activeFileName) {
        this.id = id;
        this.displayName = displayName;
        this.activeFileName = activeFileName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String activeFileName() {
        return activeFileName;
    }

    /** Stable keeps the legacy packId key; preview tracks its own sequence history. */
    public String historyKey(String packId) {
        return this == STABLE ? packId : packId + "#" + id;
    }

    public static ReleaseChannel parse(String raw) throws IOException {
        if (raw == null) throw new IOException("Missing release channel");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ReleaseChannel channel : values()) {
            if (channel.id.equals(normalized)) return channel;
        }
        throw new IOException("Unsupported release channel: " + raw);
    }

    public static ReleaseChannel parseOrFallback(String raw) {
        if (raw == null || raw.isBlank()) return STABLE;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ReleaseChannel channel : values()) {
            if (channel.id.equals(normalized)) return channel;
        }
        return STABLE;
    }
}
