# Pillar

Pillar is the **control plane** of a Minecraft network. It knows the fleet, carries
cross-server messages, and — as it grows — routes players and distributes tasks. Game
plugins (skyblock, minigames, and so on) are the **data plane**: they own their worlds,
schemas, and mechanics, and consume Pillar.

A single artifact runs on both sides of the network:

- **Paper** nodes (game servers) — the plugin entry point is `paper.Pillar`.
- **Velocity** proxies — the plugin entry point is `velocity.PillarVelocity`.

Instances discover and talk to each other over **Redis**: presence through expiring
heartbeat keys, messaging through Redis Streams with request/response correlation.

> Status: **Iteration 1 complete** — asynchronous inter-instance communication is in
> place (fleet presence, stream transport, correlated request/response, diagnostics).
> Iteration 2 (health snapshots and routing decisions) is next. See
> [`development/BOARD.md`](../development/BOARD.md) for the live task board.

## How it works

- **Presence.** Each node writes a heartbeat key (`SETEX`, TTL = 3× the 3 s interval).
  A node that stops beating expires from every fleet view within ~9 s via native TTL —
  no central registry, no cleanup job. `FleetView` reads the live set with `SCAN`+`MGET`;
  latency-sensitive callers (tab-completion) read an in-memory snapshot refreshed on the
  heartbeat loop instead.
- **Transport.** Messages are compact JSON envelopes published with `XADD` to a
  per-node inbox stream (`pillar:inbox:<id>`) and consumed with a single consumer group
  (`XREADGROUP` + `XACK`). Undecodable "poison" entries are dropped; a handler failure
  leaves the entry pending for later recovery.
- **Request/response.** `RequestSender` registers a correlation id, publishes, and
  returns a `CompletableFuture`. The reply's id resolves the future; a scheduled timeout
  fails it if no reply arrives. Results that touch game state hop back to the main thread
  through `PaperScheduler` / `VelocityScheduler`.

A full round trip is diagrammed under **Message flow** in
[`development/ARCHITECTURE.md`](../development/ARCHITECTURE.md).

## Commands

`/pillar` (permission `pillar.admin`), identical surface on Paper and Velocity:

| Subcommand | Description |
|---|---|
| `/pillar fleet` | Lists every node currently in the fleet. |
| `/pillar status` | Connection state, pending inbox entries, and a recent-activity log tail. |
| `/pillar ping <server>` | Measures round-trip latency to another node. |
| `/pillar reload` | Reloads message text and by-path config values. |

## Build

Gradle 9.6.1 + Shadow, Java 25 toolchain. Jedis and SnakeYAML are bundled and relocated
under `com.markineo.pillar.lib`.

```bash
# from the repository root
Pillar/gradlew.bat -p Pillar shadowJar     # Windows
./gradlew -p Pillar shadowJar              # *nix
```

The shaded plugin lands at `Pillar/build/libs/Pillar-0.1.0.jar`. Drop it into the
`plugins/` folder of each Paper server and the Velocity proxy.

To compile without packaging (the standard inner-loop check):

```bash
Pillar/gradlew.bat -p Pillar compileJava
```

## Configuration

Plain YAML, extracted to the plugin's data folder on first run.

- Paper — `config.yml`: server `name`/`role`, `redis` connection, active `language`.
- Velocity — `config-velocity.yml`: proxy `name`, `redis` connection, `language`.
- `lang/en-us.yml`, `lang/pt-br.yml`: MiniMessage-formatted message catalogs
  (en-us is the fallback locale).

Critical values (identity, Redis host) are read fail-fast: a missing or empty one aborts
enable with an actionable message. Optional values are read by path with a code default,
so adding a setting never means adding a class.

> Note: extracted config/lang files are not overwritten on update. A stale file can
> shadow keys added in a newer version — delete the extracted `lang/` folder to
> re-extract. A bundled-first resolution is tracked as PIL-18.

## Run it locally

`test-topology/` is a ready-made local network — a Velocity proxy, two Paper nodes, and
a Redis container — with pre-seeded configs and a failure-drill checklist. See
[`test-topology/README.md`](test-topology/README.md).

## Testing

JUnit 5 for pure-unit logic (envelope codec, correlation registry, dispatch, fleet
snapshot, config paths) and Testcontainers for Redis-backed integration (presence,
stream transport, ping/pong end-to-end). Integration tests self-skip when Docker is
absent.

```bash
Pillar/gradlew.bat -p Pillar test
```

## Project layout

```
com.markineo.pillar
├── core        Pure Java: identity, fleet, task envelope + correlation (no Jedis, no platform)
├── error       Unchecked exception hierarchy (PillarException)
├── logger      PillarLogger over SLF4J, with a diagnostics ring buffer
├── concurrent  Named executors and the platform-scheduler seam
├── config      YAML loading, dotted-path access, typed settings, language
├── redis       The only package that imports Jedis: connection, presence, transport
├── paper       Paper adapter — entry point, commands, main-thread scheduler
└── velocity    Velocity adapter — entry point, command, scheduler
```

Package boundaries are enforced by convention now and by ArchUnit later (PIL-51). The
authoritative rules live in [`development/ARCHITECTURE.md`](../development/ARCHITECTURE.md).

## Contributing

This is a one-person, iteratively-built project with a strict working guide. Before
writing code, read [`development/STANDARDS.md`](../development/STANDARDS.md),
[`development/ARCHITECTURE.md`](../development/ARCHITECTURE.md), and
[`development/BOARD.md`](../development/BOARD.md). In short: American English everywhere,
comments explain *why* not *what*, constructor injection, immutability by default, no
mutable static state, and the smallest solution that works.
