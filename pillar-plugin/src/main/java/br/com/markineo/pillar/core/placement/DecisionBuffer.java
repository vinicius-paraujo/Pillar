package br.com.markineo.pillar.core.placement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class DecisionBuffer {

    private final int capacity;
    private final Deque<PlacementDecision> buffer = new ArrayDeque<>();

    public DecisionBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }

        this.capacity = capacity;
    }

    public synchronized void add(PlacementDecision decision) {
        if (buffer.size() == capacity) {
            buffer.removeFirst();
        }

        buffer.addLast(decision);
    }

    public synchronized List<PlacementDecision> recent() {
        return List.copyOf(buffer);
    }
}
