package com.afterlife.rp.shared.economy;

import com.afterlife.rp.database.DatabaseManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Daily economy source/sink report (§7.5, §16): how much clean money each
 * transaction reason moved into or out of player/organization accounts versus
 * the system clearing accounts, so staff can see currency creation and
 * destruction before adjusting rewards.
 */
public final class EconomyReportService {

    /** Net flow to non-system accounts for a reason: positive = money created into the economy. */
    public record ReasonFlow(String reason, long netToPlayers, int transactions) {}

    public record Report(List<ReasonFlow> flows, long systemBalanceTotal) {
        public long totalCreated() {
            return flows.stream().filter(f -> f.netToPlayers() > 0)
                    .mapToLong(ReasonFlow::netToPlayers).sum();
        }

        public long totalDestroyed() {
            return flows.stream().filter(f -> f.netToPlayers() < 0)
                    .mapToLong(ReasonFlow::netToPlayers).sum();
        }
    }

    private final DatabaseManager databaseManager;

    public EconomyReportService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** Report over the last {@code hours} hours (24 for the daily summary). */
    public CompletableFuture<Report> report(int hours) {
        return databaseManager.db().supply(connection -> {
            List<ReasonFlow> flows = new ArrayList<>();
            // Sum entries on NON-system accounts per reason: the net change to the
            // player/organization economy. System clearing accounts are excluded,
            // so their mirror entries reveal true creation vs destruction.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT t.reason, SUM(e.amount) AS net, COUNT(DISTINCT t.id) AS txns "
                            + "FROM ledger_entries e "
                            + "JOIN ledger_transactions t ON t.id = e.transaction_id "
                            + "JOIN accounts a ON a.id = e.account_id "
                            + "WHERE a.owner_type <> 'SYSTEM' "
                            + "AND e.created_at > TIMESTAMPADD(HOUR, -?, CURRENT_TIMESTAMP(3)) "
                            + "GROUP BY t.reason ORDER BY net DESC")) {
                statement.setInt(1, hours);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        flows.add(new ReasonFlow(rs.getString("reason"), rs.getLong("net"),
                                rs.getInt("txns")));
                    }
                }
            }
            long systemTotal;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE owner_type = 'SYSTEM'");
                    ResultSet rs = statement.executeQuery()) {
                rs.next();
                systemTotal = rs.getLong(1);
            }
            return new Report(List.copyOf(flows), systemTotal);
        });
    }
}
