package com.afterlife.rp.module.electrician;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
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

/**
 * Electrician dispatch job (§9.5). POIs fail on a schedule; an on-duty
 * electrician claims the call, repairs through the wiring minigame, and is
 * paid from the government budget. Completion runs the single server-side
 * circuit-board roll — never derived from GUI clicks.
 */
public final class ElectricianService implements MissionHandler {

    public static final String MISSION_TYPE = "ELECTRICIAN_REPAIR";
    public static final String JOB = "ELECTRICIAN";
    public static final String ITEM_TYPE_TOOL_KIT = "tool_kit";
    public static final String ITEM_TYPE_CIRCUIT_BOARD = "circuit_board";
    public static final int CIRCUIT_BOARD_MODEL_DATA = 2004;

    private final DatabaseManager databaseManager;
    private final MissionService missionService;
    private final PoiService poiService;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final SerializedItemRepository itemRepository;
    private final AuditService auditService;
    private final ElectricianConfig config;
    private final SecureRandom random = new SecureRandom();

    public ElectricianService(
            DatabaseManager databaseManager,
            MissionService missionService,
            PoiService poiService,
            AccountService accountService,
            LedgerService ledgerService,
            SerializedItemRepository itemRepository,
            AuditService auditService,
            ElectricianConfig config) {
        this.databaseManager = databaseManager;
        this.missionService = missionService;
        this.poiService = poiService;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.itemRepository = itemRepository;
        this.auditService = auditService;
        this.config = config;
    }

    public ElectricianConfig config() {
        return config;
    }

    /** Periodically fails one eligible POI (dispatch service, §9.5). */
    public CompletableFuture<Optional<Poi>> dispatchFailure() {
        List<Poi> eligible = poiService.byTypeAndStatus(config.poiTypes(), "ACTIVE");
        if (eligible.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Poi chosen = eligible.get(random.nextInt(eligible.size()));
        return poiService.updateStatus(chosen.id(), "ACTIVE", "FAILED")
                .thenApply(changed -> changed ? Optional.of(chosen) : Optional.empty());
    }

    public List<Poi> openCalls() {
        return poiService.byTypeAndStatus(config.poiTypes(), "FAILED");
    }

    public Poi poiById(UUID id) {
        return poiService.byId(id).orElse(null);
    }

    /** Claims a failed POI: one winner takes the call, one mission per player. */
    public CompletableFuture<Optional<Mission>> accept(UUID player, Poi poi) {
        return poiService.updateStatus(poi.id(), "FAILED", "REPAIRING").thenCompose(taken -> {
            if (!taken) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            int complexity = 1 + random.nextInt(3);
            JsonObject data = new JsonObject();
            data.addProperty("complexity", complexity);
            data.addProperty("accepted_ms", System.currentTimeMillis());
            return missionService.claim(MISSION_TYPE, player, poi.id(), null,
                            config.missionDeadlineMinutes() * 60, 0, data.toString())
                    .thenCompose(claimed -> {
                        if (claimed.isEmpty()) {
                            // Player already has a call: release the POI.
                            return poiService.updateStatus(poi.id(), "REPAIRING", "FAILED")
                                    .thenApply(v -> Optional.<Mission>empty());
                        }
                        return CompletableFuture.completedFuture(claimed);
                    });
        });
    }

    public record RepairResult(boolean rewarded, long rewardCents, boolean circuitBoard,
            SerializedItem boardItem) {}

    /**
     * Pays exactly once through the mission COMPLETED gate, restores the POI,
     * and performs the single 1% circuit-board roll (§9.5).
     */
    public CompletableFuture<RepairResult> completeRepair(Mission mission, UUID playerAccountId) {
        return missionService.complete(mission.id()).thenCompose(won -> {
            if (!won) {
                return CompletableFuture.completedFuture(new RepairResult(false, 0, false, null));
            }
            int complexity = (int) mission.dataLong("complexity", 1);
            long elapsedSeconds =
                    (System.currentTimeMillis() - mission.dataLong("accepted_ms",
                            System.currentTimeMillis())) / 1000;
            long reward = config.reward(complexity, elapsedSeconds);
            UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();

            boolean rollBoard = random.nextDouble() < config.circuitBoardChance();
            return ledgerService.execute("elec-" + mission.id(), "ELECTRICIAN_PAY",
                            mission.owner(), "riparazione " + mission.targetPoiId(),
                            List.of(new LedgerService.Line(government, -reward),
                                    new LedgerService.Line(playerAccountId, reward)),
                            false)
                    .thenCompose(ledger -> poiService.updateStatus(
                            mission.targetPoiId(), "REPAIRING", "ACTIVE")
                            .thenCompose(v -> {
                                if (!rollBoard) {
                                    return CompletableFuture.completedFuture(
                                            new RepairResult(true, reward, false, null));
                                }
                                return issueCircuitBoard(mission.owner())
                                        .thenApply(board -> new RepairResult(
                                                true, reward, true, board));
                            }));
        });
    }

    private CompletableFuture<SerializedItem> issueCircuitBoard(UUID owner) {
        SerializedItem board = new SerializedItem(UUID.randomUUID(), ITEM_TYPE_CIRCUIT_BOARD,
                owner, null, ItemStatus.ISSUED, null, System.currentTimeMillis(), null);
        return databaseManager.db().<SerializedItem>inTransaction(connection -> {
            itemRepository.insert(connection, board);
            return board;
        }).thenApply(saved -> {
            // Discreet audit entry (§9.5): no broadcast, no player-facing fanfare.
            auditService.log(null, "SYSTEM", "CIRCUIT_BOARD_ISSUED", owner.toString(),
                    Map.of("serial", saved.serial().toString()));
            return saved;
        });
    }

    /** Framework callback: released calls go back to FAILED so others can take them. */
    @Override
    public void onEnded(Mission mission, String endState) {
        if (mission.targetPoiId() != null) {
            poiService.updateStatus(mission.targetPoiId(), "REPAIRING", "FAILED");
        }
    }
}
