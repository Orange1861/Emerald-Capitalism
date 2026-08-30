package com.orangevillager61.emeraldcapitalism.world.village.books;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Defines whether an authored book is static or resolves a world-data token. */
public enum LibraryBookType {
    STATIC("static"),
    STEVE_GRAVE_LOCATION("steve_grave_location");

    public static final String STEVE_GRAVE_COORDINATES_TOKEN = "{{steve_grave_coordinates}}";

    private final String id;

    LibraryBookType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<LibraryBookType> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        for (LibraryBookType type : values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Replaces supported game-data tokens before a generated book is stored. */
    public List<String> resolvePages(List<String> pageTemplates, Optional<BlockPos> steveGraveTarget) {
        Objects.requireNonNull(pageTemplates, "pageTemplates");
        Objects.requireNonNull(steveGraveTarget, "steveGraveTarget");
        if (this == STATIC) {
            return pageTemplates;
        }

        String coordinates = steveGraveTarget
                .map(target -> "[" + target.getX() + ", " + target.getY() + ", " + target.getZ() + "]")
                .orElse("[coordinates unavailable]");
        return pageTemplates.stream()
                .map(page -> page.replace(STEVE_GRAVE_COORDINATES_TOKEN, coordinates))
                .toList();
    }
}
