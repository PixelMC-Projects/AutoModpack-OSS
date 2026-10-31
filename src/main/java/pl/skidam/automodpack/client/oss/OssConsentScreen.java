package pl.skidam.automodpack.client.oss;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import pl.skidam.automodpack_core.oss.PackTrust;
import pl.skidam.automodpack_core.oss.client.ConsentCountdown;
import java.util.*;
import java.util.function.Consumer;

/** Native Minecraft widgets and background, matching upstream's visual style. */
final class OssConsentScreen extends Screen {
    private final OssLoginSession session;
    private final ConsentCountdown countdown; // session-owned; init/resize cannot recreate the timer
    private final Consumer<Boolean> decision;
    private final String body;
    private Button accept;
    private int scroll;
    private List<FormattedCharSequence> lines = List.of();
    private boolean decided;
    OssConsentScreen(OssLoginSession session, PackTrust trust, ConsentCountdown countdown, Consumer<Boolean> decision) {
        super(Component.literal("整合包自动同步授权"));
        this.session = session; this.countdown = countdown; this.decision = decision;
        body = "可信维护者：" + trust.identity() + "\n整合包：" + trust.packId() + "\n管理目录：" + String.join("、", new TreeSet<>(trust.allowedRoots()))
                + "\n\n同意后，将为此整合包自动下载并校验正常更新，不逐次询问。只处理签名清单列出的模组、配置和资源文件；个人存档、截图等范围外文件不会修改。被替换的受管理文件会保留备份；发现冲突且无法安全处理时停止，保留原文件供手动处理。"
                + "\n\n模组包含可执行代码，需要信任维护者。签名证明来源和完整性，不保证没有漏洞。下载校验完成后仅提示手动重启，下次启动时应用；不会自动退出、重启游戏或自动进入服务器。"
                + "\n\n同步会使用已验证的游戏账号、版本和受管理文件校验信息。连接及下载日志可能包含公网 IP、时间和下载记录，用于授权、排错与防滥用。"
                + "\n\n可在模组设置关闭自动同步或撤销授权。选择“暂不启用”不会改动整合包文件；本地不兼容时可能无法进入服务器。管理范围扩大或协议实质变化时会重新征求同意。";
    }
    @Override protected void init() {
        int panel = Math.max(160, Math.min(620, width - 32));
        lines = font.split(Component.literal(body), panel - 16);
        int buttonWidth = Math.min(190, (width - 38) / 2);
        Button decline = addRenderableWidget(Button.builder(Component.literal("暂不启用"), b -> decide(false))
                .bounds(width / 2 - buttonWidth - 4, height - 30, buttonWidth, 20).build());
        accept = addRenderableWidget(Button.builder(Component.literal("同意并启用同步"), b -> decide(true))
                .bounds(width / 2 + 4, height - 30, buttonWidth, 20).build());
        accept.active = countdown.canAccept();
        setInitialFocus(decline);
    }
    private void decide(boolean approved) {
        if (decided || (approved && !countdown.canAccept())) return;
        decided = true; decision.accept(approved);
    }
    @Override public void tick() {
        session.tickConnection();
        if (accept != null) accept.active = !decided && countdown.canAccept();
    }
    @Override public void removed() { countdown.hidden(); }
    @Override public void onClose() { decide(false); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        countdown.shown(); // Start only when the authorization page is actually rendered.
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xffffff);
        int panel = Math.max(160, Math.min(620, width - 32)), left = (width - panel) / 2;
        int top = 34, bottom = Math.max(top + 10, height - 56);
        graphics.fill(left, top, left + panel, bottom, 0x80000000);
        graphics.enableScissor(left, top, left + panel, bottom);
        for (int i = 0; i < lines.size(); i++) graphics.drawString(font, lines.get(i), left + 8, top + 6 + i * 11 - scroll, 0xffffff);
        graphics.disableScissor();
        String hint = countdown.remainingSeconds() > 0 ? "请阅读授权说明，还需显示 " + countdown.remainingSeconds() + " 秒" : "请按自己的意愿选择；可随时在设置中撤销";
        graphics.drawCenteredString(font, Component.literal(hint), width / 2, height - 47, 0xdddddd);
        super.render(graphics, mouseX, mouseY, delta);
    }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        int max = Math.max(0, lines.size() * 11 + 12 - Math.max(10, height - 90));
        scroll = Math.max(0, Math.min(max, scroll - (int) (amount * 22)));
        return true;
    }
}
