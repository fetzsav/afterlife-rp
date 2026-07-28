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
import com.afterlife.rp.module.delivery.DeliveryCommands;
import com.afterlife.rp.module.delivery.DeliveryConfig;
import com.afterlife.rp.module.delivery.DeliveryListener;
import com.afterlife.rp.module.delivery.DeliveryService;
import com.afterlife.rp.module.electrician.ElectricianCommands;
import com.afterlife.rp.module.electrician.ElectricianConfig;
import com.afterlife.rp.module.electrician.ElectricianService;
import com.afterlife.rp.module.ems.EmsCommands;
import com.afterlife.rp.module.ems.EmsConfig;
import com.afterlife.rp.module.ems.EmsListener;
import com.afterlife.rp.module.ems.EmsRuntime;
import com.afterlife.rp.module.ems.EmsService;
import com.afterlife.rp.module.legal.LegalCommands;
import com.afterlife.rp.module.legal.LegalConfig;
import com.afterlife.rp.module.legal.LegalService;
import com.afterlife.rp.module.crime.CrimeCommands;
import com.afterlife.rp.module.crime.CrimeConfig;
import com.afterlife.rp.module.crime.CrimeListener;
import com.afterlife.rp.module.crime.CrimeRuntime;
import com.afterlife.rp.module.crime.CrimeService;
import com.afterlife.rp.module.nightclub.NightclubCommands;
import com.afterlife.rp.module.nightclub.NightclubConfig;
import com.afterlife.rp.module.nightclub.NightclubListener;
import com.afterlife.rp.module.nightclub.NightclubService;
import com.afterlife.rp.module.police.K9Runtime;
import com.afterlife.rp.module.police.PoliceCommands;
import com.afterlife.rp.module.police.PoliceConfig;
import com.afterlife.rp.module.police.PoliceService;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.missions.MissionListener;
import com.afterlife.rp.shared.missions.MissionRepository;
import com.afterlife.rp.shared.missions.MissionService;
import com.afterlife.rp.shared.missions.MissionTracker;
import com.afterlife.rp.module.realestate.RealEstateCommands;
import com.afterlife.rp.module.realestate.RealEstateConfig;
import com.afterlife.rp.module.realestate.RealEstateService;
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
import java.util.UUID;
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
    private MissionTracker missionTracker;
    private EmsRuntime emsRuntime;
    private K9Runtime k9Runtime;
    private CrimeRuntime crimeRuntime;

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

        // Legal module (M3).
        LegalService legalService = null;
        try {
            LegalConfig legalConfig = LegalConfig.from(
                    ModuleConfigs.load(this, "legal").getConfigurationSection("legal"));
            if (legalConfig.enabled()) {
                legalService = new LegalService(databaseManager, accountService, ledgerService,
                        itemRepository, auditService, legalConfig);
                LegalCommands legalCommands = new LegalCommands(databaseManager, accountService,
                        legalService, itemService, messages);
                for (String name : List.of("contratto", "valida_contratto", "fedina",
                        "pulisci_fedina", "arresto", "rilascio", "avvocato", "ricorso",
                        "prova", "licenza")) {
                    Objects.requireNonNull(getCommand(name)).setExecutor(legalCommands);
                }
            } else {
                getLogger().info("Legal module disabled in modules/legal.yml");
            }
        } catch (ConfigValidationException e) {
            e.errors().forEach(error -> getLogger().severe("Legal config: " + error));
            getLogger().severe("Legal module disabled until modules/legal.yml is fixed.");
        }

        // Real-estate module (M3); requires banking for dirty-money issuance.
        RealEstateService realEstateService = null;
        if (bankingService != null) {
            try {
                RealEstateConfig realEstateConfig = RealEstateConfig.from(
                        ModuleConfigs.load(this, "realestate").getConfigurationSection("realestate"));
                if (realEstateConfig.enabled()) {
                    realEstateService = new RealEstateService(databaseManager, accountService,
                            ledgerService, itemRepository, pendingDeliveryService, auditService,
                            realEstateConfig);
                    RealEstateCommands realEstateCommands = new RealEstateCommands(databaseManager,
                            accountService, realEstateService, bankingService, itemService, messages);
                    for (String name : List.of("luoghidisponibili", "luoghisporchi", "agenzia",
                            "cambia_serratura", "cassaforte", "chiave")) {
                        Objects.requireNonNull(getCommand(name)).setExecutor(realEstateCommands);
                    }
                    RealEstateService finalService = realEstateService;
                    long periodTicks = 20L * 60 * realEstateConfig.powerCheckMinutes();
                    Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                        if (!databaseManager.ready()) {
                            return;
                        }
                        finalService.tickPowerAnomalies().thenAccept(alerts ->
                                databaseManager.db().onMain(() -> alerts.forEach(alert ->
                                        Bukkit.getOnlinePlayers().stream()
                                                .filter(p -> p.hasPermission("afterlife.police.officer"))
                                                .forEach(p -> messages.send(p, "estate.power-alert",
                                                        net.kyori.adventure.text.minimessage.tag.resolver
                                                                .Placeholder.unparsed("name",
                                                                        alert.propertyName()),
                                                        net.kyori.adventure.text.minimessage.tag.resolver
                                                                .Placeholder.unparsed("district",
                                                                        alert.district()))))));
                    }, periodTicks, periodTicks);
                } else {
                    getLogger().info("Real-estate module disabled in modules/realestate.yml");
                }
            } catch (ConfigValidationException e) {
                e.errors().forEach(error -> getLogger().severe("Real-estate config: " + error));
                getLogger().severe("Real-estate module disabled until modules/realestate.yml is fixed.");
            }
        } else {
            getLogger().info("Real-estate module inactive: it requires the banking module.");
        }

        // Shared mission framework (M5).
        MissionService missionService = new MissionService(databaseManager,
                new MissionRepository(), auditService, getLogger());
        JobSessionService jobSessionService = new JobSessionService(databaseManager);
        missionTracker = new MissionTracker(this, missionService, poiService);
        missionTracker.start();
        getServer().getPluginManager().registerEvents(new MissionListener(
                databaseManager, missionService, jobSessionService), this);
        // Deadline sweep every 30 seconds (live expiry; startup recovery below).
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (databaseManager.ready()) {
                missionService.expireOverdue("sweep");
            }
        }, 20L * 30, 20L * 30);

        // Electrician module (M5).
        try {
            ElectricianConfig electricianConfig = ElectricianConfig.from(
                    ModuleConfigs.load(this, "electrician").getConfigurationSection("electrician"));
            if (electricianConfig.enabled()) {
                ElectricianService electricianService = new ElectricianService(databaseManager,
                        missionService, poiService, accountService, ledgerService,
                        itemRepository, auditService, electricianConfig);
                missionService.registerHandler("ELECTRICIAN_", electricianService);
                Objects.requireNonNull(getCommand("elettricista")).setExecutor(
                        new ElectricianCommands(databaseManager, electricianService, missionService,
                                jobSessionService, accountService, itemService, guiManager, messages));
                long dispatchTicks = 20L * 60 * electricianConfig.dispatchIntervalMinutes();
                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    if (!databaseManager.ready()) {
                        return;
                    }
                    electricianService.dispatchFailure().thenAccept(failed ->
                            failed.ifPresent(poi -> databaseManager.db().onMain(() ->
                                    Bukkit.getOnlinePlayers().stream()
                                            .filter(p -> p.hasPermission("afterlife.electrician.worker"))
                                            .forEach(p -> messages.send(p,
                                                    "electrician.dispatch-broadcast",
                                                    net.kyori.adventure.text.minimessage.tag.resolver
                                                            .Placeholder.unparsed("name", poi.name()),
                                                    net.kyori.adventure.text.minimessage.tag.resolver
                                                            .Placeholder.unparsed("type", poi.type()))))));
                }, dispatchTicks, dispatchTicks);
            } else {
                getLogger().info("Electrician module disabled in modules/electrician.yml");
            }
        } catch (ConfigValidationException e) {
            e.errors().forEach(error -> getLogger().severe("Electrician config: " + error));
            getLogger().severe("Electrician module disabled until modules/electrician.yml is fixed.");
        }

        // Delivery module (M5); requires banking for dirty-money payouts.
        if (bankingService != null) {
            try {
                DeliveryConfig deliveryConfig = DeliveryConfig.from(
                        ModuleConfigs.load(this, "delivery").getConfigurationSection("delivery"));
                if (deliveryConfig.enabled()) {
                    DeliveryService deliveryService = new DeliveryService(databaseManager,
                            missionService, poiService, accountService, ledgerService,
                            bankingService, itemRepository, auditService, deliveryConfig);
                    missionService.registerHandler("FOOD_", deliveryService);
                    missionService.registerHandler("CONTRABAND_", deliveryService);
                    Objects.requireNonNull(getCommand("rider")).setExecutor(
                            new DeliveryCommands(databaseManager, deliveryService, missionService,
                                    jobSessionService, accountService, itemService, messages));
                    getServer().getPluginManager().registerEvents(new DeliveryListener(
                            databaseManager, missionService, itemService), this);
                } else {
                    getLogger().info("Delivery module disabled in modules/delivery.yml");
                }
            } catch (ConfigValidationException e) {
                e.errors().forEach(error -> getLogger().severe("Delivery config: " + error));
                getLogger().severe("Delivery module disabled until modules/delivery.yml is fixed.");
            }
        } else {
            getLogger().info("Delivery module inactive: it requires the banking module.");
        }

        // Nightclub module (M7); requires banking for dirty-money flows.
        NightclubService nightclubService = null;
        if (bankingService != null) {
            try {
                NightclubConfig nightclubConfig = NightclubConfig.from(
                        ModuleConfigs.load(this, "nightclub").getConfigurationSection("nightclub"));
                if (nightclubConfig.enabled()) {
                    nightclubService = new NightclubService(databaseManager, accountService,
                            ledgerService, bankingService, itemRepository, itemService,
                            pendingDeliveryService, auditService, nightclubConfig);
                    Objects.requireNonNull(getCommand("club")).setExecutor(new NightclubCommands(
                            this, databaseManager, nightclubService, accountService, itemService,
                            poiService, guiManager, messages));
                    getServer().getPluginManager().registerEvents(new NightclubListener(
                            this, databaseManager, nightclubService, itemService,
                            integrationManager.worldGuard(), messages), this);
                    NightclubService clubRef = nightclubService;
                    Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                        if (databaseManager.ready()) {
                            clubRef.deliverDueRestocks();
                            clubRef.expireStaleEscrows();
                        }
                    }, 20L * 60, 20L * 60);
                } else {
                    getLogger().info("Nightclub module disabled in modules/nightclub.yml");
                }
            } catch (ConfigValidationException e) {
                e.errors().forEach(error -> getLogger().severe("Nightclub config: " + error));
                getLogger().severe("Nightclub module disabled until modules/nightclub.yml is fixed.");
            }
        } else {
            getLogger().info("Nightclub module inactive: it requires the banking module.");
        }

        // EMS module (M6).
        try {
            EmsConfig emsConfig = EmsConfig.from(
                    ModuleConfigs.load(this, "ems").getConfigurationSection("ems"));
            if (emsConfig.enabled()) {
                EmsService emsService = new EmsService(databaseManager, missionService,
                        accountService, ledgerService, itemRepository, auditService, emsConfig);
                emsRuntime = new EmsRuntime(this, databaseManager, emsService, missionService,
                        poiService, jobSessionService, accountService, ledgerService,
                        itemService, messages);
                EmsListener emsListener = new EmsListener(this, databaseManager, emsService,
                        messages);
                getServer().getPluginManager().registerEvents(emsListener, this);
                // Emergencies clean their NPC on any end state; extraction tasks
                // self-cancel when the mission leaves the cache.
                EmsRuntime runtimeRef = emsRuntime;
                missionService.registerHandler("EMS_", (mission, endState) -> {
                    if (EmsService.MISSION_EMERGENCY.equals(mission.type())) {
                        runtimeRef.cleanupEmergency(mission);
                    }
                });
                Objects.requireNonNull(getCommand("ems")).setExecutor(new EmsCommands(
                        databaseManager, emsService, emsRuntime, emsListener, jobSessionService,
                        accountService, itemService, poiService, messages));
                emsRuntime.start();
                // Hourly wage for on-duty medics from the government budget (§9.8).
                Bukkit.getScheduler().runTaskTimer(this, () -> {
                    if (!databaseManager.ready()) {
                        return;
                    }
                    UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
                    Bukkit.getOnlinePlayers().stream()
                            .filter(p -> jobSessionService.isOnDuty(p.getUniqueId(), EmsService.JOB))
                            .forEach(p -> accountService.cachedPersonal(p.getUniqueId())
                                    .ifPresent(account -> ledgerService.execute(
                                            "wage-" + UUID.randomUUID(), "EMS_WAGE",
                                            p.getUniqueId(), null,
                                            List.of(new LedgerService.Line(government,
                                                            -emsConfig.wageHourlyCents()),
                                                    new LedgerService.Line(account.id(),
                                                            emsConfig.wageHourlyCents())),
                                            false)));
                }, 20L * 3600, 20L * 3600);
            } else {
                getLogger().info("EMS module disabled in modules/ems.yml");
            }
        } catch (ConfigValidationException e) {
            e.errors().forEach(error -> getLogger().severe("EMS config: " + error));
            getLogger().severe("EMS module disabled until modules/ems.yml is fixed.");
        }

        // Police module (M8); needs the legal module for the evidence chain.
        PoliceService policeService = null;
        if (legalService != null) {
            try {
                PoliceConfig policeConfig = PoliceConfig.from(
                        ModuleConfigs.load(this, "police").getConfigurationSection("police"));
                if (policeConfig.enabled()) {
                    policeService = new PoliceService(databaseManager, accountService,
                            legalService, auditService, policeConfig);
                    k9Runtime = new K9Runtime(this, policeConfig, jobSessionService, itemService,
                            messages);
                    k9Runtime.start();
                    Objects.requireNonNull(getCommand("polizia")).setExecutor(new PoliceCommands(
                            databaseManager, policeService, k9Runtime, jobSessionService,
                            itemService, messages));
                    var policeCommands = getCommand("polizia").getExecutor();
                    Objects.requireNonNull(getCommand("k9")).setExecutor(policeCommands);
                    Objects.requireNonNull(getCommand("acconsenti")).setExecutor(policeCommands);
                } else {
                    getLogger().info("Police module disabled in modules/police.yml");
                }
            } catch (ConfigValidationException e) {
                e.errors().forEach(error -> getLogger().severe("Police config: " + error));
                getLogger().severe("Police module disabled until modules/police.yml is fixed.");
            }
        } else {
            getLogger().info("Police module inactive: it requires the legal module.");
        }

        // Crime module (M8); needs banking (dirty money) and police (alerts).
        if (bankingService != null && policeService != null) {
            try {
                CrimeConfig crimeConfig = CrimeConfig.from(
                        ModuleConfigs.load(this, "crime").getConfigurationSection("crime"));
                if (crimeConfig.enabled()) {
                    CrimeService crimeService = new CrimeService(databaseManager, missionService,
                            bankingService, itemRepository, auditService, crimeConfig);
                    crimeRuntime = new CrimeRuntime(this, databaseManager, crimeConfig, crimeService,
                            policeService, missionService, poiService, jobSessionService,
                            itemService, messages);
                    missionService.registerHandler("ATM_HACK", crimeService);
                    getServer().getPluginManager().registerEvents(new CrimeListener(
                            this, databaseManager, crimeService, crimeRuntime, itemService,
                            messages), this);
                    Objects.requireNonNull(getCommand("gang")).setExecutor(new CrimeCommands(
                            databaseManager, crimeService, crimeRuntime, policeService,
                            jobSessionService, itemService, poiService, messages));
                    crimeRuntime.start();
                } else {
                    getLogger().info("Crime module disabled in modules/crime.yml");
                }
            } catch (ConfigValidationException e) {
                e.errors().forEach(error -> getLogger().severe("Crime config: " + error));
                getLogger().severe("Crime module disabled until modules/crime.yml is fixed.");
            }
        } else {
            getLogger().info("Crime module inactive: it requires the banking and police modules.");
        }

        AfterLifeCommand afterLifeCommand = new AfterLifeCommand(
                this, databaseManager, integrationManager, poiService,
                itemService, coreConfig, messages, reconciliationService,
                accountService, bankingService, legalService, realEstateService);
        PluginCommand rootCommand = Objects.requireNonNull(getCommand("afterlife"));
        rootCommand.setExecutor(afterLifeCommand);
        rootCommand.setTabCompleter(afterLifeCommand);
        Objects.requireNonNull(getCommand("id")).setExecutor(new IdCommand(identityService, messages));
        Objects.requireNonNull(getCommand("setnick")).setExecutor(new SetNickCommand(
                databaseManager, identityService, nametagService, auditService, coreConfig, messages));

        NightclubService nightclubServiceFinal = nightclubService;

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
            if (nightclubServiceFinal != null) {
                nightclubServiceFinal.seedStockRows();
            }
            poiService.load().whenComplete((count, error) -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "POI load failed", error);
                } else {
                    getLogger().info("Loaded " + count + " POI(s) from the database");
                }
            });
            // Restart recovery (rule 13): expire overdue missions, close stale duty.
            missionService.expireOverdue("startup").thenAccept(expired -> {
                if (expired > 0) {
                    getLogger().info("Startup recovery expired " + expired + " overdue mission(s)");
                }
            });
            jobSessionService.closeStaleOnStartup().thenAccept(closed -> {
                if (closed > 0) {
                    getLogger().info("Startup recovery closed " + closed + " stale job session(s)");
                }
            });
            getLogger().info("AfterLifeRP " + getPluginMeta().getVersion()
                    + " ready — database connected, milestones 0-3 services active.");
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
        if (crimeRuntime != null) {
            crimeRuntime.stop();
        }
        if (k9Runtime != null) {
            k9Runtime.stop();
        }
        if (emsRuntime != null) {
            emsRuntime.stop();
        }
        if (missionTracker != null) {
            missionTracker.stop();
        }
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
