package com.orangevillager61.emeraldcapitalism.scenario;

import com.orangevillager61.emeraldcapitalism.util.EntityCreation;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import com.orangevillager61.emeraldcapitalism.world.village.VillagerPOIRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The step 1 mixed-village safety scene: three employed vanilla villagers that belong to a
 * registered mod village, with a bed each, a bell, and their own job sites, inside a glass pen.
 *
 * <p>Callers: {@link ScenarioRunner#load} builds it for {@code /ecapscenario load mixed_village}
 * in development runs, and {@code ScenarioRunnerGameTests} drives the same runner for the
 * automated standing check, so the manual and automated scenes cannot drift.
 *
 * <p>Setup holds the actors with NoAI. {@link #release()} hands them to their own Brain. After
 * that the scene changes the world only through named external events: the time of day, a
 * hostile appearing and leaving, and a player opening a trade. It never ticks a behavior, writes
 * a Brain memory, sets a path, claims a POI, or moves a villager. {@link #observe()} and
 * {@link #inspect} only read.
 */
public final class MixedVillageScene {

    public static final String ID = "mixed_village";
    /** The pen is {@code SIZE x SIZE} blocks including its glass wall. */
    public static final int SIZE = 20;

    /** Day times inside each vanilla villager schedule window (WORK 2000, MEET 9000, REST 12000). */
    public static final long WORK_TIME = 2_500L;
    public static final long MEET_TIME = 9_200L;
    public static final long REST_TIME = 13_000L;

    /** Walking the 20-block pen takes well under a minute; a minute is the failure limit. */
    static final int PHASE_TIMEOUT_TICKS = 1_200;
    static final int PANIC_TIMEOUT_TICKS = 200;
    static final int CALM_TIMEOUT_TICKS = 600;
    static final int TRADE_RESUME_TIMEOUT_TICKS = 400;

    /** Vanilla work starts within 1.73 blocks of the job site; 2.5 allows for standing beside it. */
    static final double WORK_DISTANCE = 2.5D;
    /** Vanilla walks MEET villagers to within 6 blocks of the bell. */
    static final double MEET_DISTANCE = 7.0D;

    static final BlockPos BELL = new BlockPos(16, 1, 3);
    static final BlockPos HOSTILE = new BlockPos(9, 1, 9);
    private static final List<ActorLayout> LAYOUT = List.of(
            new ActorLayout("farmer", VillagerProfession.FARMER, Blocks.COMPOSTER,
                    new BlockPos(3, 1, 3), new BlockPos(11, 1, 15), new BlockPos(8, 1, 11)),
            new ActorLayout("librarian", VillagerProfession.LIBRARIAN, Blocks.LECTERN,
                    new BlockPos(3, 1, 8), new BlockPos(13, 1, 15), new BlockPos(9, 1, 11)),
            new ActorLayout("cleric", VillagerProfession.CLERIC, Blocks.BREWING_STAND,
                    new BlockPos(8, 1, 3), new BlockPos(15, 1, 15), new BlockPos(10, 1, 11)));
    private static final List<BlockPos> FLOOR_LIGHTS = List.of(
            new BlockPos(5, 0, 5), new BlockPos(5, 0, 14), new BlockPos(14, 0, 5),
            new BlockPos(14, 0, 14), new BlockPos(9, 0, 9));

    /** The checkpoints the result reports, in the order the run sheet exercises them. */
    public enum Checkpoint {
        WORK, TRADE, PANIC, CALM, MEET, REST;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Status { NOT_RUN, WAITING, PASS, FAIL }

    /** Actions the run sheet and the GameTest may invoke; each is an ordinary external event. */
    public static final List<String> ACTIONS = List.of("work", "meet", "rest", "panic", "calm", "trade");

    private record ActorLayout(String role, VillagerProfession profession, Block jobBlock,
                               BlockPos jobSite, BlockPos bedFoot, BlockPos spawn) {
    }

    /** One villager in the scene and the absolute positions it is judged against. */
    public record Actor(String role, Villager villager, BlockPos jobSite) {
    }

    private final ServerLevel level;
    private final BlockPos origin;
    private final UUID villageId;
    private final List<Actor> actors;
    private final Map<Checkpoint, Long> startedAt = new EnumMap<>(Checkpoint.class);
    private final Map<Checkpoint, Long> passedAt = new EnumMap<>(Checkpoint.class);
    private final Map<Checkpoint, Set<UUID>> reached = new EnumMap<>(Checkpoint.class);
    private long releasedAt = -1L;
    @Nullable
    private Pillager hostile;
    @Nullable
    private UUID tradingActor;
    private long tradeClosedAt = -1L;

    private MixedVillageScene(ServerLevel level, BlockPos origin, UUID villageId, List<Actor> actors) {
        this.level = level;
        this.origin = origin;
        this.villageId = villageId;
        this.actors = List.copyOf(actors);
        for (Checkpoint checkpoint : Checkpoint.values()) {
            reached.put(checkpoint, new HashSet<>());
        }
    }

    /**
     * Builds the pen with its north-west floor corner at {@code origin}, registers it as a mod
     * village, and spawns the actors held with NoAI. The area must not belong to a village.
     */
    public static MixedVillageScene build(ServerLevel level, BlockPos origin) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(SIZE - 1, 3, SIZE - 1))) {
            int dx = pos.getX() - origin.getX();
            int dy = pos.getY() - origin.getY();
            int dz = pos.getZ() - origin.getZ();
            BlockState state;
            if (dy == 0) {
                state = FLOOR_LIGHTS.contains(new BlockPos(dx, 0, dz))
                        ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.STONE.defaultBlockState();
            } else if (dy <= 2 && (dx == 0 || dz == 0 || dx == SIZE - 1 || dz == SIZE - 1)) {
                state = Blocks.GLASS.defaultBlockState();
            } else {
                state = Blocks.AIR.defaultBlockState();
            }
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }

        BlockPos bell = origin.offset(BELL);
        level.setBlock(bell, Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
        for (ActorLayout layout : LAYOUT) {
            level.setBlock(origin.offset(layout.jobSite()), layout.jobBlock().defaultBlockState(),
                    Block.UPDATE_ALL);
            placeBed(level, origin.offset(layout.bedFoot()));
        }
        // Clear weather so a storm does not change what the villagers do between runs.
        level.setWeatherParameters(12_000, 0, false, false);

        AABB bounds = new AABB(origin.getX(), origin.getY() - 4, origin.getZ(),
                origin.getX() + SIZE, origin.getY() + 8, origin.getZ() + SIZE);
        VillageRecord village = VillageRegistryData.get(level)
                .getOrCreateVillage(UUID.randomUUID(), bell, bounds);

        List<Actor> actors = new ArrayList<>();
        for (ActorLayout layout : LAYOUT) {
            Villager villager = EntityCreation.create(EntityType.VILLAGER, level);
            BlockPos spawn = origin.offset(layout.spawn());
            villager.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, 0.0F, 0.0F);
            villager.setVillagerData(villager.getVillagerData().setProfession(layout.profession()));
            // The Brain was built for an unemployed villager; vanilla rebuilds it on a profession
            // change, otherwise the old job-site checks erase every job site this actor claims.
            villager.refreshBrain(level);
            // Vanilla resets a level-one, zero-XP villager that has no job-site memory yet.
            villager.setVillagerXp(1);
            villager.setNoAi(true);
            level.addFreshEntity(villager);
            village.addMember(new VillagerPOIRecord(
                    villager.getUUID(), villager.getName().getString(), layout.profession().name(),
                    null, null, null, villager.getHealth(), VillagerPOIRecord.Status.ACTIVE,
                    0, level.getGameTime()));
            actors.add(new Actor(layout.role(), villager, origin.offset(layout.jobSite())));
        }
        return new MixedVillageScene(level, origin, village.getVillageId(), actors);
    }

    private static void placeBed(ServerLevel level, BlockPos foot) {
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        // Skip shape updates so neither half sees a missing partner while the other is placed.
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), flags);
        level.setBlock(foot.relative(Direction.SOUTH), bed.setValue(BedBlock.PART, BedPart.HEAD), flags);
    }

    /** Hands every actor to its own Brain. */
    public void release() {
        for (Actor actor : actors) {
            actor.villager().setNoAi(false);
        }
        releasedAt = level.getGameTime();
    }

    public boolean released() {
        return releasedAt >= 0L;
    }

    public ServerLevel level() {
        return level;
    }

    public BlockPos origin() {
        return origin;
    }

    public UUID villageId() {
        return villageId;
    }

    public List<Actor> actors() {
        return actors;
    }

    /**
     * Performs one named external event. {@code player} is needed only by {@code trade}, which
     * makes that player right-click the farmer exactly as the developer would.
     *
     * @return a message for the caller, or null if the action is unknown
     */
    @Nullable
    public String action(String id, @Nullable Player player) {
        long now = level.getGameTime();
        switch (id) {
            case "work" -> {
                level.setDayTime(WORK_TIME);
                restart(Checkpoint.WORK, now);
                return "time set to " + WORK_TIME + " (WORK); watching for every actor at its job site";
            }
            case "meet" -> {
                level.setDayTime(MEET_TIME);
                restart(Checkpoint.MEET, now);
                return "time set to " + MEET_TIME + " (MEET); watching for every actor near the bell";
            }
            case "rest" -> {
                level.setDayTime(REST_TIME);
                restart(Checkpoint.REST, now);
                return "time set to " + REST_TIME + " (REST); watching for every actor asleep";
            }
            case "panic" -> {
                if (level.getDifficulty() == Difficulty.PEACEFUL) {
                    return "panic needs difficulty easy or higher; peaceful removes the hostile";
                }
                if (hostile == null || !hostile.isAlive()) {
                    hostile = spawnHostile();
                }
                restart(Checkpoint.PANIC, now);
                return "a held pillager stands at the pen centre; watching for every actor to panic";
            }
            case "calm" -> {
                if (hostile != null) {
                    hostile.discard();
                    hostile = null;
                }
                restart(Checkpoint.CALM, now);
                return "the pillager is gone; watching for every actor to return to its schedule";
            }
            case "trade" -> {
                if (player == null) {
                    return "trade needs a player to open the trade";
                }
                Villager farmer = actors.get(0).villager();
                InteractionResult result = farmer.interact(player, InteractionHand.MAIN_HAND);
                return "opened a trade with the farmer (" + result + "); close the screen to end it";
            }
            default -> {
                return null;
            }
        }
    }

    private Pillager spawnHostile() {
        Pillager pillager = EntityCreation.create(EntityType.PILLAGER, level);
        BlockPos pos = origin.offset(HOSTILE);
        pillager.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        // Held and harmless: the villagers react to it being there, not to anything it does.
        pillager.setNoAi(true);
        pillager.setInvulnerable(true);
        pillager.setPersistenceRequired();
        level.addFreshEntity(pillager);
        return pillager;
    }

    private void restart(Checkpoint checkpoint, long now) {
        startedAt.put(checkpoint, now);
        passedAt.remove(checkpoint);
        reached.get(checkpoint).clear();
    }

    /** Samples every actor once. Read-only; {@link ScenarioRunner} calls it every 20 ticks. */
    public void observe() {
        if (!released()) {
            return;
        }
        long now = level.getGameTime();
        for (Actor actor : actors) {
            Villager villager = actor.villager();
            if (!villager.isAlive()) {
                continue;
            }
            UUID id = villager.getUUID();
            Activity activity = villager.getBrain().getActiveNonCoreActivity().orElse(null);
            if (watching(Checkpoint.WORK, now) && activity == Activity.WORK
                    && distance(villager, actor.jobSite()) <= WORK_DISTANCE) {
                reached.get(Checkpoint.WORK).add(id);
            }
            if (watching(Checkpoint.MEET, now) && activity == Activity.MEET
                    && distance(villager, origin.offset(BELL)) <= MEET_DISTANCE) {
                reached.get(Checkpoint.MEET).add(id);
            }
            if (watching(Checkpoint.REST, now) && villager.isSleeping()) {
                reached.get(Checkpoint.REST).add(id);
            }
            if (watching(Checkpoint.PANIC, now) && activity == Activity.PANIC) {
                reached.get(Checkpoint.PANIC).add(id);
            }
            if (watching(Checkpoint.CALM, now) && activity != Activity.PANIC
                    && activity == scheduled(villager)) {
                reached.get(Checkpoint.CALM).add(id);
            }
            observeTrade(villager, activity, now);
        }
        for (Checkpoint checkpoint : List.of(Checkpoint.WORK, Checkpoint.MEET, Checkpoint.REST,
                Checkpoint.PANIC, Checkpoint.CALM)) {
            if (watching(checkpoint, now) && reached.get(checkpoint).size() == actors.size()) {
                passedAt.put(checkpoint, now);
            }
        }
    }

    /** A trade opened by any player counts; the actor must then return to its schedule. */
    private void observeTrade(Villager villager, @Nullable Activity activity, long now) {
        UUID id = villager.getUUID();
        if (villager.isTrading()) {
            if (!id.equals(tradingActor) || tradeClosedAt >= 0L) {
                tradingActor = id;
                tradeClosedAt = -1L;
                startedAt.remove(Checkpoint.TRADE);
                passedAt.remove(Checkpoint.TRADE);
            }
            return;
        }
        if (!id.equals(tradingActor)) {
            return;
        }
        if (tradeClosedAt < 0L) {
            tradeClosedAt = now;
            startedAt.put(Checkpoint.TRADE, now);
        }
        if (watching(Checkpoint.TRADE, now) && activity == scheduled(villager)) {
            passedAt.put(Checkpoint.TRADE, now);
        }
    }

    private boolean watching(Checkpoint checkpoint, long now) {
        Long start = startedAt.get(checkpoint);
        return start != null && !passedAt.containsKey(checkpoint) && now - start <= timeout(checkpoint);
    }

    private static int timeout(Checkpoint checkpoint) {
        return switch (checkpoint) {
            case WORK, MEET, REST -> PHASE_TIMEOUT_TICKS;
            case PANIC -> PANIC_TIMEOUT_TICKS;
            case CALM -> CALM_TIMEOUT_TICKS;
            case TRADE -> TRADE_RESUME_TIMEOUT_TICKS;
        };
    }

    @Nullable
    private Activity scheduled(Villager villager) {
        return villager.getBrain().getSchedule().getActivityAt((int) (level.getDayTime() % 24_000L));
    }

    private static double distance(Villager villager, BlockPos pos) {
        return villager.position().distanceTo(Vec3.atBottomCenterOf(pos));
    }

    public Status status(Checkpoint checkpoint) {
        if (passedAt.containsKey(checkpoint)) {
            return Status.PASS;
        }
        if (checkpoint == Checkpoint.TRADE && tradingActor != null && tradeClosedAt < 0L) {
            return Status.WAITING;
        }
        Long start = startedAt.get(checkpoint);
        if (start == null) {
            return Status.NOT_RUN;
        }
        return level.getGameTime() - start > timeout(checkpoint) ? Status.FAIL : Status.WAITING;
    }

    /** The scenario result: elapsed ticks and each checkpoint's status. Read-only. */
    public List<String> result() {
        List<String> lines = new ArrayList<>();
        long now = level.getGameTime();
        lines.add(ID + ": " + (released() ? "released " + (now - releasedAt) + " ticks ago" : "held, not started")
                + ", day time " + (level.getDayTime() % 24_000L));
        for (Checkpoint checkpoint : Checkpoint.values()) {
            Status status = status(checkpoint);
            StringBuilder line = new StringBuilder(checkpoint.id()).append(' ').append(status);
            Long start = startedAt.get(checkpoint);
            Long passed = passedAt.get(checkpoint);
            if (passed != null && start != null) {
                line.append(" after ").append(passed - start).append(" ticks");
            } else if (status == Status.WAITING && start != null) {
                line.append(' ').append(now - start).append('/').append(timeout(checkpoint)).append(" ticks");
            }
            if (checkpoint != Checkpoint.TRADE && start != null && passed == null) {
                List<String> missing = new ArrayList<>();
                for (Actor actor : actors) {
                    if (!reached.get(checkpoint).contains(actor.villager().getUUID())) {
                        missing.add(actor.role());
                    }
                }
                line.append(" missing ").append(missing);
            }
            if (checkpoint == Checkpoint.TRADE && status == Status.NOT_RUN) {
                line.append(" (no trade opened yet)");
            }
            lines.add(line.toString());
        }
        return lines;
    }

    public boolean allPassed() {
        for (Checkpoint checkpoint : Checkpoint.values()) {
            if (status(checkpoint) != Status.PASS) {
                return false;
            }
        }
        return true;
    }

    /** One read-only line per actor, or only for {@code target} when it is an actor. */
    public List<String> inspect(@Nullable UUID target) {
        List<String> lines = new ArrayList<>();
        VillageRegistryData registry = VillageRegistryData.get(level);
        for (Actor actor : actors) {
            Villager villager = actor.villager();
            if (target != null && !target.equals(villager.getUUID())) {
                continue;
            }
            VillageRecord village = registry.getVillageFor(villager.blockPosition());
            lines.add(actor.role()
                    + " alive=" + villager.isAlive()
                    + " heldNoAi=" + villager.isNoAi()
                    + " activity=" + villager.getBrain().getActiveNonCoreActivity().map(Activity::getName).orElse("none")
                    + " scheduled=" + Optional.ofNullable(scheduled(villager)).map(Activity::getName).orElse("none")
                    + " sleeping=" + villager.isSleeping()
                    + " trading=" + villager.isTrading()
                    + " pos=" + villager.blockPosition().toShortString()
                    + " jobSite=" + memoryPos(villager, MemoryModuleType.JOB_SITE)
                    + " home=" + memoryPos(villager, MemoryModuleType.HOME)
                    + " meetingPoint=" + memoryPos(villager, MemoryModuleType.MEETING_POINT)
                    + " walkTarget=" + villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                    .map(WalkTarget::getTarget).map(target1 -> target1.currentBlockPosition().toShortString())
                    .orElse("none")
                    + " toJobSite=" + String.format(Locale.ROOT, "%.1f", distance(villager, actor.jobSite()))
                    + " toBell=" + String.format(Locale.ROOT, "%.1f", distance(villager, origin.offset(BELL)))
                    + " village=" + (village == null ? "none"
                    : village.getVillageId().equals(villageId) ? "scene" : village.getVillageId()));
        }
        return lines;
    }

    private static String memoryPos(Villager villager, MemoryModuleType<GlobalPos> memory) {
        return villager.getBrain().getMemory(memory).map(pos -> pos.pos().toShortString()).orElse("none");
    }

    /** Removes the actors, the hostile, the village record, and every block above the floor. */
    public void reset() {
        if (hostile != null) {
            hostile.discard();
            hostile = null;
        }
        for (Actor actor : actors) {
            actor.villager().discard();
        }
        VillageRegistryData.get(level).removeScenarioVillage(villageId);
        for (BlockPos pos : BlockPos.betweenClosed(origin.above(), origin.offset(SIZE - 1, 3, SIZE - 1))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }
}
