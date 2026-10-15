package com.example.bank.account.service;

import org.springframework.stereotype.Component;

import java.math.BigInteger;

/**
 * Türkiye IBAN'ı: TR + 2 kontrol hanesi + 5 banka kodu + 1 rezerv + 16 hesap no = 26 karakter.
 * Kontrol hanesi ISO 13616 mod-97 ile hesaplanır.
 */
@Component
public class IbanGenerator {

    private static final String BANK_CODE = "00999";
    private static final String RESERVED = "0";

    public String generate(long accountNumber) {
        String bban = BANK_CODE + RESERVED + String.format("%016d", accountNumber);
        // "TR" harfleri sayıya çevrilir (T=29, R=27) ve kontrol hanesi yerine 00 konur.
        BigInteger number = new BigInteger(bban + "2927" + "00");
        int check = 98 - number.mod(BigInteger.valueOf(97)).intValue();
        return "TR" + String.format("%02d", check) + bban;
    }
}
