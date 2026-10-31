package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {

    @Override
    public void initArguments(Map<String, ?> arguments) {
        // ALL locators are initialized before discoverMods invokes any candidate scan.
        new Preload(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
    }

    @Override
    public String name() {
        return "automodpack";
    }

    @Override
    public Stream<Path> scanCandidates() {
        return Stream.empty(); // Standard Forge locator discovers the applied mods folder.
    }
}
