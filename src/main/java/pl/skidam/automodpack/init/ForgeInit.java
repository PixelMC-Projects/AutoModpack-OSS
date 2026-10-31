package pl.skidam.automodpack.init;

/*? if forge {*/
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@Mod(MOD_ID + "_mod")
public class ForgeInit {

    public ForgeInit() {
        preload = false;

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

         Common.init();

        if (LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.SERVER) {
            ScreenManager.INSTANCE = new ScreenImpl();
            pl.skidam.automodpack.client.oss.OssClientNetworking.register();
            new AudioManager(FMLJavaModLoadingContext.get().getModEventBus());
        }


        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }

    @Mod.EventBusSubscriber(modid = MOD_ID + "_mod")
    public static class events {
        @SubscribeEvent
        public static void onServerStarting(ServerAboutToStartEvent event) {
            if (event.getServer().isDedicatedServer()) Common.serverInit();
        }
        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            Common.beforeShutdownServer();
        }
    }
}
/*?}*/