package site.vackstudio.vanticheat.detection.probe;

import java.util.function.Consumer;

public interface ClientProbeTransport {
    ProbeHandle send(ProbeRequest request, Consumer<ProbeResponse> response);
    void stop();
}
