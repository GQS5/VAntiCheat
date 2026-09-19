package site.vackstudio.vanticheat.policy;

import site.vackstudio.vanticheat.client.ModRecord;

public class MatchResult {

    public enum State {
        MATCHED,
        NO_MATCH
    }

    private final String blockedRuleId;
    private final String displayName;
    private final String matchedField;
    private final String matchedValue;
    private final ModRule rule;
    private final State state;

    public MatchResult(String blockedRuleId, String displayName, String matchedField,
                       String matchedValue, ModRule rule, State state) {
        this.blockedRuleId = blockedRuleId;
        this.displayName = displayName;
        this.matchedField = matchedField;
        this.matchedValue = matchedValue;
        this.rule = rule;
        this.state = state;
    }

    public static MatchResult noMatch() {
        return new MatchResult("", "", "", "", null, State.NO_MATCH);
    }

    public boolean isMatch() { return state == State.MATCHED; }
    public String getBlockedRuleId() { return blockedRuleId; }
    public String getDisplayName() { return displayName; }
    public String getMatchedField() { return matchedField; }
    public String getMatchedValue() { return matchedValue; }
    public State getState() { return state; }
    public String getAction() { return rule != null ? rule.getAction() : ""; }
    public int getPriority() { return rule != null ? rule.hashCode() : 0; }

    @Override
    public String toString() {
        return "MatchResult{rule='" + blockedRuleId + "', field='" + matchedField
                + "', value='" + matchedValue + "', state=" + state + "}";
    }
}