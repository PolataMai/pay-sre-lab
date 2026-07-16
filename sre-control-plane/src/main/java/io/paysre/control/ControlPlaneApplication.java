package io.paysre.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.evidence.EvidenceRepository;
import io.paysre.control.incident.IncidentApplicationService;
import io.paysre.control.incident.IncidentIdGenerator;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.incident.UlidIncidentIdGenerator;
import io.paysre.control.investigation.ConclusionValidator;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import io.paysre.control.investigation.InvestigationModel;
import io.paysre.control.investigation.InvestigationOrchestrator;
import io.paysre.control.investigation.StubInvestigationModel;
import io.paysre.control.observability.HttpLokiReadClient;
import io.paysre.control.observability.HttpPrometheusReadClient;
import io.paysre.control.observability.HttpTempoReadClient;
import io.paysre.control.observability.LokiReadClient;
import io.paysre.control.observability.PrometheusReadClient;
import io.paysre.control.observability.TempoReadClient;
import io.paysre.control.tools.CalculateIncidentImpactTool;
import io.paysre.control.tools.ChannelReadClient;
import io.paysre.control.tools.GetDistributedTraceTool;
import io.paysre.control.tools.GetPaymentTimelineTool;
import io.paysre.control.tools.HttpChannelReadClient;
import io.paysre.control.tools.HttpPaymentReadClient;
import io.paysre.control.tools.PaymentReadClient;
import io.paysre.control.tools.QueryServiceMetricsTool;
import io.paysre.control.tools.QueryChannelFinalStateTool;
import io.paysre.control.tools.SearchStructuredLogsTool;
import io.paysre.control.tools.ToolAuditRepository;
import io.paysre.control.tools.ToolGateway;
import io.paysre.control.tools.ToolHandler;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@SpringBootApplication(proxyBeanMethods = false)
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }

    @Bean
    Clock controlPlaneClock() {
        return Clock.systemUTC();
    }

    @Bean
    IncidentIdGenerator incidentIdGenerator(Clock clock) {
        return new UlidIncidentIdGenerator(clock);
    }

    @Bean
    IncidentApplicationService incidentApplicationService(
            IncidentRepository repository, IncidentIdGenerator ids) {
        return new IncidentApplicationService(repository, ids);
    }

    @Bean
    ObjectMapper controlObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    PaymentReadClient paymentReadClient(
            @Value("${paysre.payment.base-url}") String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        return new HttpPaymentReadClient(
                readOnlyRestClient(restClientBuilder, baseUrl), objectMapper);
    }

    @Bean
    ChannelReadClient channelReadClient(
            @Value("${paysre.channel.base-url}") String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        return new HttpChannelReadClient(
                readOnlyRestClient(restClientBuilder, baseUrl), objectMapper);
    }

    @Bean
    PrometheusReadClient prometheusReadClient(
            @Value("${paysre.observability.prometheus.base-url}") String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            Clock clock) {
        return new HttpPrometheusReadClient(
                readOnlyRestClient(restClientBuilder, baseUrl), objectMapper, clock);
    }

    @Bean
    LokiReadClient lokiReadClient(
            @Value("${paysre.observability.loki.base-url}") String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            Clock clock) {
        return new HttpLokiReadClient(
                readOnlyRestClient(restClientBuilder, baseUrl), objectMapper, clock);
    }

    @Bean
    TempoReadClient tempoReadClient(
            @Value("${paysre.observability.tempo.base-url}") String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            Clock clock) {
        return new HttpTempoReadClient(
                readOnlyRestClient(restClientBuilder, baseUrl), objectMapper, clock);
    }

    @Bean
    GetPaymentTimelineTool getPaymentTimelineTool(PaymentReadClient client) {
        return new GetPaymentTimelineTool(client);
    }

    @Bean
    QueryChannelFinalStateTool queryChannelFinalStateTool(ChannelReadClient client) {
        return new QueryChannelFinalStateTool(client);
    }

    @Bean
    CalculateIncidentImpactTool calculateIncidentImpactTool(PaymentReadClient client) {
        return new CalculateIncidentImpactTool(client);
    }

    @Bean
    QueryServiceMetricsTool queryServiceMetricsTool(PrometheusReadClient client) {
        return new QueryServiceMetricsTool(client);
    }

    @Bean
    SearchStructuredLogsTool searchStructuredLogsTool(LokiReadClient client) {
        return new SearchStructuredLogsTool(client);
    }

    @Bean
    GetDistributedTraceTool getDistributedTraceTool(TempoReadClient client) {
        return new GetDistributedTraceTool(client);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService toolExecutor() {
        var sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                2,
                4,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                runnable -> {
                    var thread = new Thread(
                            runnable, "paysre-tool-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ToolGateway toolGateway(
            List<ToolHandler<?, ?>> handlers,
            ObjectMapper objectMapper,
            @Qualifier("toolExecutor") ExecutorService toolExecutor,
            EvidenceRepository evidenceRepository,
            ToolAuditRepository auditRepository,
            Clock clock) {
        return new ToolGateway(
                handlers,
                objectMapper,
                toolExecutor,
                evidenceRepository,
                auditRepository,
                clock,
                () -> "EVD-" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService modelExecutor() {
        var sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                1,
                2,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                runnable -> {
                    var thread = new Thread(
                            runnable, "paysre-model-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    InvestigationModel investigationModel(ObjectMapper objectMapper) {
        return new StubInvestigationModel(objectMapper);
    }

    @Bean
    ConclusionValidator conclusionValidator(EvidenceRepository evidenceRepository) {
        return new ConclusionValidator(evidenceRepository);
    }

    @Bean
    InvestigationOrchestrator investigationOrchestrator(
            IncidentRepository incidentRepository,
            EvidenceRepository evidenceRepository,
            InvestigationConclusionRepository conclusionRepository,
            InvestigationModel investigationModel,
            ToolGateway toolGateway,
            ConclusionValidator validator,
            Clock clock,
            @Qualifier("modelExecutor") ExecutorService modelExecutor,
            @Value("${paysre.investigation.model-timeout}") Duration modelTimeout) {
        return new InvestigationOrchestrator(
                incidentRepository,
                evidenceRepository,
                conclusionRepository,
                investigationModel,
                toolGateway,
                validator,
                clock,
                modelExecutor,
                modelTimeout);
    }

    private RestClient readOnlyRestClient(RestClient.Builder builder, String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
