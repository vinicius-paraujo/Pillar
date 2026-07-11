package br.com.markineo.pillar.core.fleet;

import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FleetSnapshot {

    private final Map<ServerId, ServerIdentity> members;

    private FleetSnapshot(Map<ServerId, ServerIdentity> members) {
        this.members = members;
    }

    public static FleetSnapshot of(Collection<ServerIdentity> members) {
        // Insertion order preserved so listings are stable across reads.
        Map<ServerId, ServerIdentity> byId = new LinkedHashMap<>();
        for (ServerIdentity member : members) {
            byId.put(member.id(), member);
        }
        return new FleetSnapshot(Collections.unmodifiableMap(byId));
    }

    public static FleetSnapshot empty() {
        return new FleetSnapshot(Map.of());
    }

    public Collection<ServerIdentity> members() {
        return members.values();
    }

    public int size() {
        return members.size();
    }

    public boolean contains(ServerId id) {
        return members.containsKey(id);
    }

    public Optional<ServerIdentity> find(ServerId id) {
        return Optional.ofNullable(members.get(id));
    }

    public List<ServerIdentity> withRole(ServerRole role) {
        return members.values().stream().filter(member -> member.role().equals(role)).toList();
    }
}
