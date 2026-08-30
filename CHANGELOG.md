# Changelog

All notable changes to Pillar are documented here.

## [Unreleased]

## [0.6.2] — 2026-08-30

Patch release: Redis connection reliability, command tab completion, and correct published metadata.

### Fixed
- Redis driver failures were discarded without a trace. A command that never reached Redis and a Redis that simply answered no both surfaced as the same empty result internally, and neither left anything in the log, so an operator had no way to tell them apart after the fact. The driver's own reason for failing is now logged with its cause, throttled so a store that keeps failing does not produce one line per call. What callers receive is unchanged, so no API behavior moved.
- The inbox consumer could stop consuming indefinitely while the plugin still reported Redis as healthy. Jedis lifts the socket read timeout for the duration of a blocking command, so the consumer's blocking `XREADGROUP` carried no client-side deadline at all: a connection that stopped delivering without closing, such as a replaced Redis container or an expired NAT entry, left the consumer thread waiting on it forever, and the health check runs on a separate connection. The connection pool is now built with an explicit deadline for blocking reads, set above the consumer's block window.
- `/pillar` subcommands were flagged as invalid arguments in the client's command display, and tab completion never worked. The command registered an executor but never a tab completer, so `PillarCommand.onTabComplete` was unreachable and the client was told `/pillar` accepts no arguments.
- `plugin.yml` reported a hardcoded `0.6.0` regardless of the built version, so the server's plugin list showed the wrong number. The descriptor now takes the version from the build.
- `/pillar` usage text listed only `fleet` and `status`, omitting `doctor`, `ping`, and `reload`.
- A single slow Redis command made the platform declare the connection unreachable, which stopped every other operation until the next health check. On a busy machine a network hiccup of well under a second was enough, and while the state held, work that Redis could still have served was refused outright, including lease acquisition, which reaches players as a resource that will not open. The health check now needs three consecutive failures before declaring a loss, so a brief stall passes without consequence while a genuine outage is still recognised within fifteen seconds.
- The summary line of `/pillar reload <role>` printed a literal `<count>` instead of a result. The message named a placeholder the command never provides, and an unresolved placeholder is passed through to the player as written. It now reports how many nodes succeeded and how many failed, which the command already tracked.
- `/pillar reload` did not complete its role argument. Paper and Velocity now suggest the distinct roles represented by servers currently online, without maintaining a second catalogue of roles that may be stale.
- A remote reload left no operational record on the receiving node. Each node now logs who requested the reload and whether reloading its configuration succeeded or failed, retaining the failure cause for an administrator.
- `gradlew` was not marked executable in the repository, so `./gradlew` failed with a permission error on a fresh clone on Linux or macOS. Contributors had to work around it; it now carries the executable bit.
- The `pillar-api` project and SCM links published to Maven Central pointed at a GitHub organization that does not exist, so source links from the artifact page led nowhere and dependency bots could not fetch the changelog. They now point at the real repository.

### Changed
- Gson is now a declared dependency instead of arriving only through Jedis. Nothing about the build output changes today, at the same 2.11.0 it already resolved to; the point is that a future Jedis bump can no longer move the library that deserializes the wire format without that being visible here.

## [0.6.1] — 2026-07-29

Patch release: fixes a login-time crash uncovered during Velocity 4.0.0 compatibility testing.

### Fixed
- `routing.entry-role` left empty in `config-velocity.yml` crashed every login attempt (`ServerRole must not be blank`) instead of skipping Pillar's routing, despite the config file's own comment documenting that exact behavior. `LoginListener` now correctly returns before touching the event when the role is blank, leaving Velocity's own `try` list to pick the server.

## [0.6.0] — 2026-07-19

Sixth release: Documentation, Module Split, and Maven Central. Completes the public documentation and prepares the codebase for community adoption by strictly separating the API contracts from the implementation.

### Added
- **Public Documentation** — Extensive documentation site launched at [pillar.markineo.com.br](https://pillar.markineo.com.br), featuring architecture overviews, developer cookbooks, and a getting started guide.
- **Internationalization (i18n)** — Full documentation support for English (EN), Spanish (ES), and Brazilian Portuguese (PT-BR), heavily audited for native software engineering taxonomy.
- **Maven Central Publication** — The `pillar-api` artifact is now published to Maven Central, allowing consumers to securely depend on the API contracts.

### Changed
- **Module Split** — Hard architectural boundary enforced via Gradle: `pillar-api` contains pure API contracts with no external dependencies, while `pillar-plugin` houses implementations, shading, and platform adapters.
- **Lease API Minimization** — Reduced the surface area of `LeaseService` by removing `OwnerToken` exposures, relying entirely on atomic Redis script fencing to guarantee safety.

## [0.5.0] — 2026-07-17

Fifth release: The Messaging API & Javadoc Consolidation. Extracts the internal core capabilities into a stable, public-facing API surface.

### Added
- **Public API Root** — Introduced `PillarProvider` as the static entry point for consumer plugins, exposing the `Pillar` interface for interacting with the control-plane.
- **Messaging API** — `pillar.messaging()` provides a high-level `CompletableFuture` API for cross-server communication: `listen`, `send`, `request`/`handle`, and `broadcast`.
- **Type-safe Payloads** — Introduced `PillarMessage<T>` for strong type binding of message identifiers to their record-based payloads. Mandatory namespace validation (`plugin:name`).
- **Leases API** — `pillar.leases()` exposes distributed mutual exclusion primitives (`LeaseService`) to public consumers, enabling cross-server state sync and island-lease mechanisms.
- **Routing API** — `pillar.routing()` exposes the `move(player, role)` and `moveToServer` APIs for proxy-actuated player routing.
- **Level-2 Defense** — Introduced `PillarFutures` anti-join guard to aggressively prevent consumers from blocking the main server thread, failing fast with clear error messages.
- **Architecture Boundaries** — ArchUnit tests now enforce that consumers depend only on the `pillar-api` surface and never leak internal transport or core classes.

## [0.4.0] — 2026-07-13

Fourth release: production hardening and resilience. Transforms the system from a functioning MVP into a fault-tolerant mesh that degrades gracefully under fire.

### Added
- **/pillar doctor command** — new administrative subcommand providing typed decision telemetry and anomaly-focused health checks, built with a strictly local-first philosophy (zero-cost `AtomicLong` counters and ring buffers) to ensure availability even when Redis is down.
- **Orphan-inbox reaping** — the proxy now runs a self-healing background service (`InboxReaper`) that safely reaps abandoned inboxes and dead-letters from permanently dead nodes using atomic Lua scripts (`EXISTS` + `DEL`), preventing memory leaks over time.
- **Degraded modes** — introduced read-outcome-driven stale retention with bounded windows. Nodes now automatically fall back to their last-known-good state during transient Redis outages, explicitly documenting their degraded contracts.
- **Targeted reloads** — `/pillar reload <role>` allows signal-only `RELOAD_CONFIG` fan-out to specific server roles, replacing cluster-wide config blasts.

### Changed
- Refactored `DecisionBuffer` (placement history) into a lock-free, zero-allocation Ring Buffer using `AtomicReferenceArray` and `AtomicInteger`, preventing O(N) traversal and GC churn on the login hot-path.
- Rewrote `PlacementDecision` sum types using Java 21+ sealed interfaces and records, eliminating `Optional` heap allocation overheads in critical loops.
- `HealthRegistry.oldestSnapshotAge()` now correctly returns `Optional<Duration>` to properly distinguish between uninitialized states and fresh snapshots, eliminating false-positive "0s" readings during degraded startups.
- Refactored Fully Qualified Names in transport classes to use standard static imports, improving codebase readability.

### Security & Tests
- Added failure drills: automated Testcontainers assertions locking the degraded-mode contract and orphan reaping mechanisms, ensuring resilience features are immune to future regressions.

## [0.3.0] — 2026-07-10

Third release: MVP consolidation. Brings player routing, cross-server actuation, and the distributed lease primitive.

### Added
- **Player routing at login** — `PlacementService` composes eligibility, Power of Two Choices (P2C) scoring, and active reservations over the cached fleet health. Velocity intercepts joining players (`PlayerChooseInitialServerEvent`) and routes them to the best candidate for the entry role, or kicks with a localized message if no node is eligible.
- **In-memory health cache** — `HealthRegistry` runs a background loop to refresh the `HealthSnapshot` for all active nodes via a single `HealthView.fetchAll(cachedFleet)` call, completely removing Redis I/O from the login hot-path.
- **Cross-server player moves** — `ROUTE_PLAYER` message type allows any node in the control plane to request moving a specific player to a specific server (or role). The proxy actuates the move and replies with the outcome.
- **Network messaging** — `SEND_PLAYER_MESSAGE` and `BROADCAST` message types allow dispatching text components to specific players or the entire network directly from any backend node.
- **Generic lease primitive** — `core.lease` and `redis.lease` introduce distributed mutual exclusion without fencing tokens (`SET NX PX` acquire, Lua-based owner-checked renew/release).
- **MVP Reliability Polish** — Added a 30s periodic PEL drain to automatically retry handlers that failed deterministically (PIL-41); replaced fragile string matching for timeouts with `TimeoutPillarException` (PIL-42); implemented immediate proactive node eviction (`evict(ServerId)`) in `PresenceService` upon connection request failures (PIL-43).
- **Dynamic Versioning** — Entry points (Paper/Velocity) now extract the plugin version directly from their compiled metadata (`plugin.yml` / `velocity-plugin.json`) instead of hardcoded strings in the startup log.

### Changed
- Refactored `JsonEnvelopeCodec` payload serialization to O(n) string literals instead of O(3n) JSON subtrees, changing the wire format (Protocol Version bumped to 2).
- Consolidated Redis connection acquisitions behind a safe `RedisConnector.withResource(fn)` boundary, preventing `JedisPool` leakage.
- Micro-optimized `ReservationRegistry` to rely on a pure `synchronized ArrayDeque`, eliminating GC pressure from `ConcurrentLinkedQueue` node allocations.

## [0.2.0] — 2026-07-09

Second release: health snapshots and the routing placement logic (pure algorithm, runtime integration deferred to 0.3.0).

### Added
- **Health snapshots** — Nodes now periodically publish metrics: MSPT, memory usage, player count, world count, and pending signals.
- **Placement Logic** — Introduced `EligibilityFilter` (hard caps evaluation), `PlacementSelector` (Power of Two Choices algorithm over health data), and `ReservationRegistry` (in-flight connection reservations with TTL).
- **Simulation Harness** — Added a placement simulation suite to validate synthetic bursts and cap-violation metrics under load.
- **Dispatch Model Rewrite** — `StreamConsumer` now offloads handler execution to a bounded `ThreadPoolExecutor` worker pool. `XACK` is decoupled from synchronous returns and strictly confirms successful processing.
- **Idempotency** — Implemented at-least-once dedup keys for incoming stream messages to ensure safe retries.
- **Minimal PEL reclaim** — Consumers now drain their own PEL on (re)start.

### Changed
- Subdivided the `redis` package into `presence`, `transport`, and `lifecycle` for architectural clarity.
- The `/pillar fleet` command now serves from the in-memory `cachedFleet()` to avoid blocking the main thread with `SCAN+MGET`.

## [0.1.0] — 2026-07-08

First release: the control-plane communication foundation. A single artifact runs on
both Paper (game servers) and Velocity (proxies), discovering peers and exchanging
messages over Redis. **Pre-release** — for validation, not production.

### Added
- **Fleet presence** — heartbeat keys with native TTL expiry (`SETEX`, TTL = 3× the
  3 s interval); `FleetView` (`SCAN`+`MGET`) and `PresenceService` own the schedule. A
  node that stops beating drops from every view within ~9 s, with no central registry.
- **Message envelope** — immutable `Envelope` (versioned, correlation id, string
  payload), the `EnvelopeCodec` seam, and a compact-JSON `JsonEnvelopeCodec` with
  two-phase decode.
- **Stream transport** — `StreamPublisher` (`XADD` to a per-node inbox) and
  `StreamConsumer` (`XREADGROUP` + `XACK`, single consumer group), dropping undecodable
  poison entries and leaving handler failures pending for later recovery.
- **Request/response correlation** — `CorrelationRegistry` and `RequestSender` expose a
  non-blocking `CompletableFuture` API with scheduled timeouts; `HandlerRegistry`
  dispatches by type and routes responses back to awaiting futures.
- **Commands** — `/pillar fleet`, `/pillar status` (connection state, pending inbox
  count, recent-activity log tail), `/pillar ping <server>`, `/pillar reload`, on both
  Paper and Velocity, gated by `pillar.admin`. Tab-completion for subcommands and
  server names.
- **Configuration & language** — YAML loading with dotted-path access, fail-fast typed
  settings, and MiniMessage locales (`en-us`, `pt-br`) with en-us fallback.
- **Foundations** — `PillarException` hierarchy, `PillarLogger` over SLF4J with a
  diagnostics ring buffer, and named Pillar-owned executors with a logging uncaught
  handler.
- **Redis lifecycle** — `RedisConnector` (pooled) with a `STARTING/READY/DEGRADED/
  SHUT_DOWN` state machine driven by a single health loop; Redis loss degrades rather
  than crashes the host.
- **Tooling** — Gradle + Shadow build with Jedis and SnakeYAML relocated under
  `com.markineo.pillar.lib`; JUnit 5 unit tests and Testcontainers Redis integration
  tests; a `test-topology/` local network with a failure-drill checklist.

### Known limitations
- Messages delivered but unacknowledged when a JVM is killed (`kill -9`) stay pending
  until PEL reclaim is implemented (PIL-40).
- A node pinging itself is misrouted (self-send); the command layer short-circuits
  `ping <self>`, but the dispatch-level bug remains (covered by `HandlerRegistryTest`).
- Message handling is single-threaded per node — adequate for this release's traffic,
  to be reworked before heavier handlers arrive in Iteration 2.
- Extracted `lang/` files are not overwritten on update; a stale file can shadow keys
  added in a newer version (PIL-18).

[Unreleased]: https://github.com/vinicius-paraujo/Pillar/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/vinicius-paraujo/Pillar/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/vinicius-paraujo/Pillar/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/vinicius-paraujo/Pillar/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/vinicius-paraujo/Pillar/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/vinicius-paraujo/Pillar/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/vinicius-paraujo/Pillar/releases/tag/v0.1.0

---
*Adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) and [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).*
