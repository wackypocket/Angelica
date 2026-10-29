package me.jellysquid.mods.sodium.client.gui.options;

import me.jellysquid.mods.sodium.client.gui.options.control.Control;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

import java.util.Collection;

public interface Option<T> {
    String getName();

    String getTooltip();

    OptionImpact getImpact();

    Control<T> getControl();

    T getValue();

    void setValue(T value);

    void reset();

    default void syncIfUnchanged() {}

    OptionStorage<?> getStorage();

    boolean isAvailable();

    /** Whether the option is shown at all; a hidden option still holds and applies its value. */
    default boolean isVisible() { return true; }

    boolean hasChanged();

    void applyChanges();

    Collection<OptionFlag> getFlags();

}
