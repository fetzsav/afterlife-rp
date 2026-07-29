package com.afterlife.rp.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Turns the registry's requirements into a live readiness checklist with, for
 * every gap, the exact command that closes it. Dependencies are injected as
 * plain functions so the evaluation is unit-testable without a running server.
 */
public final class SetupStatusService {

    public enum Status { OK, MISSING, UNKNOWN }

    /** One evaluated requirement: what is missing and the command that fixes it. */
    public record Check(SetupRequirement requirement, Status status, String detail, String fix) {

        public boolean blocking() {
            return status == Status.MISSING && !requirement.optional();
        }
    }

    public record ModuleReport(SetupRegistry.Module module, List<Check> checks) {

        public ModuleReport {
            checks = List.copyOf(checks);
        }

        public boolean ready() {
            return module.active() && checks.stream().noneMatch(Check::blocking);
        }
    }

    public record Report(List<ModuleReport> modules) {

        public Report {
            modules = List.copyOf(modules);
        }

        public long readyModules() {
            return modules.stream().filter(ModuleReport::ready).count();
        }

        public long activeModules() {
            return modules.stream().filter(report -> report.module().active()).count();
        }

        /** Blocking gaps in declaration order — the admin's to-do list. */
        public List<Check> blocking() {
            return modules.stream().flatMap(report -> report.checks().stream())
                    .filter(Check::blocking).toList();
        }
    }

    private final SetupRegistry registry;
    private final ToIntFunction<String> poiCountByType;
    private final BiPredicate<String, String> regionExists;
    private final Predicate<String> pluginPresent;
    private final Supplier<Optional<Set<String>>> grantedNodes;
    private final Supplier<CompletableFuture<Integer>> propertyCount;

    public SetupStatusService(
            SetupRegistry registry,
            ToIntFunction<String> poiCountByType,
            BiPredicate<String, String> regionExists,
            Predicate<String> pluginPresent,
            Supplier<Optional<Set<String>>> grantedNodes,
            Supplier<CompletableFuture<Integer>> propertyCount) {
        this.registry = registry;
        this.poiCountByType = poiCountByType;
        this.regionExists = regionExists;
        this.pluginPresent = pluginPresent;
        this.grantedNodes = grantedNodes;
        this.propertyCount = propertyCount;
    }

    /**
     * Evaluates every module against the live server. {@code worldName} is the
     * world region checks run against (the admin's world, or the main world).
     */
    public CompletableFuture<Report> evaluate(String worldName) {
        boolean needsProperties = registry.modules().stream()
                .filter(SetupRegistry.Module::active)
                .flatMap(module -> module.requirements().stream())
                .anyMatch(requirement -> requirement.kind() == SetupRequirement.Kind.PROPERTY);
        CompletableFuture<Integer> properties = needsProperties
                ? propertyCount.get().exceptionally(error -> -1)
                : CompletableFuture.completedFuture(0);
        return properties.thenApply(propertyTotal -> {
            Optional<Set<String>> nodes = grantedNodes.get();
            List<ModuleReport> reports = new ArrayList<>();
            for (SetupRegistry.Module module : registry.modules()) {
                List<Check> checks = new ArrayList<>();
                if (module.active()) {
                    for (SetupRequirement requirement : module.requirements()) {
                        checks.add(check(requirement, worldName, nodes, propertyTotal));
                    }
                }
                reports.add(new ModuleReport(module, checks));
            }
            return new Report(reports);
        });
    }

    private Check check(SetupRequirement requirement, String worldName,
            Optional<Set<String>> nodes, int propertyTotal) {
        return switch (requirement.kind()) {
            case POI -> poiCheck(requirement);
            case REGION -> regionCheck(requirement, worldName);
            case PERMISSION -> permissionCheck(requirement, nodes);
            case PROPERTY -> propertyCheck(requirement, propertyTotal);
            case PLUGIN -> pluginCheck(requirement);
        };
    }

    private Check poiCheck(SetupRequirement requirement) {
        int found = 0;
        for (String type : requirement.targets()) {
            found += poiCountByType.applyAsInt(type);
        }
        String type = requirement.targets().get(0);
        String detail = found + "/" + requirement.minimum() + " · " + String.join(", ", requirement.targets());
        String fix = "/afterlife setup poi create " + type + " "
                + type.toLowerCase(Locale.ROOT) + (poiCountByType.applyAsInt(type) + 1);
        return new Check(requirement, found >= requirement.minimum() ? Status.OK : Status.MISSING,
                detail, fix);
    }

    private Check regionCheck(SetupRequirement requirement, String worldName) {
        String region = requirement.target();
        if (!pluginPresent.test("WorldGuard")) {
            return new Check(requirement, Status.UNKNOWN, region + " · WorldGuard", null);
        }
        boolean exists = regionExists.test(worldName, region);
        return new Check(requirement, exists ? Status.OK : Status.MISSING,
                region + " · " + worldName, "/rg define " + region);
    }

    private Check permissionCheck(SetupRequirement requirement, Optional<Set<String>> nodes) {
        if (nodes.isEmpty()) {
            return new Check(requirement, Status.UNKNOWN,
                    String.join(", ", requirement.targets()), null);
        }
        List<String> missing = requirement.targets().stream()
                .filter(node -> !granted(nodes.get(), node)).toList();
        if (missing.isEmpty()) {
            return new Check(requirement, Status.OK, String.join(", ", requirement.targets()), null);
        }
        return new Check(requirement, Status.MISSING, String.join(", ", missing),
                "/lp group " + requirement.group() + " permission set " + missing.get(0) + " true");
    }

    private Check propertyCheck(SetupRequirement requirement, int propertyTotal) {
        if (propertyTotal < 0) {
            return new Check(requirement, Status.UNKNOWN, "?", null);
        }
        String fix = "/afterlife setup property create HOUSE house" + (propertyTotal + 1) + " 150000";
        return new Check(requirement,
                propertyTotal >= requirement.minimum() ? Status.OK : Status.MISSING,
                propertyTotal + "/" + requirement.minimum(), fix);
    }

    private Check pluginCheck(SetupRequirement requirement) {
        boolean present = pluginPresent.test(requirement.target());
        return new Check(requirement, present ? Status.OK : Status.MISSING,
                requirement.target(), null);
    }

    /** A node counts as granted through an exact match or any covering wildcard. */
    static boolean granted(Set<String> nodes, String node) {
        if (nodes.contains(node) || nodes.contains("*")) {
            return true;
        }
        String remaining = node;
        int dot;
        while ((dot = remaining.lastIndexOf('.')) > 0) {
            remaining = remaining.substring(0, dot);
            if (nodes.contains(remaining + ".*")) {
                return true;
            }
        }
        return false;
    }
}
