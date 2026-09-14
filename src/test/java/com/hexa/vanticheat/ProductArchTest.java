package com.hexa.vanticheat;

import com.hexa.vanticheat.platform.ServerPlatform;
import com.hexa.vanticheat.product.ConfigMigration;
import com.hexa.vanticheat.product.FeatureEntitlement;
import com.hexa.vanticheat.product.LicenseProvider;
import com.hexa.vanticheat.product.LicenseState;
import com.hexa.vanticheat.simulation.CombatGeometry;
import com.hexa.vanticheat.simulation.InfoModel;
import com.hexa.vanticheat.simulation.InvestigationState;
import com.hexa.vanticheat.simulation.InvestigationTracker;
import com.hexa.vanticheat.simulation.MovementPredictor;
import com.hexa.vanticheat.simulation.WorldReplica;
import com.hexa.vanticheat.state.NormalizedCombatState;
import com.hexa.vanticheat.state.NormalizedMovementState;
import com.hexa.vanticheat.version.ClientVersionProfile;
import com.hexa.vanticheat.version.ServerProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Commercial architecture tests: profiles, simulation gates, product layer. */
public class ProductArchTest {

    @Test
    public void nativeFastPath() {
        ServerProfile s = ServerProfile.native12111();
        assertTrue(s.nativeFastPath());
        ClientVersionProfile c = ClientVersionProfile.unknownNative(UUID.randomUUID());
        assertTrue(c.fastPath(s)); // graceful absent-ViaVersion
    }

    @Test
    public void platformDetectNeverNull() {
        assertNotNull(ServerPlatform.detect());
    }

    @Test
    public void movementPredictionSeparatesLegit() {
        ServerProfile s = ServerProfile.native12111();
        var legit = NormalizedMovementState.of(0.2, 0, 0.1, true, true, 40);
        assertTrue(MovementPredictor.error(legit, s) <= 0);
        var bad = NormalizedMovementState.of(2.0, 0, 0, true, false, 40);
        assertTrue(MovementPredictor.error(bad, s) > 0);
    }

    @Test
    public void combatGeometryNeedsRepeats() {
        var one = new NormalizedCombatState(9.0, 30, 100, false, false);
        assertFalse(CombatGeometry.impossible(one, 3.4, 1)); // single hit never proof
        assertTrue(CombatGeometry.impossible(one, 3.4, 5));
    }

    @Test
    public void investigationEscalatesAndStaysBounded() {
        InvestigationTracker t = new InvestigationTracker();
        UUID id = UUID.randomUUID();
        assertEquals(InvestigationState.NORMAL, t.get(id));
        assertEquals(InvestigationState.CONFIRMED, t.update(id, 96, 2));
        assertEquals(InvestigationState.WATCH, t.update(id, 35, 1));
    }

    @Test
    public void infoModelHonest() {
        assertTrue(InfoModel.oreWasExposed(true));
        assertFalse(InfoModel.suspiciousAcquisition(true, 20, 5));
        assertTrue(InfoModel.suspiciousAcquisition(false, 20, 5));
        assertFalse(InfoModel.suspiciousAcquisition(false, 20, 1)); // one look never enough
    }

    @Test
    public void worldReplicaBounded() {
        WorldReplica r = new WorldReplica();
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 200; i++) r.observe(id, i, 64, 0, "STONE");
        assertTrue(r.recentCount(id, 60_000) <= 48);
    }

    @Test
    public void licenseLocalFunctionalOffline() {
        LicenseProvider l = LicenseProvider.local();
        assertEquals(LicenseState.LICENSED, l.state());
        assertTrue(l.entitlements().contains(FeatureEntitlement.CORE));
        assertEquals(4, ConfigMigration.CURRENT);
    }
}
