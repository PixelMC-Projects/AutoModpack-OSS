package pl.skidam.automodpack.client.oss;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Restrained liquid-glass synchronization status screen aligned with PixelMC-Hub.
 * Includes live byte progress tweening, file verification queue, and 10-second cancellable
 * auto-close countdown once staged updates finish verification.
 */
final class OssStatusScreen extends Screen {
    private static final int PAD_X = 44;
    private static final int FOOTER_H = 74;
    private static final long AUTO_CLOSE_MS = 10_000L;

    private final OssLoginSession session;
    private final OssGlassSurface surface = new OssGlassSurface("status");
    private OssGlassSurface.Palette palette;

    private String pressedId;
    private float queueScroll;
    private float queueMaxScroll;

    private OssLoginSession.Phase lastPhase;
    private long phaseEnteredAtMs;

    // Smooth interpolated display values (prevent jarring jumps)
    private double shownPct = -1;
    private double shownBytes = -1;
    private double shownSpeed = -1;
    private int lastDoneFiles = -1;
    private long filesBumpAtMs;
    private long lastEtaSec = -1;
    private long etaBumpAtMs;

    // 10-second auto-close state when STAGED
    private long stagedAtMs;
    private boolean autoCloseCancelled;
    private boolean stopTriggered;
    private long lastCloseSec = -1;
    private long closeTickAtMs;

    OssStatusScreen(OssLoginSession session) {
        super(Component.literal("AutoModpack OSS"));
        this.session = session;
    }

    @Override
    protected void init() {
        if (palette == null && minecraft != null) {
            palette = OssGlassSurface.Palette.detect(minecraft.gameDirectory.toPath());
        }
    }

    @Override
    public void tick() {
        session.tickConnection();
        OssLoginSession.UiSnapshot snap = session.snapshot();
        if (snap.phase() == OssLoginSession.Phase.STAGED
                && stagedAtMs > 0
                && !autoCloseCancelled
                && !stopTriggered) {
            if (System.currentTimeMillis() - stagedAtMs >= AUTO_CLOSE_MS) {
                stopTriggered = true;
                session.requestCloseGame();
            }
        }
    }

    @Override
    public void removed() {
        if (minecraft != null) surface.release(minecraft);
        else surface.close();
    }

    @Override
    public void onClose() {
        autoCloseCancelled = true;
        session.cancel();
        if (minecraft != null) minecraft.setScreen(new TitleScreen());
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
                if ("back".equals(clicked)) {
                    onClose();
                    return true;
                }
                if ("close_now".equals(clicked)) {
                    stopTriggered = true;
                    session.requestCloseGame();
                    return true;
                }
                if ("toggle_auto_close".equals(clicked)) {
                    autoCloseCancelled = !autoCloseCancelled;
                    if (!autoCloseCancelled) {
                        stagedAtMs = System.currentTimeMillis();
                        lastCloseSec = -1;
                    }
                    return true;
                }
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (queueMaxScroll > 0 && surface.insidePanel(mouseX, mouseY)) {
            queueScroll =
                    Math.max(0f, Math.min(queueMaxScroll, queueScroll - (float) (delta * 26.0)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (palette == null && minecraft != null) {
            palette = OssGlassSurface.Palette.detect(minecraft.gameDirectory.toPath());
        }
        OssLoginSession.UiSnapshot snap = session.snapshot();
        long now = System.currentTimeMillis();
        if (snap.phase() != lastPhase) {
            lastPhase = snap.phase();
            phaseEnteredAtMs = now;
            queueScroll = 0;
            if (snap.phase() == OssLoginSession.Phase.STAGED) {
                stagedAtMs = now;
                autoCloseCancelled = false;
                stopTriggered = false;
                lastCloseSec = -1;
            }
        }
        float enterAlpha = Math.min(1f, (now - phaseEnteredAtMs) / 220f);

        // Smooth interpolation toward real download telemetry
        double targetPct =
                snap.totalBytes() > 0
                        ? Math.min(100.0, (snap.downloadedBytes() * 100.0) / snap.totalBytes())
                        : snap.totalFiles() > 0
                                ? Math.min(100.0, (snap.doneFiles() * 100.0) / snap.totalFiles())
                                : 0.0;
        if (shownPct < 0) {
            shownPct = targetPct;
            shownBytes = snap.downloadedBytes();
            shownSpeed = snap.speedBytesPerSec();
        } else {
            double k = 0.16;
            shownPct += (targetPct - shownPct) * k;
            shownBytes += (snap.downloadedBytes() - shownBytes) * k;
            shownSpeed += (snap.speedBytesPerSec() - shownSpeed) * k;
        }
        if (snap.doneFiles() != lastDoneFiles) {
            lastDoneFiles = snap.doneFiles();
            filesBumpAtMs = now;
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
                (g, p, w, h, mx, my, pressed, hits) ->
                        paintStatus(g, p, w, h, mx, my, pressed, hits, snap, now));
    }

    private void paintStatus(
            Graphics2D g,
            OssGlassSurface.Palette p,
            int w,
            int h,
            float mx,
            float my,
            String pressed,
            List<OssGlassSurface.Hit> hits,
            OssLoginSession.UiSnapshot snap,
            long now) {
        int bodyBottom = h - FOOTER_H;
        int contentW = w - PAD_X * 2;
        String footerNote = "不会自动拉起启动器或自动重连服务器。";

        switch (snap.phase()) {
            case VERIFYING -> {
                float y = bodyBottom * 0.5f - 46f;
                g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 21f));
                g.setColor(p.text);
                g.drawString("正在检查整合包更新", PAD_X, y + 20f);

                g.setFont(OssGlassSurface.bodyFont(Font.PLAIN, 13f));
                g.setColor(p.secondary);
                String who = snap.identity().isBlank() ? "可信服务器" : snap.identity();
                String chLabel = snap.channel() == null ? "正式版" : snap.channel().displayName();
                g.drawString("正在使用本地公钥核对 " + who + " 身份与" + chLabel + "签名清单，请稍候。", PAD_X, y + 48f);

                paintIndeterminateBar(g, p, PAD_X, y + 70f, contentW, 6f, now);
                queueMaxScroll = 0;
            }
            case DOWNLOADING -> {
                footerNote = "取消后已完整校验的暂存文件会保留，下次连服可直接续传。";
                float y = 34f;
                g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 21f));
                g.setColor(p.text);
                g.drawString("正在下载整合包更新", PAD_X, y + 20f);

                String pctText = Math.round(shownPct) + "%";
                g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 20f));
                FontMetrics pfm = g.getFontMetrics();
                g.drawString(pctText, w - PAD_X - pfm.stringWidth(pctText), y + 20f);

                g.setFont(OssGlassSurface.bodyFont(Font.PLAIN, 13f));
                g.setColor(p.secondary);
                String who = snap.identity().isBlank() ? "PixelMC" : snap.identity();
                String chLabel = snap.channel() == null ? "正式版" : snap.channel().displayName();
                g.drawString(
                        who + " " + chLabel + " · 文件先写入暂存区并逐项核对 SHA-256，不直接改动当前运行目录。",
                        PAD_X,
                        y + 44f);

                paintDeterminateBar(
                        g, p, PAD_X, y + 64f, contentW, 6f, (float) (shownPct / 100.0), now);

                // Single quiet inline metrics strip (no KPI boxes)
                long etaSec =
                        shownSpeed > 1024 && snap.totalBytes() > shownBytes
                                ? Math.max(
                                        1L,
                                        Math.round((snap.totalBytes() - shownBytes) / shownSpeed))
                                : -1L;
                if (etaSec != lastEtaSec) {
                    lastEtaSec = etaSec;
                    etaBumpAtMs = now;
                }
                float myRow = y + 92f;
                float cx = PAD_X;
                cx =
                        drawStat(
                                        g,
                                        p,
                                        cx,
                                        myRow,
                                        "文件",
                                        snap.doneFiles() + " / " + snap.totalFiles(),
                                        bumpProgress(now, filesBumpAtMs))
                                + 20f;
                cx =
                        drawStat(
                                        g,
                                        p,
                                        cx,
                                        myRow,
                                        "体积",
                                        formatBytes((long) shownBytes)
                                                + " / "
                                                + formatBytes(snap.totalBytes()),
                                        1f)
                                + 20f;
                cx =
                        drawStat(
                                        g,
                                        p,
                                        cx,
                                        myRow,
                                        "速率",
                                        shownSpeed > 1
                                                ? formatBytes((long) shownSpeed) + "/s"
                                                : "计算中",
                                        1f)
                                + 20f;
                drawStat(
                        g,
                        p,
                        cx,
                        myRow,
                        "剩余",
                        etaSec > 0 ? "约 " + etaSec + " 秒" : "计算中",
                        bumpProgress(now, etaBumpAtMs));

                // Clean hairline separated file queue
                float qTop = y + 114f;
                g.setColor(p.rule);
                g.fillRect(PAD_X, Math.round(qTop), contentW, 1);

                g.setFont(OssGlassSurface.bodyFont(Font.PLAIN, 11.5f));
                g.setColor(p.muted);
                g.drawString("文件校验队列", PAD_X, qTop + 22f);

                float listTop = qTop + 32f;
                float listH = Math.max(40f, bodyBottom - listTop - 10f);
                paintFileList(g, p, PAD_X, listTop, contentW, listH, snap.rows(), now);
            }
            case STAGED -> {
                footerNote = "倒计时结束仅关闭当前游戏窗口，重新启动后手动进入服务器即可。";
                float y = bodyBottom * 0.5f - 82f;
                g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 21f));
                g.setColor(p.text);
                g.drawString("更新已下载并校验完成", PAD_X, y + 20f);

                String summary =
                        "共通过 "
                                + (snap.totalFiles() + snap.reusedFiles())
                                + " 个文件校验（本次下载 "
                                + snap.totalFiles()
                                + " · 复用 "
                                + snap.reusedFiles()
                                + "）。下次启动游戏时，会在加载模组前自动替换到位并为旧文件留备份。";
                Font subF = OssGlassSurface.bodyFont(Font.PLAIN, 13f);
                List<String> subLines = OssGlassSurface.wrapText(g, subF, summary, contentW);
                g.setFont(subF);
                g.setColor(p.secondary);
                for (int i = 0; i < subLines.size(); i++) {
                    g.drawString(subLines.get(i), PAD_X, y + 48f + i * 22f);
                }

                // 10-second auto-close callout bar
                float barY = y + 58f + subLines.size() * 22f;
                float barH = 60f;
                long elapsed = Math.max(0L, now - stagedAtMs);
                long remSec = Math.max(0L, (AUTO_CLOSE_MS - elapsed + 999L) / 1000L);
                if (remSec != lastCloseSec) {
                    lastCloseSec = remSec;
                    closeTickAtMs = now;
                }

                OssGlassSurface.drawCallout(
                        g,
                        autoCloseCancelled ? p.footerTint : p.warnTint,
                        autoCloseCancelled ? p.rule : p.warn,
                        PAD_X,
                        barY,
                        contentW,
                        barH);

                Font rowFont = OssGlassSurface.bodyFont(Font.PLAIN, 13f);
                g.setFont(rowFont);
                if (stopTriggered) {
                    g.setColor(p.text);
                    g.drawString("正在安全关闭游戏，请重新启动以应用暂存更新……", PAD_X + 16f, barY + 34f);
                } else if (autoCloseCancelled) {
                    g.setColor(p.secondary);
                    g.drawString("已取消自动关闭。自行退出并重新启动游戏后，更新会自动应用。", PAD_X + 16f, barY + 35f);
                } else {
                    float tickAge = Math.min(1f, (now - closeTickAtMs) / 240f);
                    String p1 = "将在 ";
                    String p2 = remSec + " 秒";
                    String p3 = " 后自动关闭游戏，重新启动即可应用更新。";
                    g.setColor(p.text);
                    g.drawString(p1, PAD_X + 16f, barY + 28f);
                    float x2 = PAD_X + 16f + g.getFontMetrics().stringWidth(p1);
                    g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 13f));
                    g.setColor(p.warn);
                    g.drawString(p2, x2, barY + 28f + (1f - tickAge) * 2f);
                    float x3 = x2 + g.getFontMetrics().stringWidth(p2);
                    g.setFont(rowFont);
                    g.setColor(p.text);
                    g.drawString(p3, x3, barY + 28f);

                    float lineW = 220f;
                    float lineY = barY + 42f;
                    g.setColor(p.rule);
                    g.fill(new RoundRectangle2D.Float(PAD_X + 16f, lineY, lineW, 2.5f, 2.5f, 2.5f));
                    float closePct = Math.min(1f, elapsed / (float) AUTO_CLOSE_MS);
                    g.setColor(p.warn);
                    g.fill(
                            new RoundRectangle2D.Float(
                                    PAD_X + 16f, lineY, lineW * closePct, 2.5f, 2.5f, 2.5f));
                }

                if (!stopTriggered) {
                    String linkLabel = autoCloseCancelled ? "恢复倒计时" : "取消自动关闭";
                    Font lf = OssGlassSurface.bodyFont(Font.PLAIN, 12.5f);
                    g.setFont(lf);
                    int lw = g.getFontMetrics().stringWidth(linkLabel);
                    float lx = PAD_X + contentW - lw - 16f;
                    float ly = barY + 22f;
                    boolean lHover =
                            mx >= lx - 6 && mx <= lx + lw + 6 && my >= ly - 4 && my <= ly + 22;
                    hits.add(
                            new OssGlassSurface.Hit(
                                    "toggle_auto_close", lx - 6, ly - 4, lw + 12, 24, true));
                    g.setColor(lHover ? p.text : p.secondary);
                    g.drawString(linkLabel, lx, ly + 13f);
                    g.drawLine(
                            Math.round(lx),
                            Math.round(ly + 16f),
                            Math.round(lx + lw),
                            Math.round(ly + 16f));
                }
                queueMaxScroll = 0;
            }
            case CONFLICT, ERROR -> {
                float y = 34f;
                g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 21f));
                g.setColor(p.text);
                g.drawString(
                        snap.phase() == OssLoginSession.Phase.CONFLICT ? "已停止安装：发现个人文件冲突" : "同步已停止",
                        PAD_X,
                        y + 20f);

                Font cf = OssGlassSurface.bodyFont(Font.PLAIN, 13f);
                String msg =
                        snap.message().isBlank()
                                ? "已安全停止本次更新，现有整合包保持原状。已完整校验的暂存文件会保留，可手动检查后重试。"
                                : snap.message();
                List<String> lines = OssGlassSurface.wrapText(g, cf, msg, contentW - 34f);
                float boxH = lines.size() * 22f + 24f;
                OssGlassSurface.drawCallout(
                        g, p.dangerTint, p.danger, PAD_X, y + 40f, contentW, boxH);
                g.setFont(cf);
                g.setColor(p.text);
                for (int i = 0; i < lines.size(); i++) {
                    g.drawString(lines.get(i), PAD_X + 16f, y + 64f + i * 22f);
                }

                if (!snap.rows().isEmpty()) {
                    float listTop = y + 56f + boxH;
                    float listH = Math.max(40f, bodyBottom - listTop - 10f);
                    paintFileList(g, p, PAD_X, listTop, contentW, listH, snap.rows(), now);
                } else {
                    queueMaxScroll = 0;
                }
            }
            case DISCONNECTED -> {
                float y = bodyBottom * 0.5f - 62f;
                g.setFont(OssGlassSurface.bodyFont(Font.BOLD, 21f));
                g.setColor(p.text);
                g.drawString("连接已断开，更新尚未完成", PAD_X, y + 20f);

                Font cf = OssGlassSurface.bodyFont(Font.PLAIN, 13f);
                String msg =
                        snap.message().isBlank()
                                ? "已完整校验通过的暂存文件已安全保留在本地，当前整合包未被修改。你可以直接重新连接服务器继续完成剩余下载。"
                                : snap.message();
                List<String> lines = OssGlassSurface.wrapText(g, cf, msg, contentW - 34f);
                float boxH = lines.size() * 22f + 24f;
                OssGlassSurface.drawCallout(g, p.warnTint, p.warn, PAD_X, y + 40f, contentW, boxH);
                g.setFont(cf);
                g.setColor(p.text);
                for (int i = 0; i < lines.size(); i++) {
                    g.drawString(lines.get(i), PAD_X + 16f, y + 64f + i * 22f);
                }
                queueMaxScroll = 0;
            }
        }

        // Footer bar
        g.setColor(p.footerTint);
        g.fillRect(0, bodyBottom, w, FOOTER_H);
        g.setColor(p.rule);
        g.fillRect(0, bodyBottom, w, 1);

        g.setFont(OssGlassSurface.bodyFont(Font.PLAIN, 12.5f));
        g.setColor(p.secondary);
        g.drawString(footerNote, PAD_X, bodyBottom + 41f);

        float btnH = 36f;
        float btnY = bodyBottom + (FOOTER_H - btnH) * 0.5f;
        if (snap.phase() == OssLoginSession.Phase.STAGED) {
            float closeW = 122f;
            float backW = 104f;
            float closeX = w - PAD_X - closeW;
            float backX = closeX - 10f - backW;
            OssGlassSurface.drawButton(
                    g, p, hits, "back", "返回主菜单", backX, btnY, backW, btnH, false, true, mx, my,
                    pressed);
            OssGlassSurface.drawButton(
                    g,
                    p,
                    hits,
                    "close_now",
                    "立即关闭游戏",
                    closeX,
                    btnY,
                    closeW,
                    btnH,
                    true,
                    !stopTriggered,
                    mx,
                    my,
                    pressed);
        } else {
            String backLabel =
                    snap.phase() == OssLoginSession.Phase.DOWNLOADING ? "取消并返回主菜单" : "返回主菜单";
            float backW = snap.phase() == OssLoginSession.Phase.DOWNLOADING ? 142f : 108f;
            OssGlassSurface.drawButton(
                    g,
                    p,
                    hits,
                    "back",
                    backLabel,
                    w - PAD_X - backW,
                    btnY,
                    backW,
                    btnH,
                    false,
                    true,
                    mx,
                    my,
                    pressed);
        }
    }

    private void paintFileList(
            Graphics2D g,
            OssGlassSurface.Palette p,
            float x,
            float y,
            float w,
            float h,
            List<OssLoginSession.FileRow> rows,
            long now) {
        float rowH = 32f;
        float totalH = rows.size() * rowH;
        queueMaxScroll = Math.max(0f, totalH - h);
        if (queueScroll > queueMaxScroll) queueScroll = queueMaxScroll;

        Shape oldClip = g.getClip();
        g.clipRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h));

        Font mono = OssGlassSurface.monoFont(Font.PLAIN, 12f);
        Font small = OssGlassSurface.bodyFont(Font.PLAIN, 11.5f);
        Font smallBold = OssGlassSurface.bodyFont(Font.BOLD, 11.5f);

        for (int i = 0; i < rows.size(); i++) {
            float ry = y + i * rowH - queueScroll;
            if (ry + rowH < y || ry > y + h) continue;
            OssLoginSession.FileRow row = rows.get(i);

            if (row.state() == OssLoginSession.FileState.DOWNLOADING) {
                g.setColor(p.accentSoft);
                g.fill(new RoundRectangle2D.Float(x, ry + 1, w, rowH - 2, 16, 16));
            } else if (row.state() == OssLoginSession.FileState.VERIFIED
                    && row.verifiedAtMs() > 0) {
                long age = now - row.verifiedAtMs();
                if (age >= 0 && age < 560) {
                    float alpha = 1f - (age / 560f);
                    g.setColor(
                            new Color(
                                    p.ok.getRed(),
                                    p.ok.getGreen(),
                                    p.ok.getBlue(),
                                    Math.round(alpha * 34f)));
                    g.fill(new RoundRectangle2D.Float(x, ry + 1, w, rowH - 2, 16, 16));
                }
            }
            if (i > 0) {
                g.setColor(
                        new Color(
                                p.rule.getRed(),
                                p.rule.getGreen(),
                                p.rule.getBlue(),
                                Math.max(12, p.rule.getAlpha() / 2)));
                g.fillRect(Math.round(x + 8), Math.round(ry), Math.round(w - 16), 1);
            }

            // Crisp vector status indicator (no missing-glyph risk across platforms)
            Color iconColor;
            float cx = x + 14f;
            float cy = ry + rowH * 0.5f;
            Stroke oldStroke = g.getStroke();
            switch (row.state()) {
                case VERIFIED -> {
                    iconColor = p.ok;
                    g.setColor(iconColor);
                    g.setStroke(
                            new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    java.awt.geom.Path2D check = new java.awt.geom.Path2D.Float();
                    check.moveTo(cx - 4.2f, cy + 0.2f);
                    check.lineTo(cx - 1.2f, cy + 3.2f);
                    check.lineTo(cx + 4.4f, cy - 3.0f);
                    g.draw(check);
                }
                case DOWNLOADING -> {
                    iconColor = p.accent;
                    float dy = (float) Math.sin(now / 180.0) * 1.4f;
                    g.setColor(iconColor);
                    g.setStroke(
                            new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Line2D.Float(cx, cy - 4.2f + dy, cx, cy + 3.5f + dy));
                    java.awt.geom.Path2D head = new java.awt.geom.Path2D.Float();
                    head.moveTo(cx - 3.1f, cy + 0.8f + dy);
                    head.lineTo(cx, cy + 3.9f + dy);
                    head.lineTo(cx + 3.1f, cy + 0.8f + dy);
                    g.draw(head);
                }
                case CONFLICT -> {
                    iconColor = p.danger;
                    g.setColor(iconColor);
                    g.setStroke(
                            new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Line2D.Float(cx, cy - 4.4f, cx, cy + 1.0f));
                    g.fill(new java.awt.geom.Ellipse2D.Float(cx - 1.1f, cy + 3.0f, 2.2f, 2.2f));
                }
                default -> {
                    iconColor = p.muted;
                    g.setColor(iconColor);
                    g.fill(new java.awt.geom.Ellipse2D.Float(cx - 2.0f, cy - 2.0f, 4.0f, 4.0f));
                }
            }
            g.setStroke(oldStroke);

            // Right state label + size
            String stLabel =
                    switch (row.state()) {
                        case VERIFIED -> "已校验";
                        case DOWNLOADING -> "下载中";
                        case CONFLICT -> row.note().isBlank() ? "已保留原文件" : row.note();
                        default -> "排队";
                    };
            g.setFont(row.state() == OssLoginSession.FileState.DOWNLOADING ? smallBold : small);
            int stW = g.getFontMetrics().stringWidth(stLabel);
            g.setColor(iconColor);
            g.drawString(stLabel, x + w - 10f - stW, ry + 20f);

            String sizeLabel = formatBytes(row.size());
            g.setFont(small);
            int szW = g.getFontMetrics().stringWidth(sizeLabel);
            g.setColor(p.muted);
            g.drawString(sizeLabel, x + w - 22f - stW - szW, ry + 20f);

            // Truncated path
            float nameMaxW = Math.max(80f, w - 58f - stW - szW);
            g.setFont(mono);
            g.setColor(p.text);
            g.drawString(
                    OssGlassSurface.ellipsis(g, mono, row.path(), nameMaxW), x + 28f, ry + 20f);
        }

        g.setClip(oldClip);
        OssGlassSurface.drawScrollbar(g, p, x + w + 8f, y, h, queueScroll, queueMaxScroll, h);
    }

    private static void paintDeterminateBar(
            Graphics2D g,
            OssGlassSurface.Palette p,
            float x,
            float y,
            float w,
            float h,
            float pct,
            long now) {
        RoundRectangle2D track = new RoundRectangle2D.Float(x, y, w, h, h, h);
        g.setColor(p.rule);
        g.fill(track);
        float fillW = Math.max(0f, Math.min(w, w * pct));
        if (fillW < 1f) return;

        Shape oldClip = g.getClip();
        g.clip(new RoundRectangle2D.Float(x, y, fillW, h, h, h));
        g.setPaint(new GradientPaint(x, y, p.progressStart, x + w, y, p.accent));
        g.fillRect(Math.round(x), Math.round(y), Math.round(fillW + 1), Math.round(h));

        // Soft moving highlight sheen
        float cycle = (now % 2200L) / 2200f;
        float sheenX = x + (cycle * 1.5f - 0.25f) * w;
        float sheenW = Math.max(48f, w * 0.16f);
        g.setPaint(
                new GradientPaint(
                        sheenX,
                        y,
                        new Color(255, 255, 255, 0),
                        sheenX + sheenW * 0.5f,
                        y,
                        new Color(255, 255, 255, 96),
                        true));
        g.fillRect(Math.round(sheenX), Math.round(y), Math.round(sheenW), Math.round(h));
        g.setClip(oldClip);
    }

    private static void paintIndeterminateBar(
            Graphics2D g, OssGlassSurface.Palette p, float x, float y, float w, float h, long now) {
        RoundRectangle2D track = new RoundRectangle2D.Float(x, y, w, h, h, h);
        g.setColor(p.rule);
        g.fill(track);

        Shape oldClip = g.getClip();
        g.clip(track);
        float segW = w * 0.34f;
        float cycle = (now % 1400L) / 1400f;
        float sx = x - segW + cycle * (w + segW * 2f);
        g.setPaint(new GradientPaint(sx, y, p.progressStart, sx + segW, y, p.accent));
        g.fill(new RoundRectangle2D.Float(sx, y, segW, h, h, h));
        g.setClip(oldClip);
    }

    private static float bumpProgress(long now, long bumpAtMs) {
        if (bumpAtMs <= 0) return 1f;
        return Math.min(1f, (now - bumpAtMs) / 320f);
    }

    private static float drawStat(
            Graphics2D g,
            OssGlassSurface.Palette p,
            float x,
            float y,
            String label,
            String value,
            float bump) {
        Font lf = OssGlassSurface.bodyFont(Font.PLAIN, 12.5f);
        g.setFont(lf);
        g.setColor(p.secondary);
        g.drawString(label, x, y);
        float vx = x + g.getFontMetrics().stringWidth(label) + 5f;

        Font vf = OssGlassSurface.bodyFont(Font.BOLD, 12.5f);
        g.setFont(vf);
        g.setColor(bump < 1f ? blend(p.accent, p.text, bump) : p.text);
        g.drawString(value, vx, y + (1f - bump) * 2f);
        return vx + g.getFontMetrics().stringWidth(value);
    }

    private static Color blend(Color a, Color b, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * clamped),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * clamped),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * clamped));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
