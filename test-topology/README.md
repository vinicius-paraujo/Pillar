# Test Topology

Local environment for end-to-end acceptance and failure drills.
All processes run on one machine — no VMs, no hypervisor.

## Components

| Component | Process | Port | Pillar identity |
|---|---|---|---|
| Redis 7 | Docker (compose) | 6379 | — |
| Velocity 3.4 + Pillar | JVM | 25565 | `proxy-1` |
| Paper 26.1.2 + Pillar ("alpha") | JVM | 25566 | `alpha` (role `skyblock`) |
| Paper 26.1.2 + Pillar ("beta") | JVM | 25567 | `beta` (role `skyblock`) |

## Setup

### 1. Start Redis

```bash
cd test-topology
docker compose up -d
```

### 2. Build the plugin

```bash
cd Pillar
./gradlew shadowJar
```

The shadow jar is at `Pillar/build/libs/Pillar-1.0.0.jar`.

### 3. Prepare server directories

Each subdirectory (`alpha/`, `beta/`, `proxy/`) has a pre-configured
`plugins/Pillar/config.yml` (or `config-velocity.yml`).

Download the server jars manually (they are not checked in):

- **Paper 26.1.2** → `alpha/paper.jar` and `beta/paper.jar`
- **Velocity 3.4.0** → `proxy/velocity.jar`

Accept the EULA for each Paper server:

```bash
echo "eula=true" > alpha/eula.txt
echo "eula=true" > beta/eula.txt
```

Copy the plugin to each server:

```bash
cp Pillar/build/libs/Pillar-1.0.0.jar alpha/plugins/
cp Pillar/build/libs/Pillar-1.0.0.jar beta/plugins/
cp Pillar/build/libs/Pillar-1.0.0.jar proxy/plugins/
```

### 4. Configure Velocity to know both backends

Edit `proxy/velocity.toml` (created on first run) and add both backends:

```toml
[servers]
alpha = "127.0.0.1:25566"
beta = "127.0.0.1:25567"

try = ["alpha"]
```

### 5. Start the servers

In separate terminals:

```bash
cd alpha && java -jar paper.jar --port 25566 --nogui
cd beta  && java -jar paper.jar --port 25567 --nogui
cd proxy && java -jar velocity.jar
```

### 6. Verify

On any server console:

```
/pillar fleet
```

Should show all three nodes within one heartbeat interval (~3 s) of startup.

## Failure drills

| Drill | Command | Expected |
|---|---|---|
| Node death | Kill the alpha process | `alpha` disappears from fleet within ~9 s (TTL) on all other nodes |
| Redis restart | `docker compose restart redis` | Nodes log one DEGRADED transition, reconnect, presence resumes |
| Graceful restart | Stop and restart alpha | Consumer group retains undelivered entries; no messages lost |
