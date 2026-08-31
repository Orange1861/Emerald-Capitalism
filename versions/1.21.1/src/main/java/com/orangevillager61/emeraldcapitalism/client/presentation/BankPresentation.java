package com.orangevillager61.emeraldcapitalism.client.presentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Pure preparation for bank account and linked-chest display rows. */
public final class BankPresentation {
    private BankPresentation() {
    }

    public record AccountSnapshot(String name, int balance, boolean queued, int queuePosition) {
    }

    public record AccountRow(AccountSnapshot account, boolean separator) {
        public static AccountRow account(AccountSnapshot snapshot) {
            return new AccountRow(snapshot, false);
        }

        public static AccountRow separatorRow() {
            return new AccountRow(null, true);
        }
    }

    public record ChestPosition(int x, int y, int z) {
        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }

    /** Keeps player-only affordability failures ahead of bank-blocked offers. */
    public static <T> List<T> sortMarketEntries(List<T> entries,
                                                Predicate<T> unavailableForBank) {
        List<T> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(entry -> unavailableForBank.test(entry) ? 1 : 0));
        return List.copyOf(sorted);
    }

    public static List<AccountRow> accountRows(List<AccountSnapshot> accounts) {
        List<AccountSnapshot> queued = new ArrayList<>();
        List<AccountSnapshot> regular = new ArrayList<>();
        for (AccountSnapshot account : accounts) {
            (account.queued() ? queued : regular).add(account);
        }
        queued.sort(Comparator.comparingInt(AccountSnapshot::queuePosition));

        List<AccountRow> rows = new ArrayList<>(accounts.size() + 1);
        queued.stream().map(AccountRow::account).forEach(rows::add);
        if (!queued.isEmpty() && !regular.isEmpty()) {
            rows.add(AccountRow.separatorRow());
        }
        regular.stream().map(AccountRow::account).forEach(rows::add);
        return List.copyOf(rows);
    }

    public static List<String> chestLines(List<ChestPosition> positions, int shownLimit, int totalCount) {
        List<String> lines = positions.stream().map(ChestPosition::toString).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (positions.size() < totalCount) {
            lines.add("... (showing " + shownLimit + " of " + totalCount + ")");
        }
        return List.copyOf(lines);
    }
}
