package pl.skidam.automodpack.client;
import pl.skidam.automodpack_loader_core.screen.ScreenService;
import pl.skidam.automodpack.client.oss.OssClientNetworking;
import net.minecraft.client.Minecraft;
import java.util.Optional;
/** Compatibility facade: old trust-bypass/download/file-selection screens cannot be opened. */
public final class ScreenImpl implements ScreenService {
    private void disabled() { throw new SecurityException("Legacy AutoModpack UI is disabled in this OSS fork"); }
    public void download(Object... args) { disabled(); }
    public void fetch(Object... args) { disabled(); }
    public void changelog(Object... args) { disabled(); }
    public void restart(Object... args) { disabled(); }
    public void danger(Object... args) { disabled(); }
    public void validation(Object... args) { disabled(); }
    public void error(String... args) { disabled(); }
    public void menu(Object... args) { OssClientNetworking.openSettings(); }
    public void title(Object... args) { disabled(); }
    public Optional<String> getScreenString() { return Optional.ofNullable(Minecraft.getInstance().screen).map(s -> s.getTitle().getString()); }
    public Optional<Object> getScreen() { return Optional.ofNullable(Minecraft.getInstance().screen); }
}
