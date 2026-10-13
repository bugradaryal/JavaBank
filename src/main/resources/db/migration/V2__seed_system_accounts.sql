-- Her para birimi için bir kasa hesabı. Para yatırma bu hesaptan, çekme bu hesaba yapılır.
INSERT INTO account (iban, customer_id, kind, currency, balance, status) VALUES
    ('TR000000000000000000000001', NULL, 'SYSTEM', 'TRY', 0, 'ACTIVE'),
    ('TR000000000000000000000002', NULL, 'SYSTEM', 'USD', 0, 'ACTIVE'),
    ('TR000000000000000000000003', NULL, 'SYSTEM', 'EUR', 0, 'ACTIVE');
