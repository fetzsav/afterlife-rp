package com.afterlife.rp.module.police;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.legal.LegalService;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountService;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Police services (§9.3): warrants (DB-clock expiry), authorized searches with
 * audited denials, seizures into the evidence chain, scoped account checks, and
 * approximate-location alerts. Every privileged read is audited (rule 9, §14).
 */
public final class PoliceService {

    public enum SearchAuthority { CONSENT, WARRANT, EXIGENT }

    public record Warrant(UUID id, String type, UUID target, UUID issuer, String scope) {}

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final LegalService legalService;
    private final AuditService auditService;
    private final PoliceConfig config;

    public PoliceService(DatabaseManager databaseManager, AccountService accountService,
            LegalService legalService, AuditService auditService, PoliceConfig config) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.legalService = legalService;
        this.auditService = auditService;
        this.config = config;
    }

    public PoliceConfig config() {
        return config;
    }

    // --- warrants ---

    public CompletableFuture<UUID> issueWarrant(String type, UUID target, UUID issuer,
            String issuerName, String scope, Integer minutes) {
        UUID id = UUID.randomUUID();
        int duration = minutes == null ? config.warrantDefaultMinutes() : minutes;
        return databaseManager.db().<UUID>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO warrants (id, type, target_uuid, issuer_uuid, scope, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, TIMESTAMPADD(MINUTE, ?, CURRENT_TIMESTAMP(3)))")) {
                statement.setString(1, id.toString());
                statement.setString(2, type);
                statement.setString(3, target.toString());
                statement.setString(4, issuer.toString());
                statement.setString(5, scope);
                statement.setInt(6, duration);
                statement.executeUpdate();
            }
            return id;
        }).thenApply(created -> {
            auditService.log(issuer, issuerName, "WARRANT_ISSUE", target.toString(),
                    Map.of("type", type, "minutes", String.valueOf(duration)));
            return created;
        });
    }

    /** Active, non-expired warrant of a type against a target, judged by the DB clock. */
    public CompletableFuture<Optional<Warrant>> activeWarrant(UUID target, String type) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, type, target_uuid, issuer_uuid, scope FROM warrants "
                            + "WHERE target_uuid = ? AND type = ? AND state = 'ACTIVE' "
                            + "AND expires_at > CURRENT_TIMESTAMP(3) ORDER BY created_at DESC LIMIT 1")) {
                statement.setString(1, target.toString());
                statement.setString(2, type);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Warrant(UUID.fromString(rs.getString("id")),
                            rs.getString("type"), UUID.fromString(rs.getString("target_uuid")),
                            UUID.fromString(rs.getString("issuer_uuid")), rs.getString("scope")));
                }
            }
        });
    }

    // --- searches ---

    public enum SearchDecision { AUTHORIZED, DENIED }

    /**
     * A search proceeds only with consent, an active SEARCH warrant, or a
     * declared exigent circumstance. Both authorizations and denials are
     * audited (§9.3 exit gate).
     */
    public CompletableFuture<SearchDecision> authorizeSearch(UUID officer, String officerName,
            UUID target, SearchAuthority claimed, boolean consentGiven) {
        CompletableFuture<Boolean> allowed = switch (claimed) {
            case CONSENT -> CompletableFuture.completedFuture(consentGiven);
            case EXIGENT -> CompletableFuture.completedFuture(true);
            case WARRANT -> activeWarrant(target, "SEARCH").thenApply(Optional::isPresent);
        };
        // The decision only completes after its audit record is durable (§14).
        return allowed.thenCompose(ok -> auditService.log(officer, officerName,
                        ok ? "SEARCH_AUTHORIZED" : "SEARCH_DENIED", target.toString(),
                        Map.of("authority", claimed.name()))
                .thenApply(v -> ok ? SearchDecision.AUTHORIZED : SearchDecision.DENIED));
    }

    // --- seizure into the evidence chain (reuses LegalService) ---

    public CompletableFuture<Long> seize(String description, UUID itemSerial, UUID officer,
            String officerName) {
        return legalService.createEvidence(description, itemSerial, officer, officerName);
    }

    // --- scoped account check (§9.3) ---

    public record AccountCheck(String publicIdOrName, String balanceBand, boolean frozen) {}

    /** Officers see only a balance band and frozen state, never exact funds. */
    public CompletableFuture<Optional<AccountCheck>> checkAccount(UUID officer, String officerName,
            UUID target, String targetName) {
        // Audit the privileged read before returning any information (§14).
        return auditService.log(officer, officerName, "ACCOUNT_CHECK", target.toString(), Map.of())
                .thenCompose(v -> accountService.findPersonal(target))
                .thenApply(account -> account.map(a ->
                        new AccountCheck(targetName, config.band(cachedOrStored(a)), a.frozen())));
    }

    private long cachedOrStored(Account account) {
        return accountService.cachedBalance(account.id()).orElse(account.balance());
    }

    // --- alerts (approximate district only, §9.3) ---

    public CompletableFuture<Long> raiseAlert(String type, String district, String world,
            String source) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO police_alerts (type, district, world, source) VALUES (?, ?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, type);
                statement.setString(2, district);
                statement.setString(3, world);
                statement.setString(4, source);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    public record AlertView(long id, String type, String district, String createdAt) {}

    public CompletableFuture<List<AlertView>> openAlerts() {
        return databaseManager.db().supply(connection -> {
            List<AlertView> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, type, district, created_at FROM police_alerts "
                            + "WHERE state = 'OPEN' ORDER BY created_at DESC LIMIT 20");
                    ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new AlertView(rs.getLong("id"), rs.getString("type"),
                            rs.getString("district"), rs.getTimestamp("created_at").toString()));
                }
            }
            return result;
        });
    }

    public CompletableFuture<Boolean> respondAlert(long alertId, UUID officer, String officerName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE police_alerts SET state = 'RESPONDED', version = version + 1 "
                            + "WHERE id = ? AND state = 'OPEN'")) {
                statement.setLong(1, alertId);
                return statement.executeUpdate() == 1;
            }
        }).thenApply(responded -> {
            if (responded) {
                auditService.log(officer, officerName, "ALERT_RESPOND", String.valueOf(alertId),
                        Map.of());
            }
            return responded;
        });
    }
}
