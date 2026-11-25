package pl.skidam.automodpack.client.oss;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Self-contained high-DPI liquid-glass surface aligned with PixelMC-Hub's standard light/dark
 * aesthetic. Uses Java2D antialiased rendering uploaded to a reused OpenGL texture so AutoModpack
 * OSS stays independent of Hub and native Skija binaries while keeping crisp system typography.
 */
final class OssGlassSurface implements AutoCloseable {
    static final int LOGICAL_W = 760;
    static final int LOGICAL_H = 560;
    private static final int SHADOW_PAD = 36;
    private static final float MAX_DPI_SCALE = 1.75f;

    static final class Palette {
        final boolean dark;
        final Color canvasBase;
        final Color ambientBlue;
        final Color ambientPurple;
        final Color ambientRose;
        final Color shellBg;
        final Color shellBorder;
        final Color shellTopRim;
        final Color footerTint;
        final Color text;
        final Color secondary;
        final Color muted;
        final Color rule;
        final Color accent;
        final Color accentHover;
        final Color accentSoft;
        final Color progressStart;
        final Color ok;
        final Color okTint;
        final Color warn;
        final Color warnTint;
        final Color danger;
        final Color dangerTint;
        final Color btnBg;
        final Color btnHover;
        final Color btnBorder;
        final Color shadowColor;

        private Palette(boolean dark) {
            this.dark = dark;
            if (!dark) {
                canvasBase = new Color(0xe6, 0xea, 0xf8, 0xd8);
                ambientBlue = new Color(0xb6, 0xda, 0xfa, 0x99);
                ambientPurple = new Color(0xbf, 0xb1, 0xf0, 0x8f);
                ambientRose = new Color(0xf9, 0xe3, 0xed, 0x7a);
                shellBg = new Color(0xf8, 0xfa, 0xff, 0xdc);
                shellBorder = new Color(0xff, 0xff, 0xff, 0xeb);
                shellTopRim = new Color(0xff, 0xff, 0xff, 0xff);
                footerTint = new Color(0xff, 0xff, 0xff, 0x8f);
                text = new Color(0x26, 0x34, 0x4f);
                secondary = new Color(0x5e, 0x6b, 0x85);
                muted = new Color(0x84, 0x90, 0xa8);
                rule = new Color(0xa4, 0xb1, 0xce, 0x52);
                accent = new Color(0x72, 0x56, 0xd4);
                accentHover = new Color(0x63, 0x46, 0xc4);
                accentSoft = new Color(0x76, 0x5b, 0xd4, 0x1a);
                progressStart = new Color(0x5d, 0x84, 0xd6);
                ok = new Color(0x25, 0x7d, 0x66);
                okTint = new Color(0x25, 0x7d, 0x66, 0x15);
                warn = new Color(0xa0, 0x6f, 0x24);
                warnTint = new Color(0xae, 0x81, 0x32, 0x17);
                danger = new Color(0xb8, 0x3d, 0x52);
                dangerTint = new Color(0xb8, 0x3d, 0x52, 0x15);
                btnBg = new Color(0xff, 0xff, 0xff, 0xc8);
                btnHover = new Color(0xff, 0xff, 0xff, 0xff);
                btnBorder = new Color(0xbc, 0xc6, 0xe0, 0xa6);
                shadowColor = new Color(0x43, 0x58, 0x82);
            } else {
                canvasBase = new Color(0x15, 0x1d, 0x30, 0xe4);
                ambientBlue = new Color(0x2c, 0x45, 0x66, 0x96);
                ambientPurple = new Color(0x3a, 0x31, 0x5c, 0x94);
                ambientRose = new Color(0x32, 0x2d, 0x47, 0x7a);
                shellBg = new Color(0x22, 0x2c, 0x42, 0xdc);
                shellBorder = new Color(0xc4, 0xd7, 0xff, 0x30);
                shellTopRim = new Color(0xdb, 0xe6, 0xff, 0x1e);
                footerTint = new Color(0x19, 0x21, 0x34, 0x88);
                text = new Color(0xe6, 0xeb, 0xf6);
                secondary = new Color(0xad, 0xb9, 0xd1);
                muted = new Color(0x7f, 0x8d, 0xab);
                rule = new Color(0xa5, 0xb7, 0xe0, 0x2a);
                accent = new Color(0xa6, 0x8e, 0xf0);
                accentHover = new Color(0xb4, 0x9f, 0xf6);
                accentSoft = new Color(0xa6, 0x8e, 0xf0, 0x24);
                progressStart = new Color(0x5d, 0x84, 0xd6);
                ok = new Color(0x85, 0xcb, 0xb1);
                okTint = new Color(0x85, 0xcb, 0xb1, 0x1a);
                warn = new Color(0xe0, 0xbc, 0x7a);
                warnTint = new Color(0xe0, 0xbc, 0x7a, 0x1a);
                danger = new Color(0xee, 0x89, 0x98);
                dangerTint = new Color(0xee, 0x89, 0x98, 0x1c);
                btnBg = new Color(0x2e, 0x39, 0x54, 0xb8);
                btnHover = new Color(0x38, 0x44, 0x62, 0xeb);
                btnBorder = new Color(0xb8, 0xc8, 0xef, 0x33);
                shadowColor = new Color(0x05, 0x0b, 0x26);
            }
        }

        static Palette detect(Path gameDir) {
            boolean dark = false;
            try {
                Path hubPref = gameDir.resolve("config/pixelmc-hub-client.json");
                if (Files.isRegularFile(hubPref)) {
                    String raw = Files.readString(hubPref, StandardCharsets.UTF_8);
                    JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                    if (root.has("scopes") && root.get("scopes").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e :
                                root.getAsJsonObject("scopes").entrySet()) {
                            if (e.getValue().isJsonObject()) {
                                JsonObject scope = e.getValue().getAsJsonObject();
                                if (scope.has("theme")
                                        && "dark"
                                                .equalsIgnoreCase(
                                                        scope.get("theme").getAsString())) {
                                    dark = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return new Palette(dark);
        }
    }

    record Hit(String id, float x, float y, float w, float h, boolean enabled) {
        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    interface Painter {
        void paint(
                Graphics2D g,
                Palette palette,
                int width,
                int height,
                float mouseX,
                float mouseY,
                String pressedId,
                List<Hit> hits);
    }

    private static final String BODY_FONT =
            resolveFont(
                    true,
                    "Microsoft YaHei UI",
                    "Microsoft YaHei",
                    "PingFang SC",
                    "Hiragino Sans GB",
                    "Noto Sans CJK SC",
                    "Source Han Sans SC",
                    "WenQuanYi Micro Hei",
                    "SimHei",
                    Font.SANS_SERIF);
    private static final String MONO_FONT =
            resolveFont(
                    false,
                    "Cascadia Mono",
                    "Consolas",
                    "JetBrains Mono",
                    "SF Mono",
                    Font.MONOSPACED);

    private static String resolveFont(boolean requireCjk, String... names) {
        try {
            Set<String> installed =
                    new HashSet<>(
                            Arrays.asList(
                                    GraphicsEnvironment.getLocalGraphicsEnvironment()
                                            .getAvailableFontFamilyNames(Locale.ROOT)));
            for (String candidate : names) {
                if (!candidate.equals(Font.SANS_SERIF)
                        && !candidate.equals(Font.MONOSPACED)
                        && !installed.contains(candidate)) {
                    continue;
                }
                Font probe = new Font(candidate, Font.PLAIN, 14);
                boolean ok = probe.canDisplay('A') && probe.canDisplay('0');
                if (requireCjk) {
                    ok =
                            ok
                                    && probe.canDisplay('中')
                                    && probe.canDisplay('文')
                                    && probe.canDisplay('同');
                }
                if (ok) return candidate;
            }
        } catch (Throwable ignored) {
        }
        return names[names.length - 1];
    }

    static Font bodyFont(int style, float size) {
        return new Font(BODY_FONT, style, Math.round(size)).deriveFont(size);
    }

    static Font monoFont(int style, float size) {
        return new Font(MONO_FONT, style, Math.round(size)).deriveFont(size);
    }

    private final ResourceLocation backdropId;
    private final ResourceLocation panelId;
    private BufferedImage backdropBuf;
    private DynamicTexture backdropTex;
    private int backdropW, backdropH;

    private BufferedImage panelBuf;
    private DynamicTexture panelTex;
    private int panelTexW, panelTexH;

    private final List<Hit> hits = new ArrayList<>();
    private float lastPanelX, lastPanelY, lastPanelScale = 1f;
    private int lastGuiW, lastGuiH;

    OssGlassSurface(String tag) {
        this.backdropId = new ResourceLocation("automodpack", "oss_glass_bg_" + tag);
        this.panelId = new ResourceLocation("automodpack", "oss_glass_panel_" + tag);
    }

    void render(
            GuiGraphics graphics,
            Minecraft mc,
            Palette palette,
            int guiW,
            int guiH,
            int mouseGuiX,
            int mouseGuiY,
            String pressedId,
            float enterAlpha,
            Painter painter) {
        lastGuiW = guiW;
        lastGuiH = guiH;
        int fbW = Math.max(1, mc.getWindow().getWidth());
        int fbH = Math.max(1, mc.getWindow().getHeight());

        ensureBackdrop(mc, palette, Math.min(640, fbW), Math.min(360, fbH));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(
                backdropId, 0, 0, guiW, guiH, 0, 0, backdropW, backdropH, backdropW, backdropH);

        float fitScale =
                Math.min(Math.min(fbW * 0.90f / LOGICAL_W, fbH * 0.84f / LOGICAL_H), MAX_DPI_SCALE);
        float scale = Math.max(0.65f, fitScale);
        float guiToFbX = (float) fbW / Math.max(1, guiW);
        float guiToFbY = (float) fbH / Math.max(1, guiH);

        float panelFbW = LOGICAL_W * scale;
        float panelFbH = LOGICAL_H * scale;
        float panelGuiW = panelFbW / guiToFbX;
        float panelGuiH = panelFbH / guiToFbY;
        float padGuiX = (SHADOW_PAD * scale) / guiToFbX;
        float padGuiY = (SHADOW_PAD * scale) / guiToFbY;

        float enterOffsetGui = (1f - enterAlpha) * 6f;
        lastPanelX = (guiW - panelGuiW) * 0.5f;
        lastPanelY = (guiH - panelGuiH) * 0.5f + enterOffsetGui;
        lastPanelScale = scale / guiToFbX;

        int texW = Math.max(1, Math.round((LOGICAL_W + SHADOW_PAD * 2) * scale));
        int texH = Math.max(1, Math.round((LOGICAL_H + SHADOW_PAD * 2) * scale));
        ensurePanelBuffer(mc, texW, texH);

        float localMouseX = (mouseGuiX - lastPanelX) / Math.max(0.001f, lastPanelScale);
        float localMouseY = (mouseGuiY - lastPanelY) / Math.max(0.001f, lastPanelScale);

        hits.clear();
        Graphics2D g = panelBuf.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, texW, texH);
            g.setComposite(AlphaComposite.SrcOver);
            applyQualityHints(g);
            g.scale(scale, scale);
            g.translate(SHADOW_PAD, SHADOW_PAD);

            paintShellShadowAndFrame(g, palette, LOGICAL_W, LOGICAL_H);

            Shape oldClip = g.getClip();
            g.clip(new RoundRectangle2D.Float(0, 0, LOGICAL_W, LOGICAL_H, 48, 48));
            painter.paint(
                    g, palette, LOGICAL_W, LOGICAL_H, localMouseX, localMouseY, pressedId, hits);
            g.setClip(oldClip);

            // Crisp outer glass border + subtle top inner rim
            g.setStroke(new BasicStroke(1f));
            g.setColor(palette.shellBorder);
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, LOGICAL_W - 1f, LOGICAL_H - 1f, 47, 47));
            g.setColor(palette.shellTopRim);
            g.drawLine(28, 1, LOGICAL_W - 28, 1);
        } finally {
            g.dispose();
        }

        upload(panelBuf, panelTex);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0f, Math.min(1f, enterAlpha)));
        graphics.blit(
                panelId,
                Math.round(lastPanelX - padGuiX),
                Math.round(lastPanelY - padGuiY),
                Math.round(panelGuiW + padGuiX * 2f),
                Math.round(panelGuiH + padGuiY * 2f),
                0,
                0,
                panelTexW,
                panelTexH,
                panelTexW,
                panelTexH);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    String hitAt(double mouseGuiX, double mouseGuiY) {
        float lx = (float) ((mouseGuiX - lastPanelX) / Math.max(0.001f, lastPanelScale));
        float ly = (float) ((mouseGuiY - lastPanelY) / Math.max(0.001f, lastPanelScale));
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.enabled() && h.contains(lx, ly)) return h.id();
        }
        return null;
    }

    boolean insidePanel(double mouseGuiX, double mouseGuiY) {
        float lx = (float) ((mouseGuiX - lastPanelX) / Math.max(0.001f, lastPanelScale));
        float ly = (float) ((mouseGuiY - lastPanelY) / Math.max(0.001f, lastPanelScale));
        return lx >= 0 && lx <= LOGICAL_W && ly >= 0 && ly <= LOGICAL_H;
    }

    private void paintShellShadowAndFrame(Graphics2D g, Palette palette, int w, int h) {
        // Smooth multi-stop elevation shadow approximating CSS blur
        int[] spreads = {30, 24, 19, 14, 10, 6, 3};
        int[] offsets = {16, 13, 10, 8, 6, 4, 2};
        int[] alphas =
                palette.dark
                        ? new int[] {10, 14, 18, 22, 26, 28, 26}
                        : new int[] {5, 7, 9, 11, 13, 14, 12};
        for (int i = 0; i < spreads.length; i++) {
            int s = spreads[i];
            g.setColor(
                    new Color(
                            palette.shadowColor.getRed(),
                            palette.shadowColor.getGreen(),
                            palette.shadowColor.getBlue(),
                            alphas[i]));
            g.fill(
                    new RoundRectangle2D.Float(
                            -s * 0.5f,
                            offsets[i] - s * 0.32f,
                            w + s,
                            h + s * 0.85f,
                            48 + s * 0.65f,
                            48 + s * 0.65f));
        }

        // Frosted liquid-glass shell surface
        g.setPaint(
                new GradientPaint(
                        0,
                        0,
                        palette.shellBg,
                        w,
                        h,
                        new Color(
                                palette.shellBg.getRed(),
                                palette.shellBg.getGreen(),
                                palette.shellBg.getBlue(),
                                Math.max(160, palette.shellBg.getAlpha() - 16))));
        g.fill(new RoundRectangle2D.Float(0, 0, w, h, 48, 48));
    }

    private void ensureBackdrop(Minecraft mc, Palette palette, int w, int h) {
        if (backdropTex != null && backdropW == w && backdropH == h) return;
        releaseBackdrop(mc);
        backdropW = Math.max(1, w);
        backdropH = Math.max(1, h);
        backdropBuf = new BufferedImage(backdropW, backdropH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = backdropBuf.createGraphics();
        try {
            applyQualityHints(g);
            g.setColor(palette.canvasBase);
            g.fillRect(0, 0, backdropW, backdropH);
            paintRadialGlow(
                    g,
                    backdropW * 0.14f,
                    backdropH * 0.12f,
                    backdropW * 0.52f,
                    palette.ambientBlue);
            paintRadialGlow(
                    g,
                    backdropW * 0.90f,
                    backdropH * 0.88f,
                    backdropW * 0.54f,
                    palette.ambientPurple);
            paintRadialGlow(
                    g,
                    backdropW * 0.72f,
                    backdropH * 0.08f,
                    backdropW * 0.44f,
                    palette.ambientRose);
        } finally {
            g.dispose();
        }
        backdropTex = new DynamicTexture(new NativeImage(backdropW, backdropH, false));
        upload(backdropBuf, backdropTex);
        mc.getTextureManager().register(backdropId, backdropTex);
    }

    private static void paintRadialGlow(
            Graphics2D g, float cx, float cy, float radius, Color color) {
        Color transparent = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
        g.setPaint(
                new RadialGradientPaint(
                        cx,
                        cy,
                        Math.max(1f, radius),
                        new float[] {0f, 1f},
                        new Color[] {color, transparent}));
        g.fill(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));
    }

    private void ensurePanelBuffer(Minecraft mc, int w, int h) {
        if (panelTex != null && panelTexW == w && panelTexH == h) return;
        releasePanel(mc);
        panelTexW = w;
        panelTexH = h;
        panelBuf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        panelTex = new DynamicTexture(new NativeImage(w, h, false));
        mc.getTextureManager().register(panelId, panelTex);
    }

    private static void upload(BufferedImage src, DynamicTexture dst) {
        NativeImage img = dst.getPixels();
        if (img == null) return;
        int w = src.getWidth(), h = src.getHeight();
        int[] argb = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = argb[row + x];
                int a = (c >>> 24) & 0xff;
                int r = (c >>> 16) & 0xff;
                int g = (c >>> 8) & 0xff;
                int b = c & 0xff;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        dst.upload();
    }

    private static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    static void drawButton(
            Graphics2D g,
            Palette palette,
            List<Hit> hits,
            String id,
            String label,
            float x,
            float y,
            float w,
            float h,
            boolean primary,
            boolean enabled,
            float mouseX,
            float mouseY,
            String pressedId) {
        hits.add(new Hit(id, x, y, w, h, enabled));
        boolean hover = enabled && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        boolean pressed = enabled && id.equals(pressedId);
        float dy = pressed ? 1f : 0f;

        Composite oldComp = g.getComposite();
        if (!enabled) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.48f));

        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y + dy, w, h, 22, 22);
        if (primary) {
            if (enabled) {
                g.setColor(new Color(0x72, 0x56, 0xd4, hover ? 72 : 52));
                g.fill(new RoundRectangle2D.Float(x, y + dy + 3, w, h, 22, 22));
            }
            g.setColor(hover ? palette.accentHover : palette.accent);
            g.fill(shape);
            g.setColor(new Color(255, 255, 255, 68));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(
                    Math.round(x + 12),
                    Math.round(y + dy + 1),
                    Math.round(x + w - 12),
                    Math.round(y + dy + 1));
        } else {
            g.setColor(hover ? palette.btnHover : palette.btnBg);
            g.fill(shape);
            g.setColor(palette.btnBorder);
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(x + 0.5f, y + dy + 0.5f, w - 1f, h - 1f, 21, 21));
        }

        g.setFont(bodyFont(Font.BOLD, 13f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label);
        float tx = x + (w - tw) * 0.5f;
        float ty = y + dy + (h - fm.getHeight()) * 0.5f + fm.getAscent();
        g.setColor(primary ? Color.WHITE : palette.text);
        g.drawString(label, tx, ty);
        g.setComposite(oldComp);
    }

    static void drawCallout(
            Graphics2D g, Color tint, Color barColor, float x, float y, float w, float h) {
        RoundRectangle2D full = new RoundRectangle2D.Float(x, y, w, h, 20, 20);
        // Square left edge + rounded right edge, matching Hub/Agreement callouts
        Area area = new Area(full);
        area.add(new Area(new Rectangle.Float(x, y, 12, h)));
        g.setColor(tint);
        g.fill(area);
        g.setColor(barColor);
        g.fillRect(Math.round(x), Math.round(y), 3, Math.round(h));
    }

    private static final String NO_LINE_START = "。，、；：！？）》」』】.,;:!?)]";

    static List<String> wrapText(Graphics2D g, Font font, String text, float maxWidth) {
        FontMetrics fm = g.getFontMetrics(font);
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                out.add(line.toString());
                line.setLength(0);
                continue;
            }
            if (fm.stringWidth(line.toString() + ch) > maxWidth && line.length() > 0) {
                if (NO_LINE_START.indexOf(ch) >= 0) {
                    // Pull the preceding character down with closing punctuation (避头尾)
                    if (line.length() > 1) {
                        char prev = line.charAt(line.length() - 1);
                        line.setLength(line.length() - 1);
                        out.add(line.toString());
                        line.setLength(0);
                        line.append(prev).append(ch);
                        continue;
                    }
                }
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(ch);
        }
        if (line.length() > 0 || out.isEmpty()) out.add(line.toString());
        return out;
    }

    static String ellipsis(Graphics2D g, Font font, String text, float maxWidth) {
        FontMetrics fm = g.getFontMetrics(font);
        if (fm.stringWidth(text) <= maxWidth) return text;
        String suffix = "…";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(text.substring(0, mid) + suffix) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, Math.max(0, lo)) + suffix;
    }

    static void drawScrollbar(
            Graphics2D g,
            Palette palette,
            float trackX,
            float trackY,
            float trackH,
            float scroll,
            float maxScroll,
            float viewH) {
        if (maxScroll <= 0.5f) return;
        float contentH = viewH + maxScroll;
        float thumbH = Math.max(28f, trackH * (viewH / contentH));
        float thumbY = trackY + (trackH - thumbH) * (scroll / maxScroll);
        g.setColor(
                new Color(
                        palette.muted.getRed(),
                        palette.muted.getGreen(),
                        palette.muted.getBlue(),
                        128));
        g.fill(new RoundRectangle2D.Float(trackX, thumbY, 3f, thumbH, 3f, 3f));
    }

    private void releaseBackdrop(Minecraft mc) {
        if (backdropTex != null) {
            mc.getTextureManager().release(backdropId);
            backdropTex.close();
            backdropTex = null;
        }
        backdropBuf = null;
    }

    private void releasePanel(Minecraft mc) {
        if (panelTex != null) {
            mc.getTextureManager().release(panelId);
            panelTex.close();
            panelTex = null;
        }
        panelBuf = null;
    }

    void release(Minecraft mc) {
        releaseBackdrop(mc);
        releasePanel(mc);
    }

    @Override
    public void close() {
        if (backdropTex != null) {
            backdropTex.close();
            backdropTex = null;
        }
        if (panelTex != null) {
            panelTex.close();
            panelTex = null;
        }
        backdropBuf = null;
        panelBuf = null;
    }
}
