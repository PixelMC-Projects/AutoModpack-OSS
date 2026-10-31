package pl.skidam.automodpack_core.oss.server;

import pl.skidam.automodpack_core.oss.ManifestFile;
import pl.skidam.automodpack_core.oss.VerifiedManifest;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Bounded URL issuance, not a bandwidth or cost ceiling: issued URLs remain reusable until expiry. */
public final class DownloadAuthorizer {
    public record Grant(String token, long expiresAt) {}
    public record SignedFile(URI canonicalUrl, URI url, long expiresAt) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;
    private final int sessionLifetime, urlLifetime;
    private final OssUrlSigner signer;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Window> accounts = new HashMap<>(), ips = new HashMap<>(), logins = new HashMap<>(), metadata = new HashMap<>();
    private String activeRelease;
    private long activeSequence;
    private boolean enabled = true;
    private int inFlight;
    private final Map<String, Window> global = new HashMap<>();

    private static final class Session {
        UUID account; String ip, release; long sequence, expires; int count, concurrent; boolean accepted;
        final Map<String,Integer> renewals = new HashMap<>();
    }
    private static final class Window { long start; int count; Window(long start) { this.start = start; } }

    public DownloadAuthorizer(OssUrlSigner signer, int sessionLifetime, int urlLifetime, Clock clock) {
        this.signer = signer; this.sessionLifetime = sessionLifetime; this.urlLifetime = urlLifetime; this.clock = clock;
    }

    public synchronized void activate(VerifiedManifest manifest) {
        if (!Objects.equals(activeRelease, manifest.releaseId()) || activeSequence != manifest.sequence()) sessions.clear();
        activeRelease = manifest.releaseId(); activeSequence = manifest.sequence();
    }
    public synchronized void disable() { enabled = false; sessions.clear(); }
    public synchronized void revoke(String token) { sessions.remove(token); }
    public synchronized boolean accept(String token) {
        Session s = sessions.get(token);
        if (s == null || s.accepted || s.expires <= now() || !enabled) return false;
        s.accepted = true; s.expires = now() + sessionLifetime; return true;
    }
    public synchronized boolean allowMetadata(String token, String ip) {
        Session s = sessions.get(token);
        return enabled && s != null && s.accepted && s.expires > now() && s.ip.equals(ip)
                && s.release.equals(activeRelease) && s.sequence == activeSequence
                && take(global,"metadata",60,20000) && take(metadata,"a:" + s.account,60,360) && take(metadata,"i:" + ip,60,3600);
    }

    /** Caller must supply a profile captured from a successful Mojang hasJoinedServer result. */
    public synchronized Grant issue(UUID authenticatedAccount, String remoteIp, VerifiedManifest manifest) {
        cleanup();
        if (!enabled || authenticatedAccount == null || remoteIp == null || !manifest.releaseId().equals(activeRelease)
                || manifest.sequence() != activeSequence || sessions.size() >= 1024) throw new SecurityException("No active authenticated release");
        String account = authenticatedAccount.toString();
        if (sessions.values().stream().filter(s -> s.account.equals(authenticatedAccount)).count() >= 2
                || sessions.values().stream().filter(s -> s.ip.equals(remoteIp)).count() >= 64
                || !take(global,"logins",60,2048)
                || !take(logins, "a:" + account, 900, 8) || !take(logins, "i:" + remoteIp, 900, 128))
            throw new SecurityException("Authorization login rate exceeded");
        byte[] random = new byte[32]; RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Session s = new Session(); s.account = authenticatedAccount; s.ip = remoteIp; s.release = manifest.releaseId();
        s.sequence = manifest.sequence(); s.expires = now() + sessionLifetime; sessions.put(token, s);
        return new Grant(token, s.expires);
    }

    public synchronized SignedFile authorize(String token, String remoteIp, String releaseId, String path, VerifiedManifest manifest) {
        cleanup();
        Session s = sessions.get(token);
        if (!enabled || s == null || !s.accepted || !s.ip.equals(remoteIp) || !s.release.equals(releaseId)
                || !s.release.equals(activeRelease) || s.sequence != activeSequence || !manifest.releaseId().equals(s.release)
                || manifest.sequence() != s.sequence) throw new SecurityException("Invalid authorization session");
        ManifestFile file = manifest.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow(() -> new SecurityException("File is outside active release"));
        if (s.count >= 30000 || s.renewals.getOrDefault(path, 0) >= 3
                || !take(global,"files",60,10000)
                || !take(accounts, s.account.toString(), 60, 240) || !take(ips, remoteIp, 60, 2400))
            throw new SecurityException("URL issuance rate exceeded");
        int accountConcurrent = sessions.values().stream().filter(o -> o.account.equals(s.account)).mapToInt(o -> o.concurrent).sum();
        int ipConcurrent = sessions.values().stream().filter(o -> o.ip.equals(remoteIp)).mapToInt(o -> o.concurrent).sum();
        if (inFlight >= 128 || s.concurrent >= 8 || accountConcurrent >= 12 || ipConcurrent >= 64)
            throw new SecurityException("Authorization concurrency exceeded");
        s.concurrent++; inFlight++;
        try {
            long expiry = Math.min(now() + urlLifetime, s.expires);
            if (expiry - now() < 15) throw new SecurityException("Authorization expired");
            URI transport = signer.sign(file.url(), clock.instant(), Instant.ofEpochSecond(expiry));
            s.count++; s.renewals.merge(path, 1, Integer::sum);
            return new SignedFile(file.url(), transport, expiry);
        } finally { s.concurrent--; inFlight--; }
    }

    private long now() { return clock.instant().getEpochSecond(); }
    private boolean take(Map<String,Window> map, String key, int seconds, int limit) {
        if (!map.containsKey(key) && map.size() >= 16384) return false;
        Window w = map.computeIfAbsent(key, k -> new Window(now()));
        if (now() - w.start >= seconds) { w.start = now(); w.count = 0; }
        if (w.count >= limit) return false;
        w.count++; return true;
    }
    private void cleanup() {
        long current = now(); sessions.values().removeIf(s -> s.expires <= current);
        accounts.values().removeIf(w -> current - w.start >= 900);
        ips.values().removeIf(w -> current - w.start >= 900);
        logins.values().removeIf(w -> current - w.start >= 1800);
        metadata.values().removeIf(w -> current - w.start >= 900);
    }
}
