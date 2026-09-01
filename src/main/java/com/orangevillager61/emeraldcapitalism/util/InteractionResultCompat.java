package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.world.InteractionResult;

/** Version bridge for the removed InteractionResult.sidedSuccess helper. */
public final class InteractionResultCompat {
    private InteractionResultCompat() {
    }

    public static InteractionResult sidedSuccess(boolean clientSide) {
//? if >=1.21.4 {
        return clientSide ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
//?} else {
/*        return InteractionResult.sidedSuccess(clientSide);
 *///?}
    }
}
