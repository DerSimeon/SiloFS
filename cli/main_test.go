package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConfigPrecedence(t *testing.T) {
	t.Setenv("SILOS_ENDPOINT", "http://silos")
	t.Setenv("S3_ENDPOINT", "http://s3")
	cfg := resolveConfig(&flagConfig{})
	if cfg.Endpoint != "http://silos" {
		t.Fatalf("expected SILOS endpoint, got %q", cfg.Endpoint)
	}
	cfg = resolveConfig(&flagConfig{Endpoint: "http://flag"})
	if cfg.Endpoint != "http://flag" {
		t.Fatalf("expected flag endpoint, got %q", cfg.Endpoint)
	}
}

func TestParseS3URL(t *testing.T) {
	got, err := parseS3URL("s3://bucket/path/to/key", true)
	if err != nil {
		t.Fatal(err)
	}
	if got.Bucket != "bucket" || got.Key != "path/to/key" {
		t.Fatalf("unexpected parse: %+v", got)
	}
	if _, err := parseS3URL("s3://bucket", true); err == nil {
		t.Fatal("expected key requirement error")
	}
}

func TestRedact(t *testing.T) {
	in := "Authorization: AWS4-HMAC-SHA256 abc X-Amz-Signature=deadbeef&x=1 secret_access_key=topsecret"
	out := redact(in)
	for _, forbidden := range []string{"deadbeef", "topsecret", "AWS4-HMAC-SHA256 abc"} {
		if strings.Contains(out, forbidden) {
			t.Fatalf("redaction leaked %q in %q", forbidden, out)
		}
	}
}

func TestPostgresDSNConvertsJdbc(t *testing.T) {
	got, err := postgresDSN(appConfig{DBURL: "jdbc:postgresql://localhost:5432/silofs", DBUser: "u", DBPassword: "p"})
	if err != nil {
		t.Fatal(err)
	}
	if got != "postgres://u:p@localhost:5432/silofs" {
		t.Fatalf("unexpected dsn: %s", got)
	}
}

func TestEncodeSecretEncrypted(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	plain, ciphertext, nonce, keyID, err := encodeSecret("AKIATEST", "secret", appConfig{SecretKeyB64: base64.StdEncoding.EncodeToString(key)})
	if err != nil {
		t.Fatal(err)
	}
	if plain != nil || len(ciphertext) == 0 || len(nonce) == 0 || keyID == nil || *keyID != "env:aes-gcm" {
		t.Fatalf("unexpected encrypted fields plain=%v ciphertext=%d nonce=%d keyID=%v", plain, len(ciphertext), len(nonce), keyID)
	}
}

func TestRootCommandRoutesVersion(t *testing.T) {
	var out bytes.Buffer
	var errOut bytes.Buffer
	cmd := newRootCommand([]string{"version"}, &out, &errOut)
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "silofs") {
		t.Fatalf("missing version output: %q", out.String())
	}
}

func TestDryRunRequiredForRepair(t *testing.T) {
	var out bytes.Buffer
	var errOut bytes.Buffer
	cmd := newRootCommand([]string{"admin", "repair"}, &out, &errOut)
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "--dry-run") {
		t.Fatalf("expected dry-run validation, got %v", err)
	}
}

func TestVerifyEncryptedBlob(t *testing.T) {
	key := bytes.Repeat([]byte{3}, 32)
	plaintext := []byte("encrypted object")
	blob, sha := testEncryptedBlob(t, key, plaintext)

	if err := verifyEncryptedBlob(blob, sha, base64.StdEncoding.EncodeToString(key)); err != nil {
		t.Fatal(err)
	}
	if err := verifyEncryptedBlob(blob, sha, base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{4}, 32))); err == nil {
		t.Fatal("expected wrong key failure")
	}
}

func TestVerifyBlobNameRequiresKeyForEncryptedBlob(t *testing.T) {
	key := bytes.Repeat([]byte{5}, 32)
	blob, sha := testEncryptedBlob(t, key, []byte("secret"))
	dir := t.TempDir()
	path := blobPath(dir, sha)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, blob, 0o644); err != nil {
		t.Fatal(err)
	}

	err := verifyBlobName(appConfig{DataDir: dir}, sha)
	if err == nil || !strings.Contains(err.Error(), "requires object encryption key") {
		t.Fatalf("expected missing key error, got %v", err)
	}
	if err := verifyBlobName(
		appConfig{DataDir: dir, ObjectKeyB64: base64.StdEncoding.EncodeToString(key)},
		sha,
	); err != nil {
		t.Fatal(err)
	}
}

func testEncryptedBlob(t *testing.T, key []byte, plaintext []byte) ([]byte, string) {
	t.Helper()
	nonce := bytes.Repeat([]byte{9}, 12)
	keyID := []byte("local-v1")
	sum := sha256.Sum256(plaintext)
	header := bytes.NewBuffer(nil)
	header.WriteString("SILOFSENC1")
	header.WriteByte(1)
	header.Write(nonce)
	if err := binary.Write(header, binary.BigEndian, uint16(len(keyID))); err != nil {
		t.Fatal(err)
	}
	header.Write(keyID)
	if err := binary.Write(header, binary.BigEndian, uint64(len(plaintext))); err != nil {
		t.Fatal(err)
	}
	header.Write(sum[:])

	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	out := append([]byte{}, header.Bytes()...)
	out = gcm.Seal(out, nonce, plaintext, header.Bytes())
	return out, base64Hex(sum[:])
}

func base64Hex(bytes []byte) string {
	const alphabet = "0123456789abcdef"
	out := make([]byte, len(bytes)*2)
	for i, b := range bytes {
		out[i*2] = alphabet[b>>4]
		out[i*2+1] = alphabet[b&0x0f]
	}
	return string(out)
}
