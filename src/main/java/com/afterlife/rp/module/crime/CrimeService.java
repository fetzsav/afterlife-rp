package com.afterlife.rp.module.crime;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.module.electrician.ElectricianService;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionHandler;
import com.afterlife.rp.shared.missions.MissionService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Crime economy (§9.4): gang street sales paid in dirty money, drug-dose
 * items, odor-proof sealing, and the ATM-hacking device built from the M5
 * circuit board plus its single-reward channel mission.
 */
public final class CrimeService implements MissionHandler {

    public static final String ITEM_DRUG = "drug_dose";
    public static final String ITEM_SEALED = "sealed_bag";
    public static final String ITEM_HACK_DEVICE = "atm_hacking_device";
    public static final String JOB_GANG = "GANG";
    public static final String MISSION_ATM_HACK = "ATM_HACK";

    private final DatabaseManager databaseManager;
    private final MissionService missionService;
    private final BankingService bankingService;
    private final SerializedItemRepository itemRepository;
    private final AuditService auditService;
    private final CrimeConfig config;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();

    public CrimeService(DatabaseManager databaseManager, MissionService missionService,
            BankingService bankingService, SerializedItemRepository itemRepository,
            AuditService auditService, CrimeConfig config) {
        this.databaseManager = databaseManager;
        this.missionService = missionService;
        this.bankingService = bankingService;
        this.itemRepository = itemRepository;
        this.auditService = auditService;
        this.config = config;
    }

    public CrimeConfig config() {
        return config;
    }

    /** Debug/cartel issuance of a drug dose (real distribution arrives with cartel content). */
    public CompletableFuture<SerializedItem> issueDrug(UUID owner) {
        SerializedItem drug = new SerializedItem(UUID.randomUUID(), ITEM_DRUG, owner, null,
                ItemStatus.ISSUED, null, System.currentTimeMillis(), null);
        return databaseManager.db().inTransaction(connection -> {
            itemRepository.insert(connection, drug);
            return drug;
        });
    }

    public record SaleResult(boolean sold, long dirtyCents, List<SerializedItem> notes,
            boolean suspicion) {}

    /**
     * Sells one held drug dose to NPC street demand: the dose redeems once and
     * the seller is paid dirty money below cartel wholesale (§9.4). A configured
     * chance raises suspicion for a later police alert.
     */
    public CompletableFuture<SaleResult> sellDose(UUID seller, UUID drugSerial) {
        long payout = config.payoutMinCents()
                + (long) (random.nextDouble() * (config.payoutMaxCents() - config.payoutMinCents()));
        long rounded = payout - (payout % 500);
        if (rounded <= 0) {
            rounded = 500;
        }
        long finalPay = rounded;
        return databaseManager.db().<Boolean>inTransaction(connection ->
                itemRepository.transition(connection, drugSerial, ItemStatus.ISSUED,
                        ItemStatus.REDEEMED))
                .thenCompose(sold -> {
                    if (!sold) {
                        return CompletableFuture.completedFuture(
                                new SaleResult(false, 0, List.of(), false));
                    }
                    boolean suspicion = random.nextDouble() < config.suspicionChance();
                    return bankingService.issueDirty(seller, finalPay, null).thenApply(notes -> {
                        auditService.log(seller, "player", "GANG_SALE", seller.toString(),
                                java.util.Map.of("cents", String.valueOf(finalPay),
                                        "suspicion", String.valueOf(suspicion)));
                        return new SaleResult(true, finalPay, notes, suspicion);
                    });
                });
    }

    /** Seals a drug dose into an odor-proof bag that defeats ordinary K-9 scans. */
    public CompletableFuture<Optional<SerializedItem>> seal(UUID owner, UUID drugSerial) {
        return databaseManager.db().<Optional<SerializedItem>>inTransaction(connection -> {
            var record = itemRepository.find(connection, drugSerial);
            if (record.isEmpty() || record.get().status() != ItemStatus.ISSUED
                    || !ITEM_DRUG.equals(record.get().itemType())) {
                return Optional.empty();
            }
            if (!itemRepository.transition(connection, drugSerial, ItemStatus.ISSUED,
                    ItemStatus.VOID)) {
                return Optional.empty();
            }
            JsonObject metadata = new JsonObject();
            metadata.addProperty("contents", ITEM_DRUG);
            SerializedItem bag = new SerializedItem(UUID.randomUUID(), ITEM_SEALED, owner, null,
                    ItemStatus.ISSUED, owner, System.currentTimeMillis(), gson.toJson(metadata));
            itemRepository.insert(connection, bag);
            return Optional.of(bag);
        });
    }

    /** Unseals a bag back into a drug dose. */
    public CompletableFuture<Optional<SerializedItem>> unseal(UUID owner, UUID bagSerial) {
        return databaseManager.db().inTransaction(connection -> {
            var record = itemRepository.find(connection, bagSerial);
            if (record.isEmpty() || record.get().status() != ItemStatus.ISSUED
                    || !ITEM_SEALED.equals(record.get().itemType())) {
                return Optional.empty();
            }
            if (!itemRepository.transition(connection, bagSerial, ItemStatus.ISSUED,
                    ItemStatus.VOID)) {
                return Optional.empty();
            }
            SerializedItem drug = new SerializedItem(UUID.randomUUID(), ITEM_DRUG, owner, null,
                    ItemStatus.ISSUED, owner, System.currentTimeMillis(), null);
            itemRepository.insert(connection, drug);
            return Optional.of(drug);
        });
    }

    /** One-winner consumption of a drug dose; the caller then runs the trip. */
    public CompletableFuture<Boolean> consumeDose(UUID drugSerial) {
        return databaseManager.db().inTransaction(connection ->
                itemRepository.transition(connection, drugSerial, ItemStatus.ISSUED,
                        ItemStatus.REDEEMED));
    }

    /** Builds an ATM-hacking device by consuming an Intact Circuit Board (§9.5 link). */
    public CompletableFuture<Optional<SerializedItem>> buildHackDevice(UUID owner,
            UUID circuitBoardSerial) {
        return databaseManager.db().inTransaction(connection -> {
            var record = itemRepository.find(connection, circuitBoardSerial);
            if (record.isEmpty() || record.get().status() != ItemStatus.ISSUED
                    || !ElectricianService.ITEM_TYPE_CIRCUIT_BOARD.equals(record.get().itemType())) {
                return Optional.empty();
            }
            if (!itemRepository.transition(connection, circuitBoardSerial, ItemStatus.ISSUED,
                    ItemStatus.REDEEMED)) {
                return Optional.empty();
            }
            SerializedItem device = new SerializedItem(UUID.randomUUID(), ITEM_HACK_DEVICE, owner,
                    null, ItemStatus.ISSUED, owner, System.currentTimeMillis(), null);
            itemRepository.insert(connection, device);
            return Optional.of(device);
        });
    }

    /** Starts the ATM-hack channel as a mission bound to the device (consumed on start). */
    public CompletableFuture<Optional<Mission>> startHack(UUID hacker, UUID atmPoi,
            UUID deviceSerial) {
        return databaseManager.db().<Boolean>inTransaction(connection ->
                itemRepository.transition(connection, deviceSerial, ItemStatus.ISSUED,
                        ItemStatus.REDEEMED))
                .thenCompose(consumed -> {
                    if (!consumed) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    long reward = config.hackRewardMinCents() + (long) (random.nextDouble()
                            * (config.hackRewardMaxCents() - config.hackRewardMinCents()));
                    JsonObject data = new JsonObject();
                    data.addProperty("current_target", atmPoi.toString());
                    data.addProperty("reward", reward - (reward % 500));
                    return missionService.claim(MISSION_ATM_HACK, hacker, atmPoi, null,
                            config.hackChannelSeconds() + 30, reward - (reward % 500),
                            data.toString());
                });
    }

    public record HackResult(boolean rewarded, long dirtyCents, List<SerializedItem> notes) {}

    /** Completes the hack exactly once, paying dirty money. */
    public CompletableFuture<HackResult> finishHack(Mission mission) {
        return missionService.complete(mission.id()).thenCompose(won -> {
            if (!won) {
                return CompletableFuture.completedFuture(new HackResult(false, 0, List.of()));
            }
            long reward = mission.rewardSnapshot();
            return bankingService.issueDirty(mission.owner(), reward, null).thenApply(notes -> {
                auditService.log(mission.owner(), "player", "ATM_HACK_SUCCESS",
                        mission.targetPoiId() == null ? "" : mission.targetPoiId().toString(),
                        java.util.Map.of("cents", String.valueOf(reward)));
                return new HackResult(true, reward, notes);
            });
        });
    }

    @Override
    public void onEnded(Mission mission, String endState) {
        // Failed/cancelled hacks simply end; the device was already consumed at
        // start (the risk of interruption is intentional, §9.4).
    }
}
