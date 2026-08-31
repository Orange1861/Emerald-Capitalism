package com.orangevillager61.emeraldcapitalism.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import com.orangevillager61.emeraldcapitalism.entity.ai.HostileVillageMayorTargetGoal;
import com.orangevillager61.emeraldcapitalism.entity.ai.VaultGolemGoals;
import com.orangevillager61.emeraldcapitalism.util.ModIds;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

public class EmeraldGolem extends IronGolem {

    private static final ResourceKey<LootTable> DEFAULT_LOOT_TABLE = ResourceKey.create(
            Registries.LOOT_TABLE, ModIds.id("entities/emerald_golem"));
    private static final ResourceKey<LootTable> AMBUSH_LOOT_TABLE = ResourceKey.create(
            Registries.LOOT_TABLE, ModIds.id("entities/emerald_golem_ambush"));

    // Synched data

    // Anger management (same as iron golem)

    /** Bank that employs this golem, if it was spawned as a bank vault guard. */
    @Nullable
    private BlockPos bankEmployeePos;

    /** Whether this golem was created by the hostile-player ambush event. */
    private boolean ambush;

    /** Player association and countdown for the delayed ambush attack. */
    @Nullable
    private UUID ambushTarget;
    private int ambushAttackDelayTicks;
    private boolean ambushAttackStarted;
    private int ladderClimbDirection;

    /**
     * Mod-owned durable golem state. Bank assignment and the ambush marker
     * affect gameplay and drops. The delayed target and countdown are persisted
     * so an unloaded or reloaded ambush remains associated with its player.
     */
    static record PersistedState(Optional<BlockPos> bankEmployeePos, boolean ambush,
                                 Optional<UUID> ambushTarget, int ambushAttackDelayTicks) {
        private static final int MAX_AMBUSH_DELAY_TICKS = 20 * 60;

        static final Codec<PersistedState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.optionalFieldOf("bank_employee_pos")
                        .forGetter(PersistedState::bankEmployeePos),
                Codec.BOOL.optionalFieldOf("ambush", false)
                        .forGetter(PersistedState::ambush),
                UUIDUtil.CODEC.optionalFieldOf("ambush_target")
                        .forGetter(PersistedState::ambushTarget),
                Codec.intRange(0, MAX_AMBUSH_DELAY_TICKS)
                        .optionalFieldOf("ambush_attack_delay_ticks", 0)
                        .forGetter(PersistedState::ambushAttackDelayTicks)
        ).apply(instance, PersistedState::new));

        static PersistedState empty() {
            return new PersistedState(Optional.empty(), false, Optional.empty(), 0);
        }

        static PersistedState from(@Nullable BlockPos bankEmployeePos, boolean ambush,
                                   @Nullable UUID ambushTarget, int ambushAttackDelayTicks) {
            return new PersistedState(Optional.ofNullable(bankEmployeePos), ambush,
                    Optional.ofNullable(ambushTarget),
                    Math.max(0, Math.min(MAX_AMBUSH_DELAY_TICKS, ambushAttackDelayTicks)));
        }

        @Nullable
        BlockPos bankEmployeePosOrNull() {
            return bankEmployeePos.orElse(null);
        }

        @Nullable
        UUID ambushTargetOrNull() {
            return ambushTarget.orElse(null);
        }
    }

    // Attack animation

    // Flower offering

    public EmeraldGolem(EntityType<? extends EmeraldGolem> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
        tickAmbushAttack();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        tickLadderTraversal();
    }

    private void tickLadderTraversal() {
        if (!Config.enableLadderTraversal) {
            stopLadderTraversal(false);
            return;
        }
        BlockPos position = blockPosition();
        boolean onClimbable = onClimbable()
                || level().getBlockState(position).is(BlockTags.CLIMBABLE)
                || level().getBlockState(position.below()).is(BlockTags.CLIMBABLE);
        if (!onClimbable) {
            stopLadderTraversal(false);
            return;
        }

        PathNavigation navigation = getNavigation();
        Path path = navigation.getPath();
        if (path != null) {
            for (int index = Math.max(0, path.getNextNodeIndex() - 1);
                 index < path.getNodeCount(); index++) {
                Node node = path.getNode(index);
                if (node.x == position.getX() && node.z == position.getZ()
                        && node.y != position.getY()) {
                    ladderClimbDirection = node.y > position.getY() ? 1 : -1;
                    break;
                }
            }
        }
        if (ladderClimbDirection == 0) {
            setNoGravity(false);
            return;
        }

        setNoGravity(true);
        boolean canContinue = ladderClimbDirection > 0
                ? level().getBlockState(position.above()).is(BlockTags.CLIMBABLE)
                : level().getBlockState(position.below()).is(BlockTags.CLIMBABLE);
        double pullX = (position.getX() + 0.5D - getX()) * 0.2D;
        double pullZ = (position.getZ() + 0.5D - getZ()) * 0.2D;
        if (!canContinue) {
            if (ladderClimbDirection > 0) {
                if (!isClearForGolem(position.above())) {
                    stopLadderTraversal(true);
                    return;
                }
                moveAlongLadder(pullX, 0.2D, pullZ);
            } else {
                BlockState state = level().getBlockState(position);
                if (state.hasProperty(LadderBlock.FACING)) {
                    Direction facing = state.getValue(LadderBlock.FACING);
                    if (!isClearForGolem(position.relative(facing))) {
                        stopLadderTraversal(true);
                        return;
                    }
                    moveAlongLadder(facing.getStepX() * 0.2D, 0.0D,
                            facing.getStepZ() * 0.2D);
                }
            }
            return;
        }
        moveAlongLadder(pullX, ladderClimbDirection > 0 ? 0.2D : -0.15D, pullZ);
    }

    private void moveAlongLadder(double x, double y, double z) {
        setPos(getX() + x, getY() + y, getZ() + z);
        setDeltaMovement(Vec3.ZERO);
    }

    private void stopLadderTraversal(boolean stopNavigation) {
        ladderClimbDirection = 0;
        setNoGravity(false);
        if (stopNavigation) {
            setDeltaMovement(Vec3.ZERO);
            getNavigation().stop();
        }
    }

    private boolean isClearForGolem(BlockPos feetPos) {
        int bodyBlocks = (int) Math.ceil(getBbHeight());
        for (int offset = 0; offset < bodyBlocks; offset++) {
            BlockPos bodyPos = feetPos.above(offset);
            if (!level().getBlockState(bodyPos).getCollisionShape(level(), bodyPos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Arms this golem to attack the supplied player after a server-side delay. */
    public void armAmbush(ServerPlayer target, int attackDelayTicks) {
        markAmbush();
        this.ambushTarget = target.getUUID();
        this.ambushAttackDelayTicks = Math.max(0, attackDelayTicks);
        this.ambushAttackStarted = false;
        this.setTarget(null);
    }

    /** Marks this golem as an ambush golem without assigning a delayed target. */
    public void markAmbush() {
        this.ambush = true;
        this.setPlayerCreated(false);
    }

    public boolean isAmbush() {
        return ambush;
    }

    /** Returns whether this ambush is associated with the supplied player UUID. */
    public boolean isAmbushFor(UUID playerId) {
        return ambush && (playerId.equals(ambushTarget) || playerId.equals(getPersistentAngerTarget()));
    }

    /** Rebinds this ambush to the respawned server-side player entity. */
    public boolean retargetAmbush(ServerPlayer target) {
        if (level().isClientSide || !isAlive() || !target.isAlive() || target.isSpectator()
                || !isAmbushFor(target.getUUID())) {
            return false;
        }

        this.ambushAttackStarted = true;
        this.ambushAttackDelayTicks = 0;
        this.setTarget(target);
        this.setPersistentAngerTarget(target.getUUID());
        this.startPersistentAngerTimer();
        return true;
    }

    /** Clears a live cross-dimension target while retaining the persisted association. */
    public boolean pauseAmbushFor(UUID playerId) {
        if (level().isClientSide || !isAmbushFor(playerId)) {
            return false;
        }

        this.ambushAttackStarted = false;
        this.setTarget(null);
        return true;
    }

    private void tickAmbushAttack() {
        if (level().isClientSide || ambushTarget == null || ambushAttackStarted || !isAlive()
                || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (ambushAttackDelayTicks > 0) {
            ambushAttackDelayTicks--;
            if (ambushAttackDelayTicks > 0) {
                return;
            }
        }

        ServerPlayer target = serverLevel.players().stream()
                .filter(player -> player.getUUID().equals(ambushTarget))
                .findFirst()
                .orElse(null);
        if (target == null || !target.isAlive() || target.isSpectator()) {
            return;
        }

        ambushAttackStarted = true;
        setTarget(target);
        setPersistentAngerTarget(target.getUUID());
        startPersistentAngerTimer();
    }

    // AI Goals (same as iron golem, minus IronGolem-specific goals)
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(1, new HostileVillageMayorTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true,
                player -> !this.isPlayerCreated()
                        && this.bankEmployeePos != null
                        && this.level() instanceof ServerLevel serverLevel
                        && BankReputationData.get(serverLevel).getReputation(player.getUUID())
                                <= BankReputationData.HOSTILITY_THRESHOLD));
        this.goalSelector.addGoal(8, new RandomStrollGoal(this, 0.6D));
    }

    // Synched data
    // Attributes
    // Half health (50), 50% faster speed (0.375), half attack damage (7.5)
    // Emerald golems should receive normal knockback.
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 50.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.375D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0D)
                .add(Attributes.ATTACK_DAMAGE, 7.5D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    // Tick / Animation
    // Cracking (same thresholds as iron golem)
    // Interaction: heal with emeralds
    @Override
    protected @NotNull InteractionResult mobInteract(Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.EMERALD)) {
            return InteractionResult.PASS;
        }
        float health = this.getHealth();
        if (health >= this.getMaxHealth()) {
            return InteractionResult.PASS;
        }
        // Each emerald heals 12.5 HP (half of iron ingot's 25 for iron golem)
        this.heal(12.5F);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        this.playSound(SoundEvents.IRON_GOLEM_REPAIR, 1.0F, 1.0F);
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    // Sounds
    @Override
    protected SoundEvent getHurtSound(@NotNull DamageSource source) {
        return SoundEvents.IRON_GOLEM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.IRON_GOLEM_DEATH;
    }

    @Override
    protected void playStepSound(@NotNull BlockPos pos, @NotNull BlockState state) {
        this.playSound(SoundEvents.IRON_GOLEM_STEP, 1.0F, 1.0F);
    }

    // Animation accessors
    // Entity event handling (attack & flower animations)
    // Player-created flag
    /** Returns the owning Bank position for a vault-spawned golem, if any. */
    @Nullable
    public BlockPos getBankEmployeePos() {
        return bankEmployeePos;
    }

    /** Marks this golem as an employee of the Bank at {@code bankPos}. */
    public void setBankEmployeePos(@Nullable BlockPos bankPos) {
        this.bankEmployeePos = bankPos == null ? null : bankPos.immutable();
    }

    /** Returns whether this is one of the stationary guards created in a bank vault. */
    public boolean isVaultGuard() {
        return bankEmployeePos != null && VaultGolemGoals.isVaultGuard(this);
    }

    // NBT persistence
    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        PersistedState.CODEC.encodeStart(NbtOps.INSTANCE,
                        PersistedState.from(bankEmployeePos, ambush, ambushTarget, ambushAttackDelayTicks))
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.error(
                        "Could not encode emerald golem durable state: {}", message))
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast)
                .ifPresent(encodedTag -> tag.merge(encodedTag));
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        PersistedState state = PersistedState.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(message -> EmeraldCapitalism.LOGGER.warn(
                        "Ignoring malformed emerald golem durable state: {}", message))
                .orElseGet(PersistedState::empty);
        this.bankEmployeePos = state.bankEmployeePosOrNull();
        this.ambush = state.ambush();
        this.ambushTarget = state.ambush() ? state.ambushTargetOrNull() : null;
        this.ambushAttackDelayTicks = state.ambush() ? state.ambushAttackDelayTicks() : 0;
        this.ambushAttackStarted = false;
    }

    // NeutralMob implementation
    // Misc behavior
    @Override
    public boolean canAttackType(@NotNull EntityType<?> type) {
        // Don't attack players if player-created, unless provoked
        if (this.isPlayerCreated() && type == EntityType.PLAYER) {
            return false;
        }
        return type != EntityType.CREEPER && super.canAttackType(type);
    }

    @Override
    protected @NotNull Vec3 getPassengerAttachmentPoint(@NotNull Entity entity, EntityDimensions dimensions, float scale) {
        return new Vec3(0.0D, (double) dimensions.height() - 0.3D, 0.0D);
    }

    // Iron/emerald golems don't take fall damage
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, @NotNull DamageSource source) {
        return false;
    }

    @Override
    public float getWalkTargetValue(@NotNull BlockPos pos, @NotNull LevelReader level) {
        return 0.0F;
    }

    /** Use the emerald golem loot table instead of the inherited iron golem table. */
    @Override
    protected ResourceKey<LootTable> getDefaultLootTable() {
        return ambush && Config.emeraldGolemAmbushDropsVillageMap
                ? AMBUSH_LOOT_TABLE
                : DEFAULT_LOOT_TABLE;
    }
}
