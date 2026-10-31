package pl.skidam.automodpack_core.oss;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** No links, junctions, absolute paths, Windows aliases, or writes outside the selected instance. */
public final class SafePaths {
    public static final Set<String> MANAGED_ROOTS = Set.of("mods","config","resourcepacks","shaderpacks","defaultconfigs","kubejs","scripts");
    private SafePaths() {}
    public static void validateRelative(String relative, Set<String> roots) throws IOException {
        if (relative == null || relative.isEmpty() || relative.length() > 240 || relative.indexOf('\\') >= 0) throw new IOException("Invalid relative path");
        String[] parts = relative.split("/", -1);
        if (parts.length < 2 || !roots.contains(parts[0])) throw new IOException("Path is outside authorized managed roots");
        if (parts[0].equals("config") && parts[1].toLowerCase(Locale.ROOT).startsWith("automodpack")) throw new IOException("Updater control/trust files cannot be release-managed");
        if (parts[0].equals("mods") && parts[1].toLowerCase(Locale.ROOT).startsWith("automodpack")) throw new IOException("The synchronization mod cannot update itself through a pack release");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") ||
                part.chars().anyMatch(c -> c < 32 || ":<>\"|?*".indexOf(c) >= 0) ||
                part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) throw new IOException("Unsafe managed path");
        }
    }
    public static Path resolve(Path root, String relative, Set<String> roots) throws IOException {
        validateRelative(relative, roots); return child(root, relative);
    }
    static Path child(Path root, String relative) throws IOException {
        Path base = root.toAbsolutePath().normalize(); checkChain(base);
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base) || result.equals(base)) throw new IOException("Path escaped its root");
        checkChain(result); return result;
    }
    static void checkChain(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(current)) throw new IOException("Link/reparse path refused");
                // Windows junctions are directory reparse points; real path changes expose these too.
                if (!current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) throw new IOException("Linked directory refused");
            }
        }
    }
    public static void requireNoLinks(Path path) throws IOException { checkChain(path); }
    public static void requireRegularFile(Path path) throws IOException {
        checkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular file");
        Object links;
        try { links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS); }
        catch (UnsupportedOperationException | IllegalArgumentException ex) { return; }
        if (links instanceof Number n && n.longValue() > 1) throw new IOException("Hard-linked file refused");
    }
    static void directories(Path directory) throws IOException {
        checkChain(directory); Files.createDirectories(directory); checkChain(directory);
    }
    static byte[] readBounded(Path file, int limit) throws IOException {
        requireRegularFile(file);
        if (Files.size(file) > limit) throw new IOException("File exceeds limit");
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(limit + 1); if (bytes.length > limit) throw new IOException("File exceeds limit"); return bytes;
        }
    }
    public static boolean matches(Path file, long size, String hash) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegularFile(file); if (Files.size(file) != size) return false;
        return hash(file).equals(hash);
    }
    public static String hash(Path file) throws IOException {
        requireRegularFile(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024]; for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer,0,n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException ex) { throw new IOException(ex); }
    }
    static void atomicWrite(Path path, byte[] bytes) throws IOException {
        checkChain(path); directories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(bytes); while (data.hasRemaining()) channel.write(data); channel.force(true);
            }
            checkChain(path); Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static void move(Path source, Path target) throws IOException {
        requireRegularFile(source); checkChain(target); directories(target.getParent());
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing to overwrite move destination");
        Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);
    }
}
