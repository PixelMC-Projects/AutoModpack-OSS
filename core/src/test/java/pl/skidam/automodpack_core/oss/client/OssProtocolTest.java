package pl.skidam.automodpack_core.oss.client;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.oss.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OssProtocolTest {
    @TempDir Path root;
    KeyPair publisher, server;
    PackTrust trust;
    UUID account = UUID.fromString("00000000-0000-4000-8000-000000000001");
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    int queryId = 0x6f535332;
    @BeforeEach void setup() throws Exception {
        publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        server = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        trust = trust(server);
    }
    PackTrust trust(KeyPair serverIdentity) throws Exception {
        return new PackTrust("fixture-pack", "Fixture maintainer", Base64.getEncoder().encodeToString(publisher.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(serverIdentity.getPublic().getEncoded()), URI.create("https://objects.example.test/active.json"),
                null, Set.of("objects.example.test"), Set.of("mods", "config"), 1);
    }
    JsonObject offer() {
        JsonObject p = new JsonObject(); p.addProperty("schema",1); p.addProperty("type","offer"); p.addProperty("packId","fixture-pack");
        p.addProperty("queryId",queryId); p.addProperty("clientNonce",nonce); p.addProperty("onlineAuthenticated",true);
        p.addProperty("playerUuid",account.toString()); p.addProperty("playerName","FixturePlayer");
        p.addProperty("issuedAt",1000); p.addProperty("queryExpiresAt",1180); p.addProperty("releaseId","r1"); p.addProperty("sequence",1);
        p.addProperty("urlExpiresAt",1120);
        p.addProperty("manifestUrl","https://objects.example.test/releases/r1/manifest.json");
        p.addProperty("manifestTransportUrl","https://objects.example.test/releases/r1/manifest.json?signed=fixture");
        p.addProperty("manifestSha256","0".repeat(64));
        p.addProperty("authorizationTransport","minecraft-login"); return p;
    }
    byte[] signed(JsonObject object, KeyPair signer, boolean identityEnvelope) throws Exception {
        byte[] payload=object.toString().getBytes(StandardCharsets.UTF_8);
        Signature signature=Signature.getInstance("Ed25519"); signature.initSign(signer.getPrivate()); signature.update(payload);
        JsonObject envelope=new JsonObject(); envelope.addProperty("schema",1);
        if(identityEnvelope) envelope.addProperty("packId","fixture-pack");
        envelope.addProperty("payload",Base64.getEncoder().encodeToString(payload)); envelope.addProperty("signature",Base64.getEncoder().encodeToString(signature.sign()));
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }
    VerifiedManifest manifest(long sequence, int protocol, String scope, PackTrust localTrust) throws Exception {
        return manifest(sequence, protocol, scope, localTrust, ReleaseChannel.STABLE, new byte[0]);
    }
    VerifiedManifest manifest(
            long sequence,
            int protocol,
            String scope,
            PackTrust localTrust,
            ReleaseChannel channel,
            byte[] fileBytes)
            throws Exception {
        JsonObject p = new JsonObject(); p.addProperty("schema",1); p.addProperty("packId","fixture-pack");
        p.addProperty("releaseId",(channel == ReleaseChannel.PREVIEW ? "p" : "r")+sequence);
        p.addProperty("channel",channel.id());
        p.addProperty("sequence",sequence); p.addProperty("consentProtocolVersion",protocol); p.addProperty("managementScopeVersion",scope);
        p.addProperty("createdAt","2026-01-01T00:00:00Z"); JsonObject f=new JsonObject(); f.addProperty("path","mods/fixture.jar"); f.addProperty("size",fileBytes.length);
        f.addProperty("sha256",ManifestVerifier.sha256(fileBytes)); f.addProperty("url","https://objects.example.test/releases/"+p.get("releaseId").getAsString()+"/fixture.jar");
        JsonArray files=new JsonArray(); files.add(f); p.add("files",files);
        return ManifestVerifier.verify(signed(p,publisher,false),localTrust);
    }
    @Test void signedOfferRequiresThisNonceQueryAndProvisionedServerKey() throws Exception {
        byte[] envelope=signed(offer(),server,true);
        JsonObject verified=OssProtocol.signedMessage(trust,envelope,nonce,queryId);
        assertEquals(account,OssProtocol.offer(trust,verified,account,"FixturePlayer",1001).account());
        assertThrows(IOException.class,()->OssProtocol.signedMessage(trust,envelope,"another-session",queryId));
        assertThrows(IOException.class,()->OssProtocol.signedMessage(trust,envelope,nonce,queryId+1));
        var attacker=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IOException.class,()->OssProtocol.signedMessage(trust,signed(offer(),attacker,true),nonce,queryId));
    }
    @Test void rejectsUnauthenticatedWrongAccountExpiredAndChangedObjects() throws Exception {
        JsonObject p=offer(); p.addProperty("onlineAuthenticated",false);
        assertThrows(IOException.class,()->OssProtocol.offer(trust,p,account,"FixturePlayer",1001));
        assertThrows(IOException.class,()->OssProtocol.offer(trust,offer(),UUID.randomUUID(),"FixturePlayer",1001));
        assertThrows(IOException.class,()->OssProtocol.offer(trust,offer(),account,"AnotherName",1001));
        assertThrows(IOException.class,()->OssProtocol.offer(trust,offer(),account,"FixturePlayer",1181));
        JsonObject wrongHost=offer(); wrongHost.addProperty("manifestTransportUrl","https://attacker.example.test/active.json");
        assertThrows(IOException.class,()->OssProtocol.offer(trust,wrongHost,account,"FixturePlayer",1001));
        JsonObject wrongPath=offer(); wrongPath.addProperty("manifestTransportUrl","https://objects.example.test/unannounced.json");
        assertThrows(IOException.class,()->OssProtocol.offer(trust,wrongPath,account,"FixturePlayer",1001));
    }
    @Test void grantsSurviveNormalUpdatesButScopeProtocolIdentityAndAccountRequireConsent() throws Exception {
        ConsentStore store=new ConsentStore(root); VerifiedManifest original=manifest(1,1,"scope-1",trust);
        assertFalse(store.permits(original,account)); store.grant(original,account);
        store=new ConsentStore(root);
        assertTrue(store.permits(manifest(2,1,"scope-1",trust),account));
        assertFalse(store.permits(manifest(2,2,"scope-1",trust),account));
        assertFalse(store.permits(manifest(2,1,"scope-2",trust),account));
        assertFalse(store.permits(original,UUID.randomUUID()));
        assertFalse(store.permits(manifest(2,1,"scope-1",trust(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())),account));
        store.setEnabled(false); assertFalse(new ConsentStore(root).hasAnyGrant(original));
        store.revokeAll(); assertEquals(0,new ConsentStore(root).grantCount());
    }
    @Test void provisionedRootExpansionRequiresNewConsentEvenIfVersionWasNotBumped() throws Exception {
        ConsentStore store = new ConsentStore(root); store.grant(manifest(1,1,"scope-1",trust),account);
        PackTrust expanded = new PackTrust("fixture-pack", "Fixture maintainer", Base64.getEncoder().encodeToString(publisher.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(server.getPublic().getEncoded()), trust.manifestUrl(), null,
                trust.allowedHosts(), Set.of("mods","config","resourcepacks"),1);
        assertFalse(store.permits(manifest(2,1,"scope-1",expanded),account));
    }
    @Test void revocationAfterDownloadPreventsNextBootFromApplyingPendingFiles() throws Exception {
        VerifiedManifest manifest = manifest(1,1,"scope-1",trust);
        ConsentStore store = new ConsentStore(root); store.grant(manifest,account);
        var stage = StagedInstaller.prepare(root,manifest,manifest.authorityId(),1,"scope-1");
        stage.accept(manifest.files().get(0),new ByteArrayInputStream(new byte[0])); stage.finish();
        store.revokeAll(); ConsentStore revoked = new ConsentStore(root);
        assertEquals(StagedInstaller.Status.CONSENT_REQUIRED,StagedInstaller.applyPending(root,new TrustStore(List.of(trust)),revoked::hasAnyGrant).status());
        assertFalse(Files.exists(root.resolve("mods/fixture.jar")));
    }
    @Test void previewChannelRequiresOptInAndSupportsSafeSwitchBackToStable() throws Exception {
        byte[] stableBytes = "stable-v2".getBytes(StandardCharsets.UTF_8);
        byte[] previewBytes = "preview-v5".getBytes(StandardCharsets.UTF_8);
        VerifiedManifest stable2 = manifest(2, 1, "scope-1", trust, ReleaseChannel.STABLE, stableBytes);
        VerifiedManifest stable1 = manifest(1, 1, "scope-1", trust, ReleaseChannel.STABLE, "stable-v1".getBytes(StandardCharsets.UTF_8));
        VerifiedManifest preview5 = manifest(5, 1, "scope-1", trust, ReleaseChannel.PREVIEW, previewBytes);
        ConsentStore store = new ConsentStore(root);
        assertEquals(ReleaseChannel.STABLE, store.channel());
        store.grant(stable2, account);
        assertTrue(store.permits(stable2, account));
        assertFalse(store.permits(preview5, account));

        // Opt into preview channel and install preview sequence 5.
        store.setChannel(ReleaseChannel.PREVIEW);
        assertTrue(new ConsentStore(root).permits(preview5, account));
        var previewStage = StagedInstaller.prepare(root, preview5, preview5.authorityId(), 1, "scope-1", ReleaseChannel.PREVIEW);
        previewStage.accept(preview5.files().get(0), new ByteArrayInputStream(previewBytes));
        previewStage.finish();
        assertEquals(
                StagedInstaller.Status.APPLIED,
                StagedInstaller.applyPending(root, new TrustStore(List.of(trust)), new ConsentStore(root)::hasAnyGrant, ReleaseChannel.PREVIEW).status());
        assertArrayEquals(previewBytes, Files.readAllBytes(root.resolve("mods/fixture.jar")));

        // Switch explicitly back to stable: lower sequence (2 < 5) is allowed across channels,
        // while same-channel downgrade (2 -> 1) remains strictly blocked.
        store.setChannel(ReleaseChannel.STABLE);
        var stableStage = StagedInstaller.prepare(root, stable2, stable2.authorityId(), 1, "scope-1", ReleaseChannel.STABLE);
        stableStage.accept(stable2.files().get(0), new ByteArrayInputStream(stableBytes));
        stableStage.finish();
        assertEquals(
                StagedInstaller.Status.APPLIED,
                StagedInstaller.applyPending(root, new TrustStore(List.of(trust)), new ConsentStore(root)::hasAnyGrant, ReleaseChannel.STABLE).status());
        assertArrayEquals(stableBytes, Files.readAllBytes(root.resolve("mods/fixture.jar")));
        assertThrows(
                IOException.class,
                () -> StagedInstaller.prepare(root, stable1, stable1.authorityId(), 1, "scope-1", ReleaseChannel.STABLE));
    }
}
