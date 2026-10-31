package pl.skidam.automodpack_loader_core;
import pl.skidam.automodpack_core.config.Jsons;
/** Upstream self-update would replace the trust boundary and is permanently disabled. */
public final class SelfUpdater {
    private SelfUpdater() {}
    public static boolean update() { return false; }
    public static boolean update(Jsons.ModpackContentFields ignored) { return false; }
}
