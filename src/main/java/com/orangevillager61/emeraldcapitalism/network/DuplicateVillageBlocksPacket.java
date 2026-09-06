package com.orangevillager61.emeraldcapitalism.network;

import com.orangevillager61.emeraldcapitalism.registry.ECAPItems;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRelationship;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/** Client request to receive another Village Manager and Bank block. */
public record DuplicateVillageBlocksPacket(UUID villageId) implements CustomPacketPayload {

    /** Ten minutes at Minecraft's standard 20 ticks per second. */
    public static final long DUPLICATE_COOLDOWN_TICKS = 12_000L;
    private static final String LAST_DUPLICATE_TICK_KEY =
            "emeraldcapitalism_last_duplicate_village_blocks_tick";

    public DuplicateVillageBlocksPacket {
        villageId = Objects.requireNonNull(villageId, "villageId");
    }

    public static final Type<DuplicateVillageBlocksPacket> TYPE =
            new Type<>(ModIds.id("duplicate_village_blocks"));

    public static final StreamCodec<FriendlyByteBuf, DuplicateVillageBlocksPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public @NotNull DuplicateVillageBlocksPacket decode(FriendlyByteBuf buf) {
                    return new DuplicateVillageBlocksPacket(buf.readUUID());
                }

                @Override
                public void encode(FriendlyByteBuf buf, DuplicateVillageBlocksPacket packet) {
                    buf.writeUUID(packet.villageId());
                }
            };

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DuplicateVillageBlocksPacket packet, IPayloadContext context) {
        PacketHandlerUtil.withServerPlayer(context, "duplicate_village_blocks", player -> {
            ServerLevel level = PacketHandlerUtil.serverLevel(player);
            VillageRecord village = VillageRegistryData.get(level).getVillages().get(packet.villageId());
            if (village == null) {
                player.sendSystemMessage(Component.literal("[ECAP] Village not found."));
                return;
            }
            if (!VillagePOIAccessPolicy.isMutationContextValid(player, level, village)
                    || village.getPlayerRelationship(level, player) != VillageRelationship.GOVERNOR) {
                player.sendSystemMessage(Component.literal(
                        "[ECAP] Only the village Governor can duplicate village blocks."));
                return;
            }

            long now = level.getGameTime();
            var persistentData = player.getPersistentData();
            if (persistentData.contains(LAST_DUPLICATE_TICK_KEY, Tag.TAG_LONG)) {
                long lastDuplicateTick = persistentData.getLong(LAST_DUPLICATE_TICK_KEY);
                if (now >= lastDuplicateTick
                        && now - lastDuplicateTick < DUPLICATE_COOLDOWN_TICKS) {
                    long remainingTicks = DUPLICATE_COOLDOWN_TICKS - (now - lastDuplicateTick);
                    player.sendSystemMessage(Component.literal(String.format(
                            "[ECAP] Please wait %.1f seconds before duplicating village blocks again.",
                            remainingTicks / 20.0D)));
                    return;
                }
            }

            giveItem(player, ECAPItems.VILLAGE_MANAGER.get());
            giveItem(player, ECAPItems.BANK.get());
            persistentData.putLong(LAST_DUPLICATE_TICK_KEY, now);
            player.sendSystemMessage(Component.literal(
                    "[ECAP] You received a Village Manager and Bank."));
        });
    }

    private static void giveItem(ServerPlayer player, Item item) {
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /** Preserves the economic cooldown when a player entity is cloned on death. */
    public static void copyCooldown(Player original, Player clone) {
        if (original.getPersistentData().contains(LAST_DUPLICATE_TICK_KEY, Tag.TAG_LONG)) {
            clone.getPersistentData().putLong(LAST_DUPLICATE_TICK_KEY,
                    original.getPersistentData().getLong(LAST_DUPLICATE_TICK_KEY));
        }
    }
}
