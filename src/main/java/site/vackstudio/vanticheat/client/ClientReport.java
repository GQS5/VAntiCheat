package site.vackstudio.vanticheat.client;

import site.vackstudio.vanticheat.protocol.BinaryReader;
import site.vackstudio.vanticheat.protocol.ProtocolConstants;
import site.vackstudio.vanticheat.protocol.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

public class ClientReport {

    private final String protocolVersion;
    private final String minecraftVersion;
    private final String loader;
    private final String clientBrand;
    private final String sessionId;
    private final String challenge;
    private final List<ModRecord> mods;

    public ClientReport(String protocolVersion, String minecraftVersion, String loader,
                        String clientBrand, String sessionId, String challenge) {
        this.protocolVersion = protocolVersion;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.clientBrand = clientBrand;
        this.sessionId = sessionId;
        this.challenge = challenge;
        this.mods = new ArrayList<>();
    }

    public void addMod(ModRecord mod) {
        if (mods.size() >= ProtocolConstants.MAX_MODS_PER_REPORT) {
            return;
        }
        if (!mods.contains(mod)) {
            mods.add(mod);
        }
    }

    public String getProtocolVersion() { return protocolVersion; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public String getLoader() { return loader; }
    public String getClientBrand() { return clientBrand; }
    public String getSessionId() { return sessionId; }
    public String getChallenge() { return challenge; }
    public List<ModRecord> getMods() { return List.copyOf(mods); }
    public int getModCount() { return mods.size(); }

    /**
     * Binary wire encoding for the report payload carried inside a RESPONSE packet.
     *
     * <p>Layout (big-endian, all strings are u8 length + UTF-8 bytes):
     * u16 modCount, then per mod: modId, name, version, loader,
     * environment, origin, metadataSha256, jarSha256.
     *
     * <p>Session/challenge binding is enforced by the outer RESPONSE header,
     * not duplicated inside this payload.
     */
    public byte[] toBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int count = mods.size();
        if (count > ProtocolConstants.MAX_MODS_PER_REPORT) {
            throw new IllegalArgumentException("Too many mods: " + count);
        }
        out.write((count >>> 8) & 0xFF);
        out.write(count & 0xFF);
        for (ModRecord m : mods) {
            writeBoundedUtf8(out, m.getModId());
            writeBoundedUtf8(out, m.getName());
            writeBoundedUtf8(out, m.getVersion());
            writeBoundedUtf8(out, m.getLoader());
            writeBoundedUtf8(out, m.getEnvironment());
            writeBoundedUtf8(out, m.getOrigin());
            writeBoundedUtf8(out, m.getMetadataSha256());
            writeBoundedUtf8(out, m.getJarSha256());
        }
        byte[] data = out.toByteArray();
        if (data.length > ProtocolConstants.MAX_REPORT_BYTES) {
            throw new IllegalArgumentException("Report exceeds max bytes: " + data.length);
        }
        return data;
    }

    /**
     * Decodes a report payload directly from binary using the bounded BinaryReader.
     * Rejects oversized counts, oversized strings, truncation, and trailing bytes.
     */
    public static ClientReport fromBytes(byte[] data) throws ProtocolException {
        if (data == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Report is null");
        }
        if (data.length > ProtocolConstants.MAX_REPORT_BYTES) {
            throw new ProtocolException(ProtocolException.Type.OVERSIZED,
                    "Report exceeds max bytes: " + data.length);
        }
        BinaryReader reader;
        try {
            reader = BinaryReader.from(data);
        } catch (Exception e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Invalid report: " + e.getMessage());
        }
        int count;
        try {
            if (reader.remaining() < 2) {
                throw new ProtocolException(ProtocolException.Type.MALFORMED, "Report truncated");
            }
            count = reader.readUnsignedShort();
        } catch (IllegalStateException e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Report truncated");
        }
        if (count > ProtocolConstants.MAX_MODS_PER_REPORT) {
            throw new ProtocolException(ProtocolException.Type.OVERSIZED,
                    "Too many mods: " + count);
        }
        ClientReport report = new ClientReport("", "", "", "", "", "");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            String modId = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            String name = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            String version = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            String loader = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            String environment = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            String origin = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            String metadataSha256 = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            String jarSha256 = reader.readBoundedUtf8(ProtocolConstants.MAX_STRING_LENGTH);
            if (!seen.add(modId)) {
                throw new ProtocolException(ProtocolException.Type.DUPLICATE,
                        "Duplicate mod: " + modId);
            }
            report.addMod(new ModRecord(modId, name, version, loader, environment, origin,
                    metadataSha256, jarSha256));
        }
        if (!reader.requireFullyConsumed()) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Report has trailing bytes");
        }
        return report;
    }

    private static void writeBoundedUtf8(java.io.ByteArrayOutputStream out, String s) {
        if (s == null) {
            s = "";
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ProtocolConstants.MAX_STRING_LENGTH || bytes.length > 255) {
            throw new IllegalArgumentException("String exceeds max bytes: " + bytes.length);
        }
        out.write(bytes.length & 0xFF);
        out.write(bytes, 0, bytes.length);
    }
}
