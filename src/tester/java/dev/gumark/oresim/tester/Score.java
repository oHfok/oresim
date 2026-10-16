package dev.gumark.oresim.tester;

import dev.gumark.oresim.OreType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Locale;
import java.util.Map;

/** Predicted vs real ore positions, per ore type. */
final class Score {
    private final long[] correct = new long[OreType.values().length];
    private final long[] predicted = new long[OreType.values().length];
    private final long[] real = new long[OreType.values().length];

    /** Positions in {@code excluded} (dug out) are left out on both sides. */
    static Score compare(Map<OreType, LongOpenHashSet> predictedOres, Map<OreType, LongOpenHashSet> realOres, LongSet excluded) {
        Score score = new Score();
        for (OreType type : OreType.values()) {
            LongSet predictedOfType = predictedOres.getOrDefault(type, new LongOpenHashSet());
            LongSet realOfType = realOres.getOrDefault(type, new LongOpenHashSet());
            int i = type.ordinal();
            for (long pos : predictedOfType) {
                if (excluded.contains(pos)) continue;
                score.predicted[i]++;
                if (realOfType.contains(pos)) score.correct[i]++;
            }
            for (long pos : realOfType) {
                if (!excluded.contains(pos)) score.real[i]++;
            }
        }
        return score;
    }

    long correct(OreType type) {
        return correct[type.ordinal()];
    }

    long predicted(OreType type) {
        return predicted[type.ordinal()];
    }

    long real(OreType type) {
        return real[type.ordinal()];
    }

    String precision(OreType type) {
        return percent(correct(type), predicted(type));
    }

    String recall(OreType type) {
        return percent(correct(type), real(type));
    }

    String totalPrecision() {
        return percent(sum(correct), sum(predicted));
    }

    String totalRecall() {
        return percent(sum(correct), sum(real));
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) total += value;
        return total;
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "–" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }
}
