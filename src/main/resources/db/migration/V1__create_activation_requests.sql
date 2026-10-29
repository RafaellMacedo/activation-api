CREATE TABLE activation_requests (
    id UUID PRIMARY KEY,
    external_id VARCHAR(100) NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    product_code VARCHAR(100) NOT NULL,
    consumer_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    processing_started_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_activation_requests_external_id
        UNIQUE (external_id),

    CONSTRAINT ck_activation_requests_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),

    CONSTRAINT ck_activation_requests_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_activation_requests_consumer_created
    ON activation_requests (consumer_id, created_at DESC, id DESC);

CREATE INDEX idx_activation_requests_consumer_status_created
    ON activation_requests (consumer_id, status, created_at DESC, id DESC);

CREATE INDEX idx_activation_requests_consumer_customer_created
    ON activation_requests (consumer_id, customer_id, created_at DESC, id DESC);

CREATE INDEX idx_activation_requests_pending
    ON activation_requests (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_activation_requests_processing
    ON activation_requests (processing_started_at)
    WHERE status = 'PROCESSING';