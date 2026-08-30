package br.com.markineo.pillar.redis.lifecycle;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.config.RedisSettings;
import br.com.markineo.pillar.logger.PillarLogger;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class RedisConnector implements AutoCloseable {

    private static final Duration HEALTH_INTERVAL = Duration.ofSeconds(5);
    private static final int SOCKET_TIMEOUT_MILLIS = 2000;

    // Jedis lifts the socket read timeout for the duration of a blocking command and
    // restores it afterwards, so without this value a blocking XREADGROUP inherits no
    // deadline at all: a connection that stops delivering without an RST (replaced
    // container, expired conntrack entry, firewall) parks the consumer thread for good,
    // and shutdownNow cannot free it because it interrupts threads, not socket reads.
    // It has to exceed the consumer's own block window, which is 2s, by enough margin
    // that a merely slow reply is never mistaken for a dead socket.
    private static final int BLOCKING_SOCKET_TIMEOUT_MILLIS = 5000;
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofSeconds(30);

    // Declaring DEGRADED stops every caller through withResource, so one slow sample from a
    // background loop must not do it: on a contended host a sub-second stall would refuse
    // work the store could still have served. At the health interval this is 15s to call a
    // real loss, which is still well inside a TTL.
    private static final int FAILURES_BEFORE_DEGRADED = 3;

    private final RedisSettings settings;
    private final PillarExecutors executors;
    private final PillarLogger logger;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.STARTING);
    private final AtomicReference<Instant> stateSince = new AtomicReference<>(Instant.now());
    private final AtomicReference<Instant> lastFailureLog = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong suppressedFailures = new AtomicLong();
    private final AtomicInteger consecutiveProbeFailures = new AtomicInteger();
    private JedisPool pool;
    private ScheduledExecutorService healthCheck;

    public RedisConnector(RedisSettings settings, PillarExecutors executors, PillarLogger logger) {
        this.settings = settings;
        this.executors = executors;
        this.logger = logger;
    }

    public void start() {
        this.pool = new JedisPool(
                poolConfig(),
                new HostAndPort(settings.host(), settings.port()),
                clientConfig()
        );

        probe();

        this.healthCheck = executors.newSingleThreadScheduled("redis-health");
        healthCheck.scheduleWithFixedDelay(this::probe,
                HEALTH_INTERVAL.toMillis(), HEALTH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private JedisClientConfig clientConfig() {
        return DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(SOCKET_TIMEOUT_MILLIS)
                .socketTimeoutMillis(SOCKET_TIMEOUT_MILLIS)
                .blockingSocketTimeoutMillis(BLOCKING_SOCKET_TIMEOUT_MILLIS)
                .password(settings.password().isBlank() ? null : settings.password())
                .build();
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
            logFailure(e);
            return Optional.empty();
        }
    }

    // The empty result is the right answer for the caller and the wrong answer for the
    // record: callers map it to the same "no" they get when Redis actually answered, so
    // without this the driver's reason for failing exists nowhere and an operator cannot
    // tell a refusal from a store that was never reached. Throttled rather than per-call,
    // because a store that stops answering fails every borrow in the window before the
    // health loop degrades the connector; the suppressed count keeps the magnitude
    // visible without the line-per-call flood.
    private void logFailure(JedisException cause) {
        Instant now = Instant.now();
        Instant last = lastFailureLog.get();
        if (Duration.between(last, now).compareTo(FAILURE_LOG_INTERVAL) < 0
                || !lastFailureLog.compareAndSet(last, now)) {
            suppressedFailures.incrementAndGet();
            return;
        }

        long suppressed = suppressedFailures.getAndSet(0);
        String repeats = suppressed == 0 ? "" : " (" + suppressed + " more suppressed since the previous one)";
        logger.warn("Redis command failed and its caller saw an empty result" + repeats, cause);
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
            consecutiveProbeFailures.set(0);
            markReady();
        } catch (RuntimeException e) {
            int failures = consecutiveProbeFailures.incrementAndGet();
            if (failures >= FAILURES_BEFORE_DEGRADED) {
                markDegraded(e);
            } else {
                logger.debug("Redis health probe failed (" + failures + " of "
                        + FAILURES_BEFORE_DEGRADED + " before degrading): " + e.getMessage());
            }
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
