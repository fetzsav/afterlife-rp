package com.afterlife.rp.module.ems;

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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * EMS core (§9.8): the injury/treatment state machine, traceable medicine
 * batches, hospital billing with medic commission, certificates, and the
 * toxic-extraction mission. Treatment steps advance with one-winner updates
 * so the exact tool sequence is enforced and each step happens once.
 */
public final class EmsService implements MissionHandler {

    public static final String JOB = "EMS";
    public static final String MISSION_EMERGENCY = "EMS_EMERGENCY";
    public static final String MISSION_TOXIC = "EMS_TOXIC_EXTRACTION";
    public static final String ITEM_CERTIFICATE = "medical_certificate";
    public static final String ITEM_CHEMICAL = "toxic_chemical";

    public record Injury(long id, UUID playerUuid, String type, int severity, String state,
            int step, int version) {}

    private final DatabaseManager databaseManager;
    private final MissionService missionService;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final SerializedItemRepository itemRepository;
    private final AuditService auditService;
    private final EmsConfig config;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();

    public EmsService(
            DatabaseManager databaseManager,
            MissionService missionService,
            AccountService accountService,
            LedgerService ledgerService,
            SerializedItemRepository itemRepository,
            AuditService auditService,
            EmsConfig config) {
        this.databaseManager = databaseManager;
        this.missionService = missionService;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.itemRepository = itemRepository;
        this.auditService = auditService;
        this.config = config;
    }

    public EmsConfig config() {
        return config;
    }

    // --- injuries ---

    /** Rolls an injury for a damage cause; at most one ACTIVE injury per type. */
    public CompletableFuture<Optional<String>> maybeInflict(UUID player, String cause,
            boolean lowHealth) {
        String type = null;
        if (lowHealth && random.nextDouble() < config.unconsciousChance()) {
            type = "UNCONSCIOUS";
        } else {
            EmsConfig.CauseRule rule = config.causes().get(cause);
            if (rule != null && random.nextDouble() < rule.chance()) {
                type = rule.types().get(random.nextInt(rule.types().size()));
            }
        }
        if (type == null || config.sequenceLength(type) == 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String finalType = type;
        int severity = 1 + random.nextInt(3);
        return databaseManager.db().<Optional<String>>inTransaction(connection -> {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT COUNT(*) FROM injuries WHERE player_uuid = ? AND type = ? "
                            + "AND state <> 'HEALED'")) {
                check.setString(1, player.toString());
                check.setString(2, finalType);
                try (ResultSet rs = check.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return Optional.empty();
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO injuries (player_uuid, type, severity, cause) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, finalType);
                statement.setInt(3, severity);
                statement.setString(4, cause);
                statement.executeUpdate();
            }
            return Optional.of(finalType);
        });
    }

    public CompletableFuture<List<Injury>> activeInjuries(UUID player) {
        return databaseManager.db().supply(connection -> {
            List<Injury> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, player_uuid, type, severity, state, step, version FROM injuries "
                            + "WHERE player_uuid = ? AND state <> 'HEALED'")) {
                statement.setString(1, player.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Injury(rs.getLong("id"),
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("type"), rs.getInt("severity"),
                                rs.getString("state"), rs.getInt("step"), rs.getInt("version")));
                    }
                }
            }
            return result;
        });
    }

    // --- treatment (exact sequence, one-winner steps) ---

    public enum TreatStatus { STEP_DONE, HEALED, WRONG_TOOL, NO_INJURY, RACE_LOST, PAYMENT_FAILED }

    public record TreatResult(TreatStatus status, String injuryType, String nextTool,
            UUID consumedBatchSerial) {}

    /**
     * Applies the held tool to the first injury expecting it. The step update
     * is conditional on (id, step, state) so two medics cannot both advance the
     * same step; the final step bills the patient and pays the commission in
     * the same transaction (§9.8 hospital economy).
     */
    public CompletableFuture<TreatResult> treat(UUID medic, UUID patient, UUID patientAccountId,
            UUID medicAccountId, String toolType, UUID toolSerial) {
        UUID hospital = accountService.system("hospital_treasury").id();
        return databaseManager.db().<TreatResult>inTransaction(connection -> {
            List<Injury> injuries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, player_uuid, type, severity, state, step, version FROM injuries "
                            + "WHERE player_uuid = ? AND state <> 'HEALED' ORDER BY id")) {
                statement.setString(1, patient.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        injuries.add(new Injury(rs.getLong("id"),
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("type"), rs.getInt("severity"),
                                rs.getString("state"), rs.getInt("step"), rs.getInt("version")));
                    }
                }
            }
            if (injuries.isEmpty()) {
                return new TreatResult(TreatStatus.NO_INJURY, null, null, null);
            }
            Injury match = injuries.stream()
                    .filter(injury -> toolType.equals(config.nextTool(injury.type(), injury.step())))
                    .findFirst().orElse(null);
            if (match == null) {
                // The tool fits no injury at its current step: sequence enforced.
                return new TreatResult(TreatStatus.WRONG_TOOL, injuries.getFirst().type(),
                        config.nextTool(injuries.getFirst().type(), injuries.getFirst().step()), null);
            }
            boolean healed = match.step() + 1 >= config.sequenceLength(match.type());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE injuries SET step = step + 1, state = ?, "
                            + "healed_at = CASE WHEN ? = 'HEALED' THEN CURRENT_TIMESTAMP(3) "
                            + "ELSE healed_at END, version = version + 1 "
                            + "WHERE id = ? AND step = ? AND state <> 'HEALED'")) {
                String newState = healed ? "HEALED" : "TREATING";
                statement.setString(1, newState);
                statement.setString(2, newState);
                statement.setLong(3, match.id());
                statement.setInt(4, match.step());
                if (statement.executeUpdate() != 1) {
                    return new TreatResult(TreatStatus.RACE_LOST, match.type(), null, null);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO treatments (injury_id, medic_uuid, patient_uuid, step, tool_type) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setLong(1, match.id());
                statement.setString(2, medic.toString());
                statement.setString(3, patient.toString());
                statement.setInt(4, match.step() + 1);
                statement.setString(5, toolType);
                statement.executeUpdate();
            }
            UUID consumed = null;
            if (config.consumables().contains(toolType) && toolSerial != null) {
                if (itemRepository.transition(connection, toolSerial,
                        ItemStatus.ISSUED, ItemStatus.REDEEMED)) {
                    consumed = toolSerial;
                }
            }
            if (healed) {
                long price = config.priceCentsPerStep() * config.sequenceLength(match.type());
                long commission = price * config.medicCommissionPercent() / 100;
                ledgerService.apply(connection, "ems-" + match.id() + "-" + UUID.randomUUID(),
                        "HOSPITAL_BILL", medic, "cura " + match.type(),
                        List.of(new LedgerService.Line(patientAccountId, -price),
                                new LedgerService.Line(hospital, price - commission),
                                new LedgerService.Line(medicAccountId, commission)),
                        false);
            }
            return new TreatResult(healed ? TreatStatus.HEALED : TreatStatus.STEP_DONE,
                    match.type(), config.nextTool(match.type(), match.step() + 1), consumed);
        }).handle((result, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return new TreatResult(TreatStatus.PAYMENT_FAILED, null, null, null);
            }
            return result;
        }).thenCompose(result -> result.status() == TreatStatus.HEALED
                ? accountService.refreshBalances(patientAccountId, medicAccountId, hospital)
                        .thenApply(v -> result)
                : CompletableFuture.completedFuture(result));
    }

    // --- medicine batches (§9.8) ---

    public record ProducedMedicine(SerializedItem item, String batchCode) {}

    /** Charges reagents and creates a traceable batch + serialized medicine. */
    public CompletableFuture<Optional<ProducedMedicine>> produce(UUID medic, String medicName,
            UUID medicAccountId, String medicineType, boolean illegal, UUID workstationPoi) {
        Long reagentCost = config.reagentCostCents().get(medicineType);
        if (reagentCost == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID hospital = accountService.system("hospital_treasury").id();
        return databaseManager.db().<Optional<ProducedMedicine>>inTransaction(connection -> {
            if (!illegal) {
                ledgerService.apply(connection, "reagent-" + UUID.randomUUID(), "REAGENT_COST",
                        medic, medicineType,
                        List.of(new LedgerService.Line(medicAccountId, -reagentCost),
                                new LedgerService.Line(hospital, reagentCost)),
                        false);
            }
            long batchId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO medicine_batches (code, producer_uuid, medicine_type, legality, "
                            + "workstation) VALUES (?, ?, ?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                String provisional = "tmp-" + UUID.randomUUID();
                statement.setString(1, provisional);
                statement.setString(2, medic.toString());
                statement.setString(3, medicineType);
                statement.setString(4, illegal ? "ILLEGAL" : "LEGAL");
                statement.setString(5, workstationPoi == null ? null : workstationPoi.toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    batchId = keys.getLong(1);
                }
            }
            String code = medicName.replaceAll("[^A-Za-z0-9]", "") + "-" + batchId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE medicine_batches SET code = ? WHERE id = ?")) {
                statement.setString(1, code);
                statement.setLong(2, batchId);
                statement.executeUpdate();
            }
            JsonObject metadata = new JsonObject();
            metadata.addProperty("batch", code);
            SerializedItem item = new SerializedItem(UUID.randomUUID(), medicineType, medic, null,
                    ItemStatus.ISSUED, medic, System.currentTimeMillis(), gson.toJson(metadata));
            itemRepository.insert(connection, item);
            return Optional.of(new ProducedMedicine(item, code));
        }).handle((result, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return Optional.empty();
            }
            result.ifPresent(produced -> auditService.log(medic, medicName,
                    illegal ? "MEDICINE_PRODUCED_ILLEGAL" : "MEDICINE_PRODUCED",
                    produced.batchCode(), Map.of("type", medicineType)));
            return result;
        });
    }

    public record BatchInfo(String code, String producer, String medicineType, String legality,
            String status, String createdAt) {}

    public CompletableFuture<Optional<BatchInfo>> traceBatch(String code) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT code, producer_uuid, medicine_type, legality, status, created_at "
                            + "FROM medicine_batches WHERE code = ?")) {
                statement.setString(1, code);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new BatchInfo(rs.getString("code"),
                            rs.getString("producer_uuid"), rs.getString("medicine_type"),
                            rs.getString("legality"), rs.getString("status"),
                            rs.getTimestamp("created_at").toString()));
                }
            }
        });
    }

    public String batchCodeFromMetadata(String metadata) {
        if (metadata == null) {
            return null;
        }
        JsonObject json = gson.fromJson(metadata, JsonObject.class);
        return json != null && json.has("batch") ? json.get("batch").getAsString() : null;
    }

    // --- certificates (§9.8) ---

    public enum CertificateStatus { ISSUED, PATIENT_INJURED, PAYMENT_FAILED }

    public record CertificateResult(CertificateStatus status, SerializedItem item) {}

    /** Issued only when the patient has no open injuries; patient pays the fee. */
    public CompletableFuture<CertificateResult> issueCertificate(UUID medic, String medicName,
            UUID medicAccountId, UUID patient, UUID patientAccountId) {
        UUID hospital = accountService.system("hospital_treasury").id();
        return databaseManager.db().<CertificateResult>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM injuries WHERE player_uuid = ? AND state <> 'HEALED'")) {
                statement.setString(1, patient.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return new CertificateResult(CertificateStatus.PATIENT_INJURED, null);
                    }
                }
            }
            long commission = config.certificatePriceCents() * config.medicCommissionPercent() / 100;
            ledgerService.apply(connection, "cert-" + UUID.randomUUID(), "MEDICAL_CERTIFICATE",
                    medic, "certificato per " + patient,
                    List.of(new LedgerService.Line(patientAccountId, -config.certificatePriceCents()),
                            new LedgerService.Line(hospital, config.certificatePriceCents() - commission),
                            new LedgerService.Line(medicAccountId, commission)),
                    false);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("patient", patient.toString());
            metadata.addProperty("expires_at", System.currentTimeMillis()
                    + config.certificateExpiryDays() * 86_400_000L);
            SerializedItem item = new SerializedItem(UUID.randomUUID(), ITEM_CERTIFICATE, patient,
                    null, ItemStatus.ISSUED, medic, System.currentTimeMillis(), gson.toJson(metadata));
            itemRepository.insert(connection, item);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO medical_certificates (patient_uuid, medic_uuid, item_serial, "
                            + "expires_at) VALUES (?, ?, ?, TIMESTAMPADD(DAY, ?, CURRENT_TIMESTAMP(3)))")) {
                statement.setString(1, patient.toString());
                statement.setString(2, medic.toString());
                statement.setString(3, item.serial().toString());
                statement.setInt(4, config.certificateExpiryDays());
                statement.executeUpdate();
            }
            return new CertificateResult(CertificateStatus.ISSUED, item);
        }).handle((result, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return new CertificateResult(CertificateStatus.PAYMENT_FAILED, null);
            }
            if (result.status() == CertificateStatus.ISSUED) {
                auditService.log(medic, medicName, "CERTIFICATE_ISSUED", patient.toString(),
                        Map.of("serial", result.item().serial().toString()));
                accountService.refreshBalances(patientAccountId, medicAccountId, hospital);
            }
            return result;
        });
    }

    // --- toxic extraction (mission-backed; safe cancel is the exit gate) ---

    public CompletableFuture<Optional<Mission>> startExtraction(UUID medic, UUID barrelPoi,
            int durationSeconds) {
        JsonObject data = new JsonObject();
        data.addProperty("duration", durationSeconds);
        data.addProperty("current_target", barrelPoi.toString());
        return missionService.claim(MISSION_TOXIC, medic, barrelPoi, null,
                durationSeconds + 60, 0, data.toString());
    }

    /** Completes the extraction exactly once and issues the chemical. */
    public CompletableFuture<Optional<SerializedItem>> finishExtraction(Mission mission) {
        return missionService.complete(mission.id()).thenCompose(won -> {
            if (!won) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            SerializedItem chemical = new SerializedItem(UUID.randomUUID(), ITEM_CHEMICAL,
                    mission.owner(), null, ItemStatus.ISSUED, null, System.currentTimeMillis(), null);
            return databaseManager.db().<Optional<SerializedItem>>inTransaction(connection -> {
                itemRepository.insert(connection, chemical);
                return Optional.of(chemical);
            }).thenApply(item -> {
                auditService.log(null, "SYSTEM", "TOXIC_EXTRACTION", mission.owner().toString(),
                        Map.of("serial", chemical.serial().toString()));
                return item;
            });
        });
    }

    /** Converts a chemical into ILLEGAL adrenaline at a workstation (§9.8). */
    public CompletableFuture<Optional<ProducedMedicine>> convertChemical(UUID medic, String medicName,
            UUID chemicalSerial, UUID workstationPoi) {
        return databaseManager.db().<Boolean>inTransaction(connection ->
                itemRepository.transition(connection, chemicalSerial,
                        ItemStatus.ISSUED, ItemStatus.REDEEMED))
                .thenCompose(consumed -> {
                    if (!consumed) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    return produce(medic, medicName, null, "adrenaline", true, workstationPoi);
                });
    }

    @Override
    public void onEnded(Mission mission, String endState) {
        // Toxic extraction and emergencies clean up via their schedulers; the
        // framework audit already records the end state. Nothing durable leaks.
    }
}
