-- Enforce that access-key secret material is never stored in plaintext.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM access_keys WHERE secret_access_key IS NOT NULL) THEN
        RAISE EXCEPTION
            'Plaintext access-key secrets exist. Run access-key reencrypt with S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY before applying this migration.';
    END IF;
END $$;

ALTER TABLE access_keys
    ADD CONSTRAINT ck_access_keys_encrypted_secret_only
    CHECK (
        secret_access_key IS NULL
        AND secret_ciphertext IS NOT NULL
        AND secret_nonce IS NOT NULL
        AND secret_key_id IS NOT NULL
    );
