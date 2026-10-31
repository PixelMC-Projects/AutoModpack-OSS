package pl.skidam.automodpack.client.oss;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

final class OssStatusScreen extends Screen {
    private final OssLoginSession session;
    OssStatusScreen(OssLoginSession session) { super(Component.literal("AutoModpack OSS")); this.session = session; }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("返回主菜单"), b -> onClose())
                .bounds(width / 2 - 100, height - 38, 200, 20).build());
    }
    @Override public void tick() { session.tickConnection(); }
    @Override public void onClose() { session.cancel(); minecraft.setScreen(new TitleScreen()); }
    @Override public void render(GuiGraphics graphics, int x, int y, float delta) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 24, 0xffffff);
        graphics.drawWordWrap(font, Component.literal(session.status()), Math.max(20, width / 2 - 240), 62, Math.min(480, width - 40), 0xffffff);
        super.render(graphics, x, y, delta);
    }
}
