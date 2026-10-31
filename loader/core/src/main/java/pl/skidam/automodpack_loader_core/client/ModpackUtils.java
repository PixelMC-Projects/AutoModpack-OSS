package pl.skidam.automodpack_loader_core.client;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.ClientCacheUtils;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.utils.ClientCacheUtils.*;

public class ModpackUtils {

    // Modpack may require update even if theres no files to update, because some files may need to be deleted
    public record UpdateCheckResult(boolean requiresUpdate, Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {}

    // Fast and freindly method to check if the modpack is up to date without modifying anything on disk
    public static UpdateCheckResult isUpdate(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public static boolean deleteFilesMarkedForDeletionByTheServer(Set<Jsons.ModpackContentFields.FileToDelete> filesToDeleteOnClient) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public static boolean correctFilesLocations(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, Set<String> filesNotToCopy) throws IOException {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public static boolean removeRestModsNotToCopy(Jsons.ModpackContentFields serverModpackContent, Set<String> filesNotToCopy, Set<Path> modsToKeep) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    // Copies necessary nested mods from modpack mods to standard mods folder
    // Returns true if requires client restart
    public static boolean fixNestedMods(List<FileInspection.Mod> conflictingNestedMods, Collection<FileInspection.Mod> standardModList) throws IOException {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    // Returns ignored files list, which is conflicting nested mods + workarounds set
    public static Set<String> getIgnoredFiles(List<FileInspection.Mod> conflictingNestedMods, Set<String> workarounds) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    // Checks if in standard mods folder are any mods that are in modpack
    // Returns map of modpack mods and standard mods that have the same mod id they dont necessarily have to be the same*
    public static Map<FileInspection.Mod, FileInspection.Mod> getDupeMods(Path modpackDir, Set<String> ignoredMods, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList, Set<String> forceCopyFiles) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public record RemoveDupeModsResult(boolean requiresRestart, Set<Path> modsToKeep) {}

    // Returns true if removed any mod from standard mods folder
    // If the client mod is a duplicate of what modpack contains then it removes it from client so that you dont need to restart game just when you launched it and modpack get updated - basically having these mods separately allows for seamless updates
    // If you have client mods which require specific mod which is also a duplicate of what modpack contains it should stay
    public static RemoveDupeModsResult removeDupeMods(Path modpackDir, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList, Set<String> ignoredMods, Set<String> workaroundMods, Set<String> forceCopyFiles) throws IOException {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    private static void addDependenciesRecursively(FileInspection.Mod mod, Collection<FileInspection.Mod> modList, Set<FileInspection.Mod> modsToKeep) {
        for (String depId : mod.dependencies()) {
            for (FileInspection.Mod modItem : modList) {
                if ((modItem.modID().equals(depId) || modItem.providesIDs().contains(depId)) && modsToKeep.add(modItem)) {
                    addDependenciesRecursively(modItem, modList, modsToKeep);
                }
            }
        }
    }

    public static Path renameModpackDir(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    // Returns true if value changed
    public static boolean selectModpack(Path modpackDirToSelect, Jsons.ModpackAddresses modpackAddresses, Set<String> newDownloadedFiles) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    private static void processEditableFiles(Path modpackDir, java.util.function.BiConsumer<Path, Set<String>> action) {
        Path contentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
        Jsons.ModpackContentFields content = ConfigTools.loadModpackContent(contentFile);

        if (content != null) {
            Set<String> editableFiles = getEditableFiles(content.list);
            action.accept(modpackDir, editableFiles);
        }
    }

    public static void removeModpackFromList(String modpackName) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public static void addModpackToList(String modpackName, Jsons.ModpackAddresses modpackAddresses) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    // Returns modpack name formatted for path or url if server doesn't provide modpack name
    public static Path getModpackPath(InetSocketAddress address, String modpackName) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public static Optional<Jsons.ModpackContentFields> requestServerModpackContent(Jsons.ModpackAddresses modpackAddresses, Secrets.Secret secret, boolean allowAskingUser) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public static Optional<Jsons.ModpackContentFields> refreshServerModpackContent(Jsons.ModpackAddresses modpackAddresses, Secrets.Secret secret, byte[][] fileHashes, boolean allowAskingUser) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    private static Optional<Jsons.ModpackContentFields> fetchModpackContent(Jsons.ModpackAddresses modpackAddresses, Secrets.Secret secret, Function<DownloadClient, Future<Path>> operation, String fetchType, boolean allowAskingUser) {
        if (secret == null)
            return Optional.empty();
        if (modpackAddresses.isAnyEmpty())
            throw new IllegalArgumentException("Modpack addresses are empty!");

        try (DownloadClient client = DownloadClient.tryCreate(modpackAddresses, secret.secretBytes(), 1, userValidationCallback(modpackAddresses.hostAddress, allowAskingUser))) {
            if (client == null) return Optional.empty();
            var future = operation.apply(client);
            Path path = future.get();
            var content = Optional.ofNullable(ConfigTools.loadModpackContent(path));
            Files.deleteIfExists(modpackContentTempFile);

            if (content.isPresent() && potentiallyMalicious(content.get())) {
                return Optional.empty();
            }

            return content;
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content", e);
        }

        return Optional.empty();
    }

    public static boolean canConnectModpackHost(Jsons.ModpackAddresses modpackAddresses) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    /**
     * Returns a callback for use with {@link DownloadClient} that checks for trusted fingerprints in the known hosts
     * list of the client config.
     *
     * @param address         the address being connected to
     * @param allowAskingUser whether the user should be prompted if a certificate is not trusted
     * @return the callback
     */
    public static Function<X509Certificate, Boolean> userValidationCallback(InetSocketAddress address, boolean allowAskingUser) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    private static Boolean askUserAboutCertificate(InetSocketAddress address, String fingerprint) {
        LOGGER.info("Asking user for {}", address.getHostString());
        Optional<Object> screen = new ScreenManager().getScreen();
        if (screen.isEmpty()) {
            LOGGER.warn("No screen available, cannot ask user");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);

        AtomicBoolean accepted = new AtomicBoolean(false);
        Runnable trustCallback = () -> {
            accepted.set(true);
            latch.countDown();
        };
        Runnable cancelCallback = latch::countDown;
        new ScreenManager().validation(screen.get(), fingerprint, trustCallback, cancelCallback);
        try {
            latch.await();
        } catch (InterruptedException e) {
            return false;
        }

        return accepted.get();
    }

    public static boolean potentiallyMalicious(Jsons.ModpackContentFields serverModpackContent) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    private static boolean isUnsafePath(String rawPath, boolean blankIsFine) {
        if (rawPath == null) return true;

        if (!blankIsFine && rawPath.isBlank()) return true;

        // Null Byte Check
        if (rawPath.indexOf('\0') != -1) return true;

        // Most files are just "mods/fabric-api.jar", so they hit this and return false immediately
        if (!rawPath.contains("..")) {
            return false;
        }

        // We must distinguish between malicious "../" and valid names like "super..mario.jar"
        String normalized = rawPath.replace('\\', '/');

        // Edge case
        if (normalized.equals("..") || normalized.equals(".")) return true;

        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.equals("..")) return true; // Directory traversal
        }

        if (normalized.startsWith("automodpack/") || normalized.startsWith("/automodpack/")) {
            return true; // Trying to mess with automodpack internal files
        }

        return false;
    }

    public static void preserveEditableFiles(Path modpackDir, Set<String> editableFiles, Set<String> newDownloadedFiles) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    public static void copyPreviousEditableFiles(Path modpackDir, Set<String> editableFiles, Set<String> newDownloadedFiles) {
        throw new SecurityException("Legacy AutoModpack file management is disabled in this OSS fork");
    }

    static Set<String> getEditableFiles(Set<Jsons.ModpackContentFields.ModpackContentItem> modpackContentItems) {
        Set<String> editableFiles = new HashSet<>();

        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentItem : modpackContentItems) {
            if (modpackContentItem.editable) {
                editableFiles.add(modpackContentItem.file);
            }
        }

        return editableFiles;
    }
}
