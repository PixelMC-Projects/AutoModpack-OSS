package pl.skidam.automodpack_core.oss;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class OssSecurityTest {
    @TempDir Path root;
    KeyPair publisher, server;
    PackTrust trust;
    @BeforeEach void setup() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519"); publisher=generator.generateKeyPair(); server=generator.generateKeyPair();
        trust=trust(publisher,server,1);
    }
    PackTrust trust(KeyPair publisher,KeyPair server,long minimum) throws Exception {
        return new PackTrust("fixture-pack","Fixture identity",Base64.getEncoder().encodeToString(publisher.getPublic().getEncoded()),
            Base64.getEncoder().encodeToString(server.getPublic().getEncoded()),URI.create("https://objects.example.test/fixture/active.json"),
            URI.create("https://auth.example.test/authorize"),Set.of("objects.example.test"),Set.of("mods","config"),minimum);
    }
    byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    JsonObject file(String path,String text) {
        var f=new JsonObject(); f.addProperty("path",path); f.addProperty("size",bytes(text).length); f.addProperty("sha256",ManifestVerifier.sha256(bytes(text)));
        f.addProperty("url","https://objects.example.test/fixture/releases/r1/files/"+path); return f;
    }
    JsonObject binaryFile(String path,byte[] raw) {
        var file=file(path,"");file.addProperty("size",raw.length);file.addProperty("sha256",ManifestVerifier.sha256(raw));return file;
    }
    byte[] jar(String name,String metadata) throws IOException {
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){zip.putNextEntry(new ZipEntry(name));zip.write(bytes(metadata));zip.closeEntry();}return output.toByteArray();
    }
    JsonObject payload(long sequence,JsonObject... entries) {
        var p=new JsonObject(); p.addProperty("schema",1);p.addProperty("packId","fixture-pack");p.addProperty("releaseId","r"+sequence);p.addProperty("sequence",sequence);
        p.addProperty("consentProtocolVersion",1);p.addProperty("managementScopeVersion","scope-1");p.addProperty("createdAt","2026-01-01T00:00:00Z");
        var files=new JsonArray();for(var f:entries)files.add(f);p.add("files",files);return p;
    }
    byte[] signed(JsonObject payload,KeyPair key) throws Exception {
        byte[] raw=StrictJson.bytes(payload);var sig=Signature.getInstance("Ed25519");sig.initSign(key.getPrivate());sig.update(raw);
        var envelope=new JsonObject();envelope.addProperty("schema",1);envelope.addProperty("payload",Base64.getEncoder().encodeToString(raw));envelope.addProperty("signature",Base64.getEncoder().encodeToString(sig.sign()));return StrictJson.bytes(envelope);
    }
    VerifiedManifest manifest(long sequence,JsonObject... files) throws Exception {return ManifestVerifier.verify(signed(payload(sequence,files),publisher),trust);}
    StagedInstaller.Stage prepare(VerifiedManifest manifest) throws Exception {return StagedInstaller.prepare(root,manifest,manifest.authorityId(),manifest.consentProtocolVersion(),manifest.managementScopeVersion());}
    void install(VerifiedManifest manifest,Map<String,String> content) throws Exception {
        var stage=prepare(manifest);for(var file:stage.requiredFiles()) stage.accept(file,new ByteArrayInputStream(bytes(content.get(file.path()))));stage.finish();
        assertEquals(StagedInstaller.Status.APPLIED,StagedInstaller.applyPending(root,new TrustStore(List.of(trust)),m->true).status());
    }
    @Test void missingTrustTrustsNobody() throws Exception { assertThrows(IOException.class,()->TrustStore.load(root.resolve("missing.json")).requirePack("fixture-pack")); }
    @Test void rejectsUnknownSigningKeyAndTampering() throws Exception {
        var p=payload(1,file("mods/a.jar","one"));var other=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IOException.class,()->ManifestVerifier.verify(signed(p,other),trust));
        var envelope=StrictJson.object(signed(p,publisher));var altered=p.deepCopy();altered.addProperty("releaseId","injected");envelope.addProperty("payload",Base64.getEncoder().encodeToString(StrictJson.bytes(altered)));
        assertThrows(IOException.class,()->ManifestVerifier.verify(StrictJson.bytes(envelope),trust));
    }
    @Test void serverIdentityIsSeparateFromPublisher() throws Exception {
        var object=StrictJson.object(signed(payload(1,file("mods/a.jar","one")),server));object.addProperty("packId","fixture-pack");
        assertNotNull(trust.verifyServerEnvelope(StrictJson.bytes(object)));
        assertThrows(IOException.class,()->ManifestVerifier.verify(StrictJson.bytes(object),trust));
        object.addProperty("packId","another-pack");assertThrows(IOException.class,()->trust.verifyServerEnvelope(StrictJson.bytes(object)));
    }
    @Test void signedUnsafePathsHostsAndDuplicatesStillFail() throws Exception {
        for(String path:List.of("mods/../bad.jar","/mods/a.jar","mods\\a.jar","config/CON.txt","config/a.","mods/a:b.jar","other/a.jar","mods//a.jar","config/automodpack-oss/trust.json","mods/AutoModpack-upstream.jar"))
            assertThrows(IOException.class,()->manifest(1,file(path,"one")),path);
        var wrongHost=file("mods/a.jar","one");wrongHost.addProperty("url","https://untrusted.example.test/a.jar");
        assertThrows(IOException.class,()->manifest(1,wrongHost));
        var huge=file("mods/a.jar","one");huge.addProperty("size",ManifestVerifier.MAX_FILE_SIZE+1);assertThrows(IOException.class,()->manifest(1,huge));
        assertThrows(IOException.class,()->manifest(1,file("mods/A.jar","one"),file("mods/a.jar","two")));
        assertThrows(IOException.class,()->StrictJson.object(bytes("{\"schema\":1,\"schema\":2}")));
    }
    @Test void transportCannotChangeSignedObjectOrLeakBearer() throws Exception {
        URI canonical=URI.create("https://objects.example.test/a.jar");trust.validateTransportUrl(canonical,URI.create(canonical+"?Signature=fixture&Expires=1"));
        for(String url:List.of("http://objects.example.test/a.jar","https://other.example.test/a.jar?Signature=x","https://objects.example.test/b.jar?Signature=x","https://user@objects.example.test/a.jar","https://objects.example.test:444/a.jar"))
            assertThrows(IOException.class,()->trust.validateTransportUrl(canonical,URI.create(url)));
        assertThrows(IOException.class,()->trust.validateAuthorizationEndpoint(URI.create("https://auth.example.test/steal")));
        assertThrows(IOException.class,()->trust.validateUrl(URI.create(canonical+"?Signature=x")));
        trust.validateManifestUrl(URI.create("https://objects.example.test/fixture/releases/r1/manifest.json"),"r1");
        assertThrows(IOException.class,()->trust.validateManifestUrl(trust.manifestUrl(),"r1"));
        assertThrows(IOException.class,()->trust.validateManifestUrl(URI.create("https://objects.example.test/fixture/releases/r2/manifest.json"),"r1"));
    }
    @Test void minimumAndHighestSeenPreventSignedReplayAndEquivocation() throws Exception {
        var low=signed(payload(1,file("mods/a.jar","one")),publisher);
        assertThrows(IOException.class,()->ManifestVerifier.verify(low,trust(publisher,server,2)));
        var newer=manifest(2,file("mods/a.jar","two"));StagedInstaller.observeManifest(root,newer);
        assertThrows(IOException.class,()->StagedInstaller.observeManifest(root,ManifestVerifier.verify(low,trust)));
        assertThrows(IOException.class,()->prepare(manifest(1,file("mods/a.jar","one"))));
        assertThrows(IOException.class,()->StagedInstaller.observeManifest(root,manifest(2,file("mods/a.jar","different"))));
    }
    @Test void validatesSignedSizeAndHashBeforePendingExists() throws Exception {
        var m=manifest(1,file("mods/a.jar","one"));var stage=prepare(m);
        assertThrows(IOException.class,()->stage.accept(m.files().get(0),new ByteArrayInputStream(bytes("oversized"))));
        assertThrows(IOException.class,()->stage.accept(m.files().get(0),new ByteArrayInputStream(bytes("bad"))));
        assertThrows(IOException.class,stage::finish);
        assertFalse(Files.exists(root.resolve(".automodpack-oss/pending.json")));assertFalse(Files.exists(root.resolve("mods/a.jar")));
    }
    @Test void stagesWithoutLiveMutationAndAppliesOnlyWithCurrentConsent() throws Exception {
        var m=manifest(1,file("mods/a.jar","one"));var stage=prepare(m);stage.accept(m.files().get(0),new ByteArrayInputStream(bytes("one")));stage.finish();
        assertFalse(Files.exists(root.resolve("mods/a.jar")));
        assertEquals(StagedInstaller.Status.CONSENT_REQUIRED,StagedInstaller.applyPending(root,new TrustStore(List.of(trust))).status());
        assertFalse(Files.exists(root.resolve("mods/a.jar")));
        install(m,Map.of("mods/a.jar","one"));assertTrue(StagedInstaller.matchesInstalled(root,m));
    }
    @Test void explicitConsentAdoptsIdenticalBytesWithoutRewrite() throws Exception {
        Files.createDirectories(root.resolve("mods"));Files.writeString(root.resolve("mods/a.jar"),"one");
        var timestamp=Files.getLastModifiedTime(root.resolve("mods/a.jar"));var m=manifest(1,file("mods/a.jar","one"));var stage=prepare(m);
        assertTrue(stage.requiredFiles().isEmpty());stage.finish();assertEquals(StagedInstaller.Status.APPLIED,StagedInstaller.applyPending(root,new TrustStore(List.of(trust)),v->true).status());
        assertEquals(timestamp,Files.getLastModifiedTime(root.resolve("mods/a.jar")));assertTrue(StagedInstaller.matchesInstalled(root,m));
    }
    @Test void preservesPersonalFilesAndUpdatesOnlyUntouchedOwnedFiles() throws Exception {
        var first=manifest(1,file("mods/a.jar","one"),file("config/owned.txt","owned"));install(first,Map.of("mods/a.jar","one","config/owned.txt","owned"));
        Files.writeString(root.resolve("config/personal.txt"),"personal");
        var second=manifest(2,file("mods/a.jar","two"));install(second,Map.of("mods/a.jar","two"));
        assertEquals("two",Files.readString(root.resolve("mods/a.jar")));assertFalse(Files.exists(root.resolve("config/owned.txt")));assertEquals("personal",Files.readString(root.resolve("config/personal.txt")));
        Files.writeString(root.resolve("mods/a.jar"),"my edit");
        assertThrows(IOException.class,()->prepare(manifest(3,file("mods/a.jar","three"))));assertEquals("my edit",Files.readString(root.resolve("mods/a.jar")));
        assertTrue(Files.walk(root.resolve(".automodpack-oss/backups")).anyMatch(p->p.getFileName().toString().equals("a.jar")));
    }
    @Test void personalConflictAppearingAfterDownloadBlocksApply() throws Exception {
        var m=manifest(1,file("mods/a.jar","one"));var stage=prepare(m);stage.accept(m.files().get(0),new ByteArrayInputStream(bytes("one")));stage.finish();
        Files.createDirectories(root.resolve("mods"));Files.writeString(root.resolve("mods/a.jar"),"personal");
        assertEquals(StagedInstaller.Status.CONFLICT,StagedInstaller.applyPending(root,new TrustStore(List.of(trust)),v->true).status());assertEquals("personal",Files.readString(root.resolve("mods/a.jar")));
    }
    @Test void recoversCrashBetweenMoveAndLedgerCommit() throws Exception {
        var first=manifest(1,file("mods/a.jar","one"));install(first,Map.of("mods/a.jar","one"));
        String id=UUID.randomUUID().toString();Path internal=root.resolve(".automodpack-oss");var oldState=StrictJson.object(Files.readAllBytes(internal.resolve("installed.json")));
        Path backup=internal.resolve("backups/"+id+"/files/mods/a.jar");Files.createDirectories(backup.getParent());Files.move(root.resolve("mods/a.jar"),backup);Files.writeString(root.resolve("mods/a.jar"),"two");
        Files.writeString(root.resolve("config-new.tmp"),"personal outside management");
        var ops=new JsonArray();var op=new JsonObject();op.addProperty("path","mods/a.jar");op.addProperty("oldHash",ManifestVerifier.sha256(bytes("one")));op.addProperty("newHash",ManifestVerifier.sha256(bytes("two")));ops.add(op);
        var journal=new JsonObject();journal.addProperty("id",id);journal.addProperty("status","PREPARED");journal.add("oldState",oldState);journal.add("operations",ops);Files.write(internal.resolve("transaction.json"),StrictJson.bytes(journal));
        assertEquals(StagedInstaller.Status.RECOVERED,StagedInstaller.applyPending(root,new TrustStore(List.of(trust)),v->true).status());
        assertEquals("one",Files.readString(root.resolve("mods/a.jar")));assertTrue(StagedInstaller.matchesInstalled(root,first));assertEquals("personal outside management",Files.readString(root.resolve("config-new.tmp")));
    }
    @Test void rejectsLinkedTargets() throws Exception {
        Path outside=Files.createTempDirectory("oss-outside-fixture-");Path link=root.resolve("mods");
        try {
            try { Files.createSymbolicLink(link,outside); }
            catch(IOException | UnsupportedOperationException ex) { Assumptions.abort("Symbolic-link creation unavailable"); }
            var m=manifest(1,file("mods/a.jar","one"));assertThrows(IOException.class,()->prepare(m));assertFalse(Files.exists(outside.resolve("a.jar")));
        } finally {Files.deleteIfExists(link);Files.deleteIfExists(outside);}
    }
    @Test void rejectsWindowsJunctionTargets() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));
        Path outside=Files.createTempDirectory("oss-junction-fixture-");Path link=root.resolve("mods");
        try {
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();Assumptions.assumeTrue(process.waitFor()==0,"Junction creation unavailable");
            var m=manifest(1,file("mods/a.jar","one"));assertThrows(IOException.class,()->prepare(m));assertFalse(Files.exists(outside.resolve("a.jar")));
        } finally {Files.deleteIfExists(link);Files.deleteIfExists(outside);}
    }
    @Test void reconnectReusesOnlyCompletelyVerifiedStagedFiles() throws Exception {
        var m=manifest(1,file("mods/a.jar","one"),file("mods/b.jar","two"));var first=prepare(m);
        first.accept(m.files().get(0),new ByteArrayInputStream(bytes("one")));
        var resumed=prepare(m);assertEquals(List.of(m.files().get(1)),resumed.requiredFiles());
        resumed.accept(m.files().get(1),new ByteArrayInputStream(bytes("two")));resumed.finish();resumed=prepare(m);assertTrue(resumed.requiredFiles().isEmpty());resumed.finish();
        assertEquals(StagedInstaller.Status.APPLIED,StagedInstaller.applyPending(root,new TrustStore(List.of(trust)),v->true).status());
        assertEquals("one",Files.readString(root.resolve("mods/a.jar")));assertEquals("two",Files.readString(root.resolve("mods/b.jar")));
    }
    @Test void renamedUpdaterMainIdsAreRejectedButDependenciesAreAllowed() throws Exception {
        List<byte[]> updaters=List.of(jar("fabric.mod.json","{\"id\":\"automodpack\"}"),jar("quilt.mod.json","{\"quilt_loader\":{\"id\":\"automodpack\"}}"),
            jar("META-INF/mods.toml","[[mods]]\nmodId=\"automodpack_mod\"\n"),jar("META-INF/neoforge.mods.toml","[[mods]]\nmodId='automodpack_mod'\n"));
        long sequence=1;
        for(byte[] raw:updaters){var m=manifest(sequence++,binaryFile("mods/renamed.jar",raw));var stage=prepare(m);assertThrows(IOException.class,()->stage.accept(m.files().get(0),new ByteArrayInputStream(raw)));}
        byte[] raw=jar("META-INF/mods.toml","[[mods]]\nmodId=\"fixture_compat\"\n[[dependencies.fixture_compat]]\nmodId=\"automodpack_mod\"\n");
        var m=manifest(sequence,binaryFile("mods/compat.jar",raw));var stage=prepare(m);stage.accept(m.files().get(0),new ByteArrayInputStream(raw));stage.finish();
        assertEquals(StagedInstaller.Status.APPLIED,StagedInstaller.applyPending(root,new TrustStore(List.of(trust)),v->true).status());
    }
    @Test void oversizedCompressedModMetadataIsRejectedBeforeApply() throws Exception {
        byte[] raw=jar("fabric.mod.json","{\"id\":\"fixture\",\"description\":\""+"a".repeat(129*1024)+"\"}");
        var m=manifest(1,binaryFile("mods/fixture.jar",raw));var stage=prepare(m);assertThrows(IOException.class,()->stage.accept(m.files().get(0),new ByteArrayInputStream(raw)));
        assertFalse(Files.exists(root.resolve("mods/fixture.jar")));
    }
    @Test void playerAddedExtraModsAreNeitherRejectedNorManaged() throws Exception {
        Files.createDirectories(root.resolve("mods"));Files.writeString(root.resolve("mods/extra.jar"),"player-selected client mod");
        var first=manifest(1,file("mods/a.jar","one"));install(first,Map.of("mods/a.jar","one"));
        assertTrue(StagedInstaller.matchesInstalled(root,first));assertEquals("player-selected client mod",Files.readString(root.resolve("mods/extra.jar")));
        var second=manifest(2,file("mods/a.jar","two"));install(second,Map.of("mods/a.jar","two"));
        assertTrue(StagedInstaller.matchesInstalled(root,second));assertEquals("player-selected client mod",Files.readString(root.resolve("mods/extra.jar")));
        var collision=manifest(3,file("mods/a.jar","three"),file("mods/extra.jar","server bytes"));
        assertThrows(IOException.class,()->prepare(collision));assertEquals("player-selected client mod",Files.readString(root.resolve("mods/extra.jar")));
    }
    @Test void publisherAndServerIdentityMustBeIndependent() throws Exception {assertThrows(IOException.class,()->trust(publisher,publisher,1));}
}
