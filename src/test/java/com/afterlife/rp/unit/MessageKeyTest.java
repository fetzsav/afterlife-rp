package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Player-visible text must live in the message files, in every language. These
 * checks catch the two ways that silently breaks: a key used in code but never
 * translated, and a language file that drifted out of sync with another.
 */
class MessageKeyTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** messages.send(...), .bare(...), .itemName(...) etc. with a literal key. */
    private static final Pattern USAGE = Pattern.compile(
            "messages\\.(?:send|msg|bare|bareFor|itemName|menuText)\\(\\s*(?:[^;\"()]*,\\s*)?\"([a-z0-9.\\-]+)\"");

    private Set<String> keysOf(String language) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                RESOURCES.resolve("messages_" + language + ".yml").toFile());
        Set<String> keys = new TreeSet<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Test
    void everyLanguageDefinesTheSameKeys() {
        Set<String> english = keysOf("en");
        Set<String> italian = keysOf("it");
        assertTrue(english.size() > 100, "English messages look empty: " + english.size());

        Set<String> missingInItalian = new TreeSet<>(english);
        missingInItalian.removeAll(italian);
        Set<String> missingInEnglish = new TreeSet<>(italian);
        missingInEnglish.removeAll(english);

        assertEquals(Set.of(), missingInItalian, "keys missing from messages_it.yml");
        assertEquals(Set.of(), missingInEnglish, "keys missing from messages_en.yml");
    }

    @Test
    void everyMessageKeyUsedInCodeExists() throws IOException {
        Set<String> defined = keysOf("en");
        List<String> unknown = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = USAGE.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // Dotted keys only; single words are other arguments. A literal
                    // ending in a dot is the prefix of a key assembled at runtime
                    // ("setup.module." + name) — those are covered by the test below.
                    if (key.contains(".") && !key.endsWith(".") && !defined.contains(key)) {
                        unknown.add(file.getFileName() + " -> " + key);
                    }
                }
            }
        }
        assertEquals(List.of(), unknown, "message keys used in code but not defined");
    }

    @Test
    void everyRequirementAndModuleHasALabelInEveryLanguage() {
        // These keys are built at runtime from the registry, so the scan above
        // cannot see them; the setup checklist is unreadable if they are missing.
        for (String language : List.of("en", "it")) {
            Set<String> keys = keysOf(language);
            for (String module : List.of("banking", "legal", "realestate", "electrician",
                    "delivery", "ems", "nightclub", "police", "crime")) {
                assertTrue(keys.contains("setup.module." + module),
                        "missing setup.module." + module + " in " + language);
            }
            for (String requirement : List.of("banking.atm", "banking.staff", "banking.vault",
                    "legal.lawyer", "realestate.property", "realestate.staff",
                    "electrician.poi", "electrician.worker", "delivery.restaurant",
                    "delivery.destination", "delivery.shadow", "delivery.driver",
                    "ems.workstation", "ems.emergency", "ems.toxic", "ems.medic",
                    "nightclub.pos", "nightclub.shaker", "nightclub.dj",
                    "nightclub.club-region", "nightclub.vip-region", "nightclub.staff",
                    "police.officer", "crime.sale-zone", "crime.atm", "crime.gang")) {
                assertTrue(keys.contains("setup.requirement." + requirement),
                        "missing setup.requirement." + requirement + " in " + language);
            }
        }
    }

    @Test
    void everyManualTopicExistsInEveryLanguage() {
        YamlConfiguration english = YamlConfiguration.loadConfiguration(
                RESOURCES.resolve("manuals_en.yml").toFile());
        YamlConfiguration italian = YamlConfiguration.loadConfiguration(
                RESOURCES.resolve("manuals_it.yml").toFile());
        Set<String> englishTopics = english.getConfigurationSection("manuals").getKeys(false);
        Set<String> italianTopics = italian.getConfigurationSection("manuals").getKeys(false);

        assertEquals(englishTopics, italianTopics, "manual topics differ between languages");
        assertTrue(englishTopics.contains("soccorso"), "the medic handbook must be a manual topic");
    }
}
