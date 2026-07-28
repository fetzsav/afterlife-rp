package com.afterlife.rp.shared.economy;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Ledger integrity checks (§14 money, §16): every transaction sums to zero and
 * every cached account balance equals the sum of its entries. Runs daily and
 * on /afterlife reconcile.
 */
public final class ReconciliationService {

    public record Report(int transactionsChecked, int accountsChecked, List<String> defects) {
        public boolean clean() {
            return defects.isEmpty();
        }
    }

    private final DatabaseManager databaseManager;
    private final AuditService auditService;

    public ReconciliationService(DatabaseManager databaseManager, AuditService auditService) {
        this.databaseManager = databaseManager;
        this.auditService = auditService;
    }

    public CompletableFuture<Report> run(String trigger) {
        return databaseManager.db().supply(connection -> {
            List<String> defects = new ArrayList<>();
            int transactions = 0;
            int accounts = 0;
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT transaction_id, SUM(amount) AS total, COUNT(*) AS entries "
                                + "FROM ledger_entries GROUP BY transaction_id")) {
                    while (rs.next()) {
                        transactions++;
                        if (rs.getLong("total") != 0) {
                            defects.add("transaction " + rs.getString("transaction_id")
                                    + " does not balance: " + rs.getLong("total"));
                        }
                    }
                }
                try (ResultSet rs = statement.executeQuery(
                        "SELECT a.id, a.balance, COALESCE(SUM(e.amount), 0) AS entry_sum "
                                + "FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id "
                                + "GROUP BY a.id, a.balance")) {
                    while (rs.next()) {
                        accounts++;
                        if (rs.getLong("balance") != rs.getLong("entry_sum")) {
                            defects.add("account " + rs.getString("id") + " balance "
                                    + rs.getLong("balance") + " != entry sum " + rs.getLong("entry_sum"));
                        }
                    }
                }
            }
            return new Report(transactions, accounts, List.copyOf(defects));
        }).thenApply(report -> {
            auditService.log(null, "SYSTEM", "RECONCILE_RUN", trigger, Map.of(
                    "transactions", String.valueOf(report.transactionsChecked()),
                    "accounts", String.valueOf(report.accountsChecked()),
                    "defects", String.valueOf(report.defects().size())));
            return report;
        });
    }
}
