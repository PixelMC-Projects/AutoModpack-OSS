package pl.skidam.automodpack_core.oss.client;

import com.google.gson.*;
import pl.skidam.automodpack_core.oss.*;
import java.io.IOException;
import java.nio.file.*;

/** Bounded local startup outcome; it contains no network URLs or credentials. */
public final class StartupNotice {
    private static volatile StagedInstaller.Status current;
    private StartupNotice() {}
    private static Path file(Path root) { return root.resolve("config/automodpack-oss/startup-notice.json"); }
    public static void record(Path root, StagedInstaller.ApplyResult result) throws IOException {
        if (result.status() == StagedInstaller.Status.NONE) return;
        current = result.status();
        Path file = file(root); SafePaths.requireNoLinks(file); Files.createDirectories(file.getParent());
        JsonObject notice = new JsonObject(); notice.addProperty("schema",1); notice.addProperty("status", result.status().name());
        Path temporary = Files.createTempFile(file.getParent(), "notice-", ".tmp");
        try {
            Files.writeString(temporary, notice.toString()); SafePaths.requireNoLinks(file);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public static String read(Path root) throws IOException {
        if (current != null) return text(current);
        Path file = file(root); SafePaths.requireNoLinks(file);
        if (!Files.exists(file)) return "";
        SafePaths.requireRegularFile(file);
        if (Files.size(file) > 4096) throw new IOException("Oversized startup notice");
        try {
            JsonObject notice = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (notice.get("schema").getAsInt() != 1) throw new IOException("Invalid startup notice");
            return text(StagedInstaller.Status.valueOf(notice.get("status").getAsString()));
        } catch (RuntimeException error) { throw new IOException("Invalid startup notice", error); }
    }
    private static String text(StagedInstaller.Status status) {
            return switch (status) {
                case CONFLICT -> "最近启动：更新遇到文件冲突，未应用；原文件和暂存文件已保留，请手动检查。";
                case CONSENT_REQUIRED -> "最近启动：同步授权已撤销或失效，暂存更新未应用；需要重新授权。";
                case APPLIED -> "最近启动：签名更新已在模组加载前应用，受管理旧文件备份已保留。";
                case RECOVERED -> "最近启动：已恢复中断的安装事务，请检查当前版本后手动连接。";
                case NONE -> "";
            };
    }
}
