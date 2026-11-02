-- 每次独立登录保存一条会话；刷新不新增记录、不轮换refreshToken。
USE zlowallpaper;
CREATE TABLE sessions (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话ID，JWT的sid关联此记录',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    refresh_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定刷新令牌SHA-256，仅存哈希',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    expires_at DATETIME(3) NOT NULL COMMENT '本次登录固定截止时间，UTC',
    revoked_at DATETIME(3) NULL COMMENT '主动撤销时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sessions_refresh_hash (refresh_token_hash),
    KEY idx_sessions_user_id (user_id, expires_at),
    KEY idx_sessions_expires_at (expires_at),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sessions_dates CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立登录会话';
