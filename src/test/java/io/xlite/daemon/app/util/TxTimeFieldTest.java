package io.xlite.daemon.app.util;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Time-field wire adapter: standard {@code version + vin + vout +
 * locktime} gains {@code nTime} after nVersion for flagged coins, and
 * spending nTime must cover every parent nTime (input-time consensus
 * rule). Vectors are real mainnet bytes: the daemon's rejected 225-byte
 * send (standard layout) and the nTime fields of its two live parents.
 */
class TxTimeFieldTest {

    /** The rejected send, standard-serialized (225 bytes, ends locktime 0). */
    private static final String REJECTED_SEND_HEX =
            "01000000" + "01"
            + "f6240298bc4ecebc891319610e25e746e42f40c5332d533217ad6fd0e0413fa6" + "01000000" + "6a"
            + "47304402205aa3a980c7ec99deae174caf47e6316d5ea2c05536bb364e5b177f302c3a7ef7"
            + "0220454694a563cea617e62c457993635b50d9503fe2cf6939f3a1108fc7877c39f681"
            + "210394f396dcb760308521727936401c1f722ea5cfbdb7f6ba5dcbfe70a6ce64b054"
            + "ffffffff" + "02"
            + "1027000000000000" + "19" + "76a9141541df5130309fa2b74eed8eff1b418b2338eed288ac"
            + "38102b0000000000" + "19" + "76a914abea45e4519baa43b1b2683558c8fca2c978641b88ac"
            + "00000000";

    /** First 12 bytes of live parent a63f41e0 (nTime 1790537953). */
    private static final String PARENT_A63F_PREFIX = "01000000e170b96a01be105f";

    /** First 8 bytes of live parent 2d05f943 (nTime 1790537395). */
    private static final String PARENT_2D05_PREFIX = "01000000b36eb96a";

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2)
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        return out;
    }

    @Test
    void testInsertPlacesNTimeAfterVersion() {
        byte[] standard = hex(REJECTED_SEND_HEX);
        // Live parent nTime 1790537953 -> LE e1 70 b9 6a.
        byte[] wired = TxTimeField.insertNTime(standard, 1790537953L);
        assertEquals(standard.length + 4, wired.length);
        // version preserved, nTime LE32 follows, rest shifted by 4
        assertArrayEquals(
                new byte[] {0x01, 0x00, 0x00, 0x00, (byte) 0xe1, 0x70, (byte) 0xb9, 0x6a},
                Arrays.copyOf(wired, 8));
        assertArrayEquals(
                Arrays.copyOfRange(standard, 4, standard.length),
                Arrays.copyOfRange(wired, 8, wired.length));
    }

    @Test
    void testStripInvertsInsert() {
        byte[] standard = hex(REJECTED_SEND_HEX);
        assertArrayEquals(standard,
                TxTimeField.stripNTime(TxTimeField.insertNTime(standard, 1790537953L)));
    }

    @Test
    void testStripRejectsShortInput() {
        assertThrows(IllegalArgumentException.class,
                () -> TxTimeField.stripNTime(new byte[12]));
        assertThrows(IllegalArgumentException.class,
                () -> TxTimeField.stripNTime(null));
    }

    @Test
    void testInsertRejectsShortInputAndBadNTime() {
        assertThrows(IllegalArgumentException.class,
                () -> TxTimeField.insertNTime(new byte[8], 1L));
        assertThrows(IllegalArgumentException.class,
                () -> TxTimeField.insertNTime(null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> TxTimeField.insertNTime(hex(REJECTED_SEND_HEX), -1L));
        assertThrows(IllegalArgumentException.class,
                () -> TxTimeField.insertNTime(hex(REJECTED_SEND_HEX), 0x1FFFFFFFFL));
    }

    @Test
    void testParentNTimeReadsLiveParents() {
        assertEquals(1790537953L, TxTimeField.parentNTime(hex(PARENT_A63F_PREFIX)));
        assertEquals(1790537395L, TxTimeField.parentNTime(hex(PARENT_2D05_PREFIX)));
    }

    @Test
    void testParentNTimeRejectsShortInput() {
        assertThrows(IllegalArgumentException.class,
                () -> TxTimeField.parentNTime(new byte[7]));
    }

    @Test
    void testSpendNTimeCoversFutureParent() {
        // Input-time rule: a future-dated parent (sender clock skew) forces
        // nTime above wall-clock, or the node rejects input-time > tx-time.
        assertEquals(1890540887L,
                TxTimeField.spendNTime(1790540887L, Arrays.asList(1890540887L, 1790537395L)));
    }

    @Test
    void testSpendNTimeFallsBackToNow() {
        assertEquals(1790540887L,
                TxTimeField.spendNTime(1790540887L, Arrays.asList(1790537395L)));
        assertEquals(1790540887L,
                TxTimeField.spendNTime(1790540887L, Collections.emptyList()));
        assertEquals(1790540887L, TxTimeField.spendNTime(1790540887L, null));
    }
}
