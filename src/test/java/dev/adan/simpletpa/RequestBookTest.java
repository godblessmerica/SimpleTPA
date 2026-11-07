package dev.adan.simpletpa;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RequestBookTest {
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
    final RequestBook book = new RequestBook();

    @Test void onlyOneOutgoingAcrossBothRequestTypesAndAllTargets() {
        var request = book.add(alice, bob, false, 100);
        assertThrows(IllegalArgumentException.class, () -> book.add(alice, bob, false, 100));
        assertThrows(IllegalArgumentException.class, () -> book.add(alice, carol, true, 100));
        assertSame(request, book.outgoing(alice));
        assertFalse(request.here());
    }

    @Test void selfRequestsRejected() {
        assertThrows(IllegalArgumentException.class, () -> book.add(alice, alice, true, 100));
    }

    @Test void receiverCanHaveMultipleIncomingRequests() {
        book.add(alice, bob, false, 100);
        book.add(carol, bob, true, 100);
        assertEquals(2, book.incoming(bob).size());
        assertTrue(book.incoming(alice).isEmpty());
    }

    @Test void removedRequestCannotRemoveNewRequest() {
        var old = book.add(alice, bob, false, 100);
        assertTrue(book.remove(old));
        var current = book.add(alice, bob, true, 200);
        assertNotEquals(old.id(), current.id());
        assertFalse(book.remove(old));
        assertSame(current, book.outgoing(alice));
    }

    @Test void expiryAtDeadlineReleasesSenderAndOnlyNotifiesOnce() {
        var request = book.add(alice, bob, false, 100);
        assertTrue(book.expired(99).isEmpty());
        assertEquals(java.util.List.of(request), book.expired(100));
        assertTrue(book.expired(101).isEmpty());
        assertNull(book.outgoing(alice));
        assertDoesNotThrow(() -> book.add(alice, carol, false, 200));
    }

    @Test void disconnectClearsBothDirectionsButKeepsUnrelatedRequest() {
        book.add(alice, bob, false, 100);
        book.add(bob, carol, true, 100);
        var unrelated = book.add(carol, alice, false, 100);
        assertEquals(2, book.disconnect(bob).size());
        assertTrue(book.incoming(bob).isEmpty());
        assertNull(book.outgoing(bob));
        assertSame(unrelated, book.outgoing(carol));
    }

    @Test void shutdownClearsRequests() {
        book.add(alice, bob, false, 100);
        book.clear();
        assertNull(book.outgoing(alice));
        assertTrue(book.incoming(bob).isEmpty());
    }
}
