package site.vackstudio.vanticheat.detection;

public interface DetectionModule {
    DetectionDefinition definition();
    String version();
    void initialize(DetectionModuleContext context);
    void start();
    void stop();
}
