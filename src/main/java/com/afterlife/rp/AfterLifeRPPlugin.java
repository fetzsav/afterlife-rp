package com.afterlife.rp;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.command.AfterLifeCommand;
import com.afterlife.rp.command.IdCommand;
import com.afterlife.rp.command.SetNickCommand;
import com.afterlife.rp.config.ConfigValidationException;
import com.afterlife.rp.config.CoreConfig;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.config.Messages;
import com.afterlife.rp.config.ModuleConfigs;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.integration.IntegrationManager;
import com.afterlife.rp.integration.VaultEconomyBridge;
import com.afterlife.rp.module.banking.AtmFlows;
import com.afterlife.rp.module.banking.BankingCommands;
import com.afterlife.rp.module.banking.BankingConfig;
import com.afterlife.rp.module.banking.BankingListener;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.economy.ReconciliationService;
import com.afterlife.rp.shared.gui.GuiManager;
import com.afterlife.rp.shared.identity.IdentityListener;
import com.afterlife.rp.shared.identity.IdentityRepository;
import com.afterlife.rp.shared.identity.IdentityService;
import com.afterlife.rp.shared.identity.NametagService;
import com.afterlife.rp.shared.items.HmacSigner;
import com.afterlife.rp.shared.items.SecretKeyManager;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.regions.PoiRepository;
import com.afterlife.rp.shared.regions.PoiService;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class AfterLifeRPPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private GuiManager guiManager;
    private NametagService nametagService;
    private IdentityService identityService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Messages messages = Messages.load(this);

        CoreConfig coreConfig;
        DatabaseSettings databaseSettings;
        try {
            coreConfig = CoreConfig.from(getConfig());
            databaseSettings = DatabaseSettings.from(getConfig().getConfigurationSection("database"));
        } catch (ConfigValidationException e) {
            for (String error : e.errors()) {
                getLogger().severe("Config: " + error);
            }
            getLogger().severe("Configuration invalid — fix plugins/AfterLifeRP/config.yml and restart. "
                    + "Disabling AfterLifeRP (fail loudly, master plan §11).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        HmacSigner signer;
        try {
            signer = new HmacSigner(SecretKeyManager.loadOrCreate(
                    getDataFolder().toPath().resolve("secret.key"), getLogger()));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Cannot load or create secret.key — disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        databaseManager = new DatabaseManager(
                databaseSettings,
                getClass().getClassLoader(),
                task -> {
                    if (Bukkit.isPrimaryThread()) {
                        task.run();
                    } else if (isEnabled()) {
                        Bukkit.getScheduler().runTask(this, task);
                    }
                },
                getLogger());

        // Banking module config loads independently: an invalid file disables the
        // module loudly without taking the core down (rule 10, §11).
        BankingConfig bankingConfig = null;
        try {
            BankingConfig candidate = BankingConfig.from(
                    ModuleConfigs.load(this, "banking").getConfigurationSection("banking"));
            if (candidate.enabled()) {
                bankingConfig = candidate;
            } else {
                getLogger().info("Banking module disabled in modules/banking.yml");
            }
        } catch (ConfigValidationException e) {
            for (String error : e.errors()) {
                getLogger().severe("Banking config: " + error);
            }
            getLogger().severe("Banking module disabled until modules/banking.yml is fixed.");
        }

        // All services are constructed up front; DB-touching calls are guarded by
        // databaseManager.ready() at the command/listener layer, and joins are
        // blocked with a maintenance message while the database is unavailable.
        AuditService auditService = new AuditService(databaseManager, getLogger());
        identityService = new IdentityService(databaseManager, new IdentityRepository());
        nametagService = new NametagService();
        PoiService poiService = new PoiService(databaseManager, new PoiRepository(), auditService);
        SerializedItemRepository itemRepository = new SerializedItemRepository();
        SerializedItemService itemService = new SerializedItemService(
                databaseManager, itemRepository, signer);

        AccountRepository accountRepository = new AccountRepository();
        LedgerRepository ledgerRepository = new LedgerRepository();
        AccountService accountService = new AccountService(databaseManager, accountRepository,
                auditService,
                bankingConfig != null ? bankingConfig.ibanAbi() : "05428",
                bankingConfig != null ? bankingConfig.ibanCab() : "11101");
        LedgerService ledgerService = new LedgerService(databaseManager, accountRepository,
                ledgerRepository, accountService::onLedgerCommit);
        PendingDeliveryService pendingDeliveryService = new PendingDeliveryService(databaseManager);
        ReconciliationService reconciliationService =
                new ReconciliationService(databaseManager, auditService);

        guiManager = new GuiManager(this, Duration.ofSeconds(coreConfig.guiSessionTimeoutSeconds()));
        guiManager.start();

        IntegrationManager integrationManager = new IntegrationManager();
        integrationManager.detect(this, identityService, getLogger());

        getServer().getPluginManager().registerEvents(new IdentityListener(
                databaseManager, identityService, nametagService,
                auditService, messages, getLogger()), this);

        BankingService bankingService = null;
        if (bankingConfig != null) {
            bankingService = new BankingService(databaseManager, accountService, ledgerService,
                    accountRepository, ledgerRepository, itemRepository, itemService,
                    pendingDeliveryService, auditService, bankingConfig);
            AtmFlows atmFlows = new AtmFlows(databaseManager, accountService, bankingService,
                    itemService, poiService, guiManager, messages);
            BankingCommands bankingCommands = new BankingCommands(databaseManager, accountService,
                    bankingService, atmFlows, itemService, auditService, messages);
            for (String name : List.of("iban", "atm", "bonifico", "assegno", "incassa",
                    "banchiere", "sequestro")) {
                Objects.requireNonNull(getCommand(name)).setExecutor(bankingCommands);
            }
            getServer().getPluginManager().registerEvents(new BankingListener(
                    databaseManager, accountService, pendingDeliveryService, itemService,
                    getLogger()), this);
        }

        AfterLifeCommand afterLifeCommand = new AfterLifeCommand(
                this, databaseManager, integrationManager, poiService,
                itemService, coreConfig, messages, reconciliationService,
                accountService, bankingService);
        PluginCommand rootCommand = Objects.requireNonNull(getCommand("afterlife"));
        rootCommand.setExecutor(afterLifeCommand);
        rootCommand.setTabCompleter(afterLifeCommand);
        Objects.requireNonNull(getCommand("id")).setExecutor(new IdCommand(identityService, messages));
        Objects.requireNonNull(getCommand("setnick")).setExecutor(new SetNickCommand(
                databaseManager, identityService, nametagService, auditService, coreConfig, messages));

        registerVaultProvider(accountService, ledgerService);

        // Daily ledger reconciliation (§16); first run 5 minutes after boot.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () ->
                reconciliationService.run("daily").thenAccept(report -> {
                    if (!report.clean()) {
                        report.defects().forEach(defect ->
                                getLogger().severe("RECONCILE DEFECT: " + defect));
                    }
                }), 20L * 300, 20L * 86400);

        databaseManager.start().thenAccept(state -> {
            if (state != DatabaseManager.State.READY) {
                getLogger().severe("Database unavailable — joins are blocked with a maintenance "
                        + "message. Fix the connection settings and restart.");
                return;
            }
            accountService.loadSystemAccounts().whenComplete((ignored, error) -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "System account load failed", error);
                }
            });
            poiService.load().whenComplete((count, error) -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "POI load failed", error);
                } else {
                    getLogger().info("Loaded " + count + " POI(s) from the database");
                }
            });
            getLogger().info("AfterLifeRP " + getPluginMeta().getVersion()
                    + " ready — database connected, milestone 0+1+2 services active.");
        });
    }

    /** Vault classes are only touched when the Vault plugin is actually present. */
    private void registerVaultProvider(AccountService accountService, LedgerService ledgerService) {
        if (!getServer().getPluginManager().isPluginEnabled("Vault")
                && !getServer().getPluginManager().isPluginEnabled("VaultUnlocked")) {
            return;
        }
        try {
            getServer().getServicesManager().register(
                    net.milkbowl.vault.economy.Economy.class,
                    new VaultEconomyBridge(databaseManager, accountService, ledgerService, getLogger()),
                    this, ServicePriority.Highest);
            getLogger().info("Registered AfterLifeRP as the Vault economy provider");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Vault economy provider registration failed", t);
        }
    }

    @Override
    public void onDisable() {
        if (guiManager != null) {
            guiManager.closeAll();
        }
        if (nametagService != null) {
            nametagService.clearAll();
        }
        if (identityService != null) {
            identityService.clearCache();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
}
