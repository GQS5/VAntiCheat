package site.vackstudio.vanticheat.platform;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlatformServiceTest {
    @Test
    void floodgateBedrockIsAuthoritative() {
        ClientPlatformService service = service(true, true, false, false);
        var classification = service.classify(UUID.randomUUID());
        assertEquals(ClientPlatformService.State.BEDROCK, classification.state());
        assertEquals(ClientPlatformService.Reason.FLOODGATE, classification.reason());
    }

    @Test
    void geyserBedrockAndBothProviderAgreementAreReported() {
        assertEquals(ClientPlatformService.State.BEDROCK,
                service(false, false, true, true).classify(UUID.randomUUID()).state());
        var both = service(true, true, true, true).classify(UUID.randomUUID());
        assertEquals(ClientPlatformService.State.BEDROCK, both.state());
        assertEquals(ClientPlatformService.Reason.BOTH, both.reason());
    }

    @Test
    void noProviderAllowsJavaButPendingProviderNeverDoes() {
        var noProvider = service(false, false, false, false).classify(UUID.randomUUID());
        assertEquals(ClientPlatformService.State.NO_PROVIDER, noProvider.state());
        assertTrue(noProvider.canProbe());

        var pending = service(true, null, false, false).classify(UUID.randomUUID());
        assertEquals(ClientPlatformService.State.UNKNOWN, pending.state());
        assertFalse(pending.canProbe());
        assertEquals(ClientPlatformService.Readiness.NOT_READY,
                service(true, null, false, false).readiness());
    }

    @Test
    void cacheSupportsRefreshInvalidationRemovalAndClear() {
        FakeProvider floodgate = new FakeProvider("FLOODGATE", true, false);
        ClientPlatformService service = new ClientPlatformService(floodgate,
                new FakeProvider("GEYSER", false, false));
        UUID id = UUID.randomUUID();

        assertEquals(ClientPlatformService.State.JAVA, service.classify(id).state());
        floodgate.answer = true;
        assertEquals(ClientPlatformService.State.JAVA, service.classify(id).state(), "classification is cached");
        assertEquals(ClientPlatformService.State.BEDROCK, service.refresh(id).state());
        assertEquals(1, service.size());
        service.invalidate(id);
        assertEquals(0, service.size());
        service.classify(id);
        service.remove(id);
        assertEquals(0, service.size());
        service.classify(id);
        service.clear();
        assertEquals(0, service.size());
        assertEquals(0, service.count(ClientPlatformService.State.BEDROCK));
    }

    @Test
    void preloginInspectionDoesNotRetainUuidUntilAnUnmatchedQuitEvent() {
        ClientPlatformService service = service(true, false, false, false);
        UUID id = UUID.randomUUID();

        assertEquals(ClientPlatformService.State.JAVA, service.inspect(id).state());

        assertEquals(0, service.size());
        assertEquals(0, service.count(ClientPlatformService.State.JAVA));
    }

    @Test
    void cachedProviderAndPlatformDiagnosticsDoNotRequeryProviders() {
        AtomicInteger calls = new AtomicInteger();
        FakeProvider floodgate = new FakeProvider("FLOODGATE", true, false) {
            @Override public boolean installed() { calls.incrementAndGet(); return super.installed(); }
            @Override public boolean ready() { calls.incrementAndGet(); return super.ready(); }
        };
        ClientPlatformService service = new ClientPlatformService(floodgate,
                new FakeProvider("GEYSER", false, false));
        UUID id = UUID.randomUUID();

        assertEquals(ClientPlatformService.State.JAVA, service.refresh(id).state());
        assertEquals(1, service.count(ClientPlatformService.State.JAVA));
        int providerCallsAfterClassification = calls.get();
        for (int i = 0; i < 5; i++) {
            assertEquals(ClientPlatformService.ProviderState.READY,
                    service.providerSnapshot().floodgate());
            assertEquals(ClientPlatformService.Readiness.READY, service.readiness());
            assertEquals("FLOODGATE", service.providers());
            assertEquals(1, service.count(ClientPlatformService.State.JAVA));
        }
        assertEquals(providerCallsAfterClassification, calls.get());
    }

    @Test
    void providerDiagnosticsDistinguishNotReadyFromFailure() {
        ClientPlatformService notReady = new ClientPlatformService(
                new FakeProvider("FLOODGATE", true, null), new FakeProvider("GEYSER", false, null));
        assertEquals(ClientPlatformService.ProviderState.NOT_READY,
                notReady.providerSnapshot().floodgate());
        assertEquals(ClientPlatformService.ProviderState.NOT_PRESENT,
                notReady.providerSnapshot().geyser());

        ClientPlatformService failed = new ClientPlatformService(new FakeProvider("FLOODGATE", true, null) {
            @Override public ClientPlatformService.ProviderState status() {
                return ClientPlatformService.ProviderState.FAILED;
            }
        }, new FakeProvider("GEYSER", false, null));
        assertEquals(ClientPlatformService.ProviderState.FAILED,
                failed.providerSnapshot().floodgate());
    }

    @Test
    void authoritativeBedrockTransitionNotifiesActiveProbeOwner() {
        FakeProvider floodgate = new FakeProvider("FLOODGATE", true, false);
        ClientPlatformService service = new ClientPlatformService(floodgate,
                new FakeProvider("GEYSER", false, false));
        AtomicInteger notifications = new AtomicInteger();
        service.addListener((id, classification) -> {
            if (classification.state() == ClientPlatformService.State.BEDROCK) notifications.incrementAndGet();
        });
        UUID id = UUID.randomUUID();

        assertTrue(service.classify(id).canProbe());
        floodgate.answer = true;
        assertEquals(ClientPlatformService.State.BEDROCK, service.refresh(id).state());
        assertEquals(1, notifications.get());
    }

    @Test
    void concurrentClassificationsShareCachedAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeProvider floodgate = new FakeProvider("FLOODGATE", true, false) {
            @Override public Boolean isBedrock(UUID playerId) {
                calls.incrementAndGet();
                return false;
            }
        };
        ClientPlatformService service = new ClientPlatformService(floodgate,
                new FakeProvider("GEYSER", false, false));
        UUID id = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 40; i++) executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                service.classify(id);
            });
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get());
    }

    @Test
    void providerFalseIsJavaOnlyAfterAuthoritativeAnswer() {
        assertEquals(ClientPlatformService.State.JAVA,
                service(true, false, false, false).classify(UUID.randomUUID()).state());
    }

    @Test
    void cachedCountsExposeJavaBedrockUnknownAndNoProviderSeparately() {
        ClientPlatformService service = new ClientPlatformService(
                new FakeProvider("FLOODGATE", true, null) {
                    @Override public boolean ready() { return true; }
                    @Override public Boolean isBedrock(UUID id) {
                        if (id.equals(BEDROCK_ID)) return true;
                        if (id.equals(UNKNOWN_ID)) return null;
                        return false;
                    }
                }, new FakeProvider("GEYSER", false, false));
        service.classify(JAVA_ID);
        service.classify(BEDROCK_ID);
        service.classify(UNKNOWN_ID);
        ClientPlatformService noProvider = service(false, false, false, false);
        noProvider.classify(UUID.randomUUID());

        assertEquals(1, service.count(ClientPlatformService.State.JAVA));
        assertEquals(1, service.count(ClientPlatformService.State.BEDROCK));
        assertEquals(1, service.count(ClientPlatformService.State.UNKNOWN));
        assertEquals(1, noProvider.count(ClientPlatformService.State.NO_PROVIDER));
    }

    private static final UUID JAVA_ID = UUID.randomUUID();
    private static final UUID BEDROCK_ID = UUID.randomUUID();
    private static final UUID UNKNOWN_ID = UUID.randomUUID();

    private static ClientPlatformService service(boolean floodgateInstalled, Boolean floodgateAnswer,
                                                  boolean geyserInstalled, Boolean geyserAnswer) {
        return new ClientPlatformService(new FakeProvider("FLOODGATE", floodgateInstalled, floodgateAnswer),
                new FakeProvider("GEYSER", geyserInstalled, geyserAnswer));
    }

    private static class FakeProvider implements ClientPlatformService.Provider {
        private final String name;
        private final boolean installed;
        private volatile Boolean answer;

        private FakeProvider(String name, boolean installed, Boolean answer) {
            this.name = name;
            this.installed = installed;
            this.answer = answer;
        }

        @Override public String name() { return name; }
        @Override public boolean installed() { return installed; }
        @Override public boolean ready() { return installed && answer != null; }
        @Override public Boolean isBedrock(UUID playerId) { return answer; }
    }
}
