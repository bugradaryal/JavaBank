package com.example.bank.common.domain;

/**
 * CUSTOMER: müşteri hesabı, eksiye düşemez.
 * CASH: bankanın kasa hesabı (para yatırma/çekme karşı tarafı), eksiye düşebilir.
 * CLEARING: transit (askı) hesap. Saga sırasında "yoldaki" para burada durur: gönderenden düşen para önce buraya,
 * alıcı hesaba geçerken buradan çıkar. Böylece para hiçbir anda "havada" kalmaz ve tüm bakiyelerin toplamı
 * (kasa ve clearing dahil) HER commit'te 0'dır.
 */
public enum AccountKind {
    CUSTOMER,
    CASH,
    CLEARING
}
