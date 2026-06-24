# Gaps 3

Status after the current hardening pass: all five items below have been addressed in code, tests, or docs. This file is retained as an audit log of the red flags that drove the pass.

Resolution summary:

- Blob GC now treats active `blob_write_intents` as references, applies a minimum blob age, quarantines orphan candidates, and rechecks references before final deletion.
- `UploadPart` final commit rechecks `state == "INITIATED"` before inserting or replacing a part.
- `CompleteMultipartUpload` fails immediately when `INITIATED -> COMPLETING` does not transition.
- `after-fsync` is now a real failpoint between temp-file fsync and atomic rename, with lower-level split-phase blob tests.
- `docs/MILESTONES.md` now marks M3.1 features as deferred from M3 instead of current M3 gaps.

## 1. Blob GC is still unsafe

`RecoveryJob.sweepUnreferencedBlobs()` performs the DB reference check and filesystem deletion inside a `SERIALIZABLE` database transaction, but filesystem deletion is not part of the database transaction.

If the DB transaction aborts, the deleted blob file is not restored.

A dangerous race still exists:

```txt
1. An upload writes content-addressed blob X.
2. The metadata row has not committed yet.
3. GC sees blob X as unreferenced.
4. GC deletes blob X.
5. The upload commits metadata pointing to blob X.
6. The object now points to a missing blob.
```

Serializable isolation does not make external filesystem deletion atomic with PostgreSQL.

## 2. `UploadPart` can still race with `CompleteMultipartUpload`

The first `UploadPart` check rejects uploads in `COMPLETING` state, but after the body is streamed and committed to disk, the final DB transaction re-checks the upload and can still accept `COMPLETING`.

`repo.getMultipartUpload()` returns uploads in both states:

```sql
state IN ('INITIATED', 'COMPLETING')
```

This race is still possible:

```txt
1. UploadPart starts while the upload is INITIATED.
2. CompleteMultipartUpload changes the upload to COMPLETING.
3. UploadPart finishes streaming.
4. UploadPart inserts or replaces a part while completion is in progress.
```

The final `UploadPart` transaction must require `mpu.state == "INITIATED"` before inserting or replacing the part.

## 3. `CompleteMultipartUpload` ignores a failed transition to `COMPLETING`

`CompleteMultipartUpload` stores the result of `repo.markMultipartCompleting(conn, uploadId)` but does not fail when the transition returns `false`.

This allows a concurrent completion attempt to continue even if it did not successfully transition the upload to `COMPLETING`.

A race is possible:

```txt
1. Two clients call CompleteMultipartUpload concurrently.
2. Both initially observe INITIATED.
3. One request successfully marks the upload as COMPLETING.
4. The other request gets `false` from markMultipartCompleting.
5. The second request continues anyway.
```

The request must fail immediately if the transition to `COMPLETING` did not succeed.

## 4. Failpoint crash tests still do not cover a real `after fsync before rename` window

The current failpoint test approximates the `after-fsync` crash point because `write.commit()` performs both fsync and atomic rename internally.

This means the following failure window is still not directly tested:

```txt
temp file fsynced
process dies before atomic rename
```

This is still an important durability gap.

## 5. `docs/MILESTONES.md` contains stale milestone gap text

`docs/MILESTONES.md` still has an older “Known gaps at end of M3” section that says presigned URLs and multipart checksums are not implemented, while later sections say M3.1 implements them.

The later section clarifies the current state, but the older gap list is misleading and should be cleaned up.
