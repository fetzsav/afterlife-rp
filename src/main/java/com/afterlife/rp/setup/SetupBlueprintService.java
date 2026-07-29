package com.afterlife.rp.setup;

import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.realestate.RealEstateService;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Exports the world-bound half of the setup (POIs, properties, organizations)
 * to a YAML blueprint and replays it elsewhere. This is what makes a city
 * reproducible: build once, export, and re-apply on a rebuilt or second server.
 * Import is idempotent — entries whose name already exists are skipped.
 */
public final class SetupBlueprintService {

    /** Blueprint format version; bumped only on an incompatible layout change. */
    private static final int FORMAT = 1;

    public record ExportResult(Path file, int pois, int properties, int organizations) {}

    /** One planned or applied entry: {@code kind} + {@code name} + why it was skipped. */
    public record Entry(String kind, String name, String note) {}

    public record ImportResult(List<Entry> created, List<Entry> skipped, List<Entry> failed) {

        public ImportResult {
            created = List.copyOf(created);
            skipped = List.copyOf(skipped);
            failed = List.copyOf(failed);
        }
    }

    private final DatabaseManager databaseManager;
    private final PoiService poiService;
    private final RealEstateService realEstateService;
    private final AccountService accountService;
    private final Path directory;

    public SetupBlueprintService(
            DatabaseManager databaseManager,
            PoiService poiService,
            RealEstateService realEstateService,
            AccountService accountService,
            Path directory) {
        this.databaseManager = databaseManager;
        this.poiService = poiService;
        this.realEstateService = realEstateService;
        this.accountService = accountService;
        this.directory = directory;
    }

    /** Blueprint file for a bare name, e.g. {@code city} -> setup/city.yml. */
    public Path fileFor(String name) {
        String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return directory.resolve((safe.isEmpty() ? "blueprint" : safe) + ".yml");
    }

    /** Names of the blueprints already on disk (for tab-completion). */
    public List<String> available() {
        File[] files = directory.toFile().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return List.of();
        }
        return java.util.Arrays.stream(files)
                .map(file -> file.getName().substring(0, file.getName().length() - 4))
                .sorted()
                .toList();
    }

    public CompletableFuture<ExportResult> export(Path file) {
        List<Poi> pois = List.copyOf(poiService.all());
        return databaseManager.db().supply(connection -> {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of(
                    "AfterLifeRP setup blueprint — exported " + Instant.now(),
                    "Re-apply with /afterlife setup import <name> apply.",
                    "Coordinates are world-bound: the target server needs the same build."));
            yaml.set("format", FORMAT);
            for (Poi poi : pois) {
                ConfigurationSection section = yaml.createSection("pois." + poi.name());
                section.set("type", poi.type());
                section.set("world", poi.world());
                section.set("x", poi.x());
                section.set("y", poi.y());
                section.set("z", poi.z());
                section.set("yaw", poi.yaw());
                section.set("pitch", poi.pitch());
                if (poi.regionId() != null) {
                    section.set("region", poi.regionId());
                }
            }
            int properties = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, type, world, x, y, z, region_id, price, dirty FROM properties "
                            + "ORDER BY name");
                    ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ConfigurationSection section =
                            yaml.createSection("properties." + rows.getString("name"));
                    section.set("type", rows.getString("type"));
                    section.set("world", rows.getString("world"));
                    section.set("x", rows.getDouble("x"));
                    section.set("y", rows.getDouble("y"));
                    section.set("z", rows.getDouble("z"));
                    section.set("price-euro", rows.getLong("price") / 100);
                    section.set("dirty", rows.getBoolean("dirty"));
                    if (rows.getString("region_id") != null) {
                        section.set("region", rows.getString("region_id"));
                    }
                    properties++;
                }
            }
            int organizations = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, type, display_name FROM organizations ORDER BY name");
                    ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ConfigurationSection section =
                            yaml.createSection("organizations." + rows.getString("name"));
                    section.set("type", rows.getString("type"));
                    section.set("display-name", rows.getString("display_name"));
                    organizations++;
                }
            }
            try {
                Files.createDirectories(file.getParent());
                yaml.save(file.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new ExportResult(file, pois.size(), properties, organizations);
        });
    }

    /**
     * Applies a blueprint. With {@code apply == false} nothing is written: the
     * same plan is returned as a preview, so an admin always sees the effect
     * before committing to it.
     */
    public CompletableFuture<ImportResult> importFrom(Path file, boolean apply, UUID actor, String actorName) {
        if (!Files.isRegularFile(file)) {
            return CompletableFuture.completedFuture(new ImportResult(List.of(), List.of(),
                    List.of(new Entry("file", file.getFileName().toString(), "not-found"))));
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        // The property and organization passes run concurrently on DB threads and
        // both append here, so the collectors must be synchronized.
        List<Entry> created = Collections.synchronizedList(new ArrayList<>());
        List<Entry> skipped = Collections.synchronizedList(new ArrayList<>());
        List<Entry> failed = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<?>> pending = new ArrayList<>();

        ConfigurationSection pois = yaml.getConfigurationSection("pois");
        if (pois != null) {
            for (String name : pois.getKeys(false)) {
                ConfigurationSection section = pois.getConfigurationSection(name);
                if (section == null) {
                    continue;
                }
                if (poiService.byName(name).isPresent()) {
                    skipped.add(new Entry("poi", name, "exists"));
                    continue;
                }
                World world = Bukkit.getWorld(section.getString("world", ""));
                if (world == null) {
                    failed.add(new Entry("poi", name, "world " + section.getString("world", "?")));
                    continue;
                }
                created.add(new Entry("poi", name, section.getString("type", "GENERIC")));
                if (apply) {
                    Location location = new Location(world,
                            section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                            (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
                    pending.add(poiService.create(name,
                            section.getString("type", "GENERIC").toUpperCase(Locale.ROOT),
                            location, section.getString("region"), actor, actorName));
                }
            }
        }

        ConfigurationSection properties = yaml.getConfigurationSection("properties");
        if (properties != null && !properties.getKeys(false).isEmpty()) {
            if (realEstateService == null) {
                failed.add(new Entry("property", "*", "module-inactive"));
            } else {
                pending.add(importProperties(properties, apply, actor, actorName,
                        created, skipped, failed));
            }
        }

        ConfigurationSection organizations = yaml.getConfigurationSection("organizations");
        if (organizations != null && !organizations.getKeys(false).isEmpty()) {
            pending.add(importOrganizations(organizations, apply, actor, actorName,
                    created, skipped, failed));
        }

        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .handle((ignored, error) -> {
                    if (error != null) {
                        failed.add(new Entry("import", file.getFileName().toString(),
                                String.valueOf(error.getMessage())));
                    }
                    return new ImportResult(created, skipped, failed);
                });
    }

    private CompletableFuture<Void> importProperties(ConfigurationSection properties, boolean apply,
            UUID actor, String actorName, List<Entry> created, List<Entry> skipped, List<Entry> failed) {
        return existingNames("SELECT name FROM properties").thenCompose(existing -> {
            List<CompletableFuture<?>> pending = new ArrayList<>();
            for (String name : properties.getKeys(false)) {
                ConfigurationSection section = properties.getConfigurationSection(name);
                if (section == null) {
                    continue;
                }
                if (existing.contains(name.toLowerCase(Locale.ROOT))) {
                    skipped.add(new Entry("property", name, "exists"));
                    continue;
                }
                String world = section.getString("world", "");
                if (Bukkit.getWorld(world) == null) {
                    failed.add(new Entry("property", name, "world " + world));
                    continue;
                }
                created.add(new Entry("property", name, section.getString("type", "HOUSE")));
                if (apply) {
                    pending.add(realEstateService.createProperty(name,
                            section.getString("type", "HOUSE").toUpperCase(Locale.ROOT), world,
                            section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                            section.getString("region"), section.getLong("price-euro") * 100,
                            section.getBoolean("dirty"), actor, actorName));
                }
            }
            return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
        });
    }

    private CompletableFuture<Void> importOrganizations(ConfigurationSection organizations,
            boolean apply, UUID actor, String actorName,
            List<Entry> created, List<Entry> skipped, List<Entry> failed) {
        return existingNames("SELECT name FROM organizations").thenCompose(existing -> {
            List<CompletableFuture<?>> pending = new ArrayList<>();
            for (String name : organizations.getKeys(false)) {
                ConfigurationSection section = organizations.getConfigurationSection(name);
                if (section == null) {
                    continue;
                }
                if (existing.contains(name.toLowerCase(Locale.ROOT))) {
                    skipped.add(new Entry("organization", name, "exists"));
                    continue;
                }
                created.add(new Entry("organization", name, section.getString("type", "COMPANY")));
                if (apply) {
                    pending.add(accountService.createOrganization(name,
                            section.getString("type", "COMPANY").toUpperCase(Locale.ROOT),
                            section.getString("display-name", name), actor, actorName));
                }
            }
            return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
        });
    }

    private CompletableFuture<Set<String>> existingNames(String query) {
        return databaseManager.db().supply(connection -> {
            Set<String> names = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(query);
                    ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
            }
            return names;
        });
    }
}
