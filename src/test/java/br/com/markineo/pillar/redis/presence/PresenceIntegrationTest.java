package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.redis.RedisIntegrationTest;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceIntegrationTest extends RedisIntegrationTest {

    private static ServerIdentity node(String id, String role) {
        return new ServerIdentity(new ServerId(id), new ServerRole(role));
    }

    @Test
    void publishedHeartbeatAppearsInTheFleetView() {
        new HeartbeatPublisher(connector, node("skyblock-1", "skyblock")).publish();

        FleetSnapshot fleet = new FleetView(connector).snapshot();

        assertTrue(fleet.contains(new ServerId("skyblock-1")));
        assertEquals("skyblock", fleet.find(new ServerId("skyblock-1")).orElseThrow().role().value());
    }

    @Test
    void fleetViewGathersEveryPublishedNode() {
        new HeartbeatPublisher(connector, node("skyblock-1", "skyblock")).publish();
        new HeartbeatPublisher(connector, node("skyblock-2", "skyblock")).publish();
        new HeartbeatPublisher(connector, node("hub-1", "hub")).publish();

        FleetSnapshot fleet = new FleetView(connector).snapshot();

        assertEquals(3, fleet.size());
        assertEquals(2, fleet.withRole(new ServerRole("skyblock")).size());
    }
}
