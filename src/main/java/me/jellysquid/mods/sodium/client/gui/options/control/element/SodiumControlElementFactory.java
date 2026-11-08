package me.jellysquid.mods.sodium.client.gui.options.control.element;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlElement;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.utils.Rect2i;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.function.Supplier;

public class SodiumControlElementFactory implements ControlElementFactory {
    @Override
    public <T extends Enum<T>> ControlElement<T> cyclingControlElement(Option<T> option, Dim2i dim, Supplier<T[]> allowedValues, String[] names) {
        return new CyclingControlElement<>(option, dim, allowedValues, names);
    }

    @Override
    public ControlElement<Integer> sliderControlElement(Option<Integer> option, Dim2i dim, int min, int max, int interval, ControlValueFormatter formatter) {
        return new SliderControlElement(option, dim, min, max, interval, formatter);
    }

    @Override
    public ControlElement<Boolean> tickBoxElement(Option<Boolean> option, Dim2i dim) {
        return new TickBoxControlElement(option, dim);
    }

    private static class CyclingControlElement<T extends Enum<T>> extends SodiumControlElement<T> {
        private final Supplier<T[]> allowedValues;
        private final String[] names;

        public CyclingControlElement(Option<T> option, Dim2i dim, Supplier<T[]> allowedValues, String[] names) {
            super(option, dim);

            this.allowedValues = allowedValues;
            this.names = names;
        }

        private int currentIndex() {
            return Math.max(0, this.indexOfValue());
        }

        private int indexOfValue() {
            final T[] allowed = this.allowedValues.get();
            for (int i = 0; i < allowed.length; i++) {
                if (allowed[i] == this.option.getValue()) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public void render(int mouseX, int mouseY, float delta) {
            super.render(mouseX, mouseY, delta);

            // A stored value no choice offers, such as one above what this machine allows, still shows as what it is. A disabled
            // option whose stored value is not among its choices is held at its first choice, so that is what it shows, greyed
            // but not struck through since it is in effect: Present Mode keeps a tear-free mode while VSync is off, for example.
            final T[] allowed = this.allowedValues.get();
            final int index = this.indexOfValue();
            final String name;
            if (index < 0 && !this.option.isAvailable()) {
                name = EnumChatFormatting.GRAY + this.names[allowed[0].ordinal()];
            } else {
                name = this.formatValue(this.names[(index >= 0 ? allowed[index] : this.option.getValue()).ordinal()]);
            }

            int strWidth = this.getTextWidth(name);
            this.drawString(name, this.dim.getLimitX() - strWidth - 6, this.dim.getCenterY() - 4, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (this.option.isAvailable() && this.dim.containsCursor(mouseX, mouseY) && (button == 0 || button == 1)) {
                final T[] allowed = this.allowedValues.get();
                this.option.setValue(allowed[Math.floorMod(this.currentIndex() + (button == 0 ? 1 : -1), allowed.length)]);
                this.playClickSound();

                return true;
            }

            return false;
        }
    }

    private static class SliderControlElement extends SodiumControlElement<Integer> {
        private static final int THUMB_WIDTH = 2, TRACK_HEIGHT = 1;
        private static final int DEFAULT_WIDTH = 90, ROW_TEXT_SPACE = 170;

        private final Rect2i sliderBounds;
        private final ControlValueFormatter formatter;

        private final int min;
        private final int range;
        private final int interval;
        // A slider with far more values than GUI pixels is widened to one physical pixel per value and reads the unscaled mouse position.
        private final boolean physicalPixels;
        private final int guiScale;

        private double thumbPosition;
        private boolean dragging;

        public SliderControlElement(Option<Integer> option, Dim2i dim, int min, int max, int interval, ControlValueFormatter formatter) {
            super(option, dim);

            this.min = min;
            this.range = max - min;
            this.interval = interval;
            this.thumbPosition = this.getThumbPositionForValue(option.getValue());
            this.formatter = formatter;

            final Minecraft mc = Minecraft.getMinecraft();
            final int values = this.range / this.interval;
            this.physicalPixels = values > DEFAULT_WIDTH * 2;
            this.guiScale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();

            final int width = this.physicalPixels ? Math.max(DEFAULT_WIDTH, Math.min((values + this.guiScale - 1) / this.guiScale, dim.getWidth() - ROW_TEXT_SPACE)) : DEFAULT_WIDTH;
            this.sliderBounds = new Rect2i(dim.getLimitX() - width - 6, dim.getCenterY() - 5, width, 10);
        }

        @Override
        public void render(int mouseX, int mouseY, float delta) {
            super.render(mouseX, mouseY, delta);

            // The screen does not forward mouse releases to a control the cursor has left.
            if (this.dragging && !Mouse.isButtonDown(0)) {
                this.dragging = false;
            }

            if (this.option.isAvailable() && (this.hovered || this.dragging)) {
                this.renderSlider();
            } else {
                this.renderStandaloneValue();
            }
        }

        private void renderStandaloneValue() {
            int sliderX = this.sliderBounds.getX();
            int sliderY = this.sliderBounds.getY();
            int sliderWidth = this.sliderBounds.getWidth();
            int sliderHeight = this.sliderBounds.getHeight();

            String label = this.formatValue(this.formatter.format(this.option.getValue()));
            int labelWidth = this.font.getStringWidth(label);

            this.drawString(label, sliderX + sliderWidth - labelWidth, sliderY + (sliderHeight / 2) - 4, 0xFFFFFFFF);
        }

        private void renderSlider() {
            int sliderX = this.sliderBounds.getX();
            int sliderY = this.sliderBounds.getY();
            int sliderWidth = this.sliderBounds.getWidth();
            int sliderHeight = this.sliderBounds.getHeight();

            this.thumbPosition = this.getThumbPositionForValue(option.getValue());

            double thumbOffset = MathHelper.clamp_double((double) (this.getIntValue() - this.min) / this.range * sliderWidth, 0, sliderWidth);

            double thumbX = sliderX + thumbOffset - THUMB_WIDTH;
            double trackY = sliderY + ((double) sliderHeight / 2) - ((double) TRACK_HEIGHT / 2);

            this.drawRect(thumbX, sliderY, thumbX + (THUMB_WIDTH * 2), sliderY + sliderHeight, 0xFFFFFFFF);
            this.drawRect(sliderX, trackY, sliderX + sliderWidth, trackY + TRACK_HEIGHT, 0xFFFFFFFF);

            String label = this.formatter.format(this.getIntValue());

            int labelWidth = this.font.getStringWidth(label);

            this.drawString(label, sliderX - labelWidth - 6, sliderY + (sliderHeight / 2) - 4, 0xFFFFFFFF);
        }

        public int getIntValue() {
            return this.min + (this.interval * (int) Math.round(this.getSnappedThumbPosition() / this.interval));
        }

        public double getSnappedThumbPosition() {
            return this.thumbPosition / (1.0D / this.range);
        }

        public double getThumbPositionForValue(int value) {
            return (value - this.min) * (1.0D / this.range);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (this.option.isAvailable() && button == 0 && this.sliderBounds.contains((int) mouseX, (int) mouseY)) {
                this.setValueFromMouse(mouseX);
                this.dragging = this.physicalPixels;

                return true;
            }

            return false;
        }

        private void setValueFromMouse(double d) {
            if (this.physicalPixels) {
                this.setValue((Mouse.getEventX() - this.sliderBounds.getX() * this.guiScale) / (double) (this.sliderBounds.getWidth() * this.guiScale));
                return;
            }

            this.setValue((d - (double) this.sliderBounds.getX()) / (double) this.sliderBounds.getWidth());
        }

        private void setValue(double d) {
            this.thumbPosition = MathHelper.clamp_double(d, 0.0D, 1.0D);

            int value = this.getIntValue();

            if (this.option.getValue() != value) {
                this.option.setValue(value);
            }
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button) {
            if (this.option.isAvailable() && (this.dragging || !this.physicalPixels && this.sliderBounds.contains((int) mouseX, (int) mouseY))) {
                this.setValueFromMouse(mouseX);

                return true;
            }

            return false;
        }

        @Override
        public boolean keyTyped(char typedChar, int keyCode) {
            if (!this.option.isAvailable()) {
                return false;
            }

            final int step;
            if (keyCode == Keyboard.KEY_LEFT) {
                step = -this.interval;
            } else if (keyCode == Keyboard.KEY_RIGHT) {
                step = this.interval;
            } else {
                return false;
            }

            final int value = MathHelper.clamp_int(this.option.getValue() + step, this.min, this.min + this.range);

            if (this.option.getValue() != value) {
                this.option.setValue(value);
            }

            return true;
        }
    }

    private static class TickBoxControlElement extends SodiumControlElement<Boolean> {
        private final Rect2i button;

        public TickBoxControlElement(Option<Boolean> option, Dim2i dim) {
            super(option, dim);

            this.button = new Rect2i(dim.getLimitX() - 16, dim.getCenterY() - 5, 10, 10);
        }

        @Override
        public void render(int mouseX, int mouseY, float delta) {
            super.render(mouseX, mouseY, delta);

            final int x = this.button.getX();
            final int y = this.button.getY();
            final int w = x + this.button.getWidth();
            final int h = y + this.button.getHeight();

            final boolean enabled = this.option.isAvailable();
            final boolean ticked = enabled && this.option.getValue();

            final int color;

            if (enabled) {
                color = ticked ? 0xFF94E4D3 : 0xFFFFFFFF;
            } else {
                color = 0xFFAAAAAA;
            }

            if (ticked) {
                this.drawRect(x + 2, y + 2, w - 2, h - 2, color);
            }

            this.drawRectOutline(x, y, w, h, color);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (this.option.isAvailable() && button == 0 && this.dim.containsCursor(mouseX, mouseY)) {
                this.option.setValue(!this.option.getValue());
                this.playClickSound();

                return true;
            }

            return false;
        }

        protected void drawRectOutline(int x, int y, int w, int h, int color) {
            final float a = (float) (color >> 24 & 255) / 255.0F;
            final float r = (float) (color >> 16 & 255) / 255.0F;
            final float g = (float) (color >> 8 & 255) / 255.0F;
            final float b = (float) (color & 255) / 255.0F;

            this.drawQuads(vertices -> {
                addQuad(vertices, x, y, w, y + 1, a, r, g, b);
                addQuad(vertices, x, h - 1, w, h, a, r, g, b);
                addQuad(vertices, x, y, x + 1, h, a, r, g, b);
                addQuad(vertices, w - 1, y, w, h, a, r, g, b);
            });
        }
    }

}
