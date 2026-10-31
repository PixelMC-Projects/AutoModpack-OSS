package pl.skidam.automodpack.client.oss;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import pl.skidam.automodpack_core.oss.client.ConsentStore;
import pl.skidam.automodpack_core.oss.client.StartupNotice;
import java.io.IOException;
import java.nio.file.Path;

/** Forge's Mods > AutoModpack OSS > Config is always available, even without trust configuration. */
public final class OssClientNetworking {
    private OssClientNetworking() {}
    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new Settings(parent, minecraft.gameDirectory.toPath())));
    }
    public static void openSettings() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new Settings(minecraft.screen, minecraft.gameDirectory.toPath())));
    }
    private static final class Settings extends Screen {
        private final Screen parent;
        private final Path root;
        private String message;
        Settings(Screen parent, Path root) { super(Component.literal("整合包自动同步设置")); this.parent = parent; this.root = root; }
        @Override protected void init() {
            try {
                ConsentStore store = new ConsentStore(root);
                message = "自动同步：" + (store.enabled() ? "允许已授权整合包" : "已关闭") + "；有效授权 " + store.grantCount() + " 项。无本地可信配置时始终不会同步。";
                String notice = StartupNotice.read(root);
                if (!notice.isEmpty()) message += "\n\n" + notice;
                addRenderableWidget(Button.builder(Component.literal(store.enabled() ? "关闭自动同步" : "开启自动同步（仍需授权）"), b -> {
                    try { new ConsentStore(root).setEnabled(!store.enabled()); rebuildWidgets(); }
                    catch (IOException e) { message = "无法保存设置；请检查本地配置目录权限。"; }
                }).bounds(width / 2 - 130, height / 2 - 15, 260, 20).build());
                addRenderableWidget(Button.builder(Component.literal("撤销全部授权并关闭同步"), b -> {
                    try { new ConsentStore(root).revokeAll(); rebuildWidgets(); }
                    catch (IOException e) { message = "撤销未能保存，请检查本地配置目录权限。"; }
                }).bounds(width / 2 - 130, height / 2 + 12, 260, 20).build());
            } catch (IOException e) { message = "授权记录无法安全读取，自动同步已拒绝。"; }
            addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose()).bounds(width / 2 - 100, height - 35, 200, 20).build());
        }
        @Override public void onClose() { minecraft.setScreen(parent); }
        @Override public void render(GuiGraphics graphics, int x, int y, float delta) {
            renderBackground(graphics); graphics.drawCenteredString(font, title, width / 2, 20, 0xffffff);
            graphics.drawWordWrap(font, Component.literal(message == null ? "" : message), 24, 48, width - 48, 0xffffff);
            super.render(graphics, x, y, delta);
        }
    }
}
