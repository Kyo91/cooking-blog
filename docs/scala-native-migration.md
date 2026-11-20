# Migrating Cooking Blog to Scala Native

**Status:** Feasibility and migration proposal, based on `test-out-skunk`. No Native build has been run. Artifact checks below confirm publication, not that the dependency graph links or that the app works on the target OS.

## What the Skunk port bought us

Skunk speaks PostgreSQL’s wire protocol directly. The repository SQL, typed codecs, transaction boundaries, schema, and full-text search can carry over. Keep the same-session transactions and parent-first locking; this is not another persistence rewrite.

What cannot carry over unchanged is the database bootstrap. [`Main.scala:36-39`](../src/main/scala/cookingblog/Main.scala#L36-L39) runs Flyway before opening the pool. [`Database.scala:48-84`](../src/main/scala/cookingblog/database/Database.scala#L48-L84) both runs Flyway over JDBC and calls the PostgreSQL JDBC URL parser to configure Skunk. Move Flyway to a JVM release job or one-shot container, using the existing migration files. Give the Native app typed host, port, database, credentials, and TLS settings without importing the JDBC driver. Keep the existing JDBC URL contract on the JVM path if both targets are supported. Verify the actual database endpoint and trust policy; the current Skunk adapter already rejects `sslmode=verify-full`.

## Dependencies and code that need work

| Area | Current state and migration work |
| --- | --- |
| **Build and packaging** | [`build.sbt:4-27`](../build.sbt#L4-L27) targets Java 21 and packages a JVM image. Add a Native sbt target with `ScalaNativePlugin`, `%%%` for cross-published libraries, and no JVM-specific compiler or fork settings. Keep the JVM target as a baseline. Build and link with Clang for the deployment OS/architecture, and package the executable with its required shared libraries and CA certificates. `sbt-native-packager` is not the Scala Native compiler. |
| **HTTP, config, logging** | The pinned Ember `0.23.32` and Ciris `3.10.0` do not publish Native 0.5 artifacts. Candidate upgrades are http4s `0.23.39` and Ciris `3.15.2`. Replace SLF4J/Logback with a Native-compatible Log4Cats implementation or direct `Logger[IO]` interpreter that still emits operational logs. Test the authenticated CSS/JS routes: [`StaticRoutes.scala:19-20`](../src/main/scala/cookingblog/http/StaticRoutes.scala#L19-L20) uses classloader resources, so explicitly verify embedding or change how assets are packaged and read. |
| **Photos** | [`ImageProcessor.scala:4-14`](../src/main/scala/cookingblog/storage/ImageProcessor.scala#L4-L14) uses AWT/ImageIO, metadata-extractor, and a JVM WebP plugin. Introduce a processor interface at the photo service and evaluate a Native backend (for example, libvips bindings) or keep the existing processor in a separate service. Local storage does **not** remove this dependency. Match content-based JPEG/PNG/WebP validation, byte and pixel limits, EXIF orientation, variant dimensions and content types, and cleanup after a failed or canceled upload. |
| **S3** | [`S3PhotoStore.scala:240-258`](../src/main/scala/cookingblog/storage/S3PhotoStore.scala#L240-L258) constructs the AWS Java SDK client backed by Netty. Keep `PhotoStore`; replace this interpreter with a verified Native-compatible S3 client or retain it behind a separate storage service. Verify streaming, credentials, endpoint and addressing options, pagination, readiness, and deletion against the existing contract tests and the intended provider. A local-only spike is not cloud feature parity. |
| **Scraping** | [`ContentExtractor.scala:6-18`](../src/main/scala/cookingblog/scraping/ContentExtractor.scala#L6-L18) depends on jsoup. Replace the parser or keep the extractor in a separate JVM component. Verify extraction against existing fixtures, outbound HTTPS and certificate checks, and existing safe-fetch behavior. The durable queue and worker SQL can stay. Disabling scraping at runtime does not remove JVM classes from the build; a spike must exclude or replace that interpreter. |
| **Java APIs and crypto** | Scala Native implements a subset of the JDK. In the 0.5.12 `javalib` sources, `MessageDigest` and `SecureRandom` are absent. [`SessionManager.scala:7`](../src/main/scala/cookingblog/auth/SessionManager.scala#L7) uses both; credential comparison, scraping, and the photo migration command also use `MessageDigest`. Use an OS-backed CSPRNG, a vetted SHA-256 implementation, and constant-time comparison; preserve stored hash encodings so existing sessions remain usable. Link and exercise `java.time` (Skunk brings in `scala-java-time`), filesystem paths, DNS, and TLS rather than treating all `java.*` imports as either supported or unsupported. |
| **Tests and maintenance commands** | [`PostgresIntegrationSuite.scala:8-17`](../src/test/scala/cookingblog/testing/PostgresIntegrationSuite.scala#L8-L17) uses Java Testcontainers. Keep that harness on the JVM or provision PostgreSQL and Flyway outside the Native test process. Run Native integration checks against the migrated database and browser tests against the actual executable. The photo migration CLI can remain JVM-only if packaged separately. |

### Artifact check

Maven Central has the Scala Native `0.5.12` compiler plugin for Scala `3.8.4`. The branch's pinned Skunk `1.0.0`, Cats Effect `3.7.0`, Circe `0.14.15` (core, generic, parser), ScalaTags `0.13.1`, and MUnit Cats Effect `2.2.0` have `_native0.5_3` artifacts. The suggested http4s release has all four required artifacts (Ember client/server, DSL, and Circe), and the suggested Ciris release also has one. The pinned Log4Cats core and SLF4J versions do not. Resolve the full Native dependency graph before committing to a version matrix; publication of individual modules is not a compatibility guarantee.

## Suggested migration sequence

1. **Run a small Native spike alongside the JVM app.** On the intended Linux target, link an Ember server with Skunk, external Flyway, and a test PostgreSQL database. Prove login/session round-tripping with the production crypto implementation, one recipe write and rollback, one search, and one authenticated static asset. Do not ship stub implementations or bypass authentication to make the link pass.
2. **Prototype one real photo upload early.** Decode and orient a fixture, generate variants, store them locally, and verify cleanup. This is a better estimate of porting effort than a bare server. Then choose whether to port or isolate the S3 storage and scraping implementations. Keep the current JVM app until the selected replacements pass their contracts.
3. **Validate the release artifact.** Run the repository concurrency and rollback checks, auth and browser flows, and photo and scraping contracts. Exercise startup on a fresh and an existing database, TLS against the intended PostgreSQL and external endpoints, and deployment rollback to the JVM image without a schema change. Keep Flyway as an explicit release step that must succeed before starting the Native app.
4. **Decide based on measurements.** Compare cold start, idle/loaded RSS, image upload performance, and deployment complexity on the same workload. Keep the JVM release unless the measured benefit justifies maintaining new image, S3, scraping, or crypto implementations (or extra services).

## References

- [Scala Native: building with sbt](https://scala-native.org/en/stable/user/sbt.html)
- [Scala Native: Java standard library coverage](https://scala-native.org/en/stable/lib/javalib.html)
- [Skunk documentation](https://typelevel.org/skunk/)
- [`project/Dependencies.scala`](../project/Dependencies.scala) for the current JVM dependency pins
