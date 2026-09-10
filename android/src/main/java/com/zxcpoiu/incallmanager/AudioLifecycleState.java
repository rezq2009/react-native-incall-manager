/*
 * Copyright (c) 2025 Henry Lin @zxcpoiu
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 */

package com.zxcpoiu.incallmanager;

import java.util.EnumSet;

/** Lifecycle ownership state with no Android dependencies. */
final class AudioLifecycleState {
    enum FocusOwner { MANUAL, CALL, RINGTONE }
    enum FocusUsage { CALL, RINGTONE }

    private final EnumSet<FocusOwner> focusOwners = EnumSet.noneOf(FocusOwner.class);
    private long ringtoneGeneration;
    private long ringtoneFocusGeneration = -1L;
    private boolean ringtoneActive;
    private boolean callActive;
    private boolean focusHeld;
    private boolean focusRegistered;
    private FocusUsage activeFocusUsage;

    long beginRingtone() {
        if (ringtoneActive) {
            return -1L;
        }
        ringtoneActive = true;
        return ++ringtoneGeneration;
    }

    boolean isCurrentRingtone(long generation) {
        return ringtoneActive && ringtoneGeneration == generation;
    }

    boolean isRingtoneActive() {
        return ringtoneActive;
    }

    void cancelRingtone(long generation) {
        if (ringtoneGeneration == generation) {
            ringtoneActive = false;
            ringtoneGeneration++;
        }
    }

    long currentRingtoneGeneration() {
        return ringtoneGeneration;
    }

    void startCall() {
        callActive = true;
        focusOwners.add(FocusOwner.CALL);
    }

    void stopCall() {
        callActive = false;
    }

    boolean isCallActive() {
        return callActive;
    }

    boolean shouldHoldWakeLock() {
        return callActive || ringtoneActive;
    }

    void addManualFocusOwner() {
        focusOwners.add(FocusOwner.MANUAL);
    }

    boolean addRingtoneFocusOwner(long generation) {
        if (!isCurrentRingtone(generation)) {
            return false;
        }
        ringtoneFocusGeneration = generation;
        focusOwners.add(FocusOwner.RINGTONE);
        return true;
    }

    boolean hasFocusOwner(FocusOwner owner) {
        return focusOwners.contains(owner);
    }

    FocusUsage effectiveFocusUsage() {
        if (focusOwners.contains(FocusOwner.CALL) || focusOwners.contains(FocusOwner.MANUAL)) {
            return FocusUsage.CALL;
        }
        return focusOwners.contains(FocusOwner.RINGTONE) ? FocusUsage.RINGTONE : null;
    }

    boolean releaseFocusOwner(FocusOwner owner) {
        focusOwners.remove(owner);
        return prepareImplicitAbandonIfUnowned();
    }

    boolean releaseRingtoneFocusOwner(long generation) {
        if (ringtoneFocusGeneration != generation) {
            return false;
        }
        ringtoneFocusGeneration = -1L;
        focusOwners.remove(FocusOwner.RINGTONE);
        return prepareImplicitAbandonIfUnowned();
    }

    private boolean prepareImplicitAbandonIfUnowned() {
        if (focusOwners.isEmpty() && focusRegistered) {
            focusRegistered = false;
            focusHeld = false;
            activeFocusUsage = null;
            return true;
        }
        return false;
    }

    void onFocusRequestGranted(FocusUsage usage) {
        focusHeld = true;
        focusRegistered = true;
        activeFocusUsage = usage;
    }

    void onFocusRequestFailed() {
        // A failed replacement request does not prove that an older request was removed.
        focusHeld = false;
    }

    void onFocusChange(int focusChange) {
        if (focusChange > 0) {
            focusHeld = focusRegistered;
        } else if (focusChange < 0) {
            focusHeld = false;
        }
    }

    void explicitAbandon() {
        focusOwners.remove(FocusOwner.MANUAL);
        focusRegistered = false;
        focusHeld = false;
        activeFocusUsage = null;
    }

    boolean isFocusHeld() {
        return focusHeld;
    }

    boolean isFocusRegistered() {
        return focusRegistered;
    }

    FocusUsage getActiveFocusUsage() {
        return activeFocusUsage;
    }
}
