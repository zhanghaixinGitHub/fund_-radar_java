ALTER TABLE user_account
    RENAME COLUMN login_name TO mobile;

ALTER TABLE user_account
    RENAME CONSTRAINT uq_user_account_login_name TO uq_user_account_mobile;

ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_mobile_format
        CHECK (mobile = 'legacy-local-user' OR mobile ~ '^1[3-9][0-9]{9}$');
