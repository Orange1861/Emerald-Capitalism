package com.orangevillager61.emeraldcapitalism.world.bank;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankAccountDataCodecTest {

    @Test
    void codecRoundTripPreservesEmptyAndMultipleBalances() {
        BankAccountData empty = BankAccountData.CODEC.parse(NbtOps.INSTANCE,
                BankAccountData.CODEC.encodeStart(NbtOps.INSTANCE, new BankAccountData()).result().orElseThrow())
                .result().orElseThrow();
        assertTrue(empty.getBalances().isEmpty());

        BankAccountData original = new BankAccountData();
        UUID zero = UUID.randomUUID();
        UUID positive = UUID.randomUUID();
        UUID negative = UUID.randomUUID();
        original.openAccount(zero);
        original.openAccount(positive);
        original.openAccount(negative);
        original.deposit(positive, 17);
        original.withdraw(negative, 23);
        original.generateBankName();

        BankAccountData restored = BankAccountData.CODEC.parse(NbtOps.INSTANCE,
                        BankAccountData.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow())
                .result().orElseThrow();
        assertEquals(0, restored.getBalance(zero));
        assertEquals(17, restored.getBalance(positive));
        assertEquals(-23, restored.getBalance(negative));
        assertEquals("Bank 2", restored.generateBankName());
    }

    @Test
    void currentSavedDataNbtAdapterRoundTripsTheCodec() {
        BankAccountData original = new BankAccountData();
        UUID villager = UUID.randomUUID();
        original.openAccount(villager);
        original.deposit(villager, 5);
        original.withdraw(villager, 8);
        original.generateBankName();

        CompoundTag saved = original.save(new CompoundTag(), null);
        BankAccountData restored = BankAccountData.load(saved, null);

        assertEquals(-3, restored.getBalance(villager));
        assertEquals("Bank 2", restored.generateBankName());
    }

    @Test
    void durableMutationsRejectInvalidAmountsAndKeepDirtyBehavior() {
        BankAccountData data = new BankAccountData();
        UUID villager = UUID.randomUUID();

        data.openAccount(villager);
        assertTrue(data.isDirty());

        data.setDirty(false);
        data.openAccount(villager);
        assertFalse(data.isDirty());

        assertThrows(IllegalArgumentException.class, () -> data.deposit(villager, 0));
        assertThrows(IllegalArgumentException.class, () -> data.withdraw(villager, -1));
        assertFalse(data.isDirty());

        data.deposit(villager, 4);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.withdraw(villager, 2);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.generateBankName();
        assertTrue(data.isDirty());

        assertThrows(UnsupportedOperationException.class,
                () -> data.getBalances().put(UUID.randomUUID(), 1));
    }

    @Test
    void accountMutationsRejectUnknownOrNullAccounts() {
        BankAccountData data = new BankAccountData();

        assertThrows(IllegalStateException.class,
                () -> data.deposit(UUID.randomUUID(), 1));
        assertThrows(NullPointerException.class,
                () -> data.withdraw(null, 1));
        assertThrows(NullPointerException.class,
                () -> data.getBalance(null));
    }

    @Test
    void oversizedPersistedAccountCollectionIsRejectedAndPartialRecoveryRemainsBounded() {
        CompoundTag root = new CompoundTag();
        ListTag accounts = new ListTag();
        for (int i = 0; i <= BankAccountData.MAX_PERSISTED_ACCOUNTS; i++) {
            CompoundTag account = new CompoundTag();
            account.putUUID("uuid", new UUID(0L, i));
            account.putInt("balance", i);
            accounts.add(account);
        }
        root.put("accounts", accounts);

        assertTrue(BankAccountData.CODEC.parse(NbtOps.INSTANCE, root).error().isPresent());
        assertTrue(BankAccountData.load(root, null).getBalances().size()
                <= BankAccountData.MAX_PERSISTED_ACCOUNTS);
    }

    @Test
    void malformedAndDuplicateAccountsDoNotDiscardValidBalances() {
        UUID account = UUID.randomUUID();
        CompoundTag root = new CompoundTag();
        ListTag accounts = new ListTag();

        CompoundTag valid = new CompoundTag();
        valid.putUUID("uuid", account);
        valid.putInt("balance", 17);
        accounts.add(valid);

        CompoundTag duplicate = valid.copy();
        duplicate.putInt("balance", 99);
        accounts.add(duplicate);

        CompoundTag malformed = new CompoundTag();
        malformed.putUUID("uuid", UUID.randomUUID());
        malformed.putString("balance", "not-an-int");
        accounts.add(malformed);
        root.put("accounts", accounts);

        BankAccountData restored = BankAccountData.CODEC.parse(NbtOps.INSTANCE, root)
                .result().orElseThrow();
        assertEquals(1, restored.getBalances().size());
        assertEquals(17, restored.getBalance(account));
    }
}
