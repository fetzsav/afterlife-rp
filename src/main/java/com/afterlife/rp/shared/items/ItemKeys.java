package com.afterlife.rp.shared.items;

import org.bukkit.NamespacedKey;

/** PDC keys for authoritative item identity (master plan §7.3). */
public final class ItemKeys {

    public static final String NAMESPACE = "afterlife";

    public static final NamespacedKey ITEM_TYPE = new NamespacedKey(NAMESPACE, "item_type");
    public static final NamespacedKey SERIAL = new NamespacedKey(NAMESPACE, "serial");
    public static final NamespacedKey DENOMINATION = new NamespacedKey(NAMESPACE, "denomination");
    public static final NamespacedKey ISSUED_AT = new NamespacedKey(NAMESPACE, "issued_at");
    public static final NamespacedKey SIGNATURE = new NamespacedKey(NAMESPACE, "signature");

    private ItemKeys() {}
}
