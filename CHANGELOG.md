# Changelog

All notable changes to Pillar are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the version is `0.y.z`, the public surface is unstable and may change with any
minor release.

## [Unreleased]

_Iteration 2 — health snapshots and routing decisions._

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

[Unreleased]: https://example.com/pillar/compare/v0.1.0...HEAD
[0.1.0]: https://example.com/pillar/releases/tag/v0.1.0
