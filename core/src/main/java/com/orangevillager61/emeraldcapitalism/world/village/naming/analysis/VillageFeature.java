package com.orangevillager61.emeraldcapitalism.world.village.naming.analysis;

/** Derived naming features used to build civilizational axes. */
public enum VillageFeature {
    SITE_WATER_ACCESS("site_water_access"),
    SITE_EXPOSURE("site_exposure"),
    SITE_SHELTER("site_shelter"),
    AGRICULTURE("agriculture"),
    CRAFT("craft"),
    KNOWLEDGE("knowledge"),
    SETTLEMENT_SCALE("settlement_scale"),
    SETTLEMENT_CENTER("settlement_center"),
    LAYOUT_COMPACTNESS("layout_compactness"),
    TRADE_CONTACT("trade_contact"),
    DANGER_PRESSURE("danger_pressure"),
    MEMORY_PRESSURE("memory_pressure");

    private final String id;

    VillageFeature(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
