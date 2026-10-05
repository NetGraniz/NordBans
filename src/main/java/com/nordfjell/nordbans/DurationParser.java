package com.nordfjell.nordbans;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DurationParser {
    private static final Pattern FORMAT = Pattern.compile("^([1-9][0-9]*)([mhd])$", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    static Optional<Duration> parse(String input) {
        Matcher matcher = FORMAT.matcher(input == null ? "" : input.trim());
        if (!matcher.matches()) return Optional.empty();
        try {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "m" -> Optional.of(Duration.ofMinutes(amount));
                case "h" -> Optional.of(Duration.ofHours(amount));
                case "d" -> Optional.of(Duration.ofDays(amount));
                default -> Optional.empty();
            };
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
