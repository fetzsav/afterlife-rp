package com.afterlife.rp.shared.economy;

import java.math.BigInteger;
import java.security.SecureRandom;

/** Italian-format IBAN generation and validation (ISO 13616 mod-97). */
public final class Iban {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Iban() {}

    /**
     * Builds a valid Italian IBAN: IT + 2 check digits + CIN + ABI(5) + CAB(5)
     * + 12-digit account number. Uniqueness is enforced by the database; the
     * caller retries on collision.
     */
    public static String generate(String abi, String cab) {
        StringBuilder accountNumber = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            accountNumber.append(RANDOM.nextInt(10));
        }
        String bban = "X" + abi + cab + accountNumber;
        String checkDigits = computeCheckDigits("IT", bban);
        return "IT" + checkDigits + bban;
    }

    public static boolean isValid(String iban) {
        if (iban == null || iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        return numericValue(rearranged).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static String computeCheckDigits(String countryCode, String bban) {
        BigInteger value = numericValue(bban + countryCode + "00");
        int check = 98 - value.mod(BigInteger.valueOf(97)).intValue();
        return check < 10 ? "0" + check : String.valueOf(check);
    }

    private static BigInteger numericValue(String input) {
        StringBuilder numeric = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (Character.isLetter(c)) {
                numeric.append(Character.toUpperCase(c) - 'A' + 10);
            } else {
                return BigInteger.ZERO;
            }
        }
        return new BigInteger(numeric.toString());
    }
}
