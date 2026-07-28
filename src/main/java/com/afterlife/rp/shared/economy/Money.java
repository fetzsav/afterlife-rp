package com.afterlife.rp.shared.economy;

/** Currency helpers. All amounts are integer minor units (cents, BIGINT). */
public final class Money {

    private Money() {}

    /** Formats cents as Italian-style euros: 123456 -> "1.234,56 €". */
    public static String format(long cents) {
        long absolute = Math.abs(cents);
        long whole = absolute / 100;
        long fraction = absolute % 100;
        StringBuilder grouped = new StringBuilder();
        String digits = Long.toString(whole);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append('.');
            }
            grouped.append(digits.charAt(i));
        }
        return (cents < 0 ? "-" : "") + grouped + "," + (fraction < 10 ? "0" : "") + fraction + " €";
    }

    /** Parses a whole-euro user input ("250") into cents; null when invalid or non-positive. */
    public static Long parseWholeEuros(String input) {
        if (input == null || input.isBlank() || input.length() > 12) {
            return null;
        }
        try {
            long euros = Long.parseLong(input.strip());
            if (euros <= 0) {
                return null;
            }
            return euros * 100;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
