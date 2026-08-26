package io.cloudchains.app.coinconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses one xbridge conf file into per-section key/value maps.
 *
 * <p>Format (as shipped by blockchain-configuration-files): sections opened by
 * a {@code [TICKER]} line, followed by {@code Key=Value} lines. Blank lines and
 * {@code #}/{@code ;} comments are skipped. Keys keep their original case;
 * lookups are case-sensitive, matching the files as shipped.</p>
 *
 * <p>Parsing fails hard: a UTF-8 BOM is tolerated and stripped, but any other
 * stray or malformed line throws rather than being dropped silently. A section
 * header must be exactly {@code [NAME]} (no nested brackets). Duplicates are
 * deterministic: a repeated key keeps its LAST value; a repeated section name
 * merges into the first occurrence.</p>
 */
public final class XBridgeConfParser {

    public static Map<String, Map<String, String>> parse(String contents) {
        if (contents.startsWith("\uFEFF"))
            contents = contents.substring(1);
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String current = null;
        int lineNo = 0;
        for (String rawLine : contents.split("\\r?\\n", -1)) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";"))
                continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                if (current.isEmpty() || current.indexOf('[') >= 0 || current.indexOf(']') >= 0)
                    throw new IllegalStateException("Malformed section header at line " + lineNo
                            + ": '" + rawLine + "'");
                sections.putIfAbsent(current, new LinkedHashMap<>());
                continue;
            }
            if (current == null)
                throw new IllegalStateException(
                        "Malformed line " + lineNo + " outside any section: '" + rawLine + "'");
            int eq = line.indexOf('=');
            if (eq <= 0)
                throw new IllegalStateException(
                        "Malformed line " + lineNo + " in [" + current + "]: '" + rawLine + "'");
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            sections.get(current).put(key, value);
        }
        return sections;
    }

    public static Map<String, Map<String, String>> parseFile(Path file) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
