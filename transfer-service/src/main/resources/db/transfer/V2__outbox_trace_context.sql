-- Outbox mesajı, onu üreten isteğin trace context'ini (W3C traceparent) taşır; yayın sırasında izleme zinciri buna bağlanır.
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(80);
