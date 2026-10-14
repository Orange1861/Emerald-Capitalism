package com.orangevillager61.emeraldcapitalism.entity.ai;

import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

/**
 * Per-goal convenience wrapper that retains the current capability token.
 * Each goal instance has its own binding; the manager still arbitrates the
 * entity-wide request and protects cleanup across goal replacement.
 */
public final class VillagerMovementRequestBinding {

    @Nullable
    private MovementRequestHandle handle;
    @Nullable
    private MovementRequestSpec cachedSpec;

    public boolean submit(Villager villager, MovementRequestSpec spec) {
        if (cachedSpec != null && cachedSpec.equals(spec)) {
            spec = cachedSpec;
        }
        MovementRequestSubmission submission;
        if (handle != null) {
            if (!MovementRequestManager.isActive(villager, handle)) {
                handle = null;
                cachedSpec = null;
                return false;
            }
            submission = MovementRequestManager.maintain(villager, handle, spec);
        } else {
            submission = MovementRequestManager.submit(villager, spec);
        }
        if (!submission.accepted()) {
            handle = null;
            cachedSpec = null;
            return false;
        }
        handle = submission.handle();
        cachedSpec = spec;
        return true;
    }

    public boolean isActive(Villager villager) {
        return MovementRequestManager.isActive(villager, handle);
    }

    public void cancel(Villager villager, boolean clearLookTarget) {
        if (handle != null) {
            MovementRequestManager.cancel(villager, handle, clearLookTarget);
            handle = null;
            cachedSpec = null;
        }
    }

    @Nullable
    public MovementRequestHandle handle() {
        return handle;
    }
}
