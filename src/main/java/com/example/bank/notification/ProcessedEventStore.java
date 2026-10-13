package com.example.bank.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * "Bu consumer bu event'i işledi mi" kaydı. Idempotent consumer'ın kalbi.
 *
 * <p>INSERT ... ON CONFLICT DO NOTHING atomiktir: aynı event'i aynı anda iki thread işlemeye çalışsa bile
 * sadece biri 1 satır ekler, diğeri 0 görür. İş yapan transaction ile aynı transaction'da çağrılmalı:
 * iş hata verip geri alınırsa bu kayıt da geri alınır ve event tekrar denenebilir.
 */
@Component
public class ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public ProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true: ilk kez işleniyor, false: daha önce işlenmiş (tekrar teslim) */
    public boolean markProcessed(String consumer, UUID eventId) {
        int inserted = jdbc.update(
                "insert into processed_event (consumer, event_id) values (?, ?) on conflict do nothing",
                consumer, eventId);
        return inserted == 1;
    }

    public long countFor(String consumer, UUID eventId) {
        Long count = jdbc.queryForObject(
                "select count(*) from processed_event where consumer = ? and event_id = ?",
                Long.class, consumer, eventId);
        return count == null ? 0 : count;
    }
}
