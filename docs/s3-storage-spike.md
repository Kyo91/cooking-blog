# S3 client spike

Phase 9 evaluated `fs2-aws-s3` before adding an object-storage dependency.
No dependency is added yet because the Phase 10 implementation should pin only
the client layer used by the completed `S3PhotoStore`.

## Required operations

The existing `PhotoStore` boundary requires:

- streaming put and read for immutable variants;
- content type and cache metadata on writes;
- idempotent deletion of every object under one storage key;
- paginated listing with last-modified timestamps for orphan cleanup;
- bucket/access readiness checks;
- endpoint override, signing region, path-style access, static credentials, and
  the AWS default credential chain;
- bounded connection and request resources;
- optional presigning if authenticated media redirects are adopted later.

## Findings

Metals dependency lookup found `io.laserdisc:fs2-aws-s3_3` with `7.0.0-RC1` as
the newest published candidate on July 27, 2026. The latest stable project
release was `6.5.0`, so production work must not select the release candidate
implicitly.

The documented high-level `S3[F]` API supplies streaming upload, multipart
upload, read, multipart read, and delete. Those operations fit the photo byte
paths, but the documented API does not by itself cover metadata-rich puts,
paginated listing, bucket checks, or presigning:

<https://github.com/laserdisc-io/fs2-aws>

The library is built over the AWS SDK v2 asynchronous client. Phase 10 selected
the AWS SDK v2 `S3AsyncClient` directly, pinned at `2.49.4` after the Metals
dependency lookup. This keeps the client surface small while supporting
metadata-rich writes, paginated listings, bucket checks, endpoint overrides,
signing regions, and path-style addressing without mixing two overlapping S3
abstractions. Generated variants are streamed from temporary files on upload;
the authenticated proxy response currently buffers one already-bounded variant
through the SDK response transformer.

Custom endpoints still require a signing region. Addressing style must be
configurable because AWS SDK v2 normally uses virtual-hosted addressing with an
endpoint override, while local S3-compatible services commonly need path-style
access:

<https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html>

## Phase 10 decision gate

Before changing `build.sbt`:

1. Pin a stable Scala 3 artifact using the Metals dependency lookup.
2. Implement put/read/delete/list/readiness against the development
   S3-compatible service.
3. Verify cancellation and partial-write compensation.
4. Check the resulting dependency graph for one aligned AWS SDK v2 version.
5. Prefer the smaller working dependency surface.

The application will continue proxying authenticated `/media` responses for
the first cloud cutover. Presigned redirects remain optional and should be
introduced only after provider smoke tests and an egress/latency measurement.
