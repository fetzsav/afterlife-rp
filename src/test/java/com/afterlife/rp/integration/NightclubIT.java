package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingConfig;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.module.nightclub.NightclubConfig;
import com.afterlife.rp.module.nightclub.NightclubService;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.items.HmacSigner;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Milestone 7 exit-gate tests: POS and escrow are atomic; deposits can never
 * be extracted or duplicated (§17 M7).
 */
@Tag("integration")
@Testcontainers
class NightclubIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;
    private static AccountService accountService;
    private static LedgerService ledgerService;
    private static NightclubService nightclubService;
    private static SerializedItemRepository itemRepository;
    private static PendingDeliveryService pendingDeliveryService;

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                new DatabaseSettings(MARIADB.getHost(), MARIADB.getFirstMappedPort(),
                        MARIADB.getDatabaseName(), MARIADB.getUsername(), MARIADB.getPassword(),
                        8, 5000),
                NightclubIT.class.getClassLoader(), Runnable::run, Logger.getLogger("NightclubIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join());

        AccountRepository accountRepository = new AccountRepository();
        LedgerRepository ledgerRepository = new LedgerRepository();
        itemRepository = new SerializedItemRepository();
        AuditService auditService = new AuditService(databaseManager,
                Logger.getLogger("NightclubIT"));
        accountService = new AccountService(databaseManager, accountRepository, auditService,
                "05428", "11101");
        ledgerService = new LedgerService(databaseManager, accountRepository, ledgerRepository,
                accountService::onLedgerCommit);
        pendingDeliveryService = new PendingDeliveryService(databaseManager);
        SerializedItemService itemService = new SerializedItemService(databaseManager,
                itemRepository, new HmacSigner("integration-test-key-0123456789ab".getBytes()));
        BankingService bankingService = new BankingService(databaseManager, accountService,
                ledgerService, accountRepository, ledgerRepository, itemRepository, itemService,
                pendingDeliveryService, auditService,
                new BankingConfig(true, List.of(50000L, 20000L, 10000L, 5000L, 2000L, 1000L, 500L),
                        true, List.of("ATM"), 4.0, List.of(5000L), "05428", "11101", 7, 5));
        nightclubService = new NightclubService(databaseManager, accountService, ledgerService,
                bankingService, itemRepository, itemService, pendingDeliveryService, auditService,
                new NightclubConfig(true, 5, List.of("POS_TERMINAL"), 60,
                        Map.of("vodka_redbull", new NightclubConfig.Product("Vodka Red Bull",
                                        1200, 800, 2500, 600, "SPEED:1:3", "", false)),
                        60, 2.0, 0.5, List.of("SHAKER_STATION"), 10, 5, 15, 5, 10_000,
                        20, 30, "nightclub", "nightclub_vip", List.of("IRON_SWORD"),
                        List.of("DJ_BOOTH"), 20, 45, 10));
        accountService.loadSystemAccounts().join();
        nightclubService.seedStockRows().join();
    }

    @AfterAll
    static void stop() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    private Account fundedAccount(long cents) {
        Account account = accountService.getOrCreatePersonal(UUID.randomUUID()).join();
        if (cents > 0) {
            UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
            assertEquals(LedgerService.Status.COMPLETED, ledgerService.execute(
                    "fund-" + UUID.randomUUID(), "TEST_FUND", null, null,
                    List.of(new LedgerService.Line(government, -cents),
                            new LedgerService.Line(account.id(), cents)),
                    false).join().status());
        }
        return account;
    }

    private void addStock(String product, int quantity) {
        databaseManager.db().inTransaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE business_stock SET stock = stock + ? WHERE business = ? AND product = ?")) {
                statement.setInt(1, quantity);
                statement.setString(2, NightclubService.BUSINESS);
                statement.setString(3, product);
                statement.executeUpdate();
            }
            return null;
        }).join();
    }

    @Test
    void posAcceptIsAtomicAndOneWinner() {
        addStock("vodka_redbull", 5);
        Account customer = fundedAccount(10_000);
        Account bartender = fundedAccount(0);
        UUID orderId = nightclubService.proposeOrder(bartender.ownerRef(), customer.ownerRef(),
                List.of(new NightclubService.OrderLine("vodka_redbull", 2))).join().orElseThrow();
        var order = nightclubService.pendingOrderFor(customer.ownerRef()).join().orElseThrow();
        assertEquals(orderId, order.id());
        assertEquals(2400, order.totalCents());

        var acceptA = nightclubService.acceptOrder(order, customer.id(), bartender.id(), 10);
        var acceptB = nightclubService.acceptOrder(order, customer.id(), bartender.id(), 10);
        long completed = List.of(acceptA.join(), acceptB.join()).stream()
                .filter(outcome -> outcome.result() == NightclubService.PosResult.COMPLETED)
                .count();
        assertEquals(1, completed, "an order settles exactly once (M7 exit gate)");

        // Commission 10% of 2400 = 240 to the bartender.
        assertEquals(240, accountService.cachedBalance(bartender.id()).orElse(0L));
    }

    @Test
    void stockCanNeverGoNegative() {
        addStock("vodka_redbull", 1);
        // Drain to exactly 1 by reading current stock.
        int stock = nightclubService.stock().join().stream()
                .filter(row -> row.product().equals("vodka_redbull"))
                .findFirst().orElseThrow().stock();
        Account customerA = fundedAccount(50_000);
        Account customerB = fundedAccount(50_000);
        Account bartender = fundedAccount(0);
        var orderA = nightclubService.proposeOrder(bartender.ownerRef(), customerA.ownerRef(),
                List.of(new NightclubService.OrderLine("vodka_redbull", stock))).join().orElseThrow();
        var orderB = nightclubService.proposeOrder(bartender.ownerRef(), customerB.ownerRef(),
                List.of(new NightclubService.OrderLine("vodka_redbull", stock))).join().orElseThrow();
        var viewA = nightclubService.pendingOrderFor(customerA.ownerRef()).join().orElseThrow();
        var viewB = nightclubService.pendingOrderFor(customerB.ownerRef()).join().orElseThrow();

        var acceptA = nightclubService.acceptOrder(viewA, customerA.id(), bartender.id(), 10);
        var acceptB = nightclubService.acceptOrder(viewB, customerB.id(), bartender.id(), 10);
        long completed = List.of(acceptA.join(), acceptB.join()).stream()
                .filter(outcome -> outcome.result() == NightclubService.PosResult.COMPLETED)
                .count();
        assertEquals(1, completed, "only one order can consume the last stock");
        int remaining = nightclubService.stock().join().stream()
                .filter(row -> row.product().equals("vodka_redbull"))
                .findFirst().orElseThrow().stock();
        assertEquals(0, remaining);
    }

    @Test
    void escrowSwapsAtomicallyExactlyOnceAndCancelsSafely() {
        UUID bartender = UUID.randomUUID();
        UUID gangA = UUID.randomUUID();
        UUID gangB = UUID.randomUUID();

        // A's goods: a serialized item; B's payment: dirty notes.
        SerializedItem goods = new SerializedItem(UUID.randomUUID(), "contraband_package", gangA,
                null, ItemStatus.ISSUED, gangA, System.currentTimeMillis(), null);
        SerializedItem note = new SerializedItem(UUID.randomUUID(), ItemTypes.DIRTY_MONEY, gangB,
                10_000L, ItemStatus.ISSUED, gangB, System.currentTimeMillis(), null);
        databaseManager.db().inTransaction(connection -> {
            itemRepository.insert(connection, goods);
            itemRepository.insert(connection, note);
            return null;
        }).join();

        nightclubService.createEscrow(bartender, "Barman", gangA, gangB, 10_000).join();
        var deal = nightclubService.openEscrowFor(gangA).join().orElseThrow();
        assertTrue(nightclubService.escrowDepositItem(deal, gangA, goods.serial()).join());

        deal = nightclubService.openEscrowFor(gangA).join().orElseThrow();
        var pdc = new SerializedItemService.PdcData(note.serial(), ItemTypes.DIRTY_MONEY,
                10_000, note.issuedAtEpochMs());
        assertEquals(10_000, nightclubService.escrowDepositDirty(deal, gangB, List.of(pdc)).join());
        // The same note cannot pay twice (single-transition).
        deal = nightclubService.openEscrowFor(gangA).join().orElseThrow();
        assertEquals(0, nightclubService.escrowDepositDirty(deal, gangB, List.of(pdc)).join());

        deal = nightclubService.openEscrowFor(gangA).join().orElseThrow();
        assertTrue(nightclubService.escrowLock(deal, gangA).join());
        deal = nightclubService.openEscrowFor(gangA).join().orElseThrow();
        assertTrue(nightclubService.escrowLock(deal, gangB).join());

        // Cancel after LOCKED must fail; confirm wins exactly once.
        deal = nightclubService.openEscrowFor(gangA).join().orElseThrow();
        assertFalse(nightclubService.escrowCancel(deal, gangA, "GangA").join(),
                "locked deals cannot be cancelled");
        var confirmA = nightclubService.escrowConfirm(deal, bartender, "Barman");
        var confirmB = nightclubService.escrowConfirm(deal, bartender, "Barman");
        long completed = List.of(confirmA.join(), confirmB.join()).stream()
                .filter(result -> result == NightclubService.EscrowResult.COMPLETED)
                .count();
        assertEquals(1, completed, "the swap happens exactly once (M7 exit gate)");

        // Goods now belong to B.
        var swapped = databaseManager.db().supply(connection ->
                itemRepository.find(connection, goods.serial())).join().orElseThrow();
        assertEquals(gangB, swapped.owner());
        // A's dirty payout (minus 5% commission) is queued durably.
        assertFalse(pendingDeliveryService.pendingFor(gangA).join().isEmpty());
    }

    @Test
    void bountyEscrowsFundsAndPaysOnce() {
        Account sponsor = fundedAccount(50_000);
        Account claimant = fundedAccount(0);
        Account bartender = fundedAccount(0);
        UUID target = UUID.randomUUID();

        long bountyId = nightclubService.createBounty(sponsor.ownerRef(), "Boss", sponsor.id(),
                target, 20_000).join().orElseThrow();
        assertEquals(30_000, accountService.cachedBalance(sponsor.id()).orElse(0L),
                "bounty funds are escrowed at creation");

        var payA = nightclubService.payBounty(bountyId, claimant.id(), bartender.id(),
                claimant.ownerRef(), bartender.ownerRef(), "Barman");
        var payB = nightclubService.payBounty(bountyId, claimant.id(), bartender.id(),
                claimant.ownerRef(), bartender.ownerRef(), "Barman");
        long paid = List.of(payA.join(), payB.join()).stream()
                .filter(result -> result == NightclubService.BountyPayResult.COMPLETED)
                .count();
        assertEquals(1, paid, "a bounty pays exactly once");
        assertEquals(19_000, accountService.cachedBalance(claimant.id()).orElse(0L),
                "claimant gets the amount minus the 5% bartender fee");
        assertEquals(1_000, accountService.cachedBalance(bartender.id()).orElse(0L));
    }

    @Test
    void happyHourDiscountsProposedOrders() {
        addStock("vodka_redbull", 3);
        Account customer = fundedAccount(10_000);
        Account bartender = fundedAccount(0);
        nightclubService.startHappyHour(bartender.ownerRef(), "Manager");
        nightclubService.proposeOrder(bartender.ownerRef(), customer.ownerRef(),
                List.of(new NightclubService.OrderLine("vodka_redbull", 1))).join().orElseThrow();
        var order = nightclubService.pendingOrderFor(customer.ownerRef()).join().orElseThrow();
        assertEquals(960, order.totalCents(), "20% happy-hour discount on 1200 cents");
    }
}
