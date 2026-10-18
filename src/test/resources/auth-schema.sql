DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS users;
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR_IGNORECASE(32) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK(role IN ('USER','ADMIN')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
    balance_cents BIGINT NOT NULL DEFAULT 0 CHECK(balance_cents >= 0),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    family_id CHAR(36) NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL CHECK(expires_at > created_at),
    revoked_at TIMESTAMP(3),
    revoke_reason VARCHAR(16),
    replaced_by_id BIGINT UNIQUE,
    CHECK((revoked_at IS NULL AND revoke_reason IS NULL AND replaced_by_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL
            AND revoke_reason IN ('ROTATED','LOGOUT','REPLAY','DISABLED')))
);
CREATE INDEX idx_sessions_family_id ON sessions(family_id,id);
