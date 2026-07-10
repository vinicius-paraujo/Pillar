# Changelog

All notable changes to Pillar are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the version is `0.y.z`, the public surface is unstable and may change with any
minor release.

## [Unreleased]

_Iteration 4 — production hardening and resilience._

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

[Unreleased]: https://example.com/pillar/compare/v0.3.0...HEAD
[0.3.0]: https://example.com/pillar/compare/v0.2.0...v0.3.0
[0.2.0]: https://example.com/pillar/compare/v0.1.0...v0.2.0
[0.1.0]: https://example.com/pillar/releases/tag/v0.1.0
