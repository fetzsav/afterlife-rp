package com.afterlife.rp.integration;

/** A soft dependency adapter reported by /afterlife health. */
public interface Adapter {

    String name();

    boolean available();

    /** Short human-readable detail (version, or why it is inactive). */
    String detail();
}
