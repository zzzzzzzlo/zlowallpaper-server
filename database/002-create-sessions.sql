-- 增量迁移：仅新增认证会话表，不重复执行 001，也不覆盖已有数据。
USE zlowallpaper;
CREATE TABLE sessions (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话令牌记录ID',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次登录及其轮换链的共同编号',
    refresh_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机刷新令牌的SHA-256，仅存哈希',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    expires_at DATETIME(3) NOT NULL COMMENT '整组会话固定截止时间，UTC',
    revoked_at DATETIME(3) NULL COMMENT '撤销时间，UTC',
    revoke_reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'ROTATED/LOGOUT/REPLAY/DISABLED',
    replaced_by_id BIGINT NULL COMMENT '下一代记录ID，由轮换事务维护',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sessions_refresh_hash (refresh_token_hash),
    UNIQUE KEY uk_sessions_replaced_by (replaced_by_id),
    KEY idx_sessions_family_id (family_id, id),
    KEY idx_sessions_user_id (user_id, expires_at),
    KEY idx_sessions_expires_at (expires_at),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sessions_dates CHECK (expires_at > created_at),
    CONSTRAINT ck_sessions_revocation CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL AND replaced_by_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL
            AND revoke_reason IN ('ROTATED','LOGOUT','REPLAY','DISABLED'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认证会话及刷新令牌轮换链';
-- replaced_by_id 不建自引用外键，便于整组过期清理；应用在同一事务内维护。
