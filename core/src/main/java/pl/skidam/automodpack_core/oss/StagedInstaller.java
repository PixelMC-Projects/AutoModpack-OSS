package pl.skidam.automodpack_core.oss;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Download while playing; apply only from a caller's pre-mod-load bootstrap hook. Never launches/restarts. */
public final class StagedInstaller {
    private static final String DIR = ".automodpack-oss";
    private StagedInstaller() {}
    public enum Status { NONE, APPLIED, RECOVERED, CONFLICT, CONSENT_REQUIRED }
    public record ApplyResult(Status status, String message) {}
    private interface Work<T> { T run(Path internal) throws IOException; }
    private static <T> T locked(Path root, Work<T> work) throws IOException {
        Path internal = SafePaths.child(root,DIR); SafePaths.directories(internal);
        Path lock = SafePaths.child(internal,"install.lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
             var held = channel.tryLock()) {
            if (held == null) throw new IOException("Another instance is using the installer");
            return work.run(internal);
        } catch (OverlappingFileLockException ex) { throw new IOException("Another installer operation is in progress",ex); }
    }
    private static JsonObject readObject(Path path) throws IOException {
        return Files.exists(path,LinkOption.NOFOLLOW_LINKS) ? StrictJson.object(SafePaths.readBounded(path,StrictJson.MAX_BYTES)) : new JsonObject();
    }
    private static JsonObject state(Path internal) throws IOException { return readObject(SafePaths.child(internal,"installed.json")); }
    private static JsonObject map(JsonObject object, String key) throws IOException {
        if (!object.has(key)) { var map = new JsonObject(); object.add(key,map); return map; }
        if (!object.get(key).isJsonObject()) throw new IOException("Invalid installer state"); return object.getAsJsonObject(key);
    }
    private static void assertIdentity(JsonObject previous, VerifiedManifest manifest) throws IOException {
        if (!manifest.authorityId().equals(StrictJson.string(previous,"authorityId")) ||
            !manifest.serverIdentityId().equals(StrictJson.string(previous,"serverIdentityId"))) throw new IOException("Provisioned identity changed; manual migration required");
    }
    private static void checkSequence(Path internal, VerifiedManifest manifest, boolean record) throws IOException {
        Path path = SafePaths.child(internal,"highest-seen.json"); var root = readObject(path);
        if (root.has(manifest.packId())) {
            var previous = root.getAsJsonObject(manifest.packId()); assertIdentity(previous,manifest);
            long sequence = StrictJson.number(previous,"sequence");
            if (manifest.sequence() < sequence || (manifest.sequence() == sequence && !manifest.payloadDigest().equals(StrictJson.string(previous,"payloadDigest")))) throw new IOException("Signed release rollback/equivocation refused");
        }
        if (record) { root.add(manifest.packId(), releaseMetadata(manifest)); SafePaths.atomicWrite(path,StrictJson.bytes(root)); }
    }
    private static JsonObject releaseMetadata(VerifiedManifest manifest) {
        var result = new JsonObject(); result.addProperty("authorityId",manifest.authorityId()); result.addProperty("serverIdentityId",manifest.serverIdentityId());
        result.addProperty("sequence",manifest.sequence()); result.addProperty("releaseId",manifest.releaseId()); result.addProperty("payloadDigest",manifest.payloadDigest());
        result.addProperty("consentProtocolVersion",manifest.consentProtocolVersion()); result.addProperty("managementScopeVersion",manifest.managementScopeVersion()); return result;
    }
    public static void observeManifest(Path gameRoot, VerifiedManifest manifest) throws IOException {
        locked(gameRoot, internal -> { checkSequence(internal,manifest,true); return null; });
    }
    public static boolean matchesInstalled(Path gameRoot, VerifiedManifest manifest) throws IOException {
        SafePaths.checkChain(gameRoot); Path internal = SafePaths.child(gameRoot,DIR);
        if (!Files.exists(internal,LinkOption.NOFOLLOW_LINKS)) return false;
        JsonObject installed = state(internal), packs = map(installed,"packs"), files = map(installed,"files");
        if (!packs.has(manifest.packId())) return false;
        var metadata = packs.getAsJsonObject(manifest.packId()); assertIdentity(metadata,manifest);
        if (!manifest.payloadDigest().equals(StrictJson.string(metadata,"payloadDigest"))) return false;
        Set<String> paths = new HashSet<>();
        for (var file : manifest.files()) {
            paths.add(file.path());
            if (!files.has(file.path()) || !manifest.packId().equals(StrictJson.string(files.getAsJsonObject(file.path()),"packId")) ||
                !SafePaths.matches(SafePaths.resolve(gameRoot,file.path(),manifest.trust().allowedRoots()),file.size(),file.sha256())) return false;
        }
        for (var entry : files.entrySet()) if (manifest.packId().equals(StrictJson.string(entry.getValue().getAsJsonObject(),"packId")) && !paths.contains(entry.getKey())) return false;
        return true;
    }
    public static Stage prepare(Path gameRoot, VerifiedManifest manifest, String authorityId, int protocolVersion, String scopeVersion) throws IOException {
        if (!manifest.authorityId().equals(authorityId) || protocolVersion != manifest.consentProtocolVersion() || !manifest.managementScopeVersion().equals(scopeVersion)) throw new IOException("Consent does not match signed management scope");
        return locked(gameRoot, internal -> {
            if (Files.exists(SafePaths.child(internal,"transaction.json"),LinkOption.NOFOLLOW_LINKS)) throw new IOException("Pending recovery must run before staging");
            checkSequence(internal,manifest,true); preflight(gameRoot,state(internal),manifest);
            Path staging=SafePaths.child(internal,"staging");
            if(Files.isDirectory(staging,LinkOption.NOFOLLOW_LINKS)) {
                try(var entries=Files.list(staging)) {
                    for(Path candidate:entries.limit(256).toList()) {
                        if(!candidate.getFileName().toString().matches("[0-9a-f-]{36}") || !Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS)) continue;
                        try {
                            SafePaths.checkChain(candidate);
                            var existing=ManifestVerifier.verify(SafePaths.readBounded(SafePaths.child(candidate,"manifest.json"),StrictJson.MAX_BYTES),manifest.trust());
                            var consent=readObject(SafePaths.child(candidate,"consent.json")); assertIdentity(consent,manifest);
                            if(existing.payloadDigest().equals(manifest.payloadDigest()) && StrictJson.string(consent,"payloadDigest").equals(manifest.payloadDigest()) &&
                                StrictJson.number(consent,"consentProtocolVersion")==manifest.consentProtocolVersion() && StrictJson.string(consent,"managementScopeVersion").equals(manifest.managementScopeVersion()))
                                return new Stage(gameRoot.toAbsolutePath().normalize(),candidate,manifest);
                        } catch(IOException ignored) { /* Preserve incomplete/corrupt stages; never reuse unverified bytes. */ }
                    }
                }
            }
            String id = UUID.randomUUID().toString(); Path stage = SafePaths.child(internal,"staging/" + id); SafePaths.directories(stage);
            SafePaths.atomicWrite(SafePaths.child(stage,"manifest.json"),manifest.envelopeBytes());
            SafePaths.atomicWrite(SafePaths.child(stage,"consent.json"),StrictJson.bytes(releaseMetadata(manifest)));
            return new Stage(gameRoot.toAbsolutePath().normalize(),stage,manifest);
        });
    }
    public static final class Stage {
        private final Path gameRoot, directory;
        private final VerifiedManifest manifest;
        private boolean finished;
        private Stage(Path gameRoot, Path directory, VerifiedManifest manifest) { this.gameRoot=gameRoot; this.directory=directory; this.manifest=manifest; }
        public List<ManifestFile> requiredFiles() throws IOException {
            var required = new ArrayList<ManifestFile>();
            for (var file : manifest.files()) if (!SafePaths.matches(SafePaths.resolve(gameRoot,file.path(),manifest.trust().allowedRoots()),file.size(),file.sha256()) &&
                !SafePaths.matches(staged(file),file.size(),file.sha256())) required.add(file);
            return List.copyOf(required);
        }
        private Path staged(ManifestFile file) throws IOException { return SafePaths.child(directory,"files/"+file.path()); }
        public void accept(ManifestFile file, InputStream source) throws IOException {
            if (finished || !manifest.files().contains(file)) throw new IOException("File is not in this signed release");
            Path target=staged(file); SafePaths.directories(target.getParent());
            Path temp=target.resolveSibling(target.getFileName()+".part-"+UUID.randomUUID());
            try {
                MessageDigest digest=MessageDigest.getInstance("SHA-256"); long count=0;
                try (var out=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer=new byte[64*1024];
                    for (int n; (n=source.read(buffer))!=-1;) {
                        count+=n; if(count>file.size()) throw new IOException("Download exceeds signed size");
                        digest.update(buffer,0,n); var bytes=java.nio.ByteBuffer.wrap(buffer,0,n); while(bytes.hasRemaining()) out.write(bytes);
                    }
                    out.force(true);
                }
                if(count!=file.size() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) throw new IOException("Downloaded file failed signed size/hash verification");
                UpdaterGuard.inspect(file.path(),temp);
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) { SafePaths.requireRegularFile(target); Files.delete(target); }
                SafePaths.move(temp,target);
            } catch (GeneralSecurityException ex) { throw new IOException(ex); }
            finally { Files.deleteIfExists(temp); }
        }
        public void finish() throws IOException {
            if(finished) throw new IOException("Stage already finished");
            locked(gameRoot, internal -> {
                checkSequence(internal,manifest,false); preflight(gameRoot,state(internal),manifest);
                if(!requiredFiles().isEmpty()) throw new IOException("Release is not fully downloaded");
                Path pending=SafePaths.child(internal,"pending.json");
                if(Files.exists(pending,LinkOption.NOFOLLOW_LINKS)) {
                    String previous=StrictJson.string(readObject(pending),"stage");
                    if(!previous.matches("[0-9a-f-]{36}")) throw new IOException("Invalid pending stage");
                    if(previous.equals(directory.getFileName().toString())) { finished=true; return null; }
                    var old=ManifestVerifier.verify(SafePaths.readBounded(SafePaths.child(internal,"staging/"+previous+"/manifest.json"),StrictJson.MAX_BYTES),manifest.trust());
                    if(old.sequence()>=manifest.sequence()) throw new IOException("A newer/equal installation is already pending");
                }
                var record=new JsonObject(); record.addProperty("stage",directory.getFileName().toString());
                SafePaths.atomicWrite(pending,StrictJson.bytes(record)); finished=true; return null;
            });
        }
    }
    private static void preflight(Path root, JsonObject installed, VerifiedManifest manifest) throws IOException {
        JsonObject owners=map(installed,"files"), packs=map(installed,"packs");
        if(packs.has(manifest.packId())) { var old=packs.getAsJsonObject(manifest.packId()); assertIdentity(old,manifest); if(StrictJson.number(old,"sequence")>manifest.sequence()) throw new IOException("Installed release rollback refused"); }
        Set<String> desired=new HashSet<>();
        for(var file:manifest.files()) {
            desired.add(file.path()); Path target=SafePaths.resolve(root,file.path(),manifest.trust().allowedRoots());
            for(var owner:owners.entrySet()) if(owner.getKey().equalsIgnoreCase(file.path()) && !owner.getKey().equals(file.path())) throw new IOException("Case-colliding managed file");
            if(owners.has(file.path())) {
                var old=owners.getAsJsonObject(file.path());
                if(!manifest.packId().equals(StrictJson.string(old,"packId"))) throw new IOException("File belongs to another pack");
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !SafePaths.matches(target,StrictJson.number(old,"size"),StrictJson.string(old,"sha256"))) throw new IOException("Personal file conflict: "+file.path());
            } else if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !SafePaths.matches(target,file.size(),file.sha256())) throw new IOException("Personal file conflict: "+file.path());
            if(SafePaths.matches(target,file.size(),file.sha256())) UpdaterGuard.inspect(file.path(),target);
        }
        for(var entry:owners.entrySet()) {
            var old=entry.getValue().getAsJsonObject();
            if(manifest.packId().equals(StrictJson.string(old,"packId")) && !desired.contains(entry.getKey())) {
                // A scope reduction never authorizes deletion outside the newly consented roots.
                Path target=SafePaths.resolve(root,entry.getKey(),manifest.trust().allowedRoots());
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !SafePaths.matches(target,StrictJson.number(old,"size"),StrictJson.string(old,"sha256"))) throw new IOException("Personal removed-file conflict: "+entry.getKey());
            }
        }
    }
    public static ApplyResult applyPending(Path gameRoot, TrustStore trustStore) throws IOException {
        return applyPending(gameRoot,trustStore,manifest -> false);
    }
    public static ApplyResult applyPending(Path gameRoot, TrustStore trustStore, java.util.function.Predicate<VerifiedManifest> consentChecker) throws IOException {
        Objects.requireNonNull(consentChecker,"consentChecker");
        return locked(gameRoot, internal -> {
            boolean recovered=recover(gameRoot,internal);
            Path pending=SafePaths.child(internal,"pending.json");
            if(!Files.exists(pending,LinkOption.NOFOLLOW_LINKS)) return new ApplyResult(recovered?Status.RECOVERED:Status.NONE,"No pending installation");
            String id=StrictJson.string(readObject(pending),"stage");
            if(!id.matches("[0-9a-f-]{36}")) throw new IOException("Invalid pending stage");
            Path stage=SafePaths.child(internal,"staging/"+id); byte[] envelope=SafePaths.readBounded(SafePaths.child(stage,"manifest.json"),StrictJson.MAX_BYTES);
            // Parse only the signed pack name to select a local key; verification remains mandatory.
            var outer=StrictJson.object(envelope); byte[] payload;
            try { payload=Base64.getDecoder().decode(StrictJson.string(outer,"payload")); } catch(IllegalArgumentException ex) { throw new IOException("Invalid pending envelope",ex); }
            var manifest=ManifestVerifier.verify(envelope,trustStore.requirePack(StrictJson.string(StrictJson.object(payload),"packId")));
            if(!consentChecker.test(manifest)) {
                Files.delete(pending);
                return new ApplyResult(Status.CONSENT_REQUIRED,"Local management consent was revoked or is unavailable; staged files retained without applying");
            }
            checkSequence(internal,manifest,false); JsonObject consent=readObject(SafePaths.child(stage,"consent.json")); assertIdentity(consent,manifest);
            if(StrictJson.number(consent,"consentProtocolVersion")!=manifest.consentProtocolVersion() || !StrictJson.string(consent,"managementScopeVersion").equals(manifest.managementScopeVersion()) || !StrictJson.string(consent,"payloadDigest").equals(manifest.payloadDigest())) throw new IOException("Pending consent mismatch");
            JsonObject oldState=state(internal);
            try { preflight(gameRoot,oldState,manifest); }
            catch(IOException conflict) { return new ApplyResult(Status.CONFLICT,conflict.getMessage()); }
            JsonObject next=oldState.deepCopy(); JsonObject owners=map(next,"files"), packs=map(next,"packs");
            JsonArray operations=new JsonArray(); Set<String> desired=new HashSet<>();
            for(var file:manifest.files()) {
                desired.add(file.path()); Path target=SafePaths.resolve(gameRoot,file.path(),manifest.trust().allowedRoots());
                if(!SafePaths.matches(target,file.size(),file.sha256())) {
                    Path staged=SafePaths.child(stage,"files/"+file.path());
                    if(!SafePaths.matches(staged,file.size(),file.sha256())) throw new IOException("Staged file is absent or corrupted");
                    UpdaterGuard.inspect(file.path(),staged);
                    operations.add(operation(file.path(),Files.exists(target,LinkOption.NOFOLLOW_LINKS)?SafePaths.hash(target):null,file.sha256()));
                }
                var owner=new JsonObject(); owner.addProperty("packId",manifest.packId()); owner.addProperty("size",file.size()); owner.addProperty("sha256",file.sha256()); owners.add(file.path(),owner);
            }
            for(var entry:new ArrayList<>(owners.entrySet())) if(manifest.packId().equals(StrictJson.string(entry.getValue().getAsJsonObject(),"packId")) && !desired.contains(entry.getKey())) {
                Path target=SafePaths.resolve(gameRoot,entry.getKey(),manifest.trust().allowedRoots());
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) operations.add(operation(entry.getKey(),SafePaths.hash(target),null)); owners.remove(entry.getKey());
            }
            packs.add(manifest.packId(),releaseMetadata(manifest));
            var journal=new JsonObject(); journal.addProperty("id",id); journal.addProperty("status","PREPARED"); journal.add("oldState",oldState); journal.add("operations",operations);
            Path journalPath=SafePaths.child(internal,"transaction.json"); SafePaths.atomicWrite(journalPath,StrictJson.bytes(journal));
            boolean committed=false;
            try {
                for(var element:operations) {
                    var op=element.getAsJsonObject(); String path=StrictJson.string(op,"path"); Path target=SafePaths.resolve(gameRoot,path,SafePaths.MANAGED_ROOTS);
                    if(op.has("oldHash")) {
                        if(!SafePaths.hash(target).equals(StrictJson.string(op,"oldHash"))) throw new IOException("File changed during apply");
                        SafePaths.move(target,SafePaths.child(internal,"backups/"+id+"/files/"+path));
                    } else if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("New file appeared during apply");
                    if(op.has("newHash")) SafePaths.move(SafePaths.child(stage,"files/"+path),target);
                }
                SafePaths.atomicWrite(SafePaths.child(internal,"installed.json"),StrictJson.bytes(next));
                journal.addProperty("status","COMMITTED"); SafePaths.atomicWrite(journalPath,StrictJson.bytes(journal));
                committed=true;
                Files.delete(pending); Files.delete(journalPath);
                return new ApplyResult(Status.APPLIED,"Signed release applied before mod loading; backups retained");
            } catch(IOException failure) {
                try { recover(gameRoot,internal); } catch(IOException recovery) { failure.addSuppressed(recovery); throw failure; }
                if(committed) return new ApplyResult(Status.APPLIED,"Signed release committed; journal cleanup completed by recovery");
                throw new IOException("Installation failed and was rolled back",failure);
            }
        });
    }
    private static JsonObject operation(String path,String oldHash,String newHash) {
        var op=new JsonObject(); op.addProperty("path",path); if(oldHash!=null) op.addProperty("oldHash",oldHash); if(newHash!=null) op.addProperty("newHash",newHash); return op;
    }
    private static boolean recover(Path root,Path internal) throws IOException {
        Path journalPath=SafePaths.child(internal,"transaction.json");
        if(!Files.exists(journalPath,LinkOption.NOFOLLOW_LINKS)) return false;
        JsonObject journal=readObject(journalPath); String id=StrictJson.string(journal,"id");
        if(!id.matches("[0-9a-f-]{36}")) throw new IOException("Invalid recovery transaction");
        String status=StrictJson.string(journal,"status");
        if(!status.equals("COMMITTED") && !status.equals("PREPARED"))throw new IOException("Unknown recovery state; manual recovery required");
        if(!status.equals("COMMITTED")) {
            var operations=StrictJson.array(journal,"operations");
            for(int index=operations.size()-1;index>=0;index--) {
                var op=operations.get(index).getAsJsonObject(); String path=StrictJson.string(op,"path"); Path target=SafePaths.resolve(root,path,SafePaths.MANAGED_ROOTS);
                Path backup=SafePaths.child(internal,"backups/"+id+"/files/"+path);
                if(op.has("oldHash")) {
                    String old=StrictJson.string(op,"oldHash");
                    if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS)) {
                        if(!SafePaths.hash(backup).equals(old)) throw new IOException("Recovery backup corrupted; manual recovery required");
                        removeNewOnly(target,op); SafePaths.move(backup,target);
                    } else if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS) || !SafePaths.hash(target).equals(old)) throw new IOException("Recovery cannot prove original file; manual recovery required");
                } else removeNewOnly(target,op);
            }
            SafePaths.atomicWrite(SafePaths.child(internal,"installed.json"),StrictJson.bytes(journal.getAsJsonObject("oldState")));
        }
        Path pending=SafePaths.child(internal,"pending.json"); if(Files.exists(pending,LinkOption.NOFOLLOW_LINKS)) Files.delete(pending);
        Files.delete(journalPath); return true;
    }
    private static void removeNewOnly(Path target,JsonObject op) throws IOException {
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) return;
        if(!op.has("newHash") || !SafePaths.hash(target).equals(StrictJson.string(op,"newHash"))) throw new IOException("Recovery found personal changes; preserving files for manual recovery");
        Files.delete(target);
    }
}
