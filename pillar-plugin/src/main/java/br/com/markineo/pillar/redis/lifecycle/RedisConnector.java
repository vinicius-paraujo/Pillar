package br.com.markineo.pillar.redis.lifecycle;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.config.RedisSettings;
import br.com.markineo.pillar.logger.PillarLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class RedisConnector implements AutoCloseable {

    private static final Duration HEALTH_INTERVAL = Duration.ofSeconds(5);
    private static final int SOCKET_TIMEOUT_MILLIS = 2000;

    private final RedisSettings settings;
    private final PillarExecutors executors;
    private final PillarLogger logger;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.STARTING);
    private final AtomicReference<Instant> stateSince = new AtomicReference<>(Instant.now());
    private JedisPool pool;
    private ScheduledExecutorService healthCheck;

    public RedisConnector(RedisSettings settings, PillarExecutors executors, PillarLogger logger) {
        this.settings = settings;
        this.executors = executors;
        this.logger = logger;
    }

    public void start() {
        String password = settings.password().isBlank() ? null : settings.password();
        this.pool = new JedisPool(
                poolConfig(),
                settings.host(),
                settings.port(), SOCKET_TIMEOUT_MILLIS,
                password
        );

        probe();

        this.healthCheck = executors.newSingleThreadScheduled("redis-health");
        healthCheck.scheduleWithFixedDelay(this::probe,
                HEALTH_INTERVAL.toMillis(), HEALTH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    // A control plane holds several connections at once: the blocking XREADGROUP pins
    // one for up to the block window, the PEL drain another, plus heartbeat, health, and
    // command paths (placement reads join them in Iteration 3). The pool defaults are a
    // trap here — maxTotal 8 starves under a login storm, and maxWait -1 then blocks
    // callers forever instead of failing fast into the degraded state the health loop
    // already handles. Size both explicitly; maxIdle tracks maxTotal so bursts don't
    // churn connections through the evictor.
    private JedisPoolConfig poolConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(settings.poolMaxTotal());
        config.setMaxIdle(settings.poolMaxTotal());
        config.setMaxWait(settings.poolMaxWait());
        return config;
    }

    public ConnectionState state() {
        return state.get();
    }

    public Duration stateDuration() {
        return Duration.between(stateSince.get(), java.time.Instant.now());
    }

    public RedisSettings settings() {
        return settings;
    }

    public boolean isReady() {
        return state.get() == ConnectionState.READY;
    }

    public Jedis getResource() {
        return pool.getResource();
    }

    public <T> Optional<T> withResource(Function<Jedis, T> action) {
        if (!isReady()) {
            return Optional.empty();
        }

        try (Jedis jedis = getResource()) {
            return Optional.ofNullable(action.apply(jedis));
        } catch (JedisException e) {
            return Optional.empty();
        }
    }

    public static List<String> scanKeys(Jedis jedis, String pattern, int count) {
        ScanParams params = new ScanParams().match(pattern).count(count);
        List<String> keys = new ArrayList<>();
        String cursor = ScanParams.SCAN_POINTER_START;

        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

        return keys;
    }

    @Override
    public void close() {
        state.set(ConnectionState.SHUT_DOWN);
        if (healthCheck != null) {
            healthCheck.shutdownNow();
        }
        if (pool != null) {
            pool.close();
        }
    }

    private void probe() {
        if (state.get() == ConnectionState.SHUT_DOWN) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
            markReady();
        } catch (RuntimeException e) {
            markDegraded(e);
        }
    }

    private void markReady() {
        // Refuse to leave SHUT_DOWN: a probe racing a close() must not revive the connector.
        ConnectionState previous = state.getAndUpdate(this::readyUnlessShutDown);
        if (previous != ConnectionState.READY && previous != ConnectionState.SHUT_DOWN) {
            stateSince.set(Instant.now());
            logger.info("Redis connection ready (" + settings.host() + ":" + settings.port() + "). Recovered from degraded state.");
        }
    }

    private void markDegraded(RuntimeException cause) {
        ConnectionState previous = state.getAndUpdate(this::degradedUnlessShutDown);
        if (previous != ConnectionState.DEGRADED && previous != ConnectionState.SHUT_DOWN) {
            stateSince.set(Instant.now());
            logger.warn("Redis unreachable (" + settings.host() + ":" + settings.port()
                    + "); running degraded, retrying every " + HEALTH_INTERVAL.toSeconds()
                    + "s: " + cause.getMessage());
        }
    }

    private ConnectionState readyUnlessShutDown(ConnectionState current) {
        return current == ConnectionState.SHUT_DOWN ? current : ConnectionState.READY;
    }

    private ConnectionState degradedUnlessShutDown(ConnectionState current) {
        return current == ConnectionState.SHUT_DOWN ? current : ConnectionState.DEGRADED;
    }
}
