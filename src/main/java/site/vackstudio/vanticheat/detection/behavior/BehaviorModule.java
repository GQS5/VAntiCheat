package site.vackstudio.vanticheat.detection.behavior;

public interface BehaviorModule {
    BehaviorDefinition definition();
    default void initialize(BehaviorModuleContext context) { }
    void observe(BehaviorObservation observation, PlayerBehaviorSession session, BehaviorModuleContext context);
    default void shutdown() { }
}
