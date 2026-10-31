package pl.skidam.automodpack_loader_core;
import java.nio.file.Path;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.utils.UpdateType;
/** Never exits, starts a process, reconnects, or executes legacy apply callbacks. */
public final class ReLauncher {
    public ReLauncher(UpdateType ignored) {}
    public ReLauncher(Path ignored, UpdateType type, Changelogs changelogs) {}
    public void restart(boolean ignored, Runnable... callbacks) {
        throw new SecurityException("Automatic game exit/restart is disabled; restart manually after OSS staging");
    }
}
