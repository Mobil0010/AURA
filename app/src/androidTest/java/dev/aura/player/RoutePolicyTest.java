package dev.aura.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class RoutePolicyTest {
    @Test
    public void savedSpeakerDoesNotBlockBluetooth() {
        assertTrue(RoutePolicy.selectNew(true, false, true, false));
    }

    @Test
    public void reconnectDoesNotStealExternalRoute() {
        assertFalse(RoutePolicy.selectNew(true, false, true, true));
    }

    @Test
    public void wiredAttachmentOverridesExternalRoute() {
        assertTrue(RoutePolicy.selectNew(true, true, false, true));
    }

    @Test
    public void disabledAutomaticSwitchPreservesSelection() {
        assertFalse(RoutePolicy.selectNew(false, true, false, false));
    }

    @Test
    public void internalEndpointDoesNotStealPlayback() {
        assertFalse(RoutePolicy.selectNew(true, false, false, false));
    }
}
