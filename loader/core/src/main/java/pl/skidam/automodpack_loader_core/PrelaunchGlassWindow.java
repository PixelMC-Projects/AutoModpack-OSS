package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.oss.PackTrust;
import pl.skidam.automodpack_core.oss.VerifiedManifest;
import pl.skidam.automodpack_core.oss.client.ConsentCountdown;
import pl.skidam.automodpack_core.oss.client.PrelaunchSyncCoordinator;
import pl.skidam.automodpack_core.oss.client.ui.OssGlassCore;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

/**
 * Borderless HiDPI liquid-glass Swing window displayed before Forge scans mods when an update is
 * available. Shares its exact palette, typography, callouts, and progress bar language with the
 * in-game screens via {@link OssGlassCore}. Created lazily so up-to-date or offline launches never
 * initialize any window.
 */
final class PrelaunchGlassWindow implements PrelaunchSyncCoordinator.PrelaunchUi {
    private static final int PAD_X = 44;
    private static final int FOOTER_H = 74;

    private enum Mode {
        NONE,
        CONSENT,
        STATUS
    }

    private final Path gameRoot;
    private final AtomicBoolean skipped = new AtomicBoolean(false);
    private final AtomicReference<PrelaunchSyncCoordinator.UiSnapshot> snapshotRef =
            new AtomicReference<>();
    private final AtomicReference<Mode> modeRef = new AtomicReference<>(Mode.NONE);

    // Consent state
    private volatile PackTrust consentTrust;
    private volatile VerifiedManifest consentManifest;
    private volatile ConsentCountdown consentCountdown;
    private volatile String consentRootsLabel = "";
    private volatile CountDownLatch consentLatch;
    private final AtomicBoolean consentApproved = new AtomicBoolean(false);
    private final AtomicBoolean consentDecided = new AtomicBoolean(false);

    // Wait-until-closed state (for CONFLICT / ERROR / SELF_UPDATE_REQUIRED)
    private volatile CountDownLatch closeWaitLatch;

    // Swing UI fields (accessed on EDT)
    private JFrame dialog;
    private GlassCanvas canvas;
    private javax.swing.Timer repaintTimer;
    private OssGlassCore.Palette palette;
    private boolean translucentSupported;

    PrelaunchGlassWindow(Path gameRoot) {
        this.gameRoot = Objects.requireNonNull(gameRoot, "gameRoot");
    }

    @Override
    public boolean promptConsent(
            PackTrust trust, VerifiedManifest manifest, ConsentCountdown countdown) {
        this.consentTrust = trust;
        this.consentManifest = manifest;
        this.consentCountdown = countdown;
        this.consentRootsLabel = String.join(" / ", new TreeSet<>(trust.allowedRoots()));
        this.consentApproved.set(false);
        this.consentDecided.set(false);
        this.consentLatch = new CountDownLatch(1);
        this.modeRef.set(Mode.CONSENT);

        runOnEdtAndWait(
                () -> {
                    ensureDialogCreated();
                    canvas.resetConsentView();
                    showDialog();
                });

        try {
            consentLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            countdown.hidden();
        }
        return consentApproved.get();
    }

    @Override
    public void onSnapshot(PrelaunchSyncCoordinator.UiSnapshot snapshot) {
        snapshotRef.set(snapshot);
        modeRef.set(Mode.STATUS);
        if (snapshot.phase() == PrelaunchSyncCoordinator.Phase.CONFLICT
                || snapshot.phase() == PrelaunchSyncCoordinator.Phase.ERROR
                || snapshot.phase() == PrelaunchSyncCoordinator.Phase.SELF_UPDATE_REQUIRED) {
            if (closeWaitLatch == null) {
                closeWaitLatch = new CountDownLatch(1);
            }
        }
        SwingUtilities.invokeLater(
                () -> {
                    ensureDialogCreated();
                    showDialog();
                    canvas.repaint();
                });
        if (snapshot.phase() == PrelaunchSyncCoordinator.Phase.APPLIED) {
            try {
                Thread.sleep(360L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isSkipped() {
        return skipped.get();
    }

    @Override
    public void waitUntilClosed() {
        CountDownLatch latch = closeWaitLatch;
        if (latch == null) return;
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        CountDownLatch cl = consentLatch;
        if (cl != null) cl.countDown();
        CountDownLatch wl = closeWaitLatch;
        if (wl != null) wl.countDown();
        runOnEdtAndWait(
                () -> {
                    if (repaintTimer != null) {
                        repaintTimer.stop();
                        repaintTimer = null;
                    }
                    if (dialog != null) {
                        dialog.setVisible(false);
                        dialog.dispose();
                        dialog = null;
                    }
                });
    }

    private void decideConsent(boolean approved) {
        ConsentCountdown cd = consentCountdown;
        if (approved && (cd == null || !cd.canAccept())) return;
        if (!consentDecided.compareAndSet(false, true)) return;
        consentApproved.set(approved);
        CountDownLatch latch = consentLatch;
        if (latch != null) latch.countDown();
    }

    private void releaseWaitOrSkip() {
        Mode mode = modeRef.get();
        if (mode == Mode.CONSENT) {
            decideConsent(false);
            return;
        }
        PrelaunchSyncCoordinator.UiSnapshot snap = snapshotRef.get();
        if (snap != null
                && (snap.phase() == PrelaunchSyncCoordinator.Phase.DOWNLOADING
                        || snap.phase() == PrelaunchSyncCoordinator.Phase.CHECKING)) {
            skipped.set(true);
        }
        CountDownLatch wl = closeWaitLatch;
        if (wl != null) wl.countDown();
    }

    private void ensureDialogCreated() {
        if (dialog != null) return;
        palette = OssGlassCore.Palette.detect(gameRoot);

        GraphicsDevice gd =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        translucentSupported =
                gd.isWindowTranslucencySupported(
                        GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);

        dialog = new JFrame("PixelMC 整合包同步");
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setAlwaysOnTop(true);
        if (translucentSupported) {
            try {
                dialog.setBackground(new Color(0, 0, 0, 0));
            } catch (Throwable unsupported) {
                translucentSupported = false;
            }
        }

        int pad = translucentSupported ? OssGlassCore.SHADOW_PAD : 0;
        int winW = OssGlassCore.LOGICAL_W + pad * 2;
        int winH = OssGlassCore.LOGICAL_H + pad * 2;

        // Keep window safely within primary monitor bounds
        Rectangle screen =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        float scale = 1f;
        if (winW > screen.width - 32 || winH > screen.height - 32) {
            scale = Math.min((screen.width - 32f) / winW, (screen.height - 32f) / winH);
            scale = Math.max(0.72f, scale);
        }

        canvas = new GlassCanvas(pad, scale);
        canvas.setPreferredSize(new Dimension(Math.round(winW * scale), Math.round(winH * scale)));
        canvas.setOpaque(!translucentSupported);
        dialog.setContentPane(canvas);
        dialog.pack();
        int cx = screen.x + Math.max(0, (screen.width - dialog.getWidth()) / 2);
        int cy = screen.y + Math.max(0, (screen.height - dialog.getHeight()) / 2);
        dialog.setLocation(cx, cy);

        dialog.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        releaseWaitOrSkip();
                    }
                });

        dialog.getRootPane()
                .registerKeyboardAction(
                        e -> releaseWaitOrSkip(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);

        repaintTimer = new javax.swing.Timer(33, e -> canvas.repaint());
        repaintTimer.start();
    }

    private void showDialog() {
        if (dialog != null && !dialog.isVisible()) {
            dialog.setVisible(true);
            dialog.toFront();
        }
    }

    private static void runOnEdtAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
        }
    }

    private final class GlassCanvas extends JPanel {
        private final int pad;
        private final float scale;
        private java.awt.image.BufferedImage cachedShellBackdrop;
        private int cachedShellW;
        private int cachedShellH;

        private float mouseX = -1000f;
        private float mouseY = -1000f;
        private String pressedId;
        private List<OssGlassCore.Hit> lastHits = List.of();

        // Window drag tracking
        private Point dragStartScreen;
        private Point dragStartDialog;

        // Consent view state
        private float consentScroll;
        private float consentMaxScroll;
        private long lastTickSec = -1;
        private long tickFlashAtMs;

        // Status view state
        private float queueScroll;
        private float queueMaxScroll;
        private PrelaunchSyncCoordinator.Phase lastPhase;
        private double shownPct = -1;
        private double shownBytes = -1;
        private double shownSpeed = -1;
        private int lastDoneFiles = -1;
        private long filesBumpAtMs;
        private long lastEtaSec = -1;
        private long etaBumpAtMs;

        GlassCanvas(int pad, float scale) {
            this.pad = pad;
            this.scale = scale;

            MouseAdapter mouse =
                    new MouseAdapter() {
                        @Override
                        public void mouseMoved(MouseEvent e) {
                            updateMouse(e);
                            repaint();
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            mouseX = -1000f;
                            mouseY = -1000f;
                            setCursor(Cursor.getDefaultCursor());
                            repaint();
                        }

                        @Override
                        public void mousePressed(MouseEvent e) {
                            if (!SwingUtilities.isLeftMouseButton(e)) return;
                            updateMouse(e);
                            pressedId = hitAt(mouseX, mouseY);
                            if (pressedId == null && dialog != null) {
                                dragStartScreen = e.getLocationOnScreen();
                                dragStartDialog = dialog.getLocation();
                            }
                            repaint();
                        }

                        @Override
                        public void mouseDragged(MouseEvent e) {
                            if (dragStartScreen != null
                                    && dragStartDialog != null
                                    && dialog != null) {
                                Point cur = e.getLocationOnScreen();
                                dialog.setLocation(
                                        dragStartDialog.x + (cur.x - dragStartScreen.x),
                                        dragStartDialog.y + (cur.y - dragStartScreen.y));
                                return;
                            }
                            updateMouse(e);
                            repaint();
                        }

                        @Override
                        public void mouseReleased(MouseEvent e) {
                            dragStartScreen = null;
                            dragStartDialog = null;
                            if (!SwingUtilities.isLeftMouseButton(e)) return;
                            updateMouse(e);
                            String clicked = pressedId;
                            pressedId = null;
                            if (clicked != null && clicked.equals(hitAt(mouseX, mouseY))) {
                                handleButton(clicked);
                            }
                            repaint();
                        }

                        @Override
                        public void mouseWheelMoved(MouseWheelEvent e) {
                            float delta = (float) (e.getPreciseWheelRotation() * 28.0);
                            if (modeRef.get() == Mode.CONSENT && consentMaxScroll > 0) {
                                consentScroll =
                                        Math.max(
                                                0f,
                                                Math.min(consentMaxScroll, consentScroll + delta));
                                repaint();
                            } else if (modeRef.get() == Mode.STATUS && queueMaxScroll > 0) {
                                queueScroll =
                                        Math.max(0f, Math.min(queueMaxScroll, queueScroll + delta));
                                repaint();
                            }
                        }
                    };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        void resetConsentView() {
            consentScroll = 0f;
            consentMaxScroll = 0f;
            lastTickSec = -1;
            pressedId = null;
        }

        private void updateMouse(MouseEvent e) {
            mouseX = (e.getX() / scale) - pad;
            mouseY = (e.getY() / scale) - pad;
            String h = hitAt(mouseX, mouseY);
            setCursor(
                    h != null
                            ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            : Cursor.getDefaultCursor());
        }

        private String hitAt(float lx, float ly) {
            for (int i = lastHits.size() - 1; i >= 0; i--) {
                OssGlassCore.Hit hit = lastHits.get(i);
                if (hit.enabled() && hit.contains(lx, ly)) return hit.id();
            }
            return null;
        }

        private void handleButton(String id) {
            switch (id) {
                case "decline" -> decideConsent(false);
                case "accept" -> decideConsent(true);
                case "skip" -> {
                    skipped.set(true);
                    CountDownLatch wl = closeWaitLatch;
                    if (wl != null) wl.countDown();
                }
                case "continue" -> {
                    CountDownLatch wl = closeWaitLatch;
                    if (wl != null) wl.countDown();
                }
                default -> {}
            }
        }

        @Override
        protected void paintComponent(Graphics raw) {
            Graphics2D g = (Graphics2D) raw.create();
            try {
                OssGlassCore.applyQualityHints(g);
                if (translucentSupported) {
                    g.setComposite(AlphaComposite.Clear);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setComposite(AlphaComposite.SrcOver);
                } else {
                    OssGlassCore.paintBackdrop(g, palette, getWidth(), getHeight());
                }

                int w = OssGlassCore.LOGICAL_W;
                int h = OssGlassCore.LOGICAL_H;
                drawCachedShellBackdrop(g, w, h);

                g.scale(scale, scale);
                g.translate(pad, pad);

                Shape oldClip = g.getClip();
                RoundRectangle2D shellClip = new RoundRectangle2D.Float(0, 0, w, h, 48, 48);
                g.setClip(shellClip);

                List<OssGlassCore.Hit> hits = new ArrayList<>();
                Mode mode = modeRef.get();
                long now = System.currentTimeMillis();
                if (mode == Mode.CONSENT) {
                    paintConsent(g, palette, w, h, hits, now);
                } else if (mode == Mode.STATUS) {
                    PrelaunchSyncCoordinator.UiSnapshot snap = snapshotRef.get();
                    if (snap != null) {
                        paintStatus(g, palette, w, h, hits, snap, now);
                    }
                }
                lastHits = hits;

                g.setClip(oldClip);
                OssGlassCore.paintShellBorder(g, palette, w, h);
            } finally {
                g.dispose();
            }
        }

        private void drawCachedShellBackdrop(Graphics2D g, int w, int h) {
            int cw = Math.max(1, getWidth());
            int ch = Math.max(1, getHeight());
            if (cachedShellBackdrop == null || cachedShellW != cw || cachedShellH != ch) {
                java.awt.image.BufferedImage img =
                        new java.awt.image.BufferedImage(
                                cw, ch, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D bg = img.createGraphics();
                try {
                    OssGlassCore.applyQualityHints(bg);
                    bg.scale(scale, scale);
                    bg.translate(pad, pad);
                    OssGlassCore.paintShellShadowAndFrame(bg, palette, w, h);
                    RoundRectangle2D shellClip = new RoundRectangle2D.Float(0, 0, w, h, 48, 48);
                    bg.setClip(shellClip);
                    paintShellAmbientTint(bg, palette, w, h);
                } finally {
                    bg.dispose();
                }
                cachedShellBackdrop = img;
                cachedShellW = cw;
                cachedShellH = ch;
            }
            g.drawImage(cachedShellBackdrop, 0, 0, null);
        }

        private void paintShellAmbientTint(Graphics2D g, OssGlassCore.Palette p, int w, int h) {
            paintSoftGlow(g, w * 0.12f, h * 0.10f, w * 0.48f, p.ambientBlue, 42);
            paintSoftGlow(g, w * 0.88f, h * 0.86f, w * 0.50f, p.ambientPurple, 38);
            paintSoftGlow(g, w * 0.78f, h * 0.10f, w * 0.36f, p.ambientRose, 28);
        }

        private void paintSoftGlow(
                Graphics2D g, float cx, float cy, float radius, Color base, int alpha) {
            Color c = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            Color z = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);
            g.setPaint(
                    new RadialGradientPaint(
                            cx,
                            cy,
                            Math.max(1f, radius),
                            new float[] {0f, 1f},
                            new Color[] {c, z}));
            g.fill(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));
        }

        private void paintConsent(
                Graphics2D g,
                OssGlassCore.Palette p,
                int w,
                int h,
                List<OssGlassCore.Hit> hits,
                long now) {
            PackTrust trust = consentTrust;
            ConsentCountdown cd = consentCountdown;
            if (trust == null || cd == null) return;
            cd.shown();

            long rem = cd.remainingSeconds();
            if (rem != lastTickSec) {
                lastTickSec = rem;
                tickFlashAtMs = now;
            }

            int bodyBottom = h - FOOTER_H;
            int contentW = w - PAD_X * 2;

            Shape prevClip = g.getClip();
            g.clipRect(0, 0, w, bodyBottom);

            float y = 32f - consentScroll;

            Font titleFont = OssGlassCore.bodyFont(Font.BOLD, 21f);
            Font subFont = OssGlassCore.bodyFont(Font.PLAIN, 12f);
            g.setFont(titleFont);
            g.setColor(p.text);
            g.drawString("整合包自动同步授权", PAD_X, y + 20f);

            VerifiedManifest mf = consentManifest;
            String chName = mf != null && mf.channel() != null ? mf.channel().displayName() : "正式版";
            String rightTag = trust.identity() + " · " + chName;
            g.setFont(subFont);
            FontMetrics subFm = g.getFontMetrics();
            g.setColor(p.muted);
            g.drawString(rightTag, w - PAD_X - subFm.stringWidth(rightTag), y + 19f);

            y += 38f;
            g.setColor(p.rule);
            g.fillRect(PAD_X, Math.round(y), contentW, 1);

            // Metadata strip
            y += 24f;
            float cx = PAD_X;
            cx = drawMetaPair(g, p, cx, y, "整合包", trust.packId(), true) + 22f;
            cx = drawMetaPair(g, p, cx, y, "管理目录", consentRootsLabel, true) + 22f;
            drawMetaPair(g, p, cx, y, "来源校验", "本地公钥已核验", false);

            y += 14f;
            g.setColor(p.rule);
            g.fillRect(PAD_X, Math.round(y), contentW, 1);

            // Emerald safety callout
            y += 18f;
            Font calloutFont = OssGlassCore.bodyFont(Font.PLAIN, 13f);
            String calloutText =
                    "只处理签名清单内的受管文件；你的个人存档、截图及范围外文件不会被修改。被替换文件会保留备份，遇到个人修改冲突时会自动停止并保留原文件。";
            List<String> calloutLines =
                    OssGlassCore.wrapText(g, calloutFont, calloutText, contentW - 34f);
            float calloutLineH = 22f;
            float calloutH = calloutLines.size() * calloutLineH + 22f;
            OssGlassCore.drawCallout(g, p.okTint, p.ok, PAD_X, y, contentW, calloutH);
            g.setFont(calloutFont);
            g.setColor(p.text);
            for (int i = 0; i < calloutLines.size(); i++) {
                g.drawString(calloutLines.get(i), PAD_X + 16f, y + 24f + i * calloutLineH);
            }
            y += calloutH + 18f;

            // Prose paragraphs reflecting pre-launch one-shot sync
            String[][] paragraphs = {
                {"启动前一步自动同步。", "同意后，以后启动游戏时会在加载模组前自动检查更新并从私有 OSS 增量下载、逐文件校验并直接应用，无需关闭或重启游戏即可一次进入。"},
                {
                    "安全边界与随时跳过。",
                    "模组包含可执行代码，需要信任维护者。若本地已是最新版本，启动时静默通过、不弹窗；下载过程中可随时跳过，已校验文件会自动保留供下次续传。"
                },
                {
                    "连接与日志说明。",
                    "启动前仅向本服签名网关查询当前版本元数据并申请限时只读下载链接；网关与对象存储日志可能记录公网 IP、时间和下载记录，用于限流、排错与防滥用。"
                },
                {"", "选择「暂不启用」不会改动本地任何文件；如管理范围扩大或协议实质变更，会再次征求同意。后续需要切换通道或撤销授权时，可在游戏内旅人手册中管理。"}
            };

            Font proseFont = OssGlassCore.bodyFont(Font.PLAIN, 13.5f);
            Font boldProseFont = OssGlassCore.bodyFont(Font.BOLD, 13.5f);
            float proseLineH = 24f;
            for (String[] para : paragraphs) {
                String full = para[0] + para[1];
                List<String> wrapped = OssGlassCore.wrapText(g, proseFont, full, contentW);
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

            float totalContentH = (y + consentScroll) + 16f;
            consentMaxScroll = Math.max(0f, totalContentH - bodyBottom);
            if (consentScroll > consentMaxScroll) consentScroll = consentMaxScroll;

            g.setClip(prevClip);
            OssGlassCore.drawScrollbar(
                    g,
                    p,
                    w - 12f,
                    24f,
                    bodyBottom - 36f,
                    consentScroll,
                    consentMaxScroll,
                    bodyBottom);

            // Footer
            g.setColor(p.footerTint);
            g.fillRect(0, bodyBottom, w, FOOTER_H);
            g.setColor(p.rule);
            g.fillRect(0, bodyBottom, w, 1);

            boolean canAccept = !consentDecided.get() && cd.canAccept();
            float tickAge = Math.min(1f, (now - tickFlashAtMs) / 240f);
            Font noteFont = OssGlassCore.bodyFont(Font.PLAIN, 12.5f);
            g.setFont(noteFont);
            if (rem > 0) {
                String prefix = "阅读说明 ";
                String num = Long.toString(rem);
                String suffix = " 秒后可以确认";
                g.setColor(p.secondary);
                g.drawString(prefix, PAD_X, bodyBottom + 33f);
                float nx = PAD_X + g.getFontMetrics().stringWidth(prefix);
                g.setFont(OssGlassCore.bodyFont(Font.BOLD, 12.5f));
                g.setColor(p.accent);
                g.drawString(num, nx, bodyBottom + 33f + (1f - tickAge) * 2f);
                float sx = nx + g.getFontMetrics().stringWidth(num);
                g.setFont(noteFont);
                g.setColor(p.secondary);
                g.drawString(suffix, sx, bodyBottom + 33f);
            } else {
                g.setColor(p.secondary);
                g.drawString("仅需确认一次，后续启动将自动完成同步并直接进入游戏。", PAD_X, bodyBottom + 33f);
            }

            float trackW = 196f;
            float trackY = bodyBottom + 44f;
            g.setColor(p.rule);
            g.fill(new RoundRectangle2D.Float(PAD_X, trackY, trackW, 2.5f, 2.5f, 2.5f));
            float pct = Math.max(0f, Math.min(1f, (10f - rem) / 10f));
            if (pct > 0f) {
                g.setColor(p.accent);
                g.fill(new RoundRectangle2D.Float(PAD_X, trackY, trackW * pct, 2.5f, 2.5f, 2.5f));
            }

            float btnH = 36f;
            float btnY = bodyBottom + (FOOTER_H - btnH) * 0.5f;
            String acceptLabel = rem > 0 ? "同意并启用 (" + rem + "s)" : "同意并启用同步";
            float acceptW = 148f;
            float declineW = 96f;
            float acceptX = w - PAD_X - acceptW;
            float declineX = acceptX - 10f - declineW;

            OssGlassCore.drawButton(
                    g,
                    p,
                    hits,
                    "decline",
                    "暂不启用",
                    declineX,
                    btnY,
                    declineW,
                    btnH,
                    false,
                    !consentDecided.get(),
                    mouseX,
                    mouseY,
                    pressedId);
            OssGlassCore.drawButton(
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
                    mouseX,
                    mouseY,
                    pressedId);
        }

        private void paintStatus(
                Graphics2D g,
                OssGlassCore.Palette p,
                int w,
                int h,
                List<OssGlassCore.Hit> hits,
                PrelaunchSyncCoordinator.UiSnapshot snap,
                long now) {
            if (snap.phase() != lastPhase) {
                lastPhase = snap.phase();
                queueScroll = 0f;
            }

            double targetPct =
                    snap.totalBytes() > 0
                            ? Math.min(100.0, (snap.downloadedBytes() * 100.0) / snap.totalBytes())
                            : snap.totalFiles() > 0
                                    ? Math.min(
                                            100.0, (snap.doneFiles() * 100.0) / snap.totalFiles())
                                    : 0.0;
            if (shownPct < 0) {
                shownPct = targetPct;
                shownBytes = snap.downloadedBytes();
                shownSpeed = snap.speedBytesPerSec();
            } else {
                double k = 0.20;
                shownPct += (targetPct - shownPct) * k;
                shownBytes += (snap.downloadedBytes() - shownBytes) * k;
                shownSpeed += (snap.speedBytesPerSec() - shownSpeed) * k;
            }
            if (snap.doneFiles() != lastDoneFiles) {
                lastDoneFiles = snap.doneFiles();
                filesBumpAtMs = now;
            }

            int bodyBottom = h - FOOTER_H;
            int contentW = w - PAD_X * 2;
            String who =
                    snap.identity() == null || snap.identity().isBlank()
                            ? "PixelMC"
                            : snap.identity();
            String chLabel = snap.channel() == null ? "正式版" : snap.channel().displayName();
            String footerNote = "更新完成后将直接加载最新模组，无需重启游戏。";

            switch (snap.phase()) {
                case CHECKING -> {
                    float y = bodyBottom * 0.5f - 46f;
                    g.setFont(OssGlassCore.bodyFont(Font.BOLD, 21f));
                    g.setColor(p.text);
                    g.drawString("正在检查整合包更新", PAD_X, y + 20f);

                    g.setFont(OssGlassCore.bodyFont(Font.PLAIN, 13f));
                    g.setColor(p.secondary);
                    g.drawString("正在核对 " + who + " " + chLabel + "签名清单，请稍候…", PAD_X, y + 48f);
                    OssGlassCore.paintIndeterminateBar(g, p, PAD_X, y + 70f, contentW, 6f, now);
                    queueMaxScroll = 0f;
                }
                case DOWNLOADING -> {
                    footerNote = "跳过后已校验的文件会自动保留，下次启动直接续传。";
                    float y = 34f;
                    g.setFont(OssGlassCore.bodyFont(Font.BOLD, 21f));
                    g.setColor(p.text);
                    g.drawString("正在同步整合包更新", PAD_X, y + 20f);

                    String pctText = Math.round(shownPct) + "%";
                    g.setFont(OssGlassCore.bodyFont(Font.BOLD, 20f));
                    FontMetrics pfm = g.getFontMetrics();
                    g.drawString(pctText, w - PAD_X - pfm.stringWidth(pctText), y + 20f);

                    g.setFont(OssGlassCore.bodyFont(Font.PLAIN, 13f));
                    g.setColor(p.secondary);
                    g.drawString(
                            who + " " + chLabel + " · 启动前增量下载并逐项校验 SHA-256，完成后直接进入游戏。",
                            PAD_X,
                            y + 44f);

                    OssGlassCore.paintDeterminateBar(
                            g, p, PAD_X, y + 64f, contentW, 6f, (float) (shownPct / 100.0), now);

                    long etaSec =
                            shownSpeed > 1024 && snap.totalBytes() > shownBytes
                                    ? Math.max(
                                            1L,
                                            Math.round(
                                                    (snap.totalBytes() - shownBytes) / shownSpeed))
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
                                            OssGlassCore.formatBytes((long) shownBytes)
                                                    + " / "
                                                    + OssGlassCore.formatBytes(snap.totalBytes()),
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
                                                    ? OssGlassCore.formatBytes((long) shownSpeed)
                                                            + "/s"
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

                    float qTop = y + 114f;
                    g.setColor(p.rule);
                    g.fillRect(PAD_X, Math.round(qTop), contentW, 1);

                    g.setFont(OssGlassCore.bodyFont(Font.PLAIN, 11.5f));
                    g.setColor(p.muted);
                    String qLabel =
                            snap.reusedFiles() > 0
                                    ? "文件校验队列（已本地复用 " + snap.reusedFiles() + " 个未变更文件）"
                                    : "文件校验队列";
                    g.drawString(qLabel, PAD_X, qTop + 22f);

                    float listTop = qTop + 32f;
                    float listH = Math.max(40f, bodyBottom - listTop - 10f);
                    paintFileList(g, p, PAD_X, listTop, contentW, listH, snap.rows(), now);
                }
                case APPLYING -> {
                    float y = bodyBottom * 0.5f - 46f;
                    g.setFont(OssGlassCore.bodyFont(Font.BOLD, 21f));
                    g.setColor(p.text);
                    g.drawString("正在应用整合包更新", PAD_X, y + 20f);

                    g.setFont(OssGlassCore.bodyFont(Font.PLAIN, 13f));
                    g.setColor(p.secondary);
                    g.drawString("全部文件已校验通过，正在原子替换受管文件并保留旧版备份…", PAD_X, y + 48f);
                    OssGlassCore.paintIndeterminateBar(g, p, PAD_X, y + 70f, contentW, 6f, now);
                    queueMaxScroll = 0f;
                }
                case APPLIED -> {
                    float y = bodyBottom * 0.5f - 44f;
                    g.setFont(OssGlassCore.bodyFont(Font.BOLD, 21f));
                    g.setColor(p.ok);
                    g.drawString("更新已就位，正在启动游戏", PAD_X, y + 20f);

                    g.setFont(OssGlassCore.bodyFont(Font.PLAIN, 13f));
                    g.setColor(p.secondary);
                    String summary =
                            "本次共同步 "
                                    + snap.doneFiles()
                                    + " 个文件（复用 "
                                    + snap.reusedFiles()
                                    + " 个），即将加载最新模组直接进入 Minecraft。";
                    g.drawString(summary, PAD_X, y + 48f);
                    OssGlassCore.paintDeterminateBar(g, p, PAD_X, y + 70f, contentW, 6f, 1f, now);
                    queueMaxScroll = 0f;
                }
                case SELF_UPDATE_REQUIRED -> {
                    footerNote = "同步组件本身更新时需要重新启动一次游戏。";
                    float y = bodyBottom * 0.5f - 62f;
                    g.setFont(OssGlassCore.bodyFont(Font.BOLD, 21f));
                    g.setColor(p.text);
                    g.drawString("同步组件需要重启生效", PAD_X, y + 20f);

                    Font cf = OssGlassCore.bodyFont(Font.PLAIN, 13f);
                    String msg =
                            snap.message() == null || snap.message().isBlank()
                                    ? "检测到同步组件本身有更新，请点击继续后重新启动一次游戏。"
                                    : snap.message();
                    List<String> lines = OssGlassCore.wrapText(g, cf, msg, contentW - 34f);
                    float boxH = lines.size() * 22f + 24f;
                    OssGlassCore.drawCallout(g, p.warnTint, p.warn, PAD_X, y + 40f, contentW, boxH);
                    g.setFont(cf);
                    g.setColor(p.text);
                    for (int i = 0; i < lines.size(); i++) {
                        g.drawString(lines.get(i), PAD_X + 16f, y + 64f + i * 22f);
                    }
                    queueMaxScroll = 0f;
                }
                case CONFLICT, ERROR -> {
                    footerNote = "现有整合包保持原状，点击按钮即可直接继续启动游戏。";
                    float y = 34f;
                    g.setFont(OssGlassCore.bodyFont(Font.BOLD, 21f));
                    g.setColor(p.text);
                    g.drawString(
                            snap.phase() == PrelaunchSyncCoordinator.Phase.CONFLICT
                                    ? "已停止安装：发现个人文件冲突"
                                    : "本次启动前同步未完成",
                            PAD_X,
                            y + 20f);

                    Font cf = OssGlassCore.bodyFont(Font.PLAIN, 13f);
                    String msg =
                            snap.message() == null || snap.message().isBlank()
                                    ? "已安全停止本次更新，现有文件保持原状。你仍可以继续启动游戏。"
                                    : snap.message();
                    List<String> lines = OssGlassCore.wrapText(g, cf, msg, contentW - 34f);
                    float boxH = lines.size() * 22f + 24f;
                    OssGlassCore.drawCallout(
                            g, p.dangerTint, p.danger, PAD_X, y + 40f, contentW, boxH);
                    g.setFont(cf);
                    g.setColor(p.text);
                    for (int i = 0; i < lines.size(); i++) {
                        g.drawString(lines.get(i), PAD_X + 16f, y + 64f + i * 22f);
                    }
                    queueMaxScroll = 0f;
                }
            }

            // Footer
            g.setColor(p.footerTint);
            g.fillRect(0, bodyBottom, w, FOOTER_H);
            g.setColor(p.rule);
            g.fillRect(0, bodyBottom, w, 1);

            g.setFont(OssGlassCore.bodyFont(Font.PLAIN, 12.5f));
            g.setColor(p.secondary);
            g.drawString(footerNote, PAD_X, bodyBottom + 41f);

            float btnH = 36f;
            float btnY = bodyBottom + (FOOTER_H - btnH) * 0.5f;
            if (snap.phase() == PrelaunchSyncCoordinator.Phase.DOWNLOADING
                    || snap.phase() == PrelaunchSyncCoordinator.Phase.CHECKING) {
                float bw = 132f;
                OssGlassCore.drawButton(
                        g,
                        p,
                        hits,
                        "skip",
                        "跳过本次并启动",
                        w - PAD_X - bw,
                        btnY,
                        bw,
                        btnH,
                        false,
                        true,
                        mouseX,
                        mouseY,
                        pressedId);
            } else if (snap.phase() == PrelaunchSyncCoordinator.Phase.CONFLICT
                    || snap.phase() == PrelaunchSyncCoordinator.Phase.ERROR
                    || snap.phase() == PrelaunchSyncCoordinator.Phase.SELF_UPDATE_REQUIRED) {
                float bw = 124f;
                OssGlassCore.drawButton(
                        g,
                        p,
                        hits,
                        "continue",
                        "继续启动游戏",
                        w - PAD_X - bw,
                        btnY,
                        bw,
                        btnH,
                        true,
                        true,
                        mouseX,
                        mouseY,
                        pressedId);
            }
        }

        private void paintFileList(
                Graphics2D g,
                OssGlassCore.Palette p,
                float x,
                float y,
                float w,
                float h,
                List<PrelaunchSyncCoordinator.FileRow> rows,
                long now) {
            float rowH = 32f;
            float totalH = rows.size() * rowH;
            queueMaxScroll = Math.max(0f, totalH - h);
            if (queueScroll > queueMaxScroll) queueScroll = queueMaxScroll;

            Shape oldClip = g.getClip();
            g.clipRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h));

            Font mono = OssGlassCore.monoFont(Font.PLAIN, 12f);
            Font small = OssGlassCore.bodyFont(Font.PLAIN, 11.5f);
            Font smallBold = OssGlassCore.bodyFont(Font.BOLD, 11.5f);

            for (int i = 0; i < rows.size(); i++) {
                float ry = y + i * rowH - queueScroll;
                if (ry + rowH < y || ry > y + h) continue;
                PrelaunchSyncCoordinator.FileRow row = rows.get(i);

                if (row.state() == PrelaunchSyncCoordinator.FileState.DOWNLOADING) {
                    g.setColor(p.accentSoft);
                    g.fill(new RoundRectangle2D.Float(x, ry + 1, w, rowH - 2, 16, 16));
                } else if (row.state() == PrelaunchSyncCoordinator.FileState.VERIFIED
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

                Color iconColor;
                float cx = x + 14f;
                float cy = ry + rowH * 0.5f;
                Stroke oldStroke = g.getStroke();
                switch (row.state()) {
                    case VERIFIED -> {
                        iconColor = p.ok;
                        g.setColor(iconColor);
                        g.setStroke(
                                new BasicStroke(
                                        1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        Path2D check = new Path2D.Float();
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
                                new BasicStroke(
                                        1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(new Line2D.Float(cx, cy - 4.2f + dy, cx, cy + 3.5f + dy));
                        Path2D head = new Path2D.Float();
                        head.moveTo(cx - 3.1f, cy + 0.8f + dy);
                        head.lineTo(cx, cy + 3.9f + dy);
                        head.lineTo(cx + 3.1f, cy + 0.8f + dy);
                        g.draw(head);
                    }
                    case CONFLICT -> {
                        iconColor = p.danger;
                        g.setColor(iconColor);
                        g.setStroke(
                                new BasicStroke(
                                        1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(new Line2D.Float(cx, cy - 4.4f, cx, cy + 1.0f));
                        g.fill(new Ellipse2D.Float(cx - 1.1f, cy + 3.0f, 2.2f, 2.2f));
                    }
                    default -> {
                        iconColor = p.muted;
                        g.setColor(iconColor);
                        g.fill(new Ellipse2D.Float(cx - 2.0f, cy - 2.0f, 4.0f, 4.0f));
                    }
                }
                g.setStroke(oldStroke);

                String stLabel =
                        switch (row.state()) {
                            case VERIFIED -> "已校验";
                            case DOWNLOADING -> "下载中";
                            case CONFLICT -> row.note().isBlank() ? "已保留原文件" : row.note();
                            default -> "排队";
                        };
                g.setFont(
                        row.state() == PrelaunchSyncCoordinator.FileState.DOWNLOADING
                                ? smallBold
                                : small);
                int stW = g.getFontMetrics().stringWidth(stLabel);
                g.setColor(iconColor);
                g.drawString(stLabel, x + w - 10f - stW, ry + 20f);

                String sizeLabel = OssGlassCore.formatBytes(row.size());
                g.setFont(small);
                int szW = g.getFontMetrics().stringWidth(sizeLabel);
                g.setColor(p.muted);
                g.drawString(sizeLabel, x + w - 22f - stW - szW, ry + 20f);

                float nameMaxW = Math.max(80f, w - 58f - stW - szW);
                g.setFont(mono);
                g.setColor(p.text);
                g.drawString(
                        OssGlassCore.ellipsis(g, mono, row.path(), nameMaxW), x + 28f, ry + 20f);
            }

            g.setClip(oldClip);
            OssGlassCore.drawScrollbar(g, p, x + w + 8f, y, h, queueScroll, queueMaxScroll, h);
        }

        private float drawMetaPair(
                Graphics2D g,
                OssGlassCore.Palette p,
                float x,
                float y,
                String key,
                String value,
                boolean mono) {
            Font kf = OssGlassCore.bodyFont(Font.PLAIN, 12.5f);
            g.setFont(kf);
            g.setColor(p.muted);
            g.drawString(key, x, y);
            float vx = x + g.getFontMetrics().stringWidth(key) + 6f;

            Font vf =
                    mono
                            ? OssGlassCore.monoFont(Font.PLAIN, 12f)
                            : OssGlassCore.bodyFont(Font.BOLD, 12.5f);
            g.setFont(vf);
            g.setColor(p.text);
            g.drawString(value, vx, y);
            return vx + g.getFontMetrics().stringWidth(value);
        }

        private float drawStat(
                Graphics2D g,
                OssGlassCore.Palette p,
                float x,
                float y,
                String label,
                String value,
                float bump) {
            Font lf = OssGlassCore.bodyFont(Font.PLAIN, 12.5f);
            g.setFont(lf);
            g.setColor(p.secondary);
            g.drawString(label, x, y);
            float vx = x + g.getFontMetrics().stringWidth(label) + 5f;

            Font vf = OssGlassCore.bodyFont(Font.BOLD, 12.5f);
            g.setFont(vf);
            g.setColor(bump < 1f ? blend(p.accent, p.text, bump) : p.text);
            g.drawString(value, vx, y + (1f - bump) * 2f);
            return vx + g.getFontMetrics().stringWidth(value);
        }

        private float bumpProgress(long now, long bumpAtMs) {
            if (bumpAtMs <= 0) return 1f;
            return Math.min(1f, (now - bumpAtMs) / 320f);
        }

        private Color blend(Color a, Color b, float t) {
            float c = Math.max(0f, Math.min(1f, t));
            return new Color(
                    Math.round(a.getRed() + (b.getRed() - a.getRed()) * c),
                    Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * c),
                    Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * c));
        }
    }
}
