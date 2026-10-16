package dev.gumark.oresim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Overlay settings from the {@code /oresim} menu, saved in
 * {@code config/oresim-settings.json} whenever they change.
 */
public final class OreSimSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("oresim-settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** How ores are drawn. */
    public enum Style {
        /** Each vein as one shape: its outer edges and faces only. */
        VEINS("Veins"),
        /** A filled box per block, slightly shrunk so neighbours stay apart. */
        BOXES("Boxes"),
        /** The edges of every block. */
        OUTLINES("Outlines");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    /** Opacity of the see-through fill. */
    public enum Fill {
        OFF("Off", 0),
        LOW("Low", 0x30),
        MEDIUM("Medium", 0x58),
        HIGH("High", 0x88);

        public final String label;
        public final int alpha;

        Fill(String label, int alpha) {
            this.label = label;
            this.alpha = alpha;
        }
    }

    public enum LineWidth {
        THIN("Thin", 1.5F),
        NORMAL("Normal", 2.5F),
        THICK("Thick", 4F);

        public final String label;
        public final float width;

        LineWidth(String label, float width) {
            this.label = label;
            this.width = width;
        }
    }

    /** How far from the player ores are drawn; the simulation covers about 80 blocks. */
    public enum Range {
        NEAR("16 blocks", 16),
        MEDIUM("32 blocks", 32),
        FAR("48 blocks", 48),
        ALL("All", Integer.MAX_VALUE);

        public final String label;
        public final int blocks;

        Range(String label, int blocks) {
            this.label = label;
            this.blocks = blocks;
        }
    }

    private static Style style = Style.VEINS;
    private static Fill fill = Fill.LOW;
    private static LineWidth lineWidth = LineWidth.NORMAL;
    private static Range range = Range.ALL;
    /** Ore types hidden from the overlay (see {@link OverlayToggles}). */
    static final EnumSet<OreType> HIDDEN = EnumSet.noneOf(OreType.class);

    static {
        load();
    }

    private OreSimSettings() {
    }

    public static Style style() {
        return style;
    }

    public static Fill fill() {
        return fill;
    }

    public static LineWidth lineWidth() {
        return lineWidth;
    }

    public static Range range() {
        return range;
    }

    public static void cycleStyle() {
        style = next(style, Style.values());
        save();
    }

    public static void cycleFill() {
        fill = next(fill, Fill.values());
        save();
    }

    public static void cycleLineWidth() {
        lineWidth = next(lineWidth, LineWidth.values());
        save();
    }

    public static void cycleRange() {
        range = next(range, Range.values());
        save();
    }

    private static <T extends Enum<T>> T next(T current, T[] values) {
        return values[(current.ordinal() + 1) % values.length];
    }

    private static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            SettingsFile file = GSON.fromJson(Files.readString(FILE), SettingsFile.class);
            if (file == null) return;
            style = parse(Style.class, file.style, style);
            fill = parse(Fill.class, file.fill, fill);
            lineWidth = parse(LineWidth.class, file.lineWidth, lineWidth);
            range = parse(Range.class, file.range, range);
            if (file.hiddenOres != null) {
                for (String name : file.hiddenOres) {
                    OreType type = parse(OreType.class, name, null);
                    if (type != null) HIDDEN.add(type);
                }
            }
        } catch (Exception e) {
            // Keep the defaults; the next change rewrites the file.
        }
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String name, T fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static void save() {
        SettingsFile file = new SettingsFile();
        file.style = style.name();
        file.fill = fill.name();
        file.lineWidth = lineWidth.name();
        file.range = range.name();
        file.hiddenOres = new ArrayList<>();
        for (OreType type : HIDDEN) file.hiddenOres.add(type.name());
        try {
            Path parent = FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(file), StandardCharsets.UTF_8);
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Non-fatal: the settings still apply for this session.
        }
    }

    /** JSON shape of the file. */
    private static final class SettingsFile {
        String style;
        String fill;
        String lineWidth;
        String range;
        List<String> hiddenOres;
    }
}
