package com.afterlife.rp.module.delivery;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionHandler;
import com.afterlife.rp.shared.missions.MissionService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Food delivery job (§9.6): restaurant pickup, timed delivery with temperature
 * tips, and the occasional sealed contraband offer paid in dirty money.
 * Packages are serialized items bound to their mission; ended missions void
 * the package serial so leftovers become inert (rule 13).
 */
public final class DeliveryService implements MissionHandler {

    public static final String MISSION_FOOD = "FOOD_DELIVERY";
    public static final String MISSION_CONTRABAND = "CONTRABAND_DELIVERY";
    public static final String JOB = "DELIVERY";
    public static final String ITEM_FOOD_PACKAGE = "food_package";
    public static final String ITEM_CONTRABAND_PACKAGE = "contraband_package";

    private record ContrabandOffer(UUID shadowPoiId, long expiresAtMs) {}

    private final DatabaseManager databaseManager;
    private final MissionService missionService;
    private final PoiService poiService;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final BankingService bankingService;
    private final SerializedItemRepository itemRepository;
    private final AuditService auditService;
    private final DeliveryConfig config;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, ContrabandOffer> offers = new ConcurrentHashMap<>();

    public DeliveryService(
            DatabaseManager databaseManager,
            MissionService missionService,
            PoiService poiService,
            AccountService accountService,
            LedgerService ledgerService,
            BankingService bankingService,
            SerializedItemRepository itemRepository,
            AuditService auditService,
            DeliveryConfig config) {
        this.databaseManager = databaseManager;
        this.missionService = missionService;
        this.poiService = poiService;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.bankingService = bankingService;
        this.itemRepository = itemRepository;
        this.auditService = auditService;
        this.config = config;
    }

    public DeliveryConfig config() {
        return config;
    }

    public Poi poiById(UUID id) {
        return poiService.byId(id).orElse(null);
    }

    /** Assigns a random restaurant -> destination order as an ACTIVE mission. */
    public CompletableFuture<Optional<Mission>> startOrder(UUID player) {
        List<Poi> restaurants = poiService.byTypeAndStatus(config.restaurantTypes(), "ACTIVE");
        List<Poi> destinations = poiService.byTypeAndStatus(config.destinationTypes(), "ACTIVE");
        if (restaurants.isEmpty() || destinations.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Poi restaurant = restaurants.get(random.nextInt(restaurants.size()));
        Poi destination = destinations.get(random.nextInt(destinations.size()));
        JsonObject data = new JsonObject();
        data.addProperty("phase", "PICKUP");
        data.addProperty("dropoff", destination.id().toString());
        data.addProperty("current_target", restaurant.id().toString());
        return missionService.claim(MISSION_FOOD, player, restaurant.id(), restaurant.id(),
                config.packageTimeoutMinutes() * 60, 0, data.toString());
    }

    /** Pickup at the restaurant: issues the serialized order package. */
    public CompletableFuture<Optional<SerializedItem>> pickup(Mission mission) {
        if (!"PICKUP".equals(mission.dataString("phase"))) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("mission", mission.id().toString());
        SerializedItem pack = new SerializedItem(UUID.randomUUID(), ITEM_FOOD_PACKAGE,
                mission.owner(), null, ItemStatus.ISSUED, null, System.currentTimeMillis(),
                metadata.toString());
        return databaseManager.db().<SerializedItem>inTransaction(connection -> {
            itemRepository.insert(connection, pack);
            return pack;
        }).thenCompose(saved -> {
            JsonObject data = mission.dataJson();
            data.addProperty("phase", "DELIVER");
            data.addProperty("pickup_ms", System.currentTimeMillis());
            data.addProperty("package_serial", saved.serial().toString());
            data.addProperty("current_target", mission.dataString("dropoff"));
            return missionService.updateData(mission.id(), data.toString())
                    .thenApply(v -> Optional.of(saved));
        });
    }

    public record DeliveryOutcome(boolean rewarded, long rewardCents, boolean contrabandOffered) {}

    /** Completes the food delivery exactly once and pays base + distance + tip. */
    public CompletableFuture<DeliveryOutcome> deliverFood(Mission mission, UUID playerAccountId) {
        return missionService.complete(mission.id()).thenCompose(won -> {
            if (!won) {
                return CompletableFuture.completedFuture(new DeliveryOutcome(false, 0, false));
            }
            long elapsedSeconds = Math.max(0,
                    (System.currentTimeMillis() - mission.dataLong("pickup_ms",
                            System.currentTimeMillis())) / 1000);
            double routeBlocks = routeBlocks(mission);
            long reward = config.reward(elapsedSeconds, routeBlocks);
            UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
            String packageSerial = mission.dataString("package_serial");
            return databaseManager.db().<Long>inTransaction(connection -> {
                if (packageSerial != null) {
                    itemRepository.transition(connection, UUID.fromString(packageSerial),
                            ItemStatus.ISSUED, ItemStatus.REDEEMED);
                }
                ledgerService.apply(connection, "deliv-" + mission.id(), "DELIVERY_PAY",
                        mission.owner(), null,
                        List.of(new LedgerService.Line(government, -reward),
                                new LedgerService.Line(playerAccountId, reward)),
                        false);
                return reward;
            }).thenApply(paid -> {
                boolean offer = random.nextDouble() < config.contrabandChance();
                if (offer) {
                    List<Poi> shadows = poiService.byTypeAndStatus(config.shadowTypes(), "ACTIVE");
                    if (shadows.isEmpty()) {
                        offer = false;
                    } else {
                        Poi shadow = shadows.get(random.nextInt(shadows.size()));
                        offers.put(mission.owner(), new ContrabandOffer(shadow.id(),
                                System.currentTimeMillis()
                                        + config.contrabandWindowSeconds() * 1000L));
                    }
                }
                return new DeliveryOutcome(true, paid, offer);
            });
        });
    }

    /** Accepts the pending shady offer within its window (§9.6). */
    public CompletableFuture<Optional<SerializedItem>> acceptContraband(UUID player) {
        ContrabandOffer offer = offers.remove(player);
        if (offer == null || System.currentTimeMillis() > offer.expiresAtMs()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        long pay = config.contrabandPayMinCents() + (long) (random.nextDouble()
                * (config.contrabandPayMaxCents() - config.contrabandPayMinCents()));
        JsonObject data = new JsonObject();
        data.addProperty("pay_cents", pay);
        data.addProperty("current_target", offer.shadowPoiId().toString());
        return missionService.claim(MISSION_CONTRABAND, player, offer.shadowPoiId(), null,
                        config.contrabandDeadlineMinutes() * 60, pay, data.toString())
                .thenCompose(claimed -> {
                    if (claimed.isEmpty()) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    JsonObject metadata = new JsonObject();
                    metadata.addProperty("mission", claimed.get().id().toString());
                    // Sealed: cannot be opened; K-9 detects this classification (§9.3).
                    SerializedItem pack = new SerializedItem(UUID.randomUUID(),
                            ITEM_CONTRABAND_PACKAGE, player, null, ItemStatus.ISSUED, null,
                            System.currentTimeMillis(), metadata.toString());
                    return databaseManager.db().<SerializedItem>inTransaction(connection -> {
                        itemRepository.insert(connection, pack);
                        return pack;
                    }).thenCompose(saved -> {
                        JsonObject updated = claimed.get().dataJson();
                        updated.addProperty("package_serial", saved.serial().toString());
                        return missionService.updateData(claimed.get().id(), updated.toString())
                                .thenApply(v -> Optional.of(saved));
                    });
                });
    }

    public record ContrabandOutcome(boolean rewarded, long dirtyCents, List<SerializedItem> notes) {}

    /** Completes the contraband run exactly once; pays physical dirty money. */
    public CompletableFuture<ContrabandOutcome> deliverContraband(Mission mission) {
        return missionService.complete(mission.id()).thenCompose(won -> {
            if (!won) {
                return CompletableFuture.completedFuture(new ContrabandOutcome(false, 0, List.of()));
            }
            long pay = mission.dataLong("pay_cents", config.contrabandPayMinCents());
            // Round to the smallest note so the physical breakdown is exact.
            long rounded = pay - (pay % 500);
            String packageSerial = mission.dataString("package_serial");
            return databaseManager.db().<Void>inTransaction(connection -> {
                if (packageSerial != null) {
                    itemRepository.transition(connection, UUID.fromString(packageSerial),
                            ItemStatus.ISSUED, ItemStatus.REDEEMED);
                }
                return null;
            }).thenCompose(v -> bankingService.issueDirty(mission.owner(), rounded, null))
                    .thenApply(notes -> {
                        auditService.log(null, "SYSTEM", "CONTRABAND_PAYOUT",
                                mission.owner().toString(),
                                Map.of("cents", String.valueOf(rounded),
                                        "mission", mission.id().toString()));
                        return new ContrabandOutcome(true, rounded, notes);
                    });
        });
    }

    public double routeBlocks(Mission mission) {
        Poi origin = mission.originPoiId() == null ? null : poiById(mission.originPoiId());
        String dropoffId = mission.dataString("dropoff");
        Poi dropoff = dropoffId == null ? null : poiById(UUID.fromString(dropoffId));
        if (origin == null || dropoff == null || !origin.world().equals(dropoff.world())) {
            return 0;
        }
        double dx = origin.x() - dropoff.x();
        double dz = origin.z() - dropoff.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ended missions void their package so leftover items are inert. */
    @Override
    public void onEnded(Mission mission, String endState) {
        String packageSerial = mission.dataString("package_serial");
        if (packageSerial != null) {
            databaseManager.db().inTransaction(connection -> itemRepository.transition(
                    connection, UUID.fromString(packageSerial),
                    ItemStatus.ISSUED, ItemStatus.VOID));
        }
    }

    @Override
    public int[] afkWarnCancelSeconds() {
        return new int[] {config.afkWarningSeconds(), config.afkCancelSeconds()};
    }
}
