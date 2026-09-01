package com.orangevillager61.emeraldcapitalism.util;

import net.minecraft.client.Minecraft;

/** Bridges the client-thread scheduling method renamed in 1.21.4. */
public final class MinecraftExecutionCompat {
    private MinecraftExecutionCompat() {
    }

    public static void execute(Minecraft minecraft, Runnable action) {
//? if >=1.21.4 {
        minecraft.execute(action);
//?} else {
/*        minecraft.tell(action);
 *///?}
    }
}
