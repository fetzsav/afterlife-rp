package com.afterlife.rp.module.legal;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Legal module services (§9.2): licenses, binding contracts, criminal records
 * with lawyer rehabilitation, detention timers, and audited evidence access.
 * Privileged reads are audited as well as writes (§14 staff rules).
 */
public final class LegalService {

    public static final String ITEM_TYPE_CONTRACT = "contract";

    public enum ExpungeResult { COMPLETED, NOT_ELIGIBLE, NO_RECORDS, PAYMENT_FAILED }

    public record Detention(UUID id, UUID playerUuid, UUID officerUuid, int maxMinutes,
            boolean lawyerCalled, java.time.Instant startedAt, int version) {

        public boolean overdue(java.time.Instant now) {
            return now.isAfter(startedAt.plusSeconds(maxMinutes * 60L));
        }
    }

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final SerializedItemRepository itemRepository;
    private final AuditService auditService;
    private final LegalConfig config;
    private final Gson gson = new Gson();

    public LegalService(
            DatabaseManager databaseManager,
            AccountService accountService,
            LedgerService ledgerService,
            SerializedItemRepository itemRepository,
            AuditService auditService,
            LegalConfig config) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.itemRepository = itemRepository;
        this.auditService = auditService;
        this.config = config;
    }

    public LegalConfig config() {
        return config;
    }

    // --- licenses ---

    public CompletableFuture<UUID> grantLicense(UUID player, String type, Integer days,
            UUID actor, String actorName) {
        UUID id = UUID.randomUUID();
        return databaseManager.db().<UUID>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO licenses (id, player_uuid, type, issued_by, expires_at) VALUES (?, ?, ?, ?, "
                            + (days == null ? "NULL" : "TIMESTAMPADD(DAY, ?, CURRENT_TIMESTAMP(3))") + ")")) {
                statement.setString(1, id.toString());
                statement.setString(2, player.toString());
                statement.setString(3, type);
                statement.setString(4, actor == null ? null : actor.toString());
                if (days != null) {
                    statement.setInt(5, days);
                }
                statement.executeUpdate();
            }
            return id;
        }).thenApply(saved -> {
            auditService.log(actor, actorName, "LICENSE_GRANT", player.toString(),
                    Map.of("type", type, "days", days == null ? "permanent" : String.valueOf(days)));
            return saved;
        });
    }

    public CompletableFuture<Boolean> revokeLicense(UUID player, String type, UUID actor, String actorName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE licenses SET status = 'REVOKED', version = version + 1 "
                            + "WHERE player_uuid = ? AND type = ? AND status = 'ACTIVE'")) {
                statement.setString(1, player.toString());
                statement.setString(2, type);
                return statement.executeUpdate() > 0;
            }
        }).thenApply(revoked -> {
            if (revoked) {
                auditService.log(actor, actorName, "LICENSE_REVOKE", player.toString(),
                        Map.of("type", type));
            }
            return revoked;
        });
    }

    public CompletableFuture<List<String>> activeLicenses(UUID player) {
        return databaseManager.db().supply(connection -> {
            List<String> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT type, expires_at FROM licenses WHERE player_uuid = ? AND status = 'ACTIVE' "
                            + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))")) {
                statement.setString(1, player.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        var expires = rs.getTimestamp("expires_at");
                        result.add(rs.getString("type") + (expires == null ? "" : " (fino a " + expires + ")"));
                    }
                }
            }
            return result;
        });
    }

    // --- contracts ---

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Creates a SIGNED contract with both parties and its serialized item record. */
    public CompletableFuture<SerializedItem> createSignedContract(
            UUID partyA, UUID partyB, String content) {
        UUID contractId = UUID.randomUUID();
        String hash = sha256(content);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("contract", contractId.toString());
        SerializedItem item = new SerializedItem(UUID.randomUUID(), ITEM_TYPE_CONTRACT, partyA,
                null, ItemStatus.ISSUED, partyA, System.currentTimeMillis(), gson.toJson(metadata));
        return databaseManager.db().<SerializedItem>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO contracts (id, content, content_hash) VALUES (?, ?, ?)")) {
                statement.setString(1, contractId.toString());
                statement.setString(2, content);
                statement.setString(3, hash);
                statement.executeUpdate();
            }
            for (UUID party : new UUID[] {partyA, partyB}) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO contract_parties (contract_id, player_uuid) VALUES (?, ?)")) {
                    statement.setString(1, contractId.toString());
                    statement.setString(2, party.toString());
                    statement.executeUpdate();
                }
            }
            itemRepository.insert(connection, item);
            return item;
        }).thenApply(saved -> {
            auditService.log(partyA, "player", "CONTRACT_SIGNED", contractId.toString(),
                    Map.of("hash", hash, "party_b", partyB.toString()));
            return saved;
        });
    }

    /** SIGNED -> VALIDATED exactly once; records the validating lawyer (§9.2). */
    public CompletableFuture<Boolean> validateContract(UUID contractId, UUID lawyer, String lawyerName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE contracts SET state = 'VALIDATED', lawyer_uuid = ?, "
                            + "validated_at = CURRENT_TIMESTAMP(3), version = version + 1 "
                            + "WHERE id = ? AND state = 'SIGNED'")) {
                statement.setString(1, lawyer.toString());
                statement.setString(2, contractId.toString());
                return statement.executeUpdate() == 1;
            }
        }).thenApply(validated -> {
            if (validated) {
                auditService.log(lawyer, lawyerName, "CONTRACT_VALIDATED", contractId.toString(), Map.of());
            }
            return validated;
        });
    }

    public CompletableFuture<Optional<String>> contractState(UUID contractId) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state FROM contracts WHERE id = ?")) {
                statement.setString(1, contractId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString("state")) : Optional.empty();
                }
            }
        });
    }

    public Optional<UUID> contractIdFromMetadata(String metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        JsonObject json = gson.fromJson(metadata, JsonObject.class);
        return json != null && json.has("contract")
                ? Optional.of(UUID.fromString(json.get("contract").getAsString()))
                : Optional.empty();
    }

    // --- criminal records ---

    public CompletableFuture<Long> addRecord(UUID target, String severity, String charge,
            UUID officer, String officerName) {
        return databaseManager.db().<Long>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO criminal_records (player_uuid, charge, severity, created_by) "
                            + "VALUES (?, ?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, target.toString());
                statement.setString(2, charge);
                statement.setString(3, severity);
                statement.setString(4, officer.toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        }).thenApply(id -> {
            auditService.log(officer, officerName, "RECORD_ADD", target.toString(),
                    Map.of("id", String.valueOf(id), "severity", severity, "charge", charge));
            return id;
        });
    }

    public CompletableFuture<Boolean> markServed(long recordId, UUID officer, String officerName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE criminal_records SET status = 'SERVED', served_at = CURRENT_TIMESTAMP(3), "
                            + "version = version + 1 WHERE id = ? AND status = 'OPEN'")) {
                statement.setLong(1, recordId);
                return statement.executeUpdate() == 1;
            }
        }).thenApply(updated -> {
            if (updated) {
                auditService.log(officer, officerName, "RECORD_SERVED", String.valueOf(recordId), Map.of());
            }
            return updated;
        });
    }

    public CompletableFuture<List<CriminalRecord>> activeRecords(UUID player) {
        return databaseManager.db().supply(connection -> loadActiveRecords(connection, player));
    }

    private List<CriminalRecord> loadActiveRecords(Connection connection, UUID player)
            throws SQLException {
        List<CriminalRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, player_uuid, charge, severity, status, created_at, version "
                        + "FROM criminal_records WHERE player_uuid = ? AND status <> 'EXPUNGED'")) {
            statement.setString(1, player.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new CriminalRecord(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("charge"),
                            rs.getString("severity"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getInt("version")));
                }
            }
        }
        return result;
    }

    /**
     * Rehabilitation (§9.2): archives eligible records (never deletes) after the
     * client pays the configured fee. Eligibility and payment happen in one
     * transaction so a race cannot expunge without paying.
     */
    public CompletableFuture<ExpungeResult> expunge(UUID target, UUID targetAccountId,
            UUID lawyer, String lawyerName) {
        UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
        return databaseManager.db().<ExpungeResult>inTransaction(connection -> {
            List<CriminalRecord> records = loadActiveRecords(connection, target);
            if (records.isEmpty()) {
                return ExpungeResult.NO_RECORDS;
            }
            // Eligibility is evaluated against the DATABASE clock so JVM/DB
            // timezone or clock skew can never change the outcome.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM criminal_records WHERE player_uuid = ? "
                            + "AND status <> 'EXPUNGED' AND (severity <> 'MINOR' "
                            + "OR status <> 'SERVED' "
                            + "OR created_at > TIMESTAMPADD(DAY, -?, CURRENT_TIMESTAMP(3)))")) {
                statement.setString(1, target.toString());
                statement.setInt(2, config.expungeCrimeFreeDays());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return ExpungeResult.NOT_ELIGIBLE;
                    }
                }
            }
            if (config.expungeFeeCents() > 0) {
                ledgerService.apply(connection, "expunge-" + UUID.randomUUID(), "EXPUNGE_FEE",
                        lawyer, "pulizia fedina",
                        List.of(new LedgerService.Line(targetAccountId, -config.expungeFeeCents()),
                                new LedgerService.Line(government, config.expungeFeeCents())),
                        false);
            }
            for (CriminalRecord record : records) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE criminal_records SET status = 'EXPUNGED', "
                                + "archived_at = CURRENT_TIMESTAMP(3), version = version + 1 "
                                + "WHERE id = ? AND version = ?")) {
                    statement.setLong(1, record.id());
                    statement.setInt(2, record.version());
                    if (statement.executeUpdate() != 1) {
                        throw new LedgerService.LedgerAbort(LedgerService.Status.INVALID);
                    }
                }
            }
            return ExpungeResult.COMPLETED;
        }).handle((result, e) -> {
            if (e != null) {
                LedgerService.Status status = LedgerService.failureFrom(e).status();
                return status == LedgerService.Status.INSUFFICIENT_FUNDS
                        || status == LedgerService.Status.ACCOUNT_FROZEN
                        ? ExpungeResult.PAYMENT_FAILED
                        : ExpungeResult.NOT_ELIGIBLE;
            }
            if (result == ExpungeResult.COMPLETED) {
                auditService.log(lawyer, lawyerName, "RECORD_EXPUNGE", target.toString(),
                        Map.of("fee_cents", String.valueOf(config.expungeFeeCents())));
            }
            return result;
        });
    }

    // --- detention ---

    public CompletableFuture<Boolean> arrest(UUID target, UUID officer, String officerName, int minutes) {
        int clamped = Math.min(Math.max(1, minutes), config.detentionMaxMinutes());
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            if (findActiveDetention(connection, target).isPresent()) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO detentions (id, player_uuid, officer_uuid, max_minutes) "
                            + "VALUES (?, ?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, target.toString());
                statement.setString(3, officer.toString());
                statement.setInt(4, clamped);
                statement.executeUpdate();
            }
            return true;
        }).thenApply(created -> {
            if (created) {
                auditService.log(officer, officerName, "DETENTION_START", target.toString(),
                        Map.of("max_minutes", String.valueOf(clamped)));
            }
            return created;
        });
    }

    public CompletableFuture<Boolean> release(UUID target, UUID actor, String actorName, String cause) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE detentions SET state = 'RELEASED', release_cause = ?, "
                            + "released_at = CURRENT_TIMESTAMP(3), version = version + 1 "
                            + "WHERE player_uuid = ? AND state = 'DETAINED'")) {
                statement.setString(1, cause);
                statement.setString(2, target.toString());
                return statement.executeUpdate() > 0;
            }
        }).thenApply(released -> {
            if (released) {
                auditService.log(actor, actorName, "DETENTION_RELEASE", target.toString(),
                        Map.of("cause", cause));
            }
            return released;
        });
    }

    public CompletableFuture<Optional<Detention>> activeDetention(UUID target) {
        return databaseManager.db().supply(connection -> findActiveDetention(connection, target));
    }

    public CompletableFuture<Boolean> flagLawyerCalled(UUID target) {
        return databaseManager.db().inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE detentions SET lawyer_called = 1, version = version + 1 "
                            + "WHERE player_uuid = ? AND state = 'DETAINED' AND lawyer_called = 0")) {
                statement.setString(1, target.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    /**
     * Appeal (§9.2): release ONLY on the machine-verifiable rule "detention
     * exceeded its maximum duration". Any other reasoning stays with humans.
     */
    public CompletableFuture<Boolean> appeal(UUID target, UUID lawyer, String lawyerName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            // The overrun rule is checked in SQL against the database clock:
            // release happens only when the detention exceeded its maximum.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE detentions SET state = 'RELEASED', release_cause = 'APPEAL', "
                            + "released_at = CURRENT_TIMESTAMP(3), version = version + 1 "
                            + "WHERE player_uuid = ? AND state = 'DETAINED' "
                            + "AND TIMESTAMPADD(MINUTE, max_minutes, started_at) < CURRENT_TIMESTAMP(3)")) {
                statement.setString(1, target.toString());
                return statement.executeUpdate() == 1;
            }
        }).thenApply(released -> {
            auditService.log(lawyer, lawyerName, released ? "APPEAL_GRANTED" : "APPEAL_REJECTED",
                    target.toString(), Map.of("rule", "detention_overdue"));
            return released;
        });
    }

    private Optional<Detention> findActiveDetention(Connection connection, UUID target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, player_uuid, officer_uuid, max_minutes, lawyer_called, started_at, version "
                        + "FROM detentions WHERE player_uuid = ? AND state = 'DETAINED'")) {
            statement.setString(1, target.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Detention(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("player_uuid")),
                        UUID.fromString(rs.getString("officer_uuid")),
                        rs.getInt("max_minutes"),
                        rs.getBoolean("lawyer_called"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getInt("version")));
            }
        }
    }

    // --- evidence ---

    public CompletableFuture<Long> createEvidence(String description, UUID itemSerial,
            UUID officer, String officerName) {
        return databaseManager.db().<Long>inTransaction(connection -> {
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO evidence (description, item_serial, created_by) VALUES (?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, description);
                statement.setString(2, itemSerial == null ? null : itemSerial.toString());
                statement.setString(3, officer.toString());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            insertCustody(connection, id, officer, "CREATED");
            if (itemSerial != null) {
                // Confiscated serialized items move to CONFISCATED status (§9.3).
                itemRepository.transition(connection, itemSerial, ItemStatus.ISSUED, ItemStatus.CONFISCATED);
            }
            return id;
        }).thenApply(id -> {
            auditService.log(officer, officerName, "EVIDENCE_CREATE", String.valueOf(id),
                    Map.of("description", description,
                            "serial", itemSerial == null ? "" : itemSerial.toString()));
            return id;
        });
    }

    public record EvidenceView(long id, String description, String status, String createdBy) {}

    /**
     * Authorized read with chain-of-custody entry; unauthorized attempts fail
     * AND are audited (M3 exit gate).
     */
    public CompletableFuture<Optional<EvidenceView>> viewEvidence(long id, UUID actor,
            String actorName, boolean authorized) {
        if (!authorized) {
            // The denial is durably audited BEFORE the caller sees the result.
            return auditService.log(actor, actorName, "EVIDENCE_ACCESS_DENIED",
                    String.valueOf(id), Map.of()).thenApply(v -> Optional.empty());
        }
        return databaseManager.db().<Optional<EvidenceView>>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, description, status, created_by FROM evidence WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    insertCustody(connection, id, actor, "VIEWED");
                    return Optional.of(new EvidenceView(rs.getLong("id"), rs.getString("description"),
                            rs.getString("status"), rs.getString("created_by")));
                }
            }
        }).thenApply(view -> {
            view.ifPresent(v -> auditService.log(actor, actorName, "EVIDENCE_VIEW",
                    String.valueOf(id), Map.of()));
            return view;
        });
    }

    public CompletableFuture<Integer> custodyCount(long evidenceId) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM evidence_custody WHERE evidence_id = ?")) {
                statement.setLong(1, evidenceId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    private void insertCustody(Connection connection, long evidenceId, UUID actor, String action)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO evidence_custody (evidence_id, actor_uuid, action) VALUES (?, ?, ?)")) {
            statement.setLong(1, evidenceId);
            statement.setString(2, actor.toString());
            statement.setString(3, action);
            statement.executeUpdate();
        }
    }
}
