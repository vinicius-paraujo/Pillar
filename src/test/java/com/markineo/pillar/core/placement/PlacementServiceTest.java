package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlacementServiceTest {

    private PlacementService placement;
    private ReservationRegistry reservations;

    @BeforeEach
    void setUp() {
        HardCaps caps = new HardCaps(100, 0.9, 45.0);
        EligibilityFilter filter = new EligibilityFilter(caps);
        reservations = new ReservationRegistry(Clock.systemUTC(), Duration.ofSeconds(10));
        PlacementSelector selector = new PlacementSelector(reservations);
        placement = new PlacementService(filter, selector, reservations);
    }

    @Test
    void place_reservesAndReturnsEligibleNode() {
        ServerRole role = new ServerRole("hub");
        ServerIdentity node1 = new ServerIdentity(new ServerId("hub-1"), role);
        ServerIdentity node2 = new ServerIdentity(new ServerId("hub-2"), role);

        FleetSnapshot fleet = FleetSnapshot.of(List.of(node1, node2));
        
        // node1 is full, node2 is empty
        HealthSnapshot full = new HealthSnapshot(20.0, 1024L, 1024L, 100, 3, 0);
        HealthSnapshot empty = new HealthSnapshot(20.0, 512L, 1024L, 0, 3, 0);

        ServerIdentity placed = placement.place(role, fleet, id -> id.equals(node1) ? full : empty);

        assertEquals(node2, placed);
        assertEquals(1, reservations.activeReservations(node2));
        assertEquals(0, reservations.activeReservations(node1));
    }

    @Test
    void place_throwsWhenNoNodeEligible() {
        ServerRole role = new ServerRole("hub");
        ServerIdentity node1 = new ServerIdentity(new ServerId("hub-1"), role);

        FleetSnapshot fleet = FleetSnapshot.of(List.of(node1));
        
        // node1 is full
        HealthSnapshot full = new HealthSnapshot(20.0, 1024L, 1024L, 100, 3, 0);

        NoEligibleNodeException ex = assertThrows(NoEligibleNodeException.class, 
                () -> placement.place(role, fleet, id -> full));
                
        assertEquals(role, ex.role());
    }

    @Test
    void place_throwsWhenNoNodeWithRole() {
        ServerRole role = new ServerRole("hub");
        ServerIdentity node1 = new ServerIdentity(new ServerId("survival-1"), new ServerRole("survival"));

        FleetSnapshot fleet = FleetSnapshot.of(List.of(node1));

        NoEligibleNodeException ex = assertThrows(NoEligibleNodeException.class,
                () -> placement.place(role, fleet, id -> null));
                
        assertEquals(role, ex.role());
    }

    @Test
    void place_choosesHealthyNodeOverBlindNode() {
        ServerRole role = new ServerRole("hub");
        ServerIdentity node1 = new ServerIdentity(new ServerId("hub-1"), role);
        ServerIdentity node2 = new ServerIdentity(new ServerId("hub-2"), role);

        FleetSnapshot fleet = FleetSnapshot.of(List.of(node1, node2));

        // node1 has no health data (blind), node2 is empty
        HealthSnapshot empty = new HealthSnapshot(20.0, 512L, 1024L, 0, 3, 0);

        // Since node1 is blind, its score is penalized heavily. So node2 should win.
        ServerIdentity placed = placement.place(role, fleet, id -> id.equals(node2) ? empty : null);

        assertEquals(node2, placed);
        assertEquals(1, reservations.activeReservations(node2));
        assertEquals(0, reservations.activeReservations(node1));
    }

    @Test
    void place_choosesLeastReservationsAmongBlindNodes() {
        ServerRole role = new ServerRole("hub");
        ServerIdentity node1 = new ServerIdentity(new ServerId("hub-1"), role);
        ServerIdentity node2 = new ServerIdentity(new ServerId("hub-2"), role);

        FleetSnapshot fleet = FleetSnapshot.of(List.of(node1, node2));

        // Both nodes are blind. node1 has 1 reservation.
        reservations.reserve(node1);

        ServerIdentity placed = placement.place(role, fleet, id -> null);

        // node2 should be chosen because it has 0 reservations.
        assertEquals(node2, placed);
        assertEquals(1, reservations.activeReservations(node2));
        assertEquals(1, reservations.activeReservations(node1));
    }
}
