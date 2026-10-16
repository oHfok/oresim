package dev.gumark.oresim;

import java.util.EnumSet;

/** Which overlay categories are currently visible. All enabled by default. */
public final class OverlayToggles {
    private static final EnumSet<OreType> DISABLED = EnumSet.noneOf(OreType.class);

    private OverlayToggles() {
    }

    /** Per-frame render state: one boolean per {@link OreType#ordinal()}, indexed directly in the render loop. */
    public static boolean[] snapshot() {
        OreType[] types = OreType.values();
        boolean[] enabled = new boolean[types.length];
        for (OreType type : types) {
            enabled[type.ordinal()] = !DISABLED.contains(type);
        }
        return enabled;
    }

    public static EnumSet<OreType> enabledTypes() {
        return EnumSet.complementOf(DISABLED);
    }

    public static boolean isEnabled(OreType type) {
        return !DISABLED.contains(type);
    }

    public static void toggle(OreType type) {
        if (!DISABLED.remove(type)) {
            DISABLED.add(type);
        }
    }
}
