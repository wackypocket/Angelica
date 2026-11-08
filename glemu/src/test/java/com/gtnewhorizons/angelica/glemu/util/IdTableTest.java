package com.gtnewhorizons.angelica.glemu.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdTableTest {

    @Test
    void removingByValueClearsEveryEntryHoldingIt() {
        final IdTable<String> table = new IdTable<>();
        final String shared = "shared", other = "other";
        table.put(1, shared);
        table.put(2, shared);
        table.put(3, other);
        table.removeValue(shared);
        assertNull(table.get(1));
        assertNull(table.get(2));
        assertFalse(table.containsKey(1));
        assertSame(other, table.get(3));
    }

    @Test
    void valuesCannotRemoveBehindTheDenseArray() {
        final IdTable<String> table = new IdTable<>();
        table.put(1, "value");
        assertThrows(UnsupportedOperationException.class, () -> table.values().removeIf(v -> true));
    }
}
