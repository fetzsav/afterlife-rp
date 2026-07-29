package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.setup.SetupRegistry;
import com.afterlife.rp.setup.SetupRequirement;
import com.afterlife.rp.setup.SetupStatusService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The setup checklist must describe the live server, gaps first (§12). */
class SetupStatusTest {

    private static final SetupRequirement POS =
            SetupRequirement.poi("nightclub.pos", List.of("POS_TERMINAL"), 1);
    private static final SetupRequirement DJ =
            SetupRequirement.optionalPoi("nightclub.dj", List.of("DJ_BOOTH"));
    private static final SetupRequirement STAFF =
            SetupRequirement.permissions("nightclub.staff", "bartender",
                    "afterlife.nightclub.bartender", "afterlife.nightclub.manager");

    private SetupStatusService service(SetupRegistry registry, Map<String, Integer> pois,
            Set<String> regions, Optional<Set<String>> nodes, int properties) {
        return new SetupStatusService(registry,
                type -> pois.getOrDefault(type, 0),
                (world, region) -> regions.contains(region),
                plugin -> plugin.equals("WorldGuard"),
                () -> nodes,
                () -> CompletableFuture.completedFuture(properties));
    }

    private SetupStatusService.Report evaluate(SetupStatusService service) {
        return service.evaluate("world").join();
    }

    @Test
    void aMissingPoiBlocksTheModuleAndCarriesItsFixCommand() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("nightclub", List.of(POS));
        SetupStatusService.Report report = evaluate(
                service(registry, Map.of(), Set.of(), Optional.of(Set.of()), 0));

        assertFalse(report.modules().get(0).ready());
        assertEquals(0, report.readyModules());
        SetupStatusService.Check check = report.blocking().get(0);
        assertEquals(SetupStatusService.Status.MISSING, check.status());
        assertEquals("/afterlife setup poi create POS_TERMINAL pos_terminal1", check.fix());
        assertTrue(check.detail().startsWith("0/1"));
    }

    @Test
    void aRegisteredPoiSatisfiesTheRequirementAndNumbersTheNextOne() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("nightclub", List.of(POS));
        SetupStatusService.Report report = evaluate(
                service(registry, Map.of("POS_TERMINAL", 2), Set.of(), Optional.of(Set.of()), 0));

        assertTrue(report.modules().get(0).ready());
        assertTrue(report.blocking().isEmpty());
        SetupStatusService.Check check = report.modules().get(0).checks().get(0);
        assertEquals(SetupStatusService.Status.OK, check.status());
        // The suggested name never collides with the POIs already registered.
        assertEquals("/afterlife setup poi create POS_TERMINAL pos_terminal3", check.fix());
    }

    @Test
    void optionalContentIsReportedButNeverBlocks() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("nightclub", List.of(POS, DJ));
        SetupStatusService.Report report = evaluate(
                service(registry, Map.of("POS_TERMINAL", 1), Set.of(), Optional.of(Set.of()), 0));

        assertTrue(report.modules().get(0).ready());
        assertEquals(SetupStatusService.Status.MISSING,
                report.modules().get(0).checks().get(1).status());
        assertTrue(report.blocking().isEmpty());
    }

    @Test
    void permissionsCountAsGrantedThroughWildcards() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("nightclub", List.of(STAFF));
        SetupStatusService.Report granted = evaluate(service(registry, Map.of(), Set.of(),
                Optional.of(Set.of("afterlife.nightclub.*")), 0));
        assertEquals(SetupStatusService.Status.OK,
                granted.modules().get(0).checks().get(0).status());

        SetupStatusService.Report partial = evaluate(service(registry, Map.of(), Set.of(),
                Optional.of(Set.of("afterlife.nightclub.bartender")), 0));
        SetupStatusService.Check check = partial.modules().get(0).checks().get(0);
        assertEquals(SetupStatusService.Status.MISSING, check.status());
        assertEquals("afterlife.nightclub.manager", check.detail());
        assertEquals("/lp group bartender permission set afterlife.nightclub.manager true",
                check.fix());
    }

    @Test
    void whatCannotBeVerifiedIsUnknownRatherThanMissing() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("nightclub", List.of(STAFF,
                SetupRequirement.region("nightclub.club-region", "nightclub")));
        // LuckPerms absent: permissions are unknown, not "nobody has them".
        SetupStatusService.Report report = evaluate(
                service(registry, Map.of(), Set.of(), Optional.empty(), 0));

        assertEquals(SetupStatusService.Status.UNKNOWN,
                report.modules().get(0).checks().get(0).status());
        assertEquals(SetupStatusService.Status.MISSING,
                report.modules().get(0).checks().get(1).status());
        assertTrue(report.blocking().stream()
                .noneMatch(check -> check.status() == SetupStatusService.Status.UNKNOWN));
    }

    @Test
    void inactiveModulesAreListedWithoutChecksAndNeverCountAsReady() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("banking", List.of());
        registry.disabled("ems");
        registry.blocked("crime", "banking, police");
        registry.configError("police", "police.yml: bad value");

        SetupStatusService.Report report = evaluate(
                service(registry, Map.of(), Set.of(), Optional.of(Set.of()), 0));

        assertEquals(4, report.modules().size());
        assertEquals(1, report.activeModules());
        assertEquals(1, report.readyModules());
        assertTrue(report.modules().stream().skip(1)
                .allMatch(module -> module.checks().isEmpty()));
    }

    @Test
    void aModuleCanNeverRequireAPoiTypeTheCommandWouldReject() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("ems", List.of(
                SetupRequirement.poi("ems.emergency", List.of("EMERGENCY_POINT"), 1)));
        registry.disabled("crime");

        assertEquals(Set.of("EMERGENCY_POINT"), registry.requiredPoiTypes());
    }

    @Test
    void propertiesAreUnknownWhileTheDatabaseIsDown() {
        SetupRegistry registry = new SetupRegistry();
        registry.active("realestate", List.of(SetupRequirement.property("realestate.property", 1)));
        SetupStatusService service = new SetupStatusService(registry,
                type -> 0, (world, region) -> false, plugin -> false,
                () -> Optional.of(Set.of()),
                () -> CompletableFuture.failedFuture(new IllegalStateException("db down")));

        SetupStatusService.Report report = service.evaluate("world").join();
        assertEquals(SetupStatusService.Status.UNKNOWN,
                report.modules().get(0).checks().get(0).status());
    }
}
