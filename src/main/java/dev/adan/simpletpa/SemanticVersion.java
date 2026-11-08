package dev.adan.simpletpa;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

record SemanticVersion(List<BigInteger> numbers, List<String> prerelease) implements Comparable<SemanticVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    static SemanticVersion parse(String text) {
        var matcher = FORMAT.matcher(text);
        if (!matcher.matches()) throw new IllegalArgumentException("Expected a semantic version: " + text);
        List<String> pre = matcher.group(4) == null ? List.of() : List.of(matcher.group(4).split("\\."));
        for (String identifier : pre) {
            if (identifier.matches("[0-9]+") && identifier.length() > 1 && identifier.startsWith("0")) {
                throw new IllegalArgumentException("Numeric prerelease identifiers cannot have leading zeros");
            }
        }
        return new SemanticVersion(List.of(new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3))), pre);
    }

    @Override public int compareTo(SemanticVersion other) {
        for (int i = 0; i < numbers.size(); i++) {
            int comparison = numbers.get(i).compareTo(other.numbers.get(i));
            if (comparison != 0) return comparison;
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return prerelease.isEmpty() == other.prerelease.isEmpty() ? 0 : prerelease.isEmpty() ? 1 : -1;
        }
        for (int i = 0; i < Math.min(prerelease.size(), other.prerelease.size()); i++) {
            String a = prerelease.get(i), b = other.prerelease.get(i);
            boolean numericA = a.matches("[0-9]+"), numericB = b.matches("[0-9]+");
            int comparison = numericA && numericB ? new BigInteger(a).compareTo(new BigInteger(b))
                    : numericA != numericB ? (numericA ? -1 : 1) : a.compareTo(b);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }
}
