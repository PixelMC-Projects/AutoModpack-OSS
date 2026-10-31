package pl.skidam.automodpack_core.oss.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.oss.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerAuthorizationTest {
    private static final URI OBJECT = URI.create("https://example-bucket.oss-cn-hangzhou.aliyuncs.com/releases/r1/mods/a.jar");
    private static final class TestClock extends Clock {
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return instant; }
    }
    private PackTrust trust(KeyPair publisher) throws Exception {
        String key=Base64.getEncoder().encodeToString(publisher.getPublic().getEncoded());
        String serverKey=Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        return new PackTrust("test-pack","Test pack",key,serverKey,URI.create("https://example-bucket.oss-cn-hangzhou.aliyuncs.com/active.json"),Set.of(OBJECT.getHost()),Set.of("mods"),1);
    }
    private VerifiedManifest manifest(String release, long sequence) throws Exception {
        KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return manifest(release,sequence,pair,trust(pair),"mods/a.jar");
    }
    private VerifiedManifest manifest(String release,long sequence,KeyPair pair,PackTrust trust,String path) throws Exception {
        JsonObject file = new JsonObject(); file.addProperty("path",path); file.addProperty("size",42); file.addProperty("sha256","0".repeat(64)); file.addProperty("url",OBJECT.resolve("/releases/"+release+"/"+path).toString());
        JsonObject payload = new JsonObject(); payload.addProperty("schema",1);payload.addProperty("packId","test-pack");payload.addProperty("releaseId",release);payload.addProperty("sequence",sequence);
        payload.addProperty("consentProtocolVersion",1);payload.addProperty("managementScopeVersion","scope-1");payload.addProperty("createdAt","2026-01-01T00:00:00Z");
        var files = new com.google.gson.JsonArray();files.add(file);payload.add("files",files);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8); Signature s = Signature.getInstance("Ed25519");s.initSign(pair.getPrivate());s.update(bytes);
        JsonObject envelope = new JsonObject();envelope.addProperty("schema",1);envelope.addProperty("payload",Base64.getEncoder().encodeToString(bytes));envelope.addProperty("signature",Base64.getEncoder().encodeToString(s.sign()));
        return ManifestVerifier.verify(envelope.toString().getBytes(StandardCharsets.UTF_8),trust);
    }
    private DownloadAuthorizer authorizer(TestClock clock) { return new DownloadAuthorizer(new OssUrlSigner("example-bucket","cn-hangzhou","test-read-key","test-read-secret",null),900,180,clock); }

    @Test void offlineFallbackIntegratedAndUnprovedSessionsCannotAuthorize() {
        UUID player = UUID.randomUUID();
        assertTrue(OnlineSessionGate.eligible(true,false,false,player,"Player",player,"Player"));
        assertFalse(OnlineSessionGate.eligible(false,false,false,player,"Player",player,"Player"));
        assertFalse(OnlineSessionGate.eligible(true,true,false,player,"Player",player,"Player"));
        assertFalse(OnlineSessionGate.eligible(true,false,true,player,"Player",player,"Player"));
        assertFalse(OnlineSessionGate.eligible(true,false,false,null,null,player,"Player"));
        assertFalse(OnlineSessionGate.eligible(true,false,false,UUID.randomUUID(),"Player",player,"Player"));
        assertFalse(OnlineSessionGate.eligible(true,false,false,player,"Other",player,"Player"));
    }
    @Test void previousTransactionAndPreviousConnectionNonceAreRejected() {
        byte[] replay = "{\"schema\":1,\"status\":\"accepted\",\"clientNonce\":\"previous-connection\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->LoginReplyValidator.parse(replay,187,186,"previous-connection"));
        assertThrows(SecurityException.class,()->LoginReplyValidator.parse(replay,187,187,"current-connection"));
        assertThrows(SecurityException.class,()->LoginReplyValidator.parse(replay,0,0,"previous-connection"));
        assertEquals("accepted",LoginReplyValidator.parse(replay,187,187,"previous-connection").get("status").getAsString());
    }
    @Test void consentIpReleaseWhitelistRenewalAndExpiryAreEnforced() throws Exception {
        TestClock clock = new TestClock(); DownloadAuthorizer auth = authorizer(clock); VerifiedManifest release = manifest("r1",1);auth.activate(release);
        assertThrows(SecurityException.class,()->auth.issue(null,"192.0.2.10",release));
        var grant = auth.issue(UUID.randomUUID(),"192.0.2.10",release);
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.10","r1","mods/a.jar",release));
        assertTrue(auth.accept(grant.token()));
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.11","r1","mods/a.jar",release));
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.10","r2","mods/a.jar",release));
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.10","r1","../mods/a.jar",release));
        for(int i=0;i<3;i++) { var signed = auth.authorize(grant.token(),"192.0.2.10","r1","mods/a.jar",release);assertEquals(OBJECT,signed.canonicalUrl());assertEquals(clock.instant().getEpochSecond()+180,signed.expiresAt()); }
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.10","r1","mods/a.jar",release));
        clock.instant = clock.instant.plusSeconds(901);
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.10","r1","mods/a.jar",release));
    }
    @Test void activationKillSwitchAndRevokeInvalidateSessions() throws Exception {
        TestClock clock = new TestClock(); DownloadAuthorizer auth = authorizer(clock);var one=manifest("r1",1);auth.activate(one);
        UUID account=UUID.randomUUID();var grant=auth.issue(account,"192.0.2.20",one);auth.accept(grant.token());
        var two=manifest("r2",2);auth.activate(two);
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.20","r1","mods/a.jar",one));
        var second=auth.issue(account,"192.0.2.20",two);auth.accept(second.token());auth.revoke(second.token());assertFalse(auth.accept(second.token()));
        var third=auth.issue(account,"192.0.2.20",two);auth.accept(third.token());auth.disable();assertFalse(auth.accept(third.token()));
        assertThrows(SecurityException.class,()->auth.issue(account,"192.0.2.20",two));
    }
    @Test void acceptedDeadlineStartsAfterConsentAndCannotBeExtendedTwice() throws Exception {
        TestClock clock=new TestClock();DownloadAuthorizer auth=authorizer(clock);var release=manifest("r1",1);auth.activate(release);
        var grant=auth.issue(UUID.randomUUID(),"192.0.2.40",release);
        clock.instant=clock.instant.plusSeconds(170);assertTrue(auth.accept(grant.token()));assertFalse(auth.accept(grant.token()));
        clock.instant=clock.instant.plusSeconds(831);
        assertNotNull(auth.authorize(grant.token(),"192.0.2.40","r1","mods/a.jar",release));
        clock.instant=clock.instant.plusSeconds(70);
        assertThrows(SecurityException.class,()->auth.authorize(grant.token(),"192.0.2.40","r1","mods/a.jar",release));
    }
    @Test void localPinRequiresExpectedIdentityAndImmutableReleaseLocation() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var trust=trust(pair);
        java.nio.file.Path pin=java.nio.file.Files.createTempFile("automodpack-synthetic-pin-",".json");
        try {
            JsonObject value=new JsonObject();value.addProperty("schema",1);value.addProperty("packId","test-pack");value.addProperty("releaseId","r1");value.addProperty("sequence",1);value.addProperty("manifestSha256","0".repeat(64));value.addProperty("manifestUrl","https://example-bucket.oss-cn-hangzhou.aliyuncs.com/releases/r1/manifest.json");
            java.nio.file.Files.writeString(pin,value.toString());assertEquals("r1",ActiveReleasePin.load(pin,trust).releaseId());
            value.addProperty("manifestUrl",trust.manifestUrl().toString());java.nio.file.Files.writeString(pin,value.toString());assertThrows(Exception.class,()->ActiveReleasePin.load(pin,trust));
            value.addProperty("manifestUrl","https://example-bucket.oss-cn-hangzhou.aliyuncs.com/releases/r1/manifest.json");value.addProperty("packId","other-pack");java.nio.file.Files.writeString(pin,value.toString());assertThrows(Exception.class,()->ActiveReleasePin.load(pin,trust));
        } finally { java.nio.file.Files.deleteIfExists(pin); }
    }
    @Test void stagedUploadRemotePointerAndEditedPinCannotChangeRunningRelease() throws Exception {
        KeyPair publisher=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();PackTrust trust=trust(publisher);
        VerifiedManifest v1=manifest("r1",1,publisher,trust,"mods/a.jar"),v2=manifest("r2",2,publisher,trust,"mods/v2.jar");
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("automodpack-synthetic-activation-");
        java.nio.file.Path pin=directory.resolve("server-active-release.json");
        try {
            writePin(pin,v1);
            ActiveReleaseBinding running=new ActiveReleaseBinding(pin,trust);
            var authorizer=authorizer(new TestClock());authorizer.activate(running.verifyManifest(v1.envelopeBytes()));
            var grant=authorizer.issue(UUID.randomUUID(),"192.0.2.55",v1);assertTrue(authorizer.accept(grant.token()));
            // Upload/staging and remote activation are independent of the running process's local binding.
            java.nio.file.Files.write(directory.resolve("uploaded-r2-manifest.json"),v2.envelopeBytes());
            java.nio.file.Files.write(directory.resolve("remote-active.json"),v2.envelopeBytes());
            writePin(pin,v2);
            assertEquals("r1",running.pin().releaseId());
            assertEquals("r1",running.verifyManifest(v1.envelopeBytes()).releaseId());
            assertThrows(SecurityException.class,()->running.verifyManifest(v2.envelopeBytes()));
            assertNotNull(authorizer.authorize(grant.token(),"192.0.2.55","r1","mods/a.jar",v1));
            assertThrows(SecurityException.class,()->authorizer.authorize(grant.token(),"192.0.2.55","r1","mods/v2.jar",v1));
            ActiveReleaseBinding nextLifecycle=new ActiveReleaseBinding(pin,trust);
            assertEquals("r2",nextLifecycle.verifyManifest(v2.envelopeBytes()).releaseId());
            assertThrows(SecurityException.class,()->nextLifecycle.verifyManifest(v1.envelopeBytes()));
        } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("uploaded-r2-manifest.json"));java.nio.file.Files.deleteIfExists(directory.resolve("remote-active.json"));java.nio.file.Files.deleteIfExists(pin);java.nio.file.Files.deleteIfExists(directory);
        }
    }
    private void writePin(java.nio.file.Path path,VerifiedManifest manifest) throws Exception {
        JsonObject pin=new JsonObject();pin.addProperty("schema",1);pin.addProperty("packId",manifest.packId());pin.addProperty("releaseId",manifest.releaseId());pin.addProperty("sequence",manifest.sequence());
        pin.addProperty("manifestSha256",ManifestVerifier.sha256(manifest.envelopeBytes()));pin.addProperty("manifestUrl",manifest.trust().manifestUrl().resolve("releases/"+manifest.releaseId()+"/manifest.json").toASCIIString());
        java.nio.file.Files.writeString(path,pin.toString());
    }
    @Test void natAllowsDifferentAccountsButCapsRepeatedAccountSessions() throws Exception {
        TestClock clock = new TestClock();DownloadAuthorizer auth=authorizer(clock);var release=manifest("r1",1);auth.activate(release);UUID account=UUID.randomUUID();
        auth.issue(account,"192.0.2.30",release);auth.issue(account,"192.0.2.30",release);
        assertThrows(SecurityException.class,()->auth.issue(account,"192.0.2.30",release));
        assertNotNull(auth.issue(UUID.randomUUID(),"192.0.2.30",release));
    }
    @Test void signerRejectsAlternateHostsPlaintextAndExistingQueries() {
        OssUrlSigner signer=new OssUrlSigner("example-bucket","cn-hangzhou","test-key","test-secret",null);
        assertThrows(IllegalArgumentException.class,()->signer.sign(URI.create("https://attacker.invalid/mods/a.jar"),Instant.now()));
        assertThrows(IllegalArgumentException.class,()->signer.sign(URI.create(OBJECT.toString().replace("https:","http:")),Instant.now()));
        assertThrows(IllegalArgumentException.class,()->signer.sign(URI.create(OBJECT+"?existing=true"),Instant.now()));
    }
    @Test void v4MatchesOfficialSdkVectorsIncludingUtf8EscapingAndSts() throws Exception {
        JsonObject fixture;
        try (var stream = getClass().getResourceAsStream("/oss-v4-sdk-vectors.json")) {
            assertNotNull(stream);
            fixture = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Instant when=Instant.parse(fixture.get("signingTime").getAsString());
        for(var element:fixture.getAsJsonArray("vectors")) {
            var vector=element.getAsJsonObject();
            var signer=new OssUrlSigner("example-bucket","cn-hangzhou","fake-key-id","fake-secret",vector.get("token").getAsString());
            URI signed=signer.sign(URI.create(vector.get("canonical").getAsString()),when,when.plusSeconds(180));
            Map<String,String> actual=new TreeMap<>();
            for(String part:signed.getRawQuery().split("&")) {
                String[] pair=part.split("=",2);
                actual.put(java.net.URLDecoder.decode(pair[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
            }
            Map<String,String> expected=new TreeMap<>();vector.getAsJsonObject("expected").entrySet().forEach(e->expected.put(e.getKey(),e.getValue().getAsString()));
            assertEquals(expected,actual,vector.get("canonical").getAsString());
        }
    }
}
