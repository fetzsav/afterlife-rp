package com.afterlife.rp.module.electrician;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure state for the wiring minigame: numbered fuses are shuffled across
 * slots and must be clicked in ascending order. Server-side only (rule 15).
 */
public final class WiringSequence {

    private final List<Integer> slotOrder;
    private int progress;

    private WiringSequence(List<Integer> slotOrder) {
        this.slotOrder = slotOrder;
    }

    /** slotOrder.get(i) is the inventory slot holding fuse number i+1. */
    public static WiringSequence shuffle(int fuses, List<Integer> candidateSlots, SecureRandom random) {
        List<Integer> slots = new ArrayList<>(candidateSlots);
        Collections.shuffle(slots, random);
        return new WiringSequence(List.copyOf(slots.subList(0, fuses)));
    }

    public List<Integer> slotOrder() {
        return slotOrder;
    }

    /** True when this click was the correct next fuse. */
    public boolean click(int slot) {
        if (progress < slotOrder.size() && slotOrder.get(progress) == slot) {
            progress++;
            return true;
        }
        return false;
    }

    public boolean complete() {
        return progress >= slotOrder.size();
    }

    public int progress() {
        return progress;
    }
}
