package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.shared.economy.DenominationBreakdown;
import com.afterlife.rp.shared.economy.Iban;
import com.afterlife.rp.shared.economy.Money;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EconomyUnitTest {

    private static final List<Long> NOTES =
            List.of(50000L, 20000L, 10000L, 5000L, 2000L, 1000L, 500L);

    @Test
    void moneyFormatsItalianStyle() {
        assertEquals("1.234,56 €", Money.format(123456));
        assertEquals("0,05 €", Money.format(5));
        assertEquals("-5,00 €", Money.format(-500));
        assertEquals("1.000.000,00 €", Money.format(100_000_000L));
    }

    @Test
    void moneyParsesOnlyPositiveWholeEuros() {
        assertEquals(25000L, Money.parseWholeEuros("250"));
        assertNull(Money.parseWholeEuros("0"));
        assertNull(Money.parseWholeEuros("-5"));
        assertNull(Money.parseWholeEuros("12.5"));
        assertNull(Money.parseWholeEuros("abc"));
    }

    @Test
    void generatedIbansPassMod97Validation() {
        for (int i = 0; i < 200; i++) {
            String iban = Iban.generate("05428", "11101");
            assertTrue(Iban.isValid(iban), iban + " must be valid");
            assertEquals(27, iban.length(), "Italian IBAN length");
        }
    }

    @Test
    void knownIbanValidatesAndTamperedOneFails() {
        assertTrue(Iban.isValid("IT60X0542811101000000123456"));
        assertFalse(Iban.isValid("IT61X0542811101000000123456"));
        assertFalse(Iban.isValid(null));
        assertFalse(Iban.isValid("IT60"));
    }

    @Test
    void breakdownUsesLargestNotesFirst() {
        Map<Long, Integer> result = DenominationBreakdown.breakdown(17500, NOTES);
        assertEquals(Map.of(10000L, 1, 5000L, 1, 2000L, 1, 500L, 1), result);
        assertEquals(4, DenominationBreakdown.totalNotes(result));
    }

    @Test
    void unrepresentableAmountsAreRejected() {
        assertNull(DenominationBreakdown.breakdown(300, NOTES));
        assertNull(DenominationBreakdown.breakdown(0, NOTES));
        assertNull(DenominationBreakdown.breakdown(-500, NOTES));
        assertNull(DenominationBreakdown.breakdown(500, List.of()));
    }
}
