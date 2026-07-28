package com.afterlife.rp.shared.items;

import com.afterlife.rp.database.DatabaseManager;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Issues and validates serialized physical valuables. Identity is PDC + DB
 * record + HMAC — never name or lore (rules 5, 6, 15).
 */
public final class SerializedItemService {

    public enum Validation {
        VALID,
        NOT_SERIALIZED,
        TAMPERED,
        UNKNOWN_SERIAL,
        WRONG_STATUS
    }

    public record ValidationResult(Validation validation, SerializedItem record) {}

    private final DatabaseManager databaseManager;
    private final SerializedItemRepository repository;
    private final HmacSigner signer;

    public SerializedItemService(
            DatabaseManager databaseManager, SerializedItemRepository repository, HmacSigner signer) {
        this.databaseManager = databaseManager;
        this.repository = repository;
        this.signer = signer;
    }

    /**
     * Creates the DB record and a stamped single-item stack. The database write
     * commits before the stack is handed out (master plan §7.4).
     */
    public CompletableFuture<ItemStack> issue(
            String itemType,
            Material material,
            Component displayName,
            UUID owner,
            Long denomination,
            UUID issuedBy) {
        UUID serial = UUID.randomUUID();
        long issuedAt = System.currentTimeMillis();
        SerializedItem record = new SerializedItem(
                serial, itemType, owner, denomination, ItemStatus.ISSUED, issuedBy, issuedAt, null);
        return databaseManager.db().inTransaction(connection -> {
            repository.insert(connection, record);
            return record;
        }).thenApply(saved -> toItemStack(saved, material, displayName));
    }

    /** PDC payload of a physically held item whose HMAC verified. */
    public record PdcData(UUID serial, String itemType, long denomination, long issuedAt) {}

    /**
     * Reads the PDC and verifies the HMAC synchronously (safe on the main
     * thread — no database access). Empty when the item is not one of ours or
     * has been tampered with.
     */
    public java.util.Optional<PdcData> readVerified(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return java.util.Optional.empty();
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String serialText = pdc.get(ItemKeys.SERIAL, PersistentDataType.STRING);
        String itemType = pdc.get(ItemKeys.ITEM_TYPE, PersistentDataType.STRING);
        Long denomination = pdc.get(ItemKeys.DENOMINATION, PersistentDataType.LONG);
        Long issuedAt = pdc.get(ItemKeys.ISSUED_AT, PersistentDataType.LONG);
        String signature = pdc.get(ItemKeys.SIGNATURE, PersistentDataType.STRING);
        if (serialText == null || itemType == null || issuedAt == null || signature == null) {
            return java.util.Optional.empty();
        }
        long denominationValue = denomination == null ? 0L : denomination;
        if (!signer.verify(signature, serialText, itemType, denominationValue, issuedAt)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new PdcData(
                UUID.fromString(serialText), itemType, denominationValue, issuedAt));
    }

    /** Reads PDC and verifies HMAC synchronously, then checks the DB record. */
    public CompletableFuture<ValidationResult> validate(ItemStack item, ItemStatus expectedStatus) {
        if (item == null || !item.hasItemMeta()) {
            return CompletableFuture.completedFuture(new ValidationResult(Validation.NOT_SERIALIZED, null));
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String signaturePresent = pdc.get(ItemKeys.SIGNATURE, PersistentDataType.STRING);
        java.util.Optional<PdcData> data = readVerified(item);
        if (data.isEmpty()) {
            return CompletableFuture.completedFuture(new ValidationResult(
                    signaturePresent == null ? Validation.NOT_SERIALIZED : Validation.TAMPERED, null));
        }
        UUID serial = data.get().serial();
        return databaseManager.db().supply(connection -> repository.find(connection, serial))
                .thenApply(found -> found
                        .map(record -> record.status() == expectedStatus
                                ? new ValidationResult(Validation.VALID, record)
                                : new ValidationResult(Validation.WRONG_STATUS, record))
                        .orElse(new ValidationResult(Validation.UNKNOWN_SERIAL, null)));
    }

    /** One-winner status transition; a copied item with the same serial fails here. */
    public CompletableFuture<Boolean> transition(UUID serial, ItemStatus from, ItemStatus to) {
        return databaseManager.db().inTransaction(connection -> repository.transition(connection, serial, from, to));
    }

    /** Builds the stamped single-item stack for an already-persisted record. */
    public ItemStack toItemStack(SerializedItem record, Material material, Component displayName) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(displayName);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(ItemKeys.ITEM_TYPE, PersistentDataType.STRING, record.itemType());
        pdc.set(ItemKeys.SERIAL, PersistentDataType.STRING, record.serial().toString());
        long denominationValue = record.denomination() == null ? 0L : record.denomination();
        pdc.set(ItemKeys.DENOMINATION, PersistentDataType.LONG, denominationValue);
        pdc.set(ItemKeys.ISSUED_AT, PersistentDataType.LONG, record.issuedAtEpochMs());
        pdc.set(ItemKeys.SIGNATURE, PersistentDataType.STRING, signer.sign(
                record.serial().toString(), record.itemType(), denominationValue, record.issuedAtEpochMs()));
        stack.setItemMeta(meta);
        return stack;
    }
}
