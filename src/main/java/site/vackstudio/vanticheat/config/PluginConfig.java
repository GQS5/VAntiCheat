package site.vackstudio.vanticheat.config;

import java.util.List;
import java.util.Map;

public class PluginConfig {

    private boolean generalEnabled;
    private boolean debug;
    private String pluginName;
    private String pluginVersion;

    private boolean verificationEnabled;
    private boolean requireClientVerifier;
    private int timeoutMs;
    private int maxReportBytes;
    private int maxSessionCount;
    private int cleanupIntervalMs;
    private int protocolVersion;

    private Map<String, ModRuleConfig> modRules;
    private String verificationRequired;
    private String verificationFailed;
    private String forbiddenMod;
    private String verificationTimeout;
    private String protocolError;

    public static PluginConfig defaults() {
        PluginConfig c = new PluginConfig();
        c.generalEnabled = true;
        c.debug = false;
        c.pluginName = "VAntiCheat";
        c.pluginVersion = "0.1.0";
        c.verificationEnabled = true;
        c.requireClientVerifier = true;
        c.timeoutMs = 3000;
        c.maxReportBytes = 65535;
        c.maxSessionCount = 1000;
        c.cleanupIntervalMs = 60000;
        c.protocolVersion = 1;
        c.verificationRequired = "VAntiCheat verification required.";
        c.verificationFailed = "Verification failed.";
        c.forbiddenMod = "Forbidden mod detected.";
        c.verificationTimeout = "Verification timed out.";
        c.protocolError = "Protocol error.";
        return c;
    }

    public boolean isGeneralEnabled() { return generalEnabled; }
    public void setGeneralEnabled(boolean v) { this.generalEnabled = v; }
    public boolean isDebug() { return debug; }
    public void setDebug(boolean v) { this.debug = v; }
    public String getPluginName() { return pluginName; }
    public void setPluginName(String v) { this.pluginName = v; }
    public String getPluginVersion() { return pluginVersion; }
    public void setPluginVersion(String v) { this.pluginVersion = v; }

    public boolean isVerificationEnabled() { return verificationEnabled; }
    public void setVerificationEnabled(boolean v) { this.verificationEnabled = v; }
    public boolean isRequireClientVerifier() { return requireClientVerifier; }
    public void setRequireClientVerifier(boolean v) { this.requireClientVerifier = v; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int v) { this.timeoutMs = v; }
    public int getMaxReportBytes() { return maxReportBytes; }
    public void setMaxReportBytes(int v) { this.maxReportBytes = v; }
    public int getMaxSessionCount() { return maxSessionCount; }
    public void setMaxSessionCount(int v) { this.maxSessionCount = v; }
    public int getCleanupIntervalMs() { return cleanupIntervalMs; }
    public void setCleanupIntervalMs(int v) { this.cleanupIntervalMs = v; }
    public int getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(int v) { this.protocolVersion = v; }

    public Map<String, ModRuleConfig> getModRules() { return modRules; }
    public void setModRules(Map<String, ModRuleConfig> v) { this.modRules = v; }

    public String getVerificationRequired() { return verificationRequired; }
    public void setVerificationRequired(String v) { this.verificationRequired = v; }
    public String getVerificationFailed() { return verificationFailed; }
    public void setVerificationFailed(String v) { this.verificationFailed = v; }
    public String getForbiddenMod() { return forbiddenMod; }
    public void setForbiddenMod(String v) { this.forbiddenMod = v; }
    public String getVerificationTimeout() { return verificationTimeout; }
    public void setVerificationTimeout(String v) { this.verificationTimeout = v; }
    public String getProtocolError() { return protocolError; }
    public void setProtocolError(String v) { this.protocolError = v; }
}