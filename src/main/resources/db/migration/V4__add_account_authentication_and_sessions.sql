ALTER TABLE user_account
    ADD COLUMN login_name VARCHAR(64);

ALTER TABLE user_account
    ADD COLUMN password_hash VARCHAR(100);

ALTER TABLE user_account
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';

UPDATE user_account
SET login_name = 'legacy-local-user',
    display_name = '待归属的本机用户',
    status = 'DISABLED',
    role = 'USER',
    updated_at = CURRENT_TIMESTAMP
WHERE user_id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE user_account
    ALTER COLUMN login_name SET NOT NULL;

ALTER TABLE user_account
    ADD CONSTRAINT uq_user_account_login_name UNIQUE (login_name);

ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_role CHECK (role IN ('USER', 'ADMIN'));

CREATE TABLE auth_session (
    session_id UUID PRIMARY KEY,
    token_hash CHAR(64) NOT NULL,
    user_id UUID NOT NULL REFERENCES user_account (user_id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_auth_session_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_auth_session_user_expires_at
    ON auth_session (user_id, expires_at DESC);

CREATE INDEX ix_auth_session_expires_at
    ON auth_session (expires_at);
