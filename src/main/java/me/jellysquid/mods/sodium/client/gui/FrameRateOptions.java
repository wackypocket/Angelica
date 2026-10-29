package me.jellysquid.mods.sodium.client.gui;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.MinecraftOptionsStorage;
import me.jellysquid.mods.sodium.client.gui.options.storage.SodiumOptionsStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public record FrameRateOptions(Option<Boolean> vsync, Option<VSyncMode> presentMode, Option<Integer> maxFramerate) {

    public static final int MIN_FRAMERATE = 5;
    public static final int MAX_FRAMERATE = 260;

    private static final VSyncMode[] TEAR_FREE_ORDER = { VSyncMode.ON, VSyncMode.FIFO_LATEST_READY, VSyncMode.MAILBOX };

    public static FrameRateOptions create(MinecraftOptionsStorage vanillaOpts, SodiumOptionsStorage sodiumOpts) {
        final OptionImpl<GameSettings, Boolean> vsync = OptionImpl.createBuilder(boolean.class, vanillaOpts)
            .setName(I18n.format("options.vsync"))
            .setTooltip(I18n.format("sodium.options.v_sync.tooltip"))
            .setControl(TickBoxControl::new)
            .setBinding((opts, value) -> {
                opts.enableVsync = value;
                opts.saveOptions();
                GLStateManager.setVSyncEnabled(value);
            }, opts -> opts.enableVsync)
            .setImpact(OptionImpact.VARIES)
            .build();

        final String[] names = modeNames(VSyncMode.values());
        final OptionImpl<SodiumGameOptions, VSyncMode> presentMode = OptionImpl.createBuilder(VSyncMode.class, sodiumOpts)
            .setName(I18n.format("sodium.options.present_mode"))
            .setTooltip(presentModeTooltip())
            .setControl(option -> new CyclingControl<>(option, VSyncMode.class,
                () -> vsync.getValue() ? selectableModes() : new VSyncMode[]{ immediateMode() }, names))
            .setBinding((opts, value) -> {
                opts.advanced.presentMode = value;
                GLStateManager.setVSyncPreference(value);
                GLStateManager.setVSyncEnabled(Minecraft.getMinecraft().gameSettings.enableVsync);
            }, FrameRateOptions::storedMode)
            .setImpact(OptionImpact.VARIES)
            .build();
        presentMode.iris$dynamicallyEnable(vsync::getValue);

        final OptionImpl<GameSettings, Integer> maxFramerate = OptionImpl.createBuilder(int.class, vanillaOpts)
            .setName(I18n.format("options.framerateLimit"))
            .setTooltip(I18n.format("sodium.options.fps_limit.tooltip"))
            .setControl(option -> new SliderControl(option, MIN_FRAMERATE, MAX_FRAMERATE, 1, ControlValueFormatter.fpsLimit()))
            .setBinding((opts, value) -> opts.limitFramerate = value, opts -> opts.limitFramerate)
            .build();

        return new FrameRateOptions(vsync, presentMode, maxFramerate);
    }

    static VSyncMode[] selectableModes() {
        final VSyncMode[] supported = Arrays.stream(TEAR_FREE_ORDER)
            .filter(mode -> mode == VSyncMode.ON || GLStateManager.supportsVSyncMode(mode))
            .toArray(VSyncMode[]::new);
        return supported;
    }

    static VSyncMode immediateMode() {
        if (GLStateManager.supportsVSyncMode(VSyncMode.OFF)) return VSyncMode.OFF;
        return GLStateManager.supportsVSyncMode(VSyncMode.MAILBOX) ? VSyncMode.MAILBOX : VSyncMode.ON;
    }

    static String modeKey(VSyncMode mode) {
        return "sodium.options.vsync_mode." + mode.name().toLowerCase(Locale.ROOT);
    }

    private static String[] modeNames(VSyncMode[] modes) {
        return Arrays.stream(modes).map(mode -> I18n.format(modeKey(mode))).toArray(String[]::new);
    }

    private static String presentModeTooltip() {
        final String modes = Arrays.stream(VSyncMode.values())
            .filter(mode -> mode == VSyncMode.OFF || mode == VSyncMode.ON || GLStateManager.supportsVSyncMode(mode))
            .map(mode -> I18n.format("sodium.options.present_mode.tooltip." + mode.name().toLowerCase(Locale.ROOT)))
            .collect(Collectors.joining("\n"));
        return I18n.format("sodium.options.present_mode.tooltip") + "\n" + modes;
    }

    public static VSyncMode storedMode(SodiumGameOptions opts) {
        final VSyncMode stored = opts.advanced.presentMode;
        if (stored == null || !stored.tearFree() || !GLStateManager.supportsVSyncMode(stored)) return VSyncMode.ON;
        return stored;
    }
}
