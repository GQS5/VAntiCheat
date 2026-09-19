package site.vackstudio.vanticheat.client;

import java.util.Objects;

public class ModRecord {

    private final String modId;
    private final String name;
    private final String version;
    private final String loader;
    private final String environment;
    private final String origin;
    private final String metadataSha256;
    private final String jarSha256;

    public ModRecord(String modId, String name, String version, String loader,
                      String environment, String origin,
                      String metadataSha256, String jarSha256) {
        this.modId = modId != null ? modId : "";
        this.name = name != null ? name : "";
        this.version = version != null ? version : "";
        this.loader = loader != null ? loader : "Unknown";
        this.environment = environment != null ? environment : "";
        this.origin = origin != null ? origin : "";
        this.metadataSha256 = metadataSha256 != null ? metadataSha256 : "";
        this.jarSha256 = jarSha256 != null ? jarSha256 : "";
    }

    public String getModId() { return modId; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getLoader() { return loader; }
    public String getEnvironment() { return environment; }
    public String getOrigin() { return origin; }
    public String getMetadataSha256() { return metadataSha256; }
    public String getJarSha256() { return jarSha256; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModRecord)) return false;
        ModRecord that = (ModRecord) o;
        return modId.equals(that.modId);
    }

    @Override
    public int hashCode() {
        return modId.hashCode();
    }

    @Override
    public String toString() {
        return "ModRecord{id='" + modId + "', name='" + name + "', version='" + version + "'}";
    }
}