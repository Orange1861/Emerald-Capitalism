package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.network.ProtocolStringLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Represents a single job-site block found within a village's bounding box.
 *
 * @param position  the block position
 * @param jobType   the profession name this workstation serves (e.g. "Librarian")
 * @param claimed   {@code true} if a villager's {@code jobSitePos} matches this position
 */
public record JobSiteEntry(BlockPos position, String jobType, boolean claimed) {

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeBlockPos(position);
        buf.writeUtf(ProtocolStringLimits.clamp(jobType, ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH),
                ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);
        buf.writeBoolean(claimed);
    }

    public static JobSiteEntry fromNetwork(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String type = buf.readUtf(ProtocolStringLimits.MAX_PROFESSION_LABEL_LENGTH);
        boolean claimed = buf.readBoolean();
        return new JobSiteEntry(pos, type, claimed);
    }
}
