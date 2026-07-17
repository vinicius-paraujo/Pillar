package br.com.markineo.pillar.core.fleet;

import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetSnapshotTest {

    private static ServerIdentity node(String id, String role) {
        return new ServerIdentity(new ServerId(id), new ServerRole(role));
    }

    @Test
    void emptySnapshotHasNoMembers() {
        assertEquals(0, FleetSnapshot.empty().size());
        assertFalse(FleetSnapshot.empty().contains(new ServerId("skyblock-1")));
    }

    @Test
    void findsMemberById() {
        FleetSnapshot fleet = FleetSnapshot.of(List.of(node("skyblock-1", "skyblock"), node("hub-1", "hub")));

        assertTrue(fleet.contains(new ServerId("skyblock-1")));
        assertEquals("skyblock", fleet.find(new ServerId("skyblock-1")).orElseThrow().role().value());
        assertTrue(fleet.find(new ServerId("absent")).isEmpty());
    }

    @Test
    void filtersByRole() {
        FleetSnapshot fleet = FleetSnapshot.of(List.of(
                node("skyblock-1", "skyblock"),
                node("skyblock-2", "skyblock"),
                node("hub-1", "hub")));

        assertEquals(2, fleet.withRole(new ServerRole("skyblock")).size());
        assertEquals(1, fleet.withRole(new ServerRole("hub")).size());
    }

    @Test
    void deduplicatesByIdKeepingLast() {
        FleetSnapshot fleet = FleetSnapshot.of(List.of(node("skyblock-1", "skyblock"), node("skyblock-1", "hub")));

        assertEquals(1, fleet.size());
        assertEquals("hub", fleet.find(new ServerId("skyblock-1")).orElseThrow().role().value());
    }
}
