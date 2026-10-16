package dev.gumark.oresim;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@code /oresim} menu (also on the O key): which ores are shown, and how
 * they are drawn. Changes apply immediately and are saved.
 */
public class OreSimScreen extends Screen {
    private static final Component TITLE = Component.literal("OreSim by krwawiec");
    private static final Component ORES = Component.literal("Ores").withStyle(ChatFormatting.GRAY);
    private static final Component DISPLAY = Component.literal("Display").withStyle(ChatFormatting.GRAY);
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int ROW = BUTTON_HEIGHT + GAP;
    private static final int LABEL_HEIGHT = 14;

    private final List<Button> oreButtons = new ArrayList<>();
    private int oresLabelY;
    private int displayLabelY;

    public OreSimScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        oreButtons.clear();
        OreType[] types = OreType.values();
        int oreRows = (types.length + 1) / 2;
        int totalHeight = LABEL_HEIGHT + oreRows * ROW + ROW + LABEL_HEIGHT + 2 * ROW + 8 + BUTTON_HEIGHT;
        int left = (this.width - (2 * BUTTON_WIDTH + GAP)) / 2;
        int right = left + BUTTON_WIDTH + GAP;
        int y = Math.max(30, (this.height - totalHeight) / 2 + 10);

        oresLabelY = y;
        y += LABEL_HEIGHT;
        for (int i = 0; i < types.length; i++) {
            OreType type = types[i];
            int x = i < oreRows ? left : right;
            int rowY = y + (i % oreRows) * ROW;
            Button button = Button.builder(oreLabel(type), b -> {
                OverlayToggles.toggle(type);
                b.setMessage(oreLabel(type));
            }).bounds(x, rowY, BUTTON_WIDTH, BUTTON_HEIGHT).build();
            oreButtons.add(button);
            this.addRenderableWidget(button);
        }
        y += oreRows * ROW;
        this.addRenderableWidget(Button.builder(Component.literal("Show all"), b -> setAll(true))
            .bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.literal("Hide all"), b -> setAll(false))
            .bounds(right, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += ROW;

        displayLabelY = y;
        y += LABEL_HEIGHT;
        cycleButton(left, y, () -> "Style: " + OreSimSettings.style().label, OreSimSettings::cycleStyle,
            "Veins: each vein as one shape. Boxes: a filled box per block. Outlines: the edges of every block.");
        cycleButton(right, y, () -> "Fill: " + OreSimSettings.fill().label, OreSimSettings::cycleFill,
            "How strongly the boxes are filled in (see-through).");
        y += ROW;
        cycleButton(left, y, () -> "Lines: " + OreSimSettings.lineWidth().label, OreSimSettings::cycleLineWidth,
            "Thickness of the outlines.");
        cycleButton(right, y, () -> "Range: " + OreSimSettings.range().label, OreSimSettings::cycleRange,
            "Only draw ores this close to you. Less clutter; the miner still sees everything.");
        y += ROW + 8;

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds((this.width - BUTTON_WIDTH) / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void cycleButton(int x, int y, Supplier<String> label, Runnable cycle, String tooltip) {
        this.addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {
                cycle.run();
                b.setMessage(Component.literal(label.get()));
            })
            .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
            .tooltip(Tooltip.create(Component.literal(tooltip)))
            .build());
    }

    private void setAll(boolean visible) {
        OverlayToggles.setAll(visible);
        OreType[] types = OreType.values();
        for (int i = 0; i < oreButtons.size(); i++) {
            oreButtons.get(i).setMessage(oreLabel(types[i]));
        }
    }

    private static Component oreLabel(OreType type) {
        boolean on = OverlayToggles.isEnabled(type);
        return Component.literal(type.label + ": ")
            .append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, TITLE, this.width / 2, 15, 0xFFFFFFFF);
        graphics.centeredText(this.font, ORES, this.width / 2, oresLabelY + 3, 0xFFFFFFFF);
        graphics.centeredText(this.font, DISPLAY, this.width / 2, displayLabelY + 3, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
