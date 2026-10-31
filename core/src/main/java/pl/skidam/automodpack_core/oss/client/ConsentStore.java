package pl.skidam.automodpack_core.oss.client;

import com.google.gson.Gson;
import pl.skidam.automodpack_core.oss.VerifiedManifest;
import pl.skidam.automodpack_core.oss.SafePaths;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Local user decisions. No key or permission is ever accepted from a game server. */
public final class ConsentStore {
    private static final Gson JSON = new Gson();
    private final Path file;
    private State state;
    private static final class State {
        int schema = 1;
        boolean enabled = true;
        List<ConsentGrant> grants = new ArrayList<>();
    }
    public ConsentStore(Path gameRoot) throws IOException {
        file = gameRoot.resolve("config/automodpack-oss/consent.json");
        SafePaths.requireNoLinks(file);
        if (!Files.exists(file)) { state = new State(); return; }
        try {
            SafePaths.requireRegularFile(file);
            if (Files.size(file) > 1_048_576) throw new IOException("Invalid consent store");
            state = JSON.fromJson(Files.readString(file), State.class);
            if (state == null || state.schema != 1 || state.grants == null) throw new IOException("Invalid consent store");
            for (ConsentGrant grant : state.grants) {
                Objects.requireNonNull(grant.publisherIdentity()); Objects.requireNonNull(grant.serverIdentity());
                Objects.requireNonNull(grant.packId()); Objects.requireNonNull(grant.scopeVersion());
                Objects.requireNonNull(grant.rootScope());
                UUID.fromString(grant.accountUuid());
                if (grant.protocolVersion() <= 0) throw new IOException("Invalid consent protocol");
            }
        } catch (RuntimeException e) { throw new IOException("Invalid consent store", e); }
    }
    public synchronized boolean enabled() { return state.enabled; }
    public synchronized boolean permits(VerifiedManifest manifest, UUID account) {
        return state.enabled && state.grants.contains(ConsentGrant.forManifest(manifest, account));
    }
    public synchronized boolean hasAnyGrant(VerifiedManifest manifest) {
        return state.enabled && state.grants.stream().anyMatch(g -> g.covers(manifest));
    }
    public synchronized int grantCount() { return state.grants.size(); }
    public synchronized void grant(VerifiedManifest manifest, UUID account) throws IOException {
        state.enabled = true;
        ConsentGrant grant = ConsentGrant.forManifest(manifest, account);
        state.grants.removeIf(g -> g.packId().equals(grant.packId()) && g.accountUuid().equals(grant.accountUuid()));
        state.grants.add(grant);
        save();
    }
    public synchronized void setEnabled(boolean enabled) throws IOException { state.enabled = enabled; save(); }
    public synchronized void revokeAll() throws IOException { state.grants.clear(); state.enabled = false; save(); }
    private void save() throws IOException {
        SafePaths.requireNoLinks(file);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "consent-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(state));
            SafePaths.requireNoLinks(file);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
