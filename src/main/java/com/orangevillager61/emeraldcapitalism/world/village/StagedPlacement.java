package com.orangevillager61.emeraldcapitalism.world.village;

import com.orangevillager61.emeraldcapitalism.EmeraldCapitalism;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Building placement split into bounded steps, one per {@link #processStep()} call,
 * so a large structure spreads its block writes over several server ticks.
 * A step that returns false or throws stops the sequence and runs the failure
 * handler once. The sequence holds no static state and is dropped with its owner.
 */
public final class StagedPlacement {
    private final String description;
    private final List<BooleanSupplier> steps;
    private final Runnable onFailure;
    private int nextStep;
    private boolean failed;

    private StagedPlacement(String description, List<BooleanSupplier> steps, Runnable onFailure) {
        this.description = description;
        this.steps = List.copyOf(steps);
        this.onFailure = onFailure;
    }

    public static Builder builder(String description) {
        return new Builder(description);
    }

    /** @return true once every step has run or one has failed */
    public boolean processStep() {
        if (isDone()) {
            return true;
        }
        BooleanSupplier step = steps.get(nextStep++);
        boolean stepSucceeded;
        try {
            stepSucceeded = step.getAsBoolean();
        } catch (RuntimeException exception) {
            EmeraldCapitalism.LOGGER.error("[ECAP] {} failed at step {}", description, nextStep, exception);
            stepSucceeded = false;
        }
        if (!stepSucceeded) {
            failed = true;
            onFailure.run();
        }
        return isDone();
    }

    /** Runs every remaining step now, for synchronous callers and server shutdown. */
    public boolean finishNow() {
        while (!processStep()) {
            // Each call advances one step; the loop ends after the last or a failure.
        }
        return succeeded();
    }

    public boolean succeeded() {
        return !failed && nextStep >= steps.size();
    }

    private boolean isDone() {
        return failed || nextStep >= steps.size();
    }

    /**
     * Splits a template footprint into the chunk columns it covers. Placing one
     * slice per step bounds each tick's writes the way vanilla places a
     * structure piece one generating chunk at a time.
     */
    static List<BoundingBox> chunkSlices(BoundingBox box) {
        List<BoundingBox> slices = new ArrayList<>();
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                slices.add(new BoundingBox(
                        Math.max(box.minX(), chunkX << 4), box.minY(), Math.max(box.minZ(), chunkZ << 4),
                        Math.min(box.maxX(), (chunkX << 4) + 15), box.maxY(),
                        Math.min(box.maxZ(), (chunkZ << 4) + 15)));
            }
        }
        return slices;
    }

    /**
     * Adds one step per chunk slice that places only the template blocks and
     * entities inside that slice. Processors draw their randomness per block
     * position, so the sliced result matches a single full placement.
     *
     * <p>The template stores each block's final shape, so the placement uses a
     * known shape, as vanilla jigsaw pieces do. Otherwise the end of each slice
     * would re-check its edge blocks against slices not yet placed and drop
     * attached blocks such as wall torches.</p>
     */
    public static void addTemplateSlices(Builder builder, ServerLevel level, StructureTemplate template,
                                         BlockPos origin, StructurePlaceSettings settings, long randomSeed,
                                         BoundingBox footprint, Runnable afterFirstSlice) {
        settings.setKnownShape(true);
        List<BoundingBox> slices = chunkSlices(footprint);
        for (int index = 0; index < slices.size(); index++) {
            BoundingBox slice = slices.get(index);
            boolean first = index == 0;
            builder.step(() -> {
                settings.setBoundingBox(slice);
                boolean placed = template.placeInWorld(level, origin, origin, settings,
                        RandomSource.create(randomSeed), 2);
                if (placed && first) {
                    afterFirstSlice.run();
                }
                return placed;
            });
        }
    }

    public static final class Builder {
        private final String description;
        private final List<BooleanSupplier> steps = new ArrayList<>();
        private Runnable onFailure = () -> { };

        private Builder(String description) {
            this.description = description;
        }

        public Builder step(BooleanSupplier step) {
            steps.add(step);
            return this;
        }

        public Builder step(Runnable step) {
            steps.add(() -> {
                step.run();
                return true;
            });
            return this;
        }

        public Builder onFailure(Runnable handler) {
            this.onFailure = handler;
            return this;
        }

        public StagedPlacement build() {
            return new StagedPlacement(description, steps, onFailure);
        }
    }
}
