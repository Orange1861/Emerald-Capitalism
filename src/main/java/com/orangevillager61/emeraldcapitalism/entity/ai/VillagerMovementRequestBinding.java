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

    public boolean submit(Villager villager, MovementRequestSpec spec) {
        MovementRequestSubmission submission;
        if (handle != null) {
            if (!MovementRequestManager.isActive(villager, handle)) {
                handle = null;
                return false;
            }
            submission = MovementRequestManager.maintain(villager, handle, spec);
        } else {
            submission = MovementRequestManager.submit(villager, spec);
        }
        if (!submission.accepted()) {
            handle = null;
            return false;
        }
        handle = submission.handle();
        return true;
    }

    public boolean isActive(Villager villager) {
        return MovementRequestManager.isActive(villager, handle);
    }

    public void cancel(Villager villager, boolean clearLookTarget) {
        if (handle != null) {
            MovementRequestManager.cancel(villager, handle, clearLookTarget);
            handle = null;
        }
    }

    @Nullable
    public MovementRequestHandle handle() {
        return handle;
    }
}
