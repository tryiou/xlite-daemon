package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XBridgeConfParserTest {

    private static final String SAMPLE =
            "# top comment\n"
                    + "[LTC]\n"
                    + "Title=Litecoin\n"
                    + "  AddressPrefix = 48  \n"
                    + "FeePerByte=10\n"
                    + "; ini-style comment\n"
                    + "\n"
                    + "[BLOCK]\n"
                    + "Title=Blocknet\n"
                    + "AddressPrefix=26\n";

    @Test
    void testSectionsAndKeysParsed() {
        Map<String, Map<String, String>> all = XBridgeConfParser.parse(SAMPLE);
        assertEquals(2, all.size());
        Map<String, String> ltc = all.get("LTC");
        assertEquals("48", ltc.get("AddressPrefix"));
        assertEquals("10", ltc.get("FeePerByte"));
        assertEquals("Litecoin", ltc.get("Title"));
        assertEquals("26", all.get("BLOCK").get("AddressPrefix"));
    }

    @Test
    void testValuesTrimmedCommentsSkipped() {
        Map<String, Map<String, String>> all = XBridgeConfParser.parse(SAMPLE);
        assertFalse(all.get("LTC").containsKey("# top comment"));
        assertFalse(all.get("LTC").containsKey("; ini-style comment"));
        assertEquals("48", all.get("LTC").get("AddressPrefix")); // inner spaces trimmed
    }

    @Test
    void testMalformedLineOutsideSectionThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> XBridgeConfParser.parse("Orphan=1\n[A]\nK=V\n"));
        assertTrue(e.getMessage().contains("outside any section"));
    }

    @Test
    void testMalformedLineInsideSectionThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> XBridgeConfParser.parse("[A]\nJustAK\nK=V\n"));
        assertTrue(e.getMessage().contains("in [A]"));
    }

    @Test
    void testNestedBracketHeaderThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> XBridgeConfParser.parse("[A][B]\nK=V\n"));
        assertTrue(e.getMessage().contains("Malformed section header"), e.getMessage());
    }

    @Test
    void testDuplicateKeysLastWins() {
        Map<String, Map<String, String>> all = XBridgeConfParser.parse("[A]\nK=1\nK=2\n");
        assertEquals("2", all.get("A").get("K"));
    }

    @Test
    void testDuplicateSectionsMerge() {
        Map<String, Map<String, String>> all = XBridgeConfParser.parse("[A]\nK=1\n[A]\nJ=2\n");
        assertEquals(1, all.size());
        assertEquals("1", all.get("A").get("K"));
        assertEquals("2", all.get("A").get("J"));
    }

    @Test
    void testUtf8BomStrippedBeforeFirstSection() {
        Map<String, Map<String, String>> all = XBridgeConfParser.parse("\uFEFF[LTC]\nAddressPrefix=48\n");
        assertEquals("48", all.get("LTC").get("AddressPrefix"));
    }

    @Test
    void testEmptySectionHeaderThrows() {
        assertThrows(IllegalStateException.class, () -> XBridgeConfParser.parse("[]\nK=V\n"));
    }

    @Test
    void testCrlfHandled() {
        Map<String, Map<String, String>> all = XBridgeConfParser.parse("[X]\r\nA=1\r\n");
        assertEquals("1", all.get("X").get("A"));
    }

    @Test
    void testEmptyInputYieldsEmptyMap() {
        assertTrue(XBridgeConfParser.parse("").isEmpty());
    }
}
