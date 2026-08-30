package com.orangevillager61.emeraldcapitalism.test;

import com.orangevillager61.emeraldcapitalism.Config;
import com.orangevillager61.emeraldcapitalism.block.entity.BankBlockEntity;
import com.orangevillager61.emeraldcapitalism.entity.EmeraldGolem;
import com.orangevillager61.emeraldcapitalism.registry.ECAPBlocks;
import com.orangevillager61.emeraldcapitalism.registry.ECAPEntityTypes;
import com.orangevillager61.emeraldcapitalism.util.BankEmployeeLookup;
import com.orangevillager61.emeraldcapitalism.world.bank.BankReputationData;
import com.orangevillager61.emeraldcapitalism.world.village.VillageGovernance;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRecord;
import com.orangevillager61.emeraldcapitalism.world.village.VillageRegistryData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("emeraldcapitalism")
@PrefixGameTestTemplate(false)
public final class BankReputationDamageGameTests {

    private BankReputationDamageGameTests() {
    }

    @GameTest(template = "empty_3x3x3")
    public static void bankGolemDamageUsesProportionalPenalty(GameTestHelper helper) {
        boolean previous = Config.proportionalVillagerReputation;
        Config.proportionalVillagerReputation = true;
        try {
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(helper.getLevel());
            if (golem == null) {
                helper.fail("Could not create an emerald golem");
                return;
            }
            golem.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)), 0.0F, 0.0F);
            golem.setBankEmployeePos(helper.absolutePos(new net.minecraft.core.BlockPos(0, 1, 0)));
            helper.getLevel().addFreshEntity(golem);

            golem.hurt(helper.getLevel().damageSources().playerAttack(player), 4.0F);

            helper.assertValueEqual(BankReputationData.get(helper.getLevel()).getReputation(player.getUUID()), -20,
                    "bank employee damage did not use the proportional 5-points-per-HP penalty");
        } finally {
            Config.proportionalVillagerReputation = previous;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void bankGolemDamageUsesFixedPenaltyWhenProportionalConfigIsDisabled(GameTestHelper helper) {
        boolean previous = Config.proportionalVillagerReputation;
        Config.proportionalVillagerReputation = false;
        try {
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(helper.getLevel());
            if (golem == null) {
                helper.fail("Could not create an emerald golem");
                return;
            }
            golem.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)), 0.0F, 0.0F);
            golem.setBankEmployeePos(helper.absolutePos(new net.minecraft.core.BlockPos(0, 1, 0)));
            helper.getLevel().addFreshEntity(golem);

            golem.hurt(helper.getLevel().damageSources().playerAttack(player), 4.0F);

            helper.assertValueEqual(BankReputationData.get(helper.getLevel()).getReputation(player.getUUID()), -10,
                    "disabled proportional bank reputation did not use the fixed -10 penalty");
        } finally {
            Config.proportionalVillagerReputation = previous;
        }
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void contestedGovernorMayKillVaultGolemWithoutVillageOpinionPenalty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
        VillageRecord village = createContestedVillage(level, bankPos, player.getUUID());

        EmeraldGolem golem = ECAPEntityTypes.EMERALD_GOLEM.get().create(level);
        if (golem == null) {
            helper.fail("Could not create an emerald golem");
            return;
        }
        golem.moveTo(helper.absolutePos(new BlockPos(2, 1, 1)), 0.0F, 0.0F);
        golem.setBankEmployeePos(bankPos);
        level.addFreshEntity(golem);

        golem.hurt(level.damageSources().playerAttack(player), 100.0F);

        helper.assertValueEqual(village.getOpinionModifier(player.getUUID()), 0,
                "contested governor killing a vault golem changed village opinion");
        helper.assertValueEqual(BankReputationData.get(level).getReputation(player.getUUID()),
                BankReputationData.GOLEM_KILLED_PENALTY - 500,
                "vault golem damage and death did not still anger the bank");
        helper.succeed();
    }

    @GameTest(template = "empty_3x3x3")
    public static void contestedGovernorMayKillBankEmployeeWithoutVillageOpinionPenalty(GameTestHelper helper) {
        boolean previous = Config.proportionalVillagerReputation;
        Config.proportionalVillagerReputation = true;
        try {
            ServerLevel level = helper.getLevel();
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            BlockPos bankPos = helper.absolutePos(new BlockPos(1, 1, 1));
            helper.setBlock(new BlockPos(1, 1, 1), ECAPBlocks.BANK.get().defaultBlockState());
            BankBlockEntity bank = (BankBlockEntity) level.getBlockEntity(bankPos);
            if (bank == null) {
                helper.fail("Bank block entity was not created");
                return;
            }
            VillageRecord village = createContestedVillage(level, bankPos, player.getUUID());

            Villager villager = EntityType.VILLAGER.create(level);
            if (villager == null) {
                helper.fail("Could not create a bank employee villager");
                return;
            }
            villager.moveTo(helper.absolutePos(new BlockPos(1, 1, 2)), 0.0F, 0.0F);
            level.addFreshEntity(villager);
            bank.registerSpawnedEmployee(villager);
            if (!bank.isEmployee(villager.getUUID())) {
                helper.fail("Could not register the villager as a bank employee");
                return;
            }
            helper.assertTrue(BankEmployeeLookup.findEmployeeVillage(level, villager) == village,
                    "bank employee village lookup did not resolve the contested village");
            helper.assertTrue(VillageGovernance.isContestedGovernor(level, village, player.getUUID()),
                    "test candidate was not recognized as contested");

            int opinionBefore = villager.getPlayerReputation(player);
            villager.hurt(level.damageSources().playerAttack(player), 100.0F);

            helper.assertValueEqual(villager.getPlayerReputation(player), opinionBefore,
                    "contested governor killing a bank employee changed village opinion");
            helper.assertValueEqual(BankReputationData.get(level).getReputation(player.getUUID()), -500,
                    "bank employee death did not still anger the bank through damage reputation");
            helper.assertValueEqual(village.getOpinionModifier(player.getUUID()), 0,
                    "bank employee death changed the village action modifier");
        } finally {
            Config.proportionalVillagerReputation = previous;
        }
        helper.succeed();
    }

    private static VillageRecord createContestedVillage(ServerLevel level, BlockPos bankPos, UUID candidateId) {
        VillageRegistryData registry = VillageRegistryData.get(level);
        UUID villageId = UUID.randomUUID();
        VillageRecord village = registry.getOrCreateVillage(villageId, bankPos,
                new AABB(bankPos).inflate(8.0D));
        registry.registerBankPosition(villageId, bankPos);
        if (!village.becomeGovernorCandidate(candidateId, 100)) {
            throw new IllegalStateException("could not establish contested governor candidate");
        }
        return village;
    }
}
