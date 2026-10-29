package com.gtnewhorizons.angelica.config;

import me.jellysquid.mods.sodium.client.gui.options.named.NamedState;

public enum AngelicaDriver implements NamedState {
    AUTO  ("",       "options.angelica.renderer.driver.auto"),
    VULKAN("vulkan", "options.angelica.renderer.driver.vulkan"),
    METAL ("metal",  "options.angelica.renderer.driver.metal"),
    DX12  ("dx12",   "options.angelica.renderer.driver.dx12");

    private final String driverName;
    private final String key;

    AngelicaDriver(String driverName, String key) {
        this.driverName = driverName;
        this.key = key;
    }

    @Override
    public String getKey() {
        return key;
    }

    public String driverName() { return driverName; }

    public static AngelicaDriver fromDriverName(String driverName) {
        for (final AngelicaDriver driver : values()) {
            if (driver.driverName.equalsIgnoreCase(driverName)) return driver;
        }
        return null;
    }
}
