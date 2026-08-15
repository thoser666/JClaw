package biz.brumm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verhindert Regressionen im Dockerfile, die den CI-Build
 * (&quot;Build and Push Docker Image&quot;) brechen würden.
 */
class DockerfileTest {

    private static final String DOCKERFILE = "Dockerfile";

    private static List<String> dockerfileLines() throws IOException {
        Path path = Path.of(DOCKERFILE);
        assertThat(path).exists();
        return Files.readAllLines(path);
    }

    @Test
    void artifactCopyIsVersionAgnostic() throws IOException {
        assertThat(dockerfileLines())
                .anyMatch(line -> line.contains("COPY --from=build") && line.contains("*.jar"))
                .describedAs("Das gebaute JAR muss versionsagnostisch (Wildcard) kopiert werden");
    }

    @Test
    void artifactCopyDoesNotHardcodeAVersion() throws IOException {
        assertThat(dockerfileLines())
                .noneMatch(line -> line.contains("COPY --from=build") && line.matches(".*jclaw-[0-9].*\\.jar.*"))
                .describedAs("Der Dockerfile darf keine konkrete Versionsnummer hartkodieren");
    }

    @Test
    void buildStageUsesJdk25AndRuntimeStageJre25() throws IOException {
        List<String> lines = dockerfileLines();
        assertThat(lines).anyMatch(line -> line.contains("eclipse-temurin:25-jdk-alpine"));
        assertThat(lines).anyMatch(line -> line.contains("eclipse-temurin:25-jre-alpine"));
    }

    @Test
    void exposesWebPortAndRunsFatJar() throws IOException {
        List<String> lines = dockerfileLines();
        assertThat(lines).anyMatch(line -> line.contains("EXPOSE 8080"));
        assertThat(lines).anyMatch(line -> line.contains("ENTRYPOINT") && line.contains("app.jar"));
    }
}
