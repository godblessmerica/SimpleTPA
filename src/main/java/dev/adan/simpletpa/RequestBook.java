package dev.adan.simpletpa;

import java.util.*;

final class RequestBook {
    record Request(UUID id, UUID sender, UUID receiver, boolean here, long expiresAt) {}
    private final Map<UUID, Request> outgoing = new HashMap<>();

    Request add(UUID sender, UUID receiver, boolean here, long expiresAt) {
        if (sender.equals(receiver) || outgoing.containsKey(sender)) {
            throw new IllegalArgumentException("Self request or existing outgoing request");
        }
        Request request = new Request(UUID.randomUUID(), sender, receiver, here, expiresAt);
        outgoing.put(sender, request);
        return request;
    }

    Request outgoing(UUID sender) { return outgoing.get(sender); }

    List<Request> incoming(UUID receiver) {
        return outgoing.values().stream().filter(r -> r.receiver().equals(receiver)).toList();
    }

    boolean remove(Request request) { return outgoing.remove(request.sender(), request); }

    List<Request> expired(long now) {
        List<Request> expired = outgoing.values().stream().filter(r -> now >= r.expiresAt()).toList();
        expired.forEach(this::remove);
        return expired;
    }

    List<Request> disconnect(UUID player) {
        List<Request> removed = outgoing.values().stream()
                .filter(r -> r.sender().equals(player) || r.receiver().equals(player)).toList();
        removed.forEach(this::remove);
        return removed;
    }

    void clear() { outgoing.clear(); }
}
