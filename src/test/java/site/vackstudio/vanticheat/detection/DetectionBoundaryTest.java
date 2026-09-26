package site.vackstudio.vanticheat.detection;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DetectionBoundaryTest {
    @Test
    void detectionPackageDoesNotImportServerImplementationClasses() throws Exception {
        Path source = Path.of("src/main/java/site/vackstudio/vanticheat/detection");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                assertFalse(content.contains("org.bukkit"), file.toString());
                assertFalse(content.contains("io.papermc.paper"), file.toString());
                assertFalse(content.contains("PaperFoliaScheduler"), file.toString());
            }
        }
    }
}
