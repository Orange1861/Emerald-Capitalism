package com.orangevillager61.emeraldcapitalism.entity;

import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSkrimisherBankDepositGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSkrimisherCombatGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.EmeraldSkrimisherPickupGoal;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.util.VillagerSkrimisherItemPool;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * A lightweight emerald golem variant that scavenges useful village supplies.
 * The inventory is deliberately kept as a plain nine-slot carrier until a
 * dedicated interaction menu is added.
 */
public class EmeraldSkrimisher extends EmeraldGolem implements InventoryCarrier {

    public static final int INVENTORY_SIZE = 9;

    private static final ResourceKey<LootTable> LOOT_TABLE = ResourceKey.create(
            Registries.LOOT_TABLE, ModIds.id("entities/emerald_skrimisher"));

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    public EmeraldSkrimisher(EntityType<? extends EmeraldSkrimisher> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // IronGolem.registerGoals() installs its ordinary MeleeAttackGoal. The
        // Skrimisher has a different attack sequence, so do not let the
        // inherited goal compete with its dedicated combat goal.
        this.goalSelector.removeAllGoals(goal -> goal instanceof MeleeAttackGoal);
        this.goalSelector.addGoal(1, new EmeraldSkrimisherCombatGoal(this));
        this.goalSelector.addGoal(2, new EmeraldSkrimisherBankDepositGoal(this));
        this.goalSelector.addGoal(6, new EmeraldSkrimisherPickupGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.375D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    public SimpleContainer getInventory() {
        return inventory;
    }

    @Override
    public boolean canHoldItem(ItemStack stack) {
        return VillagerSkrimisherItemPool.contains(stack) && inventory.canAddItem(stack);
    }

    @Override
    public boolean wantsToPickUp(ItemStack stack) {
        return canHoldItem(stack);
    }

    @Override
    protected ResourceKey<LootTable> getDefaultLootTable() {
        return LOOT_TABLE;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        this.inventory.removeAllItems().forEach(this::spawnAtLocation);
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        InventoryCarrier.pickUpItem(this, this, itemEntity);
    }

    /** Entry point used by the pickup goal, which cannot call Mob's protected method. */
    public void pickUpItemForGoal(ItemEntity itemEntity) {
        pickUpItem(itemEntity);
    }

    /** Returns whether another emerald or iron golem is within eight blocks of the target. */
    public boolean hasNearbyGolem(LivingEntity target) {
        AABB searchArea = target.getBoundingBox().inflate(8.0D);
        return !level().getEntitiesOfClass(IronGolem.class, searchArea,
                        golem -> golem.isAlive()
                                && golem != this
                                && target.distanceToSqr(golem) <= 8.0D * 8.0D)
                .isEmpty();
    }

    /** Performs one of the Skrimisher's two attack patterns. */
    public boolean performCombatAttack(LivingEntity target, boolean jumpAttack) {
        if (level().isClientSide || !target.isAlive()) {
            return false;
        }

        level().broadcastEntityEvent(this, (byte) 4);
        float damage = jumpAttack ? 1.0F : (float) getAttributeValue(Attributes.ATTACK_DAMAGE);
        boolean damaged = target.hurt(damageSources().mobAttack(this), damage);
        if (damaged) {
            if (jumpAttack) {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6 * 20, 3), this);
            } else {
                knockTargetAwayFromVillagers(target);
            }
        }

        playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 1.0F);
        return damaged;
    }

    private void knockTargetAwayFromVillagers(LivingEntity target) {
        Vec3 sourceDirection = new Vec3(getX() - target.getX(), 0.0D, getZ() - target.getZ());
        if (sourceDirection.horizontalDistanceSqr() < 1.0E-6D) {
            sourceDirection = new Vec3(-getLookAngle().x, 0.0D, -getLookAngle().z);
        }

        Villager nearestVillager = level().getEntitiesOfClass(
                        Villager.class,
                        target.getBoundingBox().inflate(16.0D),
                        Villager::isAlive)
                .stream()
                .min(Comparator.comparingDouble(target::distanceToSqr))
                .orElse(null);
        if (nearestVillager != null) {
            Vec3 directionToVillager = new Vec3(
                    nearestVillager.getX() - target.getX(),
                    0.0D,
                    nearestVillager.getZ() - target.getZ());
            if (directionToVillager.horizontalDistanceSqr() > 1.0E-6D
                    && sourceDirection.normalize().scale(-1.0D)
                    .dot(directionToVillager.normalize()) > 0.0D) {
                // LivingEntity.knockback() receives the direction toward the
                // source and applies the opposite vector. Passing the nearby
                // villager direction therefore sends the target away from it.
                sourceDirection = directionToVillager;
            }
        }

        target.knockback(10.0D, sourceDirection.x, sourceDirection.z);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        writeInventoryToTag(tag, registryAccess());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        readInventoryFromTag(tag, registryAccess());
    }
}
