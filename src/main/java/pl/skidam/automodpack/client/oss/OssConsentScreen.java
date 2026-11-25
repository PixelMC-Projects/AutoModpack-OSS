package pl.skidam.automodpack.client.oss;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import pl.skidam.automodpack_core.oss.PackTrust;
import pl.skidam.automodpack_core.oss.client.ConsentCountdown;

/** Restrained liquid-glass first-consent document aligned with PixelMC-Hub's visual language. */
final class OssConsentScreen extends Screen {
    private static final int PAD_X = 44;
    private static final int FOOTER_H = 74;

    private final OssLoginSession session;
    private final PackTrust trust;
    private final ConsentCountdown countdown;
    private final Consumer<Boolean> decision;
    private final String rootsLabel;
    private final OssGlassSurface surface = new OssGlassSurface("consent");
    private OssGlassSurface.Palette palette;

    private boolean decided;
    private float scroll;
    private float maxScroll;
    private String pressedId;
    private long openedAtMs;
    private long lastTickSec = -1;
    private long tickFlashAtMs;

    OssConsentScreen(
            OssLoginSession session,
            PackTrust trust,
            ConsentCountdown countdown,
            Consumer<Boolean> decision) {
        super(Component.literal("整合包自动同步授权"));
        this.session = session;
        this.trust = trust;
        this.countdown = countdown;
        this.decision = decision;
        this.rootsLabel = String.join(" / ", new TreeSet<>(trust.allowedRoots()));
    }

    @Override
    protected void init() {
        if (palette == null && minecraft != null) {
            palette = OssGlassSurface.Palette.detect(minecraft.gameDirectory.toPath());
        }
        if (openedAtMs == 0L) openedAtMs = System.currentTimeMillis();
    }

    private void decide(boolean approved) {
        if (decided || (approved && !countdown.canAccept())) return;
        decided = true;
        decision.accept(approved);
    }

    @Override
    public void tick() {
        session.tickConnection();
    }

    @Override
    public void removed() {
        countdown.hidden();
        if (minecraft != null) surface.release(minecraft);
        else surface.close();
    }

    @Override
    public void onClose() {
        decide(false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        pressedId = surface.hitAt(mouseX, mouseY);
        return pressedId != null || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pressedId != null) {
            String target = surface.hitAt(mouseX, mouseY);
            String clicked = pressedId;
            pressedId = null;
            if (clicked.equals(target)) {
                if ("decline".equals(clicked)) {
                    decide(false);
                    return true;
                }
                if ("accept".equals(clicked) && countdown.canAccept()) {
                    decide(true);
                    return true;
                }
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll > 0 && surface.insidePanel(mouseX, mouseY)) {
            scroll = Math.max(0f, Math.min(maxScroll, scroll - (float) (delta * 28.0)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        countdown.shown();
        if (palette == null && minecraft != null) {
            palette = OssGlassSurface.Palette.detect(minecraft.gameDirectory.toPath());
        }
        if (openedAtMs == 0L) openedAtMs = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        float enterAlpha = Math.min(1f, (now - openedAtMs) / 220f);

        long rem = countdown.remainingSeconds();
        if (rem != lastTickSec) {
            lastTickSec = rem;
            tickFlashAtMs = now;
        }

        surface.render(
                graphics,
                minecraft,
                palette,
                width,
                height,
                mouseX,
                mouseY,
                pressedId,
                enterAlpha,
                this::paintConsent);
    }

    private void paintConsent(
            Graphics2D g,
            OssGlassSurface.Palette p,
            int w,
            int h,
            float mx,
            float my,
            String pressed,
            List<OssGlassSurface.Hit> hits) {
        int bodyBottom = h - FOOTER_H;
        int contentW = w - PAD_X * 2;

        // Scrollable reading document area
        Shape prevClip = g.getClip();
        g.clipRect(0, 0, w, bodyBottom);

        float y = 32f - scroll;

        // Document title row
        Font titleFont = OssGlassSurface.bodyFont(Font.BOLD, 21f);
        Font subFont = OssGlassSurface.bodyFont(Font.PLAIN, 12f);
        g.setFont(titleFont);
        g.setColor(p.text);
        g.drawString("整合包自动同步授权", PAD_X, y + 20f);

        String rightTag = trust.identity() + " · 正式版";
        g.setFont(subFont);
        FontMetrics subFm = g.getFontMetrics();
        g.setColor(p.muted);
        g.drawString(rightTag, w - PAD_X - subFm.stringWidth(rightTag), y + 19f);

        y += 38f;
        g.setColor(p.rule);
        g.fillRect(PAD_X, Math.round(y), contentW, 1);

        // Single clean metadata strip
        y += 24f;
        float cx = PAD_X;
        cx = drawMetaPair(g, p, cx, y, "整合包", trust.packId(), true) + 22f;
        cx = drawMetaPair(g, p, cx, y, "管理目录", rootsLabel, true) + 22f;
        drawMetaPair(g, p, cx, y, "来源校验", "本地公钥已核验", false);

        y += 14f;
        g.setColor(p.rule);
        g.fillRect(PAD_X, Math.round(y), contentW, 1);

        // Callout box with calm emerald left border
        y += 18f;
        Font calloutFont = OssGlassSurface.bodyFont(Font.PLAIN, 13f);
        String calloutText = "只处理签名清单内的受管文件；你的个人存档、截图及范围外文件不会被修改。被替换文件会保留备份，遇到个人修改冲突时会自动停止并保留原文件。";
        List<String> calloutLines =
                OssGlassSurface.wrapText(g, calloutFont, calloutText, contentW - 34f);
        float calloutLineH = 22f;
        float calloutH = calloutLines.size() * calloutLineH + 22f;
        OssGlassSurface.drawCallout(g, p.okTint, p.ok, PAD_X, y, contentW, calloutH);
        g.setFont(calloutFont);
        g.setColor(p.text);
        for (int i = 0; i < calloutLines.size(); i++) {
            g.drawString(calloutLines.get(i), PAD_X + 16f, y + 24f + i * calloutLineH);
        }
        y += calloutH + 18f;

        // Prose paragraphs
        String[][] paragraphs = {
            {"默认接收正式版更新。", "同意后，连接本服时会自动从私有 OSS 下载并逐文件校验正常更新，不再每次弹窗询问。"},
            {
                "下载完成后自动倒计时关闭。",
                "模组包含可执行代码，需要信任维护者。更新校验并写入暂存区后，将在 10 秒倒计时后自动关闭游戏（可随时取消），下次启动游戏时在模组加载前安全应用；不会自动拉起游戏或自动进服。"
            },
            {"连接与身份说明。", "同步仅使用已验证的正版账号与文件校验信息；下载日志可能记录公网 IP、时间和下载记录，用于授权、排错与防滥用。"},
            {"", "选择「暂不启用」不会改动本地任何文件；如管理范围扩大或协议实质变更，会再次征求同意。后续需要调整同步选项或撤销授权时，可在旅人手册中进行管理。"}
        };

        Font proseFont = OssGlassSurface.bodyFont(Font.PLAIN, 13.5f);
        Font boldProseFont = OssGlassSurface.bodyFont(Font.BOLD, 13.5f);
        float proseLineH = 24f;
        for (String[] para : paragraphs) {
            String full = para[0] + para[1];
            List<String> wrapped = OssGlassSurface.wrapText(g, proseFont, full, contentW);
            for (int i = 0; i < wrapped.size(); i++) {
                String line = wrapped.get(i);
                float lineY = y + 15f;
                if (i == 0 && !para[0].isEmpty() && line.startsWith(para[0])) {
                    g.setFont(boldProseFont);
                    g.setColor(p.text);
                    g.drawString(para[0], PAD_X, lineY);
                    int boldW = g.getFontMetrics().stringWidth(para[0]);
                    g.setFont(proseFont);
                    g.setColor(p.secondary);
                    g.drawString(line.substring(para[0].length()), PAD_X + boldW, lineY);
                } else {
                    g.setFont(proseFont);
                    g.setColor(p.secondary);
                    g.drawString(line, PAD_X, lineY);
                }
                y += proseLineH;
            }
            y += 10f;
        }

        float totalContentH = (y + scroll) + 16f;
        maxScroll = Math.max(0f, totalContentH - bodyBottom);
        if (scroll > maxScroll) scroll = maxScroll;

        g.setClip(prevClip);

        // Minimal 3px scrollbar if content overflows
        OssGlassSurface.drawScrollbar(
                g, p, w - 12f, 24f, bodyBottom - 36f, scroll, maxScroll, bodyBottom);

        // Footer bar
        g.setColor(p.footerTint);
        g.fillRect(0, bodyBottom, w, FOOTER_H);
        g.setColor(p.rule);
        g.fillRect(0, bodyBottom, w, 1);

        long rem = countdown.remainingSeconds();
        boolean canAccept = !decided && countdown.canAccept();
        float tickAge = Math.min(1f, (System.currentTimeMillis() - tickFlashAtMs) / 240f);

        Font noteFont = OssGlassSurface.bodyFont(Font.PLAIN, 12.5f);
        g.setFont(noteFont);
        if (rem > 0) {
            String prefix = "阅读说明 ";
            String num = Long.toString(rem);
            String suffix = " 秒后可以确认";
            g.setColor(p.secondary);
            g.drawString(prefix, PAD_X, bodyBottom + 33f);
            float nx = PAD_X + g.getFontMetrics().stringWidth(prefix);
            g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 12.5f));
            g.setColor(p.accent);
            g.drawString(num, nx, bodyBottom + 33f + (1f - tickAge) * 2f);
            float sx = nx + g.getFontMetrics().stringWidth(num);
            g.setFont(noteFont);
            g.setColor(p.secondary);
            g.drawString(suffix, sx, bodyBottom + 33f);
        } else {
            g.setColor(p.secondary);
            g.drawString("首次默认同步正式版，后续可在旅人手册中切换预览版或管理。", PAD_X, bodyBottom + 33f);
        }

        // Thin 2.5px reading progress line
        float trackW = 196f;
        float trackY = bodyBottom + 44f;
        g.setColor(p.rule);
        g.fill(new RoundRectangle2D.Float(PAD_X, trackY, trackW, 2.5f, 2.5f, 2.5f));
        float pct = Math.max(0f, Math.min(1f, (10f - rem) / 10f));
        if (pct > 0f) {
            g.setColor(p.accent);
            g.fill(new RoundRectangle2D.Float(PAD_X, trackY, trackW * pct, 2.5f, 2.5f, 2.5f));
        }

        // Two clean buttons
        float btnH = 36f;
        float btnY = bodyBottom + (FOOTER_H - btnH) * 0.5f;
        String acceptLabel = rem > 0 ? "同意并启用 (" + rem + "s)" : "同意并启用同步";
        float acceptW = 148f;
        float declineW = 96f;
        float acceptX = w - PAD_X - acceptW;
        float declineX = acceptX - 10f - declineW;

        OssGlassSurface.drawButton(
                g, p, hits, "decline", "暂不启用", declineX, btnY, declineW, btnH, false, !decided, mx,
                my, pressed);
        OssGlassSurface.drawButton(
                g,
                p,
                hits,
                "accept",
                acceptLabel,
                acceptX,
                btnY,
                acceptW,
                btnH,
                true,
                canAccept,
                mx,
                my,
                pressed);
    }

    private static float drawMetaPair(
            Graphics2D g,
            OssGlassSurface.Palette p,
            float x,
            float y,
            String key,
            String value,
            boolean mono) {
        Font kf = OssGlassSurface.bodyFont(Font.PLAIN, 12.5f);
        g.setFont(kf);
        g.setColor(p.muted);
        g.drawString(key, x, y);
        float vx = x + g.getFontMetrics().stringWidth(key) + 6f;

        Font vf =
                mono
                        ? OssGlassSurface.monoFont(Font.PLAIN, 12f)
                        : OssGlassSurface.bodyFont(Font.BOLD, 12.5f);
        g.setFont(vf);
        g.setColor(p.text);
        g.drawString(value, vx, y);
        return vx + g.getFontMetrics().stringWidth(value);
    }
}
