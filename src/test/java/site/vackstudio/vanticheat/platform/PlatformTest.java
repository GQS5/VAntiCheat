package site.vackstudio.vanticheat.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformTest {
    @Test
    void platformDetectorDistinguishesPaperFoliaAndUnknown() {
        assertEquals(Platform.PAPER, PlatformDetector.detect(true, false));
        assertEquals(Platform.FOLIA, PlatformDetector.detect(true, true));
        assertEquals(Platform.UNKNOWN, PlatformDetector.detect(false, false));
    }
}
