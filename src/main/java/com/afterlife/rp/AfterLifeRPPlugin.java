package com.afterlife.rp;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.command.AfterLifeCommand;
import com.afterlife.rp.command.IdCommand;
import com.afterlife.rp.command.SetNickCommand;
import com.afterlife.rp.config.ConfigValidationException;
import com.afterlife.rp.config.CoreConfig;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.integration.IntegrationManager;
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
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
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

        // All services are constructed up front; DB-touching calls are guarded by
        // databaseManager.ready() at the command/listener layer, and joins are
        // blocked with a maintenance message while the database is unavailable.
        AuditService auditService = new AuditService(databaseManager, getLogger());
        identityService = new IdentityService(databaseManager, new IdentityRepository());
        nametagService = new NametagService();
        PoiService poiService = new PoiService(databaseManager, new PoiRepository(), auditService);
        SerializedItemService itemService = new SerializedItemService(
                databaseManager, new SerializedItemRepository(), signer);

        guiManager = new GuiManager(this, Duration.ofSeconds(coreConfig.guiSessionTimeoutSeconds()));
        guiManager.start();

        IntegrationManager integrationManager = new IntegrationManager();
        integrationManager.detect(this, identityService, getLogger());

        getServer().getPluginManager().registerEvents(new IdentityListener(
                databaseManager, identityService, nametagService,
                auditService, messages, getLogger()), this);

        AfterLifeCommand afterLifeCommand = new AfterLifeCommand(
                this, databaseManager, integrationManager, poiService,
                itemService, coreConfig, messages);
        PluginCommand rootCommand = Objects.requireNonNull(getCommand("afterlife"));
        rootCommand.setExecutor(afterLifeCommand);
        rootCommand.setTabCompleter(afterLifeCommand);
        Objects.requireNonNull(getCommand("id")).setExecutor(new IdCommand(identityService, messages));
        Objects.requireNonNull(getCommand("setnick")).setExecutor(new SetNickCommand(
                databaseManager, identityService, nametagService, auditService, coreConfig, messages));

        databaseManager.start().thenAccept(state -> {
            if (state != DatabaseManager.State.READY) {
                getLogger().severe("Database unavailable — joins are blocked with a maintenance "
                        + "message. Fix the connection settings and restart.");
                return;
            }
            poiService.load().whenComplete((count, error) -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "POI load failed", error);
                } else {
                    getLogger().info("Loaded " + count + " POI(s) from the database");
                }
            });
            getLogger().info("AfterLifeRP " + getPluginMeta().getVersion()
                    + " ready — database connected, milestone 0+1 services active.");
        });
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
