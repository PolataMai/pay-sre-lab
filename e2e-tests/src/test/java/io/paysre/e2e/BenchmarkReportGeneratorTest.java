package io.paysre.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.control.investigation.RootCausePolicyCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regenerates docs/superpowers/reports/benchmark-baseline.md whenever
 * the fault catalog or the root cause policy registry changes. The
 * report is checked in so reviewers can read the catalogue at a
 * glance without running the lab.
 */
class BenchmarkReportGeneratorTest {

    @Test
    void regeneratesTheBaselineReport() throws IOException {
        var catalog = RootCausePolicyCatalog.defaults();
        var generator = new BenchmarkReportGenerator(catalog);
        for (var resource : List.of(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml",
                "/fault-scenarios/channel-timeout-but-failed-v1.yaml",
                "/fault-scenarios/channel-decline-spike-v1.yaml",
                "/fault-scenarios/channel-code-mapping-error-v1.yaml")) {
            generator.recordScenario(ScenarioGroundTruth.load(resource));
        }

        var reportPath = Path.of("..", "docs", "superpowers", "reports",
                "benchmark-baseline.md").toAbsolutePath();
        Files.createDirectories(reportPath.getParent());
        generator.writeTo(reportPath);

        var rendered = Files.readString(reportPath);
        assertThat(rendered)
                .contains("PaySRE Benchmark")
                .contains("channel-timeout-but-success-v1")
                .contains("channel-timeout-but-failed-v1")
                .contains("channel-decline-spike-v1")
                .contains("channel-code-mapping-error-v1")
                .contains("CHANNEL_TIMEOUT_RESPONSE_LOST")
                .contains("CHANNEL_DECLINE_SPIKE")
                .contains("CHANNEL_CODE_MAPPING_ERROR")
                .contains("query-and-sync-unknown-payments");
    }
}