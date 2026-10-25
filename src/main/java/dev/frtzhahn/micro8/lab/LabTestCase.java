package dev.frtzhahn.micro8.lab;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Immutable deterministic test case specification for a MICRO-8 Lab Challenge.
 */
public record LabTestCase(
        int caseId,
        int labId,
        int caseOrder,
        String description,
        Map<Integer, Integer> inputRam,
        int expectedOutReg,
        int expectedRegA,
        Map<Integer, Integer> expectedRam
) {

    public LabTestCase {
        Objects.requireNonNull(description, "description must not be null");
        inputRam = normalizeUnsignedByteMap(inputRam);
        expectedRam = normalizeUnsignedByteMap(expectedRam);
        if (expectedOutReg >= 0) {
            expectedOutReg &= 0xFF;
        }
        if (expectedRegA >= 0) {
            expectedRegA &= 0xFF;
        }
    }

    public static Map<Integer, Integer> parseRamSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return Map.of();
        }
        Map<Integer, Integer> map = new LinkedHashMap<>();
        String[] entries = spec.split(",");
        for (String rawEntry : entries) {
            String trimmed = rawEntry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx <= 0 || eqIdx == trimmed.length() - 1) {
                throw new IllegalArgumentException("Invalid RAM spec entry: " + trimmed);
            }
            int addr = parseUnsignedByte(trimmed.substring(0, eqIdx).trim());
            int val = parseUnsignedByte(trimmed.substring(eqIdx + 1).trim());
            map.put(addr, val);
        }
        return Collections.unmodifiableMap(map);
    }

    public static String formatRamSpec(Map<Integer, Integer> ramMap) {
        if (ramMap == null || ramMap.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(",");
        for (Map.Entry<Integer, Integer> entry : ramMap.entrySet()) {
            int addr = entry.getKey() & 0xFF;
            int val = entry.getValue() & 0xFF;
            joiner.add(String.format("0x%02X=0x%02X", addr, val));
        }
        return joiner.toString();
    }

    private static int parseUnsignedByte(String token) {
        String s = token.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseInt(s.substring(2), 16) & 0xFF;
        }
        if (s.startsWith("$")) {
            return Integer.parseInt(s.substring(1), 16) & 0xFF;
        }
        return Integer.parseInt(s, 10) & 0xFF;
    }

    private static Map<Integer, Integer> normalizeUnsignedByteMap(Map<Integer, Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : raw.entrySet()) {
            normalized.put(entry.getKey() & 0xFF, entry.getValue() & 0xFF);
        }
        return Collections.unmodifiableMap(normalized);
    }
}
