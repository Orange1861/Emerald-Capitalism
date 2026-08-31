package com.orangevillager61.emeraldcapitalism.event;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEffects;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Server-authoritative infection, progression, and death conversion for Zombie Plague. */
@EventBusSubscriber(modid = EmeraldCapitalism.MODID)
public final class ZombieVirusEvents {

    private static final float INFECTION_CHANCE = 0.20F;
    private static final int PHASE_TWO_DURATION = MobEffectInstance.INFINITE_DURATION;
    private static final int PHASE_ONE = 1;
    private static final int PHASE_TWO = 2;
    private static final ResourceLocation TURNED_INTO_ZOMBIE_ADVANCEMENT = ModIds.id("turned_into_zombie");
    private static final String TURNED_INTO_ZOMBIE_CRITERION = "turned_into_zombie";
    private static final ResourceKey<DamageType> ZOMBIE_PLAGUE_DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE, ModIds.id("zombie_plague"));

    private ZombieVirusEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide()) {
            return;
        }

        LivingEntity target = event.getEntity();
        if (isPoisonDamage(target, event.getSource())) {
            event.setCanceled(true);
            return;
        }

        if (!isZombieAttack(event.getSource())) {
            return;
        }

        if (!(target instanceof Player) && !(target instanceof Villager)) {
            return;
        }

        MobEffectInstance virus = target.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        if (virus != null) {
            // Only the rotting phase has a countdown. Once the turning phase starts it remains terminal.
            if (!isPhaseTwo(virus)) {
                reducePhaseOneDuration(target, virus);
            }
            return;
        }

        if (target.getRandom().nextFloat() < INFECTION_CHANCE) {
            infectPhaseOne(target);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || event.getLevel().isClientSide()
                || !(event.getTarget() instanceof Villager villager)
                || !shouldCureWithGoldenApple(villager, event.getItemStack())) {
            return;
        }

        cureWithGoldenApple(villager, event.getItemStack());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSpecificEntityInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.isCanceled() || event.getLevel().isClientSide()
                || !(event.getTarget() instanceof Villager villager)
                || !shouldCureWithGoldenApple(villager, event.getItemStack())) {
            return;
        }

        cureWithGoldenApple(villager, event.getItemStack());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onItemUseFinished(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof Player player)) {
            return;
        }

        if (event.getItem().is(Items.ROTTEN_FLESH)) {
            tryInfectFromRottenFlesh(player);
            return;
        }

        if (!event.getItem().is(Items.GOLDEN_APPLE) || !shouldCureWithGoldenApple(player)) {
            return;
        }

        // Vanilla has already consumed the apple when this event fires. Only remove
        // the illness here; the normal golden-apple effects remain intact.
        removeZombiePlague(player);
    }

    @SubscribeEvent
    public static void onLivingTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof Player || event.getEntity() instanceof Villager)
                || !(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }

        MobEffectInstance virus = livingEntity.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        if (virus == null) {
            return;
        }

        if (!isPhaseTwo(virus) && virus.getDuration() <= 1) {
            beginPhaseTwo(livingEntity);
        } else if (isPhaseTwo(virus)) {
            ensureWitherPhase(livingEntity);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel serverLevel)) {
            return;
        }

        LivingEntity deceased = event.getEntity();
        MobEffectInstance virus = deceased.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        if (virus == null || !isPhaseTwo(virus)) {
            return;
        }

        if (deceased instanceof Player player) {
            replacePlayer(serverLevel, player);
        } else if (deceased instanceof Villager villager) {
            replaceVillager(serverLevel, villager);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onVirusRemoved(MobEffectEvent.Remove event) {
        if (!ECAPEffects.ZOMBIE_VIRUS.equals(event.getEffect())
                || !isPhaseTwo(event.getEffectInstance())
                || event.getEntity().level().isClientSide()
                || event.getEntity().level().getServer() == null) {
            return;
        }

        // removeAllEffects() iterates the active-effect map. Defer this cleanup
        // so removing the implementation-detail Wither cannot mutate that map
        // while vanilla is still iterating it (which crashes when drinking milk).
        LivingEntity entity = event.getEntity();
        event.getEntity().level().getServer().execute(() -> {
            if (!entity.isRemoved() && entity.getEffect(ECAPEffects.ZOMBIE_VIRUS) == null) {
                entity.removeEffect(MobEffects.WITHER);
            }
        });
    }

    public static int getPhase(LivingEntity entity) {
        MobEffectInstance virus = entity.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        if (virus == null) {
            return 0;
        }
        return isPhaseTwo(virus) ? PHASE_TWO : PHASE_ONE;
    }

    /** Applies the configured finite first phase to a newly infected entity. */
    public static void infectPhaseOne(LivingEntity entity) {
        entity.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, phaseOneDurationTicks(), 0,
                false, true, true));
    }

    public static int getPhaseOneRemainingTicks(LivingEntity entity) {
        MobEffectInstance virus = entity.getEffect(ECAPEffects.ZOMBIE_VIRUS);
        return virus == null || isPhaseTwo(virus) ? 0 : Math.max(0, virus.getDuration());
    }

    public static boolean isPhaseTwo(MobEffectInstance virus) {
        return virus != null && virus.isInfiniteDuration();
    }

    private static void beginPhaseTwo(LivingEntity entity) {
        entity.removeEffect(ECAPEffects.ZOMBIE_VIRUS);
        entity.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, PHASE_TWO_DURATION, 0,
                false, false, true));
        ensureWitherPhase(entity);
    }

    private static void ensureWitherPhase(LivingEntity entity) {
        MobEffectInstance wither = entity.getEffect(MobEffects.WITHER);
        if (wither == null || wither.getAmplifier() < 2) {
            // Wither III supplies vanilla wither damage and the wither-colored hearts.
            // Its particles remain visible; the virus marker itself only supplies the icon.
            entity.addEffect(new MobEffectInstance(
                    MobEffects.WITHER, PHASE_TWO_DURATION, 2,
                    false, true, false));
        }
    }

    private static void replacePlayer(ServerLevel level, Player player) {
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            return;
        }

        zombie.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        zombie.setCustomName(player.getName());
        zombie.setCustomNameVisible(true);
        zombie.setPersistenceRequired();
        transferPlayerEquipment(player, zombie);
        level.addFreshEntity(zombie);
        recordZombiePlagueDeathMessage(level, player);
        if (player instanceof ServerPlayer serverPlayer) {
            awardZombieConversionAdvancement(serverPlayer);
        }
    }

    private static void recordZombiePlagueDeathMessage(ServerLevel level, Player player) {
        Holder<DamageType> damageType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ZOMBIE_PLAGUE_DAMAGE_TYPE);
        player.getCombatTracker().recordDamage(new DamageSource(damageType), 0.0F);
    }

    private static void awardZombieConversionAdvancement(ServerPlayer player) {
        AdvancementHolder advancement = player.getServer().getAdvancements()
                .get(TURNED_INTO_ZOMBIE_ADVANCEMENT);
        if (advancement != null) {
            player.getAdvancements().award(advancement, TURNED_INTO_ZOMBIE_CRITERION);
        }
    }

    private static void replaceVillager(ServerLevel level, Villager villager) {
        ZombieVillager zombieVillager = EntityType.ZOMBIE_VILLAGER.create(level);
        if (zombieVillager == null) {
            return;
        }

        zombieVillager.moveTo(villager.getX(), villager.getY(), villager.getZ(),
                villager.getYRot(), villager.getXRot());
        Component name = villager.getCustomName();
        if (name != null) {
            zombieVillager.setCustomName(name);
            zombieVillager.setCustomNameVisible(villager.isCustomNameVisible());
        }
        zombieVillager.setBaby(villager.isBaby());
        zombieVillager.setPersistenceRequired();
        zombieVillager.addEffect(new MobEffectInstance(
                MobEffects.DAMAGE_RESISTANCE, 3 * 20, 3,
                false, true, true));
        level.addFreshEntity(zombieVillager);
    }

    private static void transferPlayerEquipment(Player player, Zombie zombie) {
        EquipmentSlot[] slots = {
                EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND,
                EquipmentSlot.FEET,
                EquipmentSlot.LEGS,
                EquipmentSlot.CHEST,
                EquipmentSlot.HEAD
        };
        for (EquipmentSlot slot : slots) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            zombie.setItemSlot(slot, stack.copy());
            zombie.setGuaranteedDrop(slot);
            player.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    private static int phaseOneDurationTicks() {
        return Math.max(1, Config.zombieVirusPhaseOneDurationSeconds * 20);
    }

    private static int hitReductionTicks() {
        return Math.max(1, Config.zombieVirusHitTimeReductionSeconds * 20);
    }

    private static void tryInfectFromRottenFlesh(Player player) {
        if (player.getEffect(ECAPEffects.ZOMBIE_VIRUS) == null
                && player.getRandom().nextInt(100) < Config.zombieVirusRottenFleshInfectionChancePercent) {
            infectPhaseOne(player);
        }
    }

    private static void reducePhaseOneDuration(LivingEntity entity, MobEffectInstance virus) {
        int reducedDuration = virus.mapDuration(duration -> Math.max(1, duration - hitReductionTicks()));
        entity.removeEffect(ECAPEffects.ZOMBIE_VIRUS);
        entity.addEffect(new MobEffectInstance(
                ECAPEffects.ZOMBIE_VIRUS, reducedDuration, virus.getAmplifier(),
                virus.isAmbient(), virus.isVisible(), virus.showIcon()));
    }

    private static boolean shouldCureWithGoldenApple(Villager villager, ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE)
                && getPhase(villager) != 0
                && villager.getEffect(MobEffects.WEAKNESS) != null;
    }

    private static boolean shouldCureWithGoldenApple(Player player) {
        return getPhase(player) != 0
                && player.getEffect(MobEffects.WEAKNESS) != null;
    }

    private static void cureWithGoldenApple(Villager villager, ItemStack stack) {
        stack.shrink(1);
        removeZombiePlague(villager);
    }

    private static void removeZombiePlague(LivingEntity entity) {
        entity.removeEffect(ECAPEffects.ZOMBIE_VIRUS);
        entity.removeEffect(MobEffects.WITHER);
    }

    private static boolean isPoisonDamage(LivingEntity target, DamageSource source) {
        return (target instanceof Player || target instanceof Villager)
                && target.getEffect(ECAPEffects.ZOMBIE_VIRUS) != null
                && target.getEffect(MobEffects.POISON) != null
                && source.is(DamageTypes.MAGIC);
    }

    private static boolean isZombieAttack(DamageSource source) {
        return source.getDirectEntity() instanceof Zombie
                || source.getEntity() instanceof Zombie;
    }
}
