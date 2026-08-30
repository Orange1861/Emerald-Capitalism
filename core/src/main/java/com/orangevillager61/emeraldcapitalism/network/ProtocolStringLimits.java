package com.orangevillager61.emeraldcapitalism.network;

/**
 * Shared character limits for strings crossing the network or entering a menu.
 *
 * <p>{@code FriendlyByteBuf.writeUtf(value, maxLength)} validates the input but
 * does not truncate it, so callers must clamp untrusted or persisted values
 * before writing.</p>
 */
public final class ProtocolStringLimits {

    public static final int MAX_VILLAGE_NAME_LENGTH = 64;
    public static final int MAX_PROFESSION_LABEL_LENGTH = 64;
    public static final int MAX_ACCOUNT_NAME_LENGTH = 64;
    public static final int MAX_BANK_NAME_LENGTH = 64;
    public static final int MAX_PARENT_NAME_LENGTH = 64;
    public static final int MAX_WELCOME_MESSAGE_LENGTH = 512;

    private ProtocolStringLimits() {
    }

    /** Returns a non-null string no longer than {@code maxLength} characters. */
    public static String clamp(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
