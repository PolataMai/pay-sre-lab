package io.paysre.payment;

import io.paysre.payment.adapter.http.HttpChannelClient;
import io.paysre.payment.application.ChannelClient;
import io.paysre.payment.application.ChannelReturnCodeMapping;
import io.paysre.payment.application.ChannelRouter;
import io.paysre.payment.application.ChannelStateQuery;
import io.paysre.payment.application.PaymentApplicationService;
import io.paysre.payment.application.UnknownPaymentSyncService;
import io.paysre.payment.application.PaymentIdGenerator;
import io.paysre.payment.application.PaymentRepository;
import io.paysre.payment.application.UnknownPaymentQueryService;
import io.paysre.payment.observability.PaymentMetrics;
import io.paysre.payment.observability.PaymentTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@SpringBootApplication(proxyBeanMethods = false)
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

    @Bean
    Clock paymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentIdGenerator paymentIdGenerator() {
        return () -> "PAY-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Bean
    HttpChannelClient channelClient(
            RestClient.Builder restClientBuilder,
            @Value("${paysre.channel.base-url}") String channelBaseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(1));
        var restClient = restClientBuilder.clone()
                .baseUrl(channelBaseUrl)
                .requestFactory(requestFactory)
                .build();
        return new HttpChannelClient(restClient);
    }

    @Bean
    PaymentMetrics paymentMetrics(MeterRegistry registry) {
        return new PaymentMetrics(registry);
    }

    @Bean
    PaymentTelemetry paymentTelemetry(OpenTelemetry openTelemetry) {
        return new PaymentTelemetry(
                openTelemetry.getTracer("io.paysre.payment-service"));
    }

    @Bean
    UnknownPaymentQueryService unknownPaymentQueryService(PaymentRepository repository) {
        return new UnknownPaymentQueryService(repository);
    }

    @Bean
    UnknownPaymentSyncService unknownPaymentSyncService(
            PaymentRepository repository,
            ChannelStateQuery channelStateQuery,
            Clock clock,
            PaymentMetrics metrics,
            PaymentTelemetry telemetry,
            ChannelReturnCodeMapping returnCodeMapping) {
        return new UnknownPaymentSyncService(
                repository, channelStateQuery, clock, metrics, telemetry,
                returnCodeMapping);
    }

    @Bean
    PaymentApplicationService paymentApplicationService(
            PaymentRepository repository,
            ChannelClient channelClient,
            PaymentIdGenerator ids,
            Clock clock,
            PaymentMetrics metrics,
            PaymentTelemetry telemetry,
            ChannelReturnCodeMapping returnCodeMapping,
            ChannelRouter channelRouter) {
        return new PaymentApplicationService(
                repository, channelClient, ids, clock, metrics, telemetry,
                returnCodeMapping, channelRouter);
    }

    @Bean
    ChannelRouter channelRouter(
            @Value("${paysre.payment.routing.default-channel:CHANNEL_A}") String defaultChannel,
            @Value("${paysre.payment.routing.merchant-overlays:}") String overlays) {
        var map = new java.util.HashMap<String, String>();
        if (overlays != null && !overlays.isBlank()) {
            for (var entry : overlays.split(",")) {
                var trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                var parts = trimmed.split(":", 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    map.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        return new ChannelRouter.Static(defaultChannel, map);
    }

    @Bean
    ChannelReturnCodeMapping channelReturnCodeMapping(
            @Value("${paysre.channel.code-mapping.success-codes:00}") String successCodes,
            @Value("${paysre.channel.code-mapping.failure-codes:51,05,96}") String failureCodes) {
        return new ChannelReturnCodeMapping.Fixed(csv(successCodes), csv(failureCodes));
    }

    private static java.util.Set<String> csv(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
