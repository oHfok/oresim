package dev.gumark.oresim;

import java.util.EnumSet;

/** Which ore types the overlay shows. All by default; saved with {@link OreSimSettings}. */
public final class OverlayToggles {
    private OverlayToggles() {
    }

    /** Per-frame render state: one boolean per {@link OreType#ordinal()}, indexed directly in the render loop. */
    public static boolean[] snapshot() {
        OreType[] types = OreType.values();
        boolean[] enabled = new boolean[types.length];
        for (OreType type : types) {
            enabled[type.ordinal()] = !OreSimSettings.HIDDEN.contains(type);
        }
        return enabled;
    }

    public static EnumSet<OreType> enabledTypes() {
        return EnumSet.complementOf(OreSimSettings.HIDDEN);
    }

    public static boolean isEnabled(OreType type) {
        return !OreSimSettings.HIDDEN.contains(type);
    }

    public static void toggle(OreType type) {
        if (!OreSimSettings.HIDDEN.remove(type)) {
            OreSimSettings.HIDDEN.add(type);
        }
        OreSimSettings.save();
    }

    public static void setAll(boolean visible) {
        if (visible) {
            OreSimSettings.HIDDEN.clear();
        } else {
            OreSimSettings.HIDDEN.addAll(EnumSet.allOf(OreType.class));
        }
        OreSimSettings.save();
    }
}
