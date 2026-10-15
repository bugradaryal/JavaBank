-- Sabit id'ler: transfer-service bunları yapılandırmadan bilir (app.cash-accounts.*), id 1-3 kasa.
INSERT INTO account (id, iban, customer_id, kind, currency, balance, status) VALUES
    (1, 'TR000000000000000000000001', NULL, 'CASH',     'TRY', 0, 'ACTIVE'),
    (2, 'TR000000000000000000000002', NULL, 'CASH',     'USD', 0, 'ACTIVE'),
    (3, 'TR000000000000000000000003', NULL, 'CASH',     'EUR', 0, 'ACTIVE'),
    (4, 'TR000000000000000000000004', NULL, 'CLEARING', 'TRY', 0, 'ACTIVE'),
    (5, 'TR000000000000000000000005', NULL, 'CLEARING', 'USD', 0, 'ACTIVE'),
    (6, 'TR000000000000000000000006', NULL, 'CLEARING', 'EUR', 0, 'ACTIVE');
-- Identity sayacı açık id'lerden sonra devam etsin.
SELECT setval(pg_get_serial_sequence('account', 'id'), 6);
