package pl.skidam.automodpack_core.oss.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Pure Java2D liquid-glass design system shared between in-game OpenGL screens (OssGlassSurface)
 * and the pre-launch desktop window (PrelaunchGlassWindow). Zero Minecraft dependencies.
 */
public final class OssGlassCore {
    public static final int LOGICAL_W = 760;
    public static final int LOGICAL_H = 560;
    public static final int SHADOW_PAD = 36;
    public static final float MAX_DPI_SCALE = 1.75f;

    private OssGlassCore() {}

    public static final class Palette {
        public final boolean dark;
        public final Color canvasBase;
        public final Color ambientBlue;
        public final Color ambientPurple;
        public final Color ambientRose;
        public final Color shellBg;
        public final Color shellBorder;
        public final Color shellTopRim;
        public final Color footerTint;
        public final Color text;
        public final Color secondary;
        public final Color muted;
        public final Color rule;
        public final Color accent;
        public final Color accentHover;
        public final Color accentSoft;
        public final Color progressStart;
        public final Color ok;
        public final Color okTint;
        public final Color warn;
        public final Color warnTint;
        public final Color danger;
        public final Color dangerTint;
        public final Color btnBg;
        public final Color btnHover;
        public final Color btnBorder;
        public final Color shadowColor;

        public Palette(boolean dark) {
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

        public static Palette detect(Path gameDir) {
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

    public record Hit(String id, float x, float y, float w, float h, boolean enabled) {
        public boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    public interface Painter {
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

    public static Font bodyFont(int style, float size) {
        return new Font(BODY_FONT, style, Math.round(size)).deriveFont(size);
    }

    public static Font monoFont(int style, float size) {
        return new Font(MONO_FONT, style, Math.round(size)).deriveFont(size);
    }

    public static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    public static void paintBackdrop(Graphics2D g, Palette palette, int w, int h) {
        applyQualityHints(g);
        g.setColor(palette.canvasBase);
        g.fillRect(0, 0, w, h);
        paintRadialGlow(g, w * 0.14f, h * 0.12f, w * 0.52f, palette.ambientBlue);
        paintRadialGlow(g, w * 0.90f, h * 0.88f, w * 0.54f, palette.ambientPurple);
        paintRadialGlow(g, w * 0.72f, h * 0.08f, w * 0.44f, palette.ambientRose);
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

    public static void paintShellShadowAndFrame(Graphics2D g, Palette palette, int w, int h) {
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

        // Ambient liquid-glass shell surface
        Shape oldClip = g.getClip();
        RoundRectangle2D shellShape = new RoundRectangle2D.Float(0, 0, w, h, 48, 48);
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
        g.fill(shellShape);
        g.setClip(oldClip);
    }

    public static void paintShellBorder(Graphics2D g, Palette palette, int w, int h) {
        g.setStroke(new BasicStroke(1f));
        g.setColor(palette.shellBorder);
        g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 47, 47));
        g.setColor(palette.shellTopRim);
        g.drawLine(28, 1, w - 28, 1);
    }

    public static void drawButton(
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

    public static void drawCallout(
            Graphics2D g, Color tint, Color barColor, float x, float y, float w, float h) {
        RoundRectangle2D full = new RoundRectangle2D.Float(x, y, w, h, 20, 20);
        Area area = new Area(full);
        area.add(new Area(new Rectangle.Float(x, y, 12, h)));
        g.setColor(tint);
        g.fill(area);
        g.setColor(barColor);
        g.fillRect(Math.round(x), Math.round(y), 3, Math.round(h));
    }

    private static final String NO_LINE_START = "。，、；：！？）》」』】.,;:!?)]";

    public static List<String> wrapText(Graphics2D g, Font font, String text, float maxWidth) {
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

    public static String ellipsis(Graphics2D g, Font font, String text, float maxWidth) {
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

    public static void drawScrollbar(
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

    public static void paintIndeterminateBar(
            Graphics2D g, Palette p, float x, float y, float w, float h, long nowMs) {
        RoundRectangle2D track = new RoundRectangle2D.Float(x, y, w, h, h, h);
        g.setColor(p.rule);
        g.fill(track);
        Shape old = g.getClip();
        g.clip(track);
        float segW = w * 0.34f;
        float t = ((nowMs % 1600L) / 1600f);
        float pos = x - segW + (w + segW) * t;
        g.setPaint(new GradientPaint(pos, y, p.progressStart, pos + segW, y, p.accent));
        g.fill(new RoundRectangle2D.Float(pos, y, segW, h, h, h));
        g.setClip(old);
    }

    public static void paintDeterminateBar(
            Graphics2D g,
            Palette p,
            float x,
            float y,
            float w,
            float h,
            float fraction,
            long nowMs) {
        RoundRectangle2D track = new RoundRectangle2D.Float(x, y, w, h, h, h);
        g.setColor(p.rule);
        g.fill(track);
        float fillW = Math.max(0f, Math.min(w, w * fraction));
        if (fillW > 0.5f) {
            Shape old = g.getClip();
            RoundRectangle2D fillShape = new RoundRectangle2D.Float(x, y, fillW, h, h, h);
            g.setPaint(new GradientPaint(x, y, p.progressStart, x + w, y, p.accent));
            g.fill(fillShape);
            if (fraction < 0.999f) {
                g.clip(fillShape);
                float shimmerW = 72f;
                float t = ((nowMs % 1500L) / 1500f);
                float sx = x - shimmerW + (fillW + shimmerW) * t;
                g.setPaint(
                        new GradientPaint(
                                sx,
                                y,
                                new Color(255, 255, 255, 0),
                                sx + shimmerW * 0.5f,
                                y,
                                new Color(255, 255, 255, 78),
                                true));
                g.fillRect(Math.round(sx), Math.round(y), Math.round(shimmerW), Math.round(h));
            }
            g.setClip(old);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
