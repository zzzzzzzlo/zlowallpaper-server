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
    refresh_token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL CHECK(expires_at > created_at),
    revoked_at TIMESTAMP(3)
);
CREATE INDEX idx_sessions_user_id ON sessions(user_id,expires_at);
