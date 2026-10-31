package pl.skidam.automodpack.init;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Common {

    public static Map<String, Boolean> players = new HashMap<>();
    public static MinecraftServer server = null;

    public static void serverInit() {
        pl.skidam.automodpack_core.oss.server.OssServerRuntime.startFromEnvironment();
    }

    public static void init() {
        GAME_CALL = new GameCall();
        // OSS fork: never instantiate the upstream local pack generator/file host.
    }

    public static void afterSetupServer() {
        // File delivery is exclusively OSS. No embedded Netty file host is started.
    }

    public static void beforeShutdownServer() {
        pl.skidam.automodpack_core.oss.server.OssServerRuntime.stop();
    }

    public static ResourceLocation id(String path) {
        /*? if >=1.21.11 {*/
        /*return ResourceLocation.tryBuild(MOD_ID, path);
        *//*?} else if >=1.19.2 {*/
        return ResourceLocation.tryBuild(MOD_ID, path);
        /*?} else {*/
        /*return new ResourceLocation(MOD_ID, path);
        *//*?}*/
    }
}
