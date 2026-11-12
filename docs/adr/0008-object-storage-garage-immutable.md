# 8. Garage/B2/R2 via one S3 adapter; immutable objects instead of bucket versioning

Date: 2026-11-11. Status: accepted. Decisions: D-34, D-08 (MinIO dropped, F-S3-01).

## Context
MinIO is unmaintained; Garage (active) lacks bucket versioning (F-S3-02). Backups were planned to
rely on versioning (D-28 original).

## Decision
One S3ObjectStore adapter; Garage for self-hosting, B2/R2 for cloud. Objects are immutable (new id
per change); deletion is a delayed state machine (deleting -> GC job). Operator backup = scheduled
rclone sync to a second location.

## Consequences
+ No versioning requirement; accidental overwrite is impossible by construction; GC is idempotent
  and auditable (data-model.md §5).
+ Same code path for every provider; provider switch is config.
- Deleted data is gone after GC + backup retention; the trash window (30 d) + grace (7 d) are the
  user-facing undo (media.md §7).
