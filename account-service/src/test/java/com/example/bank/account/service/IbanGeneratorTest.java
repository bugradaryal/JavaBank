package com.example.bank.account.service;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IbanGeneratorTest {

    private final IbanGenerator generator = new IbanGenerator();

    @Test
    void generatesValidTurkishIbans() {
        for (long n : new long[]{1L, 1000L, 1001L, 987654321L, 9999999999999999L}) {
            String iban = generator.generate(n);
            assertThat(iban).hasSize(26).startsWith("TR");
            assertThat(isValidMod97(iban)).as(iban).isTrue();
        }
    }

    @Test
    void differentAccountNumbersGiveDifferentIbans() {
        assertThat(generator.generate(1000L)).isNotEqualTo(generator.generate(1001L));
    }

    /** ISO 13616: ilk 4 karakter sona taşınır, harfler sayıya çevrilir, mod 97 == 1 olmalı. */
    private static boolean isValidMod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isLetter(c)) {
                digits.append(c - 'A' + 10);
            } else {
                digits.append(c);
            }
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
