package com.zxcpoiu.incallmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AudioLifecycleStateTest {
    private static final AudioLifecycleState.FocusOwner CALL = AudioLifecycleState.FocusOwner.CALL;
    private static final AudioLifecycleState.FocusOwner MANUAL = AudioLifecycleState.FocusOwner.MANUAL;
    private static final AudioLifecycleState.FocusOwner RINGTONE = AudioLifecycleState.FocusOwner.RINGTONE;
    private static final AudioLifecycleState.FocusUsage CALL_USAGE = AudioLifecycleState.FocusUsage.CALL;
    private static final AudioLifecycleState.FocusUsage RING_USAGE = AudioLifecycleState.FocusUsage.RINGTONE;

    @Test
    public void staleRingtoneCleanupCannotControlReplacementSession() {
        AudioLifecycleState state = new AudioLifecycleState();
        long first = state.beginRingtone();
        state.addRingtoneFocusOwner(first);
        state.onFocusRequestGranted(RING_USAGE);
        state.cancelRingtone(first);
        assertTrue(state.releaseRingtoneFocusOwner(first));

        long second = state.beginRingtone();
        state.addRingtoneFocusOwner(second);
        state.onFocusRequestGranted(RING_USAGE);
        state.cancelRingtone(first);

        assertFalse(state.releaseRingtoneFocusOwner(first));
        assertFalse(state.isCurrentRingtone(first));
        assertTrue(state.isCurrentRingtone(second));
        assertTrue(state.isFocusHeld());
        assertTrue(state.hasFocusOwner(RINGTONE));
    }

    @Test
    public void duplicateRingtoneStartDoesNotReplaceCurrentGeneration() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        assertEquals(-1L, state.beginRingtone());
        assertTrue(state.isCurrentRingtone(generation));
    }

    @Test
    public void canceledRingtoneCannotAcquireFocusOwnership() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        state.cancelRingtone(generation);
        assertFalse(state.addRingtoneFocusOwner(generation));
        assertNull(state.effectiveFocusUsage());
        assertFalse(state.shouldHoldWakeLock());
    }

    @Test
    public void ringtoneCleanupPreservesIndependentCallAndManualOwners() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        state.addRingtoneFocusOwner(generation);
        state.addManualFocusOwner();
        state.startCall();
        state.onFocusRequestGranted(CALL_USAGE);

        state.cancelRingtone(generation);
        assertFalse(state.releaseRingtoneFocusOwner(generation));
        state.stopCall();
        assertFalse(state.releaseFocusOwner(CALL));
        assertTrue(state.isFocusHeld());
        assertTrue(state.hasFocusOwner(MANUAL));
        assertFalse(state.shouldHoldWakeLock());
        state.explicitAbandon();
        assertFalse(state.isFocusRegistered());
    }

    @Test
    public void lastRingtoneOwnerAbandonsExactlyOnce() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        state.addRingtoneFocusOwner(generation);
        state.onFocusRequestGranted(RING_USAGE);
        state.cancelRingtone(generation);

        assertTrue(state.releaseRingtoneFocusOwner(generation));
        assertFalse(state.releaseRingtoneFocusOwner(generation));
        assertFalse(state.isFocusHeld());
        assertFalse(state.isFocusRegistered());
        assertNull(state.getActiveFocusUsage());
    }

    @Test
    public void explicitAbandonPreventsLateGainOrCleanupFromRestoringFocus() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        state.addRingtoneFocusOwner(generation);
        state.startCall();
        state.addManualFocusOwner();
        state.onFocusRequestGranted(CALL_USAGE);
        state.explicitAbandon();

        state.onFocusChange(1);
        assertFalse(state.isFocusHeld());
        assertFalse(state.hasFocusOwner(MANUAL));
        assertTrue(state.hasFocusOwner(CALL));
        state.cancelRingtone(generation);
        assertFalse(state.releaseRingtoneFocusOwner(generation));
        state.stopCall();
        assertFalse(state.releaseFocusOwner(CALL));
        assertFalse(state.isFocusRegistered());
    }

    @Test
    public void newExplicitRequestCanRestoreFocusAfterAbandon() {
        AudioLifecycleState state = new AudioLifecycleState();
        state.startCall();
        state.onFocusRequestGranted(CALL_USAGE);
        state.explicitAbandon();
        state.onFocusRequestGranted(CALL_USAGE);
        state.addManualFocusOwner();
        assertTrue(state.isFocusHeld());
        assertTrue(state.isFocusRegistered());
    }

    @Test
    public void temporaryAndPermanentLossInvalidateHeldStateButKeepCleanupResponsibility() {
        for (int loss : new int[] {-1, -2}) {
            AudioLifecycleState state = new AudioLifecycleState();
            state.startCall();
            state.onFocusRequestGranted(CALL_USAGE);
            state.onFocusChange(loss);
            assertFalse(state.isFocusHeld());
            assertTrue(state.isFocusRegistered());
            assertTrue(state.hasFocusOwner(CALL));
            state.stopCall();
            assertTrue(state.releaseFocusOwner(CALL));
        }
    }

    @Test
    public void failedReplacementPreservesRegistrationUntilFinalOwnerCleanup() {
        AudioLifecycleState state = new AudioLifecycleState();
        state.startCall();
        state.onFocusRequestGranted(CALL_USAGE);
        state.onFocusChange(-2);
        state.onFocusRequestFailed();

        assertFalse(state.isFocusHeld());
        assertTrue(state.isFocusRegistered());
        assertEquals(CALL_USAGE, state.getActiveFocusUsage());
        state.stopCall();
        assertTrue(state.releaseFocusOwner(CALL));
        assertFalse(state.isFocusRegistered());
    }

    @Test
    public void failedInitialRequestDoesNotCreateRegistration() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        state.addRingtoneFocusOwner(generation);
        state.onFocusRequestFailed();
        state.cancelRingtone(generation);

        assertFalse(state.releaseRingtoneFocusOwner(generation));
        assertFalse(state.isFocusHeld());
        assertFalse(state.isFocusRegistered());
    }

    @Test
    public void callAndManualOwnersTakePriorityOverRingtoneAttributes() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        state.addRingtoneFocusOwner(generation);
        assertEquals(RING_USAGE, state.effectiveFocusUsage());
        state.startCall();
        assertEquals(CALL_USAGE, state.effectiveFocusUsage());
        state.stopCall();
        state.releaseFocusOwner(CALL);
        assertEquals(RING_USAGE, state.effectiveFocusUsage());
        state.addManualFocusOwner();
        assertEquals(CALL_USAGE, state.effectiveFocusUsage());
    }

    @Test
    public void wakeLockOwnershipSurvivesRingtoneToCallHandoff() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        state.addRingtoneFocusOwner(generation);
        state.onFocusRequestGranted(RING_USAGE);
        state.startCall();
        state.onFocusRequestGranted(CALL_USAGE);
        state.cancelRingtone(generation);

        assertFalse(state.releaseRingtoneFocusOwner(generation));
        assertTrue(state.shouldHoldWakeLock());
        assertTrue(state.isFocusHeld());
        state.stopCall();
        assertTrue(state.releaseFocusOwner(CALL));
        assertFalse(state.shouldHoldWakeLock());
    }

    @Test
    public void vibrationOnlyRingtoneDoesNotRegisterFocus() {
        AudioLifecycleState state = new AudioLifecycleState();
        long generation = state.beginRingtone();
        assertTrue(state.shouldHoldWakeLock());
        assertNull(state.effectiveFocusUsage());
        assertFalse(state.isFocusRegistered());
        state.cancelRingtone(generation);
        assertFalse(state.releaseRingtoneFocusOwner(generation));
        assertFalse(state.shouldHoldWakeLock());
    }
}
