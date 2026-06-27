package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5"
	"github.com/spf13/cobra"
)

const version = "0.10.0"

type appConfig struct {
	Endpoint        string
	Region          string
	AccessKeyID     string
	SecretAccessKey string
	DBURL           string
	DBUser          string
	DBPassword      string
	DataDir         string
	SecretKeyB64    string
	ObjectKeyB64    string
}

type flagConfig struct {
	Endpoint        string
	Region          string
	AccessKeyID     string
	SecretAccessKey string
	DBURL           string
	DBUser          string
	DBPassword      string
	DataDir         string
	SecretKeyB64    string
	ObjectKeyB64    string
}

func main() {
	if err := newRootCommand(os.Args[1:], os.Stdout, os.Stderr).Execute(); err != nil {
		fmt.Fprintln(os.Stderr, redact(err.Error()))
		os.Exit(1)
	}
}

func newRootCommand(args []string, stdout, stderr io.Writer) *cobra.Command {
	flags := &flagConfig{}
	root := &cobra.Command{
		Use:           "silofs",
		Short:         "Silofs object storage CLI",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.SetOut(stdout)
	root.SetErr(stderr)
	root.SetArgs(args)
	root.PersistentFlags().StringVar(&flags.Endpoint, "endpoint", "", "S3 endpoint URL")
	root.PersistentFlags().StringVar(&flags.Region, "region", "", "S3 region")
	root.PersistentFlags().StringVar(&flags.AccessKeyID, "access-key-id", "", "S3 access key id")
	root.PersistentFlags().StringVar(&flags.SecretAccessKey, "secret-access-key", "", "S3 secret access key")
	root.PersistentFlags().StringVar(&flags.DBURL, "db-url", "", "PostgreSQL URL or JDBC URL")
	root.PersistentFlags().StringVar(&flags.DBUser, "db-user", "", "PostgreSQL user")
	root.PersistentFlags().StringVar(&flags.DBPassword, "db-password", "", "PostgreSQL password")
	root.PersistentFlags().StringVar(&flags.DataDir, "data-dir", "", "Silofs data directory")
	root.PersistentFlags().StringVar(&flags.SecretKeyB64, "access-key-secret-encryption-key", "", "base64 AES-GCM key for access-key secrets")
	root.PersistentFlags().StringVar(&flags.ObjectKeyB64, "object-encryption-master-key", "", "base64 AES-GCM key for encrypted object verification")

	root.AddCommand(&cobra.Command{
		Use:   "version",
		Short: "Print version",
		RunE: func(cmd *cobra.Command, args []string) error {
			_, err := fmt.Fprintf(stdout, "silofs %s\n", version)
			return err
		},
	})
	root.AddCommand(mbCommand(flags, stdout), rbCommand(flags, stdout), lsCommand(flags, stdout), statCommand(flags, stdout))
	root.AddCommand(cpCommand(flags, stdout), catCommand(flags, stdout), rmCommand(flags, stdout), presignCommand(flags, stdout))
	root.AddCommand(adminCommand(flags, stdout))
	return root
}

func resolveConfig(f *flagConfig) appConfig {
	return appConfig{
		Endpoint:        choose(f.Endpoint, "SILOS_ENDPOINT", "S3_ENDPOINT", "http://127.0.0.1:8080"),
		Region:          choose(f.Region, "SILOS_REGION", "S3_REGION", "us-east-1"),
		AccessKeyID:     choose(f.AccessKeyID, "SILOS_ACCESS_KEY_ID", "S3_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE"),
		SecretAccessKey: choose(f.SecretAccessKey, "SILOS_SECRET_ACCESS_KEY", "S3_SECRET_ACCESS_KEY", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
		DBURL:           choose(f.DBURL, "SILOS_DB_URL", "S3_DB_URL", "postgres://silofs:silofs@localhost:5432/silofs"),
		DBUser:          choose(f.DBUser, "SILOS_DB_USER", "S3_DB_USER", "silofs"),
		DBPassword:      choose(f.DBPassword, "SILOS_DB_PASSWORD", "S3_DB_PASSWORD", "silofs"),
		DataDir:         choose(f.DataDir, "SILOS_DATA_DIR", "S3_DATA_DIR", "/var/lib/silofs/data"),
		SecretKeyB64:    choose(f.SecretKeyB64, "SILOS_ACCESS_KEY_SECRET_ENCRYPTION_KEY", "S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY", ""),
		ObjectKeyB64:    choose(f.ObjectKeyB64, "SILOS_OBJECT_ENCRYPTION_MASTER_KEY", "S3_OBJECT_ENCRYPTION_MASTER_KEY", ""),
	}
}

func choose(flagValue, silosEnv, s3Env, fallback string) string {
	if flagValue != "" {
		return flagValue
	}
	if v := os.Getenv(silosEnv); v != "" {
		return v
	}
	if v := os.Getenv(s3Env); v != "" {
		return v
	}
	return fallback
}

func s3Client(ctx context.Context, cfg appConfig) (*s3.Client, error) {
	awsCfg, err := awsconfig.LoadDefaultConfig(
		ctx,
		awsconfig.WithRegion(cfg.Region),
		awsconfig.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(cfg.AccessKeyID, cfg.SecretAccessKey, "")),
	)
	if err != nil {
		return nil, err
	}
	return s3.NewFromConfig(awsCfg, func(o *s3.Options) {
		o.BaseEndpoint = aws.String(cfg.Endpoint)
		o.UsePathStyle = true
	}), nil
}

type s3URL struct {
	Bucket string
	Key    string
}

func parseS3URL(raw string, requireKey bool) (s3URL, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "s3" || u.Host == "" {
		return s3URL{}, fmt.Errorf("expected s3://bucket[/key], got %q", raw)
	}
	key := strings.TrimPrefix(u.Path, "/")
	if requireKey && key == "" {
		return s3URL{}, fmt.Errorf("expected object key in %q", raw)
	}
	return s3URL{Bucket: u.Host, Key: key}, nil
}

func mbCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{
		Use:   "mb s3://bucket",
		Short: "Create a bucket",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			target, err := parseS3URL(args[0], false)
			if err != nil {
				return err
			}
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			if _, err := client.CreateBucket(cmd.Context(), &s3.CreateBucketInput{Bucket: aws.String(target.Bucket)}); err != nil {
				return redactError(err)
			}
			_, err = fmt.Fprintf(stdout, "bucket=%s created=true\n", target.Bucket)
			return err
		},
	}
}

func rbCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{
		Use:   "rb s3://bucket",
		Short: "Delete an empty bucket",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			target, err := parseS3URL(args[0], false)
			if err != nil {
				return err
			}
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			if _, err := client.DeleteBucket(cmd.Context(), &s3.DeleteBucketInput{Bucket: aws.String(target.Bucket)}); err != nil {
				return redactError(err)
			}
			_, err = fmt.Fprintf(stdout, "bucket=%s deleted=true\n", target.Bucket)
			return err
		},
	}
}

func lsCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{
		Use:   "ls [s3://bucket[/prefix]]",
		Short: "List buckets or objects",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			if len(args) == 0 {
				out, err := client.ListBuckets(cmd.Context(), &s3.ListBucketsInput{})
				if err != nil {
					return redactError(err)
				}
				for _, b := range out.Buckets {
					fmt.Fprintln(stdout, aws.ToString(b.Name))
				}
				return nil
			}
			target, err := parseS3URL(args[0], false)
			if err != nil {
				return err
			}
			out, err := client.ListObjectsV2(cmd.Context(), &s3.ListObjectsV2Input{Bucket: aws.String(target.Bucket), Prefix: aws.String(target.Key)})
			if err != nil {
				return redactError(err)
			}
			for _, obj := range out.Contents {
				fmt.Fprintf(stdout, "%d\t%s\n", aws.ToInt64(obj.Size), aws.ToString(obj.Key))
			}
			return nil
		},
	}
}

func statCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{
		Use:   "stat s3://bucket[/key]",
		Short: "Show bucket or object metadata",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			target, err := parseS3URL(args[0], false)
			if err != nil {
				return err
			}
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			if target.Key == "" {
				if _, err := client.HeadBucket(cmd.Context(), &s3.HeadBucketInput{Bucket: aws.String(target.Bucket)}); err != nil {
					return redactError(err)
				}
				_, err = fmt.Fprintf(stdout, "bucket=%s exists=true\n", target.Bucket)
				return err
			}
			out, err := client.HeadObject(cmd.Context(), &s3.HeadObjectInput{Bucket: aws.String(target.Bucket), Key: aws.String(target.Key)})
			if err != nil {
				return redactError(err)
			}
			fmt.Fprintf(stdout, "bucket=%s\nkey=%s\nsize=%d\netag=%s\ncontent_type=%s\n", target.Bucket, target.Key, aws.ToInt64(out.ContentLength), strings.Trim(aws.ToString(out.ETag), "\""), aws.ToString(out.ContentType))
			for k, v := range out.Metadata {
				fmt.Fprintf(stdout, "metadata.%s=%s\n", k, v)
			}
			return nil
		},
	}
}

func cpCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{
		Use:   "cp SOURCE DEST",
		Short: "Copy between local files and S3",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			srcIsS3 := strings.HasPrefix(args[0], "s3://")
			dstIsS3 := strings.HasPrefix(args[1], "s3://")
			switch {
			case srcIsS3 && dstIsS3:
				src, err := parseS3URL(args[0], true)
				if err != nil {
					return err
				}
				dst, err := parseS3URL(args[1], true)
				if err != nil {
					return err
				}
				copySource := url.PathEscape(src.Bucket + "/" + src.Key)
				_, err = client.CopyObject(cmd.Context(), &s3.CopyObjectInput{Bucket: aws.String(dst.Bucket), Key: aws.String(dst.Key), CopySource: aws.String(copySource)})
				if err != nil {
					return redactError(err)
				}
			case !srcIsS3 && dstIsS3:
				dst, err := parseS3URL(args[1], true)
				if err != nil {
					return err
				}
				file, err := os.Open(args[0])
				if err != nil {
					return err
				}
				defer file.Close()
				_, err = client.PutObject(cmd.Context(), &s3.PutObjectInput{Bucket: aws.String(dst.Bucket), Key: aws.String(dst.Key), Body: file, ContentType: aws.String(contentTypeForPath(args[0]))})
				if err != nil {
					return redactError(err)
				}
			case srcIsS3 && !dstIsS3:
				src, err := parseS3URL(args[0], true)
				if err != nil {
					return err
				}
				out, err := client.GetObject(cmd.Context(), &s3.GetObjectInput{Bucket: aws.String(src.Bucket), Key: aws.String(src.Key)})
				if err != nil {
					return redactError(err)
				}
				defer out.Body.Close()
				file, err := os.Create(args[1])
				if err != nil {
					return err
				}
				defer file.Close()
				if _, err := io.Copy(file, out.Body); err != nil {
					return err
				}
			default:
				return errors.New("one side of cp must be an s3:// URL")
			}
			_, err = fmt.Fprintln(stdout, "copied=true")
			return err
		},
	}
}

func catCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	var byteRange string
	cmd := &cobra.Command{
		Use:   "cat s3://bucket/key",
		Short: "Write an object to stdout",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			target, err := parseS3URL(args[0], true)
			if err != nil {
				return err
			}
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			in := &s3.GetObjectInput{Bucket: aws.String(target.Bucket), Key: aws.String(target.Key)}
			if byteRange != "" {
				in.Range = aws.String(byteRange)
			}
			out, err := client.GetObject(cmd.Context(), in)
			if err != nil {
				return redactError(err)
			}
			defer out.Body.Close()
			_, err = io.Copy(stdout, out.Body)
			return err
		},
	}
	cmd.Flags().StringVar(&byteRange, "range", "", "HTTP range, for example bytes=0-99")
	return cmd
}

func rmCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{
		Use:   "rm s3://bucket/key",
		Short: "Delete an object",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			target, err := parseS3URL(args[0], true)
			if err != nil {
				return err
			}
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			if _, err := client.DeleteObject(cmd.Context(), &s3.DeleteObjectInput{Bucket: aws.String(target.Bucket), Key: aws.String(target.Key)}); err != nil {
				return redactError(err)
			}
			_, err = fmt.Fprintln(stdout, "deleted=true")
			return err
		},
	}
}

func presignCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	cmd := &cobra.Command{Use: "presign", Short: "Generate presigned URLs"}
	cmd.AddCommand(presignObjectCommand("get", flags, stdout), presignObjectCommand("put", flags, stdout))
	return cmd
}

func presignObjectCommand(verb string, flags *flagConfig, stdout io.Writer) *cobra.Command {
	var expires time.Duration
	cmd := &cobra.Command{
		Use:   verb + " s3://bucket/key",
		Short: "Presign " + verb,
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			target, err := parseS3URL(args[0], true)
			if err != nil {
				return err
			}
			client, err := s3Client(cmd.Context(), resolveConfig(flags))
			if err != nil {
				return redactError(err)
			}
			presigner := s3.NewPresignClient(client)
			if verb == "get" {
				out, err := presigner.PresignGetObject(cmd.Context(), &s3.GetObjectInput{Bucket: aws.String(target.Bucket), Key: aws.String(target.Key)}, s3.WithPresignExpires(expires))
				if err != nil {
					return redactError(err)
				}
				_, err = fmt.Fprintln(stdout, out.URL)
				return err
			}
			out, err := presigner.PresignPutObject(cmd.Context(), &s3.PutObjectInput{Bucket: aws.String(target.Bucket), Key: aws.String(target.Key)}, s3.WithPresignExpires(expires))
			if err != nil {
				return redactError(err)
			}
			_, err = fmt.Fprintln(stdout, out.URL)
			return err
		},
	}
	cmd.Flags().DurationVar(&expires, "expires", 15*time.Minute, "expiration duration")
	return cmd
}

func adminCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	cmd := &cobra.Command{Use: "admin", Short: "Local admin commands"}
	cmd.AddCommand(adminInspectCommand(flags, stdout), adminStorageCommand(flags, stdout), adminCheckBlobsCommand(flags, stdout))
	cmd.AddCommand(adminRepairCommand(flags, stdout), adminGCCommand(flags, stdout), adminBackupCommand(flags, stdout), adminMigrateCommand(flags, stdout), adminAccessKeyCommand(flags, stdout))
	return cmd
}

func adminInspectCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	cmd := &cobra.Command{Use: "inspect", Short: "Inspect metadata"}
	cmd.AddCommand(&cobra.Command{Use: "buckets", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		return queryRows(ctx, stdout, conn, "SELECT name, region, owner_id, created_at FROM buckets WHERE deleted_at IS NULL ORDER BY created_at, name")
	})})
	var bucket string
	objects := &cobra.Command{Use: "objects", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		if bucket == "" {
			return errors.New("--bucket is required")
		}
		return queryRows(ctx, stdout, conn, "SELECT bucket, object_key, size_bytes, etag, created_at FROM objects WHERE bucket=$1 AND deleted_at IS NULL ORDER BY object_key LIMIT 10000", bucket)
	})}
	objects.Flags().StringVar(&bucket, "bucket", "", "bucket name")
	cmd.AddCommand(objects)
	var mpuBucket string
	mpu := &cobra.Command{Use: "multipart", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		if mpuBucket != "" {
			return queryRows(ctx, stdout, conn, "SELECT bucket, object_key, upload_id, state, initiated_at FROM multipart_uploads WHERE bucket=$1 ORDER BY initiated_at", mpuBucket)
		}
		return queryRows(ctx, stdout, conn, "SELECT bucket, object_key, upload_id, state, initiated_at FROM multipart_uploads ORDER BY initiated_at")
	})}
	mpu.Flags().StringVar(&mpuBucket, "bucket", "", "bucket name")
	cmd.AddCommand(mpu)
	var sha string
	refs := &cobra.Command{Use: "blob-refs", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		if sha == "" {
			return errors.New("--sha256 is required")
		}
		if !isSHA256(sha) {
			return errors.New("--sha256 must be 64 hex characters")
		}
		sql := `SELECT 'object' AS kind, bucket, object_key, NULL AS upload_id, NULL::integer AS part_number FROM objects WHERE deleted_at IS NULL AND encode(blob_sha256, 'hex')=$1
UNION ALL SELECT 'multipart_part', NULL, NULL, upload_id, part_number FROM multipart_parts WHERE encode(blob_sha256, 'hex')=$1
UNION ALL SELECT 'blob_write_intent', NULL, NULL, intent_id, NULL::integer FROM blob_write_intents WHERE blob_sha256_hex=$1`
		return queryRows(ctx, stdout, conn, sql, sha)
	})}
	refs.Flags().StringVar(&sha, "sha256", "", "blob sha256 hex")
	cmd.AddCommand(refs)
	return cmd
}

func adminStorageCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	cmd := &cobra.Command{Use: "storage", Short: "Storage commands"}
	cmd.AddCommand(&cobra.Command{Use: "usage", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		objectBytes, _ := dirBytes(filepath.Join(cfg.DataDir, "objects"))
		quarantineCount, _ := fileCount(filepath.Join(cfg.DataDir, "quarantine"))
		var active int64
		if err := conn.QueryRow(ctx, "SELECT COUNT(*) FROM multipart_uploads WHERE state IN ('INITIATED','COMPLETING')").Scan(&active); err != nil {
			return err
		}
		fmt.Fprintf(stdout, "blob_disk_bytes=%d\nactive_multipart_uploads=%d\nquarantined_blobs=%d\n", objectBytes, active, quarantineCount)
		return nil
	})})
	return cmd
}

func adminCheckBlobsCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{Use: "check-blobs", Short: "Verify DB/blob consistency without mutation", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		return checkBlobs(ctx, conn, cfg, stdout)
	})}
}

func adminRepairCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	var dryRun bool
	cmd := &cobra.Command{Use: "repair --dry-run", Short: "Report repairable issues without mutation", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		if !dryRun {
			return errors.New("repair currently supports --dry-run only")
		}
		return withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
			if err := checkBlobs(ctx, conn, cfg, stdout); err != nil {
				return err
			}
			fmt.Fprintln(stdout, "dry_run=true\nmutations=0")
			return nil
		})(cmd, args)
	}}
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "required")
	return cmd
}

func adminGCCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	var dryRun bool
	cmd := &cobra.Command{Use: "gc --dry-run", Short: "Report GC candidates without mutation", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		if !dryRun {
			return errors.New("gc currently supports --dry-run only")
		}
		return withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
			refs, err := referencedBlobSet(ctx, conn)
			if err != nil {
				return err
			}
			orphaned, err := orphanedBlobs(cfg.DataDir, refs)
			if err != nil {
				return err
			}
			fmt.Fprintln(stdout, "dry_run=true")
			fmt.Fprintf(stdout, "eligible_orphan_blobs=%d\n", len(orphaned))
			for _, sha := range orphaned {
				fmt.Fprintf(stdout, "gc_candidate sha256=%s\n", sha)
			}
			return nil
		})(cmd, args)
	}}
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "required")
	return cmd
}

func adminBackupCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	cmd := &cobra.Command{Use: "backup", Short: "Backup commands"}
	var manifest string
	verify := &cobra.Command{Use: "verify --manifest PATH", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		if manifest == "" {
			return errors.New("--manifest is required")
		}
		if _, err := os.Stat(manifest); err != nil {
			return err
		}
		return withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
			fmt.Fprintf(stdout, "manifest=%s exists=true\n", manifest)
			return checkBlobs(ctx, conn, cfg, stdout)
		})(cmd, args)
	}}
	verify.Flags().StringVar(&manifest, "manifest", "", "backup manifest")
	cmd.AddCommand(verify)
	return cmd
}

func adminMigrateCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{Use: "migrate", Short: "Probe migration state", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		var one int
		if err := conn.QueryRow(ctx, "SELECT 1").Scan(&one); err != nil {
			return err
		}
		_, err := fmt.Fprintln(stdout, "migration=ok")
		return err
	})}
}

func adminAccessKeyCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	cmd := &cobra.Command{Use: "access-key", Short: "Manage access keys"}
	var keyID, description string
	create := &cobra.Command{Use: "create", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		id := keyID
		if id == "" {
			id = randomAccessKeyID()
		}
		secret := randomSecretAccessKey()
		plain, ciphertext, nonce, secretKeyID, err := encodeSecret(id, secret, cfg)
		if err != nil {
			return err
		}
		_, err = conn.Exec(ctx, `INSERT INTO access_keys(access_key_id, secret_access_key, secret_ciphertext, secret_nonce, secret_key_id, description, state, updated_at)
VALUES ($1,$2,$3,$4,$5,$6,'ACTIVE',now())
ON CONFLICT (access_key_id) DO UPDATE SET secret_access_key=EXCLUDED.secret_access_key, secret_ciphertext=EXCLUDED.secret_ciphertext, secret_nonce=EXCLUDED.secret_nonce, secret_key_id=EXCLUDED.secret_key_id, description=EXCLUDED.description, state='ACTIVE', updated_at=now(), deleted_at=NULL`, id, plain, ciphertext, nonce, secretKeyID, description)
		if err != nil {
			return err
		}
		audit(ctx, conn, "AdminAccessKeyCreate", id)
		fmt.Fprintf(stdout, "access_key_id=%s\nsecret_access_key=%s\nstate=ACTIVE\n", id, secret)
		return nil
	})}
	create.Flags().StringVar(&keyID, "access-key-id", "", "access key id")
	create.Flags().StringVar(&description, "description", "", "description")
	cmd.AddCommand(create)
	cmd.AddCommand(&cobra.Command{Use: "list", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		return queryRows(ctx, stdout, conn, "SELECT access_key_id, state, secret_ciphertext IS NOT NULL AS encrypted, COALESCE(description,''), created_at, updated_at FROM access_keys WHERE deleted_at IS NULL ORDER BY created_at, access_key_id")
	})})
	cmd.AddCommand(accessKeyStateCommand("disable", "DISABLED", flags, stdout), accessKeyStateCommand("enable", "ACTIVE", flags, stdout))
	cmd.AddCommand(accessKeyDeleteCommand(flags, stdout), accessKeyRotateCommand(flags, stdout), accessKeyReencryptCommand(flags, stdout))
	return cmd
}

func accessKeyStateCommand(name, state string, flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{Use: name + " ID", Args: cobra.ExactArgs(1), RunE: withDBArgs(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig, args []string) error {
		sql := "UPDATE access_keys SET state=$1, updated_at=now(), disabled_at=NULL, deleted_at=NULL WHERE access_key_id=$2 AND deleted_at IS NULL"
		if state == "DISABLED" {
			sql = "UPDATE access_keys SET state=$1, updated_at=now(), disabled_at=COALESCE(disabled_at, now()), deleted_at=NULL WHERE access_key_id=$2 AND deleted_at IS NULL"
		}
		tag, err := conn.Exec(ctx, sql, state, args[0])
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return fmt.Errorf("access key not found: %s", args[0])
		}
		audit(ctx, conn, "AdminAccessKey"+state, args[0])
		fmt.Fprintf(stdout, "access_key_id=%s\nstate=%s\n", args[0], state)
		return nil
	})}
}

func accessKeyDeleteCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{Use: "delete ID", Args: cobra.ExactArgs(1), RunE: withDBArgs(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig, args []string) error {
		tag, err := conn.Exec(ctx, "UPDATE access_keys SET state='DELETED', deleted_at=COALESCE(deleted_at, now()), updated_at=now() WHERE access_key_id=$1 AND deleted_at IS NULL", args[0])
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return fmt.Errorf("access key not found: %s", args[0])
		}
		audit(ctx, conn, "AdminAccessKeyDelete", args[0])
		fmt.Fprintf(stdout, "access_key_id=%s\nstate=DELETED\n", args[0])
		return nil
	})}
}

func accessKeyRotateCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{Use: "rotate ID", Args: cobra.ExactArgs(1), RunE: withDBArgs(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig, args []string) error {
		secret := randomSecretAccessKey()
		plain, ciphertext, nonce, secretKeyID, err := encodeSecret(args[0], secret, cfg)
		if err != nil {
			return err
		}
		tag, err := conn.Exec(ctx, "UPDATE access_keys SET secret_access_key=$1, secret_ciphertext=$2, secret_nonce=$3, secret_key_id=$4, rotated_at=now(), updated_at=now() WHERE access_key_id=$5 AND deleted_at IS NULL", plain, ciphertext, nonce, secretKeyID, args[0])
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return fmt.Errorf("access key not found: %s", args[0])
		}
		audit(ctx, conn, "AdminAccessKeyRotate", args[0])
		fmt.Fprintf(stdout, "access_key_id=%s\nsecret_access_key=%s\n", args[0], secret)
		return nil
	})}
}

func accessKeyReencryptCommand(flags *flagConfig, stdout io.Writer) *cobra.Command {
	return &cobra.Command{Use: "reencrypt", Args: cobra.NoArgs, RunE: withDB(flags, func(ctx context.Context, conn *pgx.Conn, cfg appConfig) error {
		if cfg.SecretKeyB64 == "" {
			return errors.New("reencrypt requires SILOS_ACCESS_KEY_SECRET_ENCRYPTION_KEY or S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY")
		}
		keyRows, err := conn.Query(ctx, "SELECT access_key_id, COALESCE(secret_access_key, '') FROM access_keys WHERE deleted_at IS NULL")
		if err != nil {
			return err
		}
		defer keyRows.Close()
		type item struct{ id, secret string }
		var items []item
		for keyRows.Next() {
			var it item
			if err := keyRows.Scan(&it.id, &it.secret); err != nil {
				return err
			}
			if it.secret != "" {
				items = append(items, it)
			}
		}
		count := 0
		for _, it := range items {
			plain, ciphertext, nonce, secretKeyID, err := encodeSecret(it.id, it.secret, cfg)
			if err != nil {
				return err
			}
			if _, err := conn.Exec(ctx, "UPDATE access_keys SET secret_access_key=$1, secret_ciphertext=$2, secret_nonce=$3, secret_key_id=$4, updated_at=now() WHERE access_key_id=$5", plain, ciphertext, nonce, secretKeyID, it.id); err != nil {
				return err
			}
			count++
		}
		audit(ctx, conn, "AdminAccessKeyReencrypt", "")
		fmt.Fprintf(stdout, "reencrypted=%d\n", count)
		return nil
	})}
}

func withDB(flags *flagConfig, fn func(context.Context, *pgx.Conn, appConfig) error) func(*cobra.Command, []string) error {
	return func(cmd *cobra.Command, args []string) error {
		cfg := resolveConfig(flags)
		dsn, err := postgresDSN(cfg)
		if err != nil {
			return err
		}
		conn, err := pgx.Connect(cmd.Context(), dsn)
		if err != nil {
			return redactError(err)
		}
		defer conn.Close(cmd.Context())
		return redactError(fn(cmd.Context(), conn, cfg))
	}
}

func withDBArgs(flags *flagConfig, fn func(context.Context, *pgx.Conn, appConfig, []string) error) func(*cobra.Command, []string) error {
	return func(cmd *cobra.Command, args []string) error {
		cfg := resolveConfig(flags)
		dsn, err := postgresDSN(cfg)
		if err != nil {
			return err
		}
		conn, err := pgx.Connect(cmd.Context(), dsn)
		if err != nil {
			return redactError(err)
		}
		defer conn.Close(cmd.Context())
		return redactError(fn(cmd.Context(), conn, cfg, args))
	}
}

func postgresDSN(cfg appConfig) (string, error) {
	raw := cfg.DBURL
	if strings.HasPrefix(raw, "jdbc:postgresql://") {
		raw = "postgres://" + strings.TrimPrefix(raw, "jdbc:postgresql://")
	}
	if strings.HasPrefix(raw, "postgres://") || strings.HasPrefix(raw, "postgresql://") {
		u, err := url.Parse(raw)
		if err != nil {
			return "", err
		}
		if u.User == nil || u.User.Username() == "" {
			u.User = url.UserPassword(cfg.DBUser, cfg.DBPassword)
		}
		return u.String(), nil
	}
	return raw, nil
}

func rows(stdout io.Writer, r pgx.Rows, err error) error {
	if err != nil {
		return err
	}
	defer r.Close()
	fields := r.FieldDescriptions()
	for r.Next() {
		vals, err := r.Values()
		if err != nil {
			return err
		}
		parts := make([]string, 0, len(vals))
		for i, val := range vals {
			parts = append(parts, fmt.Sprintf("%s=%v", fields[i].Name, val))
		}
		fmt.Fprintln(stdout, strings.Join(parts, "\t"))
	}
	return r.Err()
}

func queryRows(ctx context.Context, stdout io.Writer, conn *pgx.Conn, sql string, args ...any) error {
	r, err := conn.Query(ctx, sql, args...)
	return rows(stdout, r, err)
}

func checkBlobs(ctx context.Context, conn *pgx.Conn, cfg appConfig, stdout io.Writer) error {
	refs, err := referencedBlobSet(ctx, conn)
	if err != nil {
		return err
	}
	missing := 0
	for sha := range refs {
		if _, err := os.Stat(blobPath(cfg.DataDir, sha)); err != nil {
			missing++
			fmt.Fprintf(stdout, "missing_blob sha256=%s path=%s\n", sha, blobPath(cfg.DataDir, sha))
		} else if err := verifyBlobName(cfg, sha); err != nil {
			missing++
			fmt.Fprintf(stdout, "invalid_blob sha256=%s error=%s\n", sha, err)
		}
	}
	orphaned, err := orphanedBlobs(cfg.DataDir, refs)
	if err != nil {
		return err
	}
	fmt.Fprintf(stdout, "referenced_blobs=%d\nmissing_blobs=%d\norphan_blobs=%d\n", len(refs), missing, len(orphaned))
	for _, sha := range orphaned {
		fmt.Fprintf(stdout, "orphan_blob sha256=%s path=%s\n", sha, blobPath(cfg.DataDir, sha))
	}
	if missing > 0 {
		return errors.New("blob consistency check failed")
	}
	return nil
}

func referencedBlobSet(ctx context.Context, conn *pgx.Conn) (map[string]struct{}, error) {
	sql := `SELECT encode(blob_sha256, 'hex') FROM objects WHERE deleted_at IS NULL
UNION SELECT encode(blob_sha256, 'hex') FROM multipart_parts
UNION SELECT blob_sha256_hex FROM blob_write_intents`
	r, err := conn.Query(ctx, sql)
	if err != nil {
		return nil, err
	}
	defer r.Close()
	refs := map[string]struct{}{}
	for r.Next() {
		var sha string
		if err := r.Scan(&sha); err != nil {
			return nil, err
		}
		refs[sha] = struct{}{}
	}
	return refs, r.Err()
}

func orphanedBlobs(dataDir string, refs map[string]struct{}) ([]string, error) {
	root := filepath.Join(dataDir, "objects")
	var out []string
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		sha := filepath.Base(path)
		if isSHA256(sha) {
			if _, ok := refs[sha]; !ok {
				out = append(out, sha)
			}
		}
		return nil
	})
	if errors.Is(err, os.ErrNotExist) {
		return out, nil
	}
	return out, err
}

func verifyBlobName(cfg appConfig, sha string) error {
	raw, err := os.ReadFile(blobPath(cfg.DataDir, sha))
	if err != nil {
		return err
	}
	sum := sha256.Sum256(raw)
	if hex.EncodeToString(sum[:]) == sha {
		return nil
	}
	if cfg.ObjectKeyB64 == "" {
		return fmt.Errorf("blob bytes do not match sha256; encrypted verification requires object encryption key")
	}
	return verifyEncryptedBlob(raw, sha, cfg.ObjectKeyB64)
}

func verifyEncryptedBlob(raw []byte, expectedSHA string, keyB64 string) error {
	const magic = "SILOFSENC1"
	if len(raw) < len(magic)+1+12+2+8+32 || string(raw[:len(magic)]) != magic {
		return fmt.Errorf("blob bytes do not match sha256 and are not a silofs encrypted blob")
	}
	pos := len(magic)
	if raw[pos] != 1 {
		return fmt.Errorf("unsupported silofs encrypted blob version: %d", raw[pos])
	}
	pos++
	nonce := raw[pos : pos+12]
	pos += 12
	keyIDLen := int(binary.BigEndian.Uint16(raw[pos : pos+2]))
	pos += 2
	if len(raw) < pos+keyIDLen+8+32 {
		return fmt.Errorf("truncated silofs encrypted blob header")
	}
	pos += keyIDLen
	plaintextSize := int64(binary.BigEndian.Uint64(raw[pos : pos+8]))
	pos += 8
	plaintextSHA := raw[pos : pos+32]
	pos += 32
	header := raw[:pos]
	ciphertext := raw[pos:]

	key, err := base64.StdEncoding.DecodeString(keyB64)
	if err != nil {
		return fmt.Errorf("invalid object encryption key: %w", err)
	}
	if len(key) != 32 {
		return fmt.Errorf("object encryption key must decode to 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	plaintext, err := gcm.Open(nil, nonce, ciphertext, header)
	if err != nil {
		return fmt.Errorf("encrypted blob decryptability verification failed: %w", err)
	}
	if int64(len(plaintext)) != plaintextSize {
		return fmt.Errorf("encrypted blob plaintext size mismatch: expected=%d actual=%d", plaintextSize, len(plaintext))
	}
	sum := sha256.Sum256(plaintext)
	if hex.EncodeToString(sum[:]) != expectedSHA || !equalBytes(sum[:], plaintextSHA) {
		return fmt.Errorf("encrypted blob plaintext SHA-256 mismatch: expected=%s actual=%s", expectedSHA, hex.EncodeToString(sum[:]))
	}
	return nil
}

func equalBytes(a []byte, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := range a {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}

func blobPath(dataDir, sha string) string {
	if len(sha) < 4 {
		return filepath.Join(dataDir, "objects", sha)
	}
	return filepath.Join(dataDir, "objects", sha[0:2], sha[2:4], sha)
}

func dirBytes(root string) (int64, error) {
	var total int64
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		total += info.Size()
		return nil
	})
	if errors.Is(err, os.ErrNotExist) {
		return 0, nil
	}
	return total, err
}

func fileCount(root string) (int64, error) {
	var total int64
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		total++
		return nil
	})
	if errors.Is(err, os.ErrNotExist) {
		return 0, nil
	}
	return total, err
}

func audit(ctx context.Context, conn *pgx.Conn, op, accessKeyID string) {
	id := randomUUID()
	_, _ = conn.Exec(ctx, "INSERT INTO audit_events(event_id, access_key_id, operation, status, latency_ms, source, detail) VALUES ($1::uuid, $2, $3, 0, 0, 'admin', '{}'::jsonb)", id, nullable(accessKeyID), op)
}

func randomUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func nullable(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func encodeSecret(accessKeyID, secret string, cfg appConfig) (plain *string, ciphertext, nonce []byte, keyID *string, err error) {
	if cfg.SecretKeyB64 == "" {
		return &secret, nil, nil, nil, nil
	}
	key, err := base64.StdEncoding.DecodeString(cfg.SecretKeyB64)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	if len(key) != 32 {
		return nil, nil, nil, nil, errors.New("access-key secret encryption key must decode to 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	nonce = make([]byte, aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, nil, nil, nil, err
	}
	ciphertext = aead.Seal(nil, nonce, []byte(secret), []byte(accessKeyID))
	id := "env:aes-gcm"
	return nil, ciphertext, nonce, &id, nil
}

func randomAccessKeyID() string {
	return "AKIA" + randomString("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 16)
}

func randomSecretAccessKey() string {
	return randomString("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/+", 40)
}

func randomString(alphabet string, n int) string {
	buf := make([]byte, n)
	randomBytes := make([]byte, n)
	_, _ = rand.Read(randomBytes)
	for i := range buf {
		buf[i] = alphabet[int(randomBytes[i])%len(alphabet)]
	}
	return string(buf)
}

func contentTypeForPath(path string) string {
	if ext := filepath.Ext(path); ext != "" {
		if ct := mime.TypeByExtension(ext); ct != "" {
			return ct
		}
	}
	return "application/octet-stream"
}

var signaturePattern = regexp.MustCompile(`(?i)(X-Amz-Signature=)[^&\s]+`)
var credentialPattern = regexp.MustCompile(`(?i)(X-Amz-Credential=)[^&\s]+`)
var authPattern = regexp.MustCompile(`(?i)(Authorization:?\s*)[^\n\r]+`)
var secretPattern = regexp.MustCompile(`(?i)(secret_access_key=)[^\s]+`)

func redact(s string) string {
	s = signaturePattern.ReplaceAllString(s, `${1}<redacted>`)
	s = credentialPattern.ReplaceAllString(s, `${1}<redacted>`)
	s = authPattern.ReplaceAllString(s, `${1}<redacted>`)
	s = secretPattern.ReplaceAllString(s, `${1}<redacted>`)
	return s
}

func redactError(err error) error {
	if err == nil {
		return nil
	}
	return errors.New(redact(err.Error()))
}

func isSHA256(v string) bool {
	if len(v) != 64 {
		return false
	}
	_, err := hex.DecodeString(v)
	return err == nil
}
