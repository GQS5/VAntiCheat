package site.vackstudio.vanticheat.enforcement;

public class EnforcementDecisionTest {

    public static void main(String[] args) {
        testAllow();
        testReject();
        testRejectConstants();
        testToString();
        System.out.println("All EnforcementDecision tests passed.");
    }

    static void testAllow() {
        assert EnforcementDecision.ALLOW.isAllowed();
        assert !EnforcementDecision.ALLOW.isRejected();
    }

    static void testReject() {
        assert EnforcementDecision.REJECT.isRejected();
        assert !EnforcementDecision.REJECT.isAllowed();
    }

    static void testRejectConstants() {
        assert EnforcementDecision.REJECT_UNKNOWN.isRejected();
        assert EnforcementDecision.REJECT_BLOCKED.isRejected();
        assert EnforcementDecision.REJECT_TIMEOUT.isRejected();
        assert EnforcementDecision.REJECT_INVALID.isRejected();
    }

    static void testToString() {
        String s = EnforcementDecision.ALLOW.toString();
        assert s.contains("ALLOW");
    }
}