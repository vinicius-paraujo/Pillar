package br.com.markineo.pillar.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// MiniMessage leaves a tag it cannot resolve in the output verbatim, so a message that
// spells a placeholder the command code does not pass reaches the player as a literal
// "<count>" with nothing logged and no exception. This locks the other direction, which
// is the one that catches it: every name the code passes has to appear in the text of
// every locale, or the value silently has nowhere to land.
class LangPlaceholderContractTest {

    private static final List<String> LOCALES = List.of("en-us", "pt-br");

    private static final Map<String, Set<String>> SUPPLIED = Map.ofEntries(
            entry("doctor.connection", Set.of("duration", "state")),
            entry("doctor.decision_refused", Set.of("reason", "role")),
            entry("doctor.decision_success", Set.of("node", "role")),
            entry("doctor.health", Set.of("missing", "oldest_age")),
            entry("doctor.transport", Set.of("dead_letters", "dispatch_queue", "pending", "reaps")),
            entry("doctor.transport_paper", Set.of("dead_letters", "dispatch_queue", "pending")),
            entry("fleet.entry", Set.of("name", "role", "status")),
            entry("fleet.header", Set.of("count")),
            entry("ping.failed", Set.of("message", "server")),
            entry("ping.publish_failed", Set.of("server")),
            entry("ping.success", Set.of("latency", "server")),
            entry("ping.timeout", Set.of("server")),
            entry("ping.unknown_server", Set.of("server")),
            entry("reload.dispatching", Set.of("count")),
            entry("reload.failed", Set.of("message", "server")),
            entry("reload.no_targets", Set.of("role")),
            entry("reload.success", Set.of("server")),
            entry("reload.summary", Set.of("failures", "success")),
            entry("status.connection", Set.of("state")),
            entry("status.log_entry", Set.of("level", "message")),
            entry("status.pending", Set.of("count")));

    @Test
    void everyLocaleUsesThePlaceholdersTheCodeSupplies() {
        List<String> problems = new ArrayList<>();

        for (String locale : LOCALES) {
            Map<String, String> messages = load(locale);
            for (Map.Entry<String, Set<String>> contract : SUPPLIED.entrySet()) {
                String text = messages.get(contract.getKey());
                // A key absent from a locale falls back to en-us at runtime, which is a
                // translation gap rather than a broken message. Tracked separately.
                if (text == null) {
                    continue;
                }
                for (String name : contract.getValue()) {
                    if (!text.contains("<" + name + ">")) {
                        problems.add(locale + " / " + contract.getKey() + " never uses <" + name + ">");
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(), "placeholders with nowhere to land:\n" + String.join("\n", problems));
    }

    private Map<String, String> load(String locale) {
        String resource = "/lang/" + locale + ".yml";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "missing language resource " + resource);
            Map<String, String> flat = new LinkedHashMap<>();
            flatten(new Yaml().load(in), "", flat);
            return flat;
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void flatten(Map<String, Object> source, String prefix, Map<String, String> target) {
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                flatten((Map<String, Object>) nested, prefix + key + ".", target);
            } else if (value != null) {
                target.put(prefix + key, value.toString());
            }
        });
    }
}
