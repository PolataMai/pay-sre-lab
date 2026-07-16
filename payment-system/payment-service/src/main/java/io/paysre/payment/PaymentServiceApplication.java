package io.paysre.payment;

import io.paysre.payment.adapter.http.HttpChannelClient;
import io.paysre.payment.application.ChannelClient;
import io.paysre.payment.application.PaymentApplicationService;
import io.paysre.payment.application.PaymentIdGenerator;
import io.paysre.payment.application.PaymentRepository;
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
    ChannelClient channelClient(@Value("${paysre.channel.base-url}") String channelBaseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(1));
        var restClient = RestClient.builder()
                .baseUrl(channelBaseUrl)
                .requestFactory(requestFactory)
                .build();
        return new HttpChannelClient(restClient);
    }

    @Bean
    PaymentApplicationService paymentApplicationService(
            PaymentRepository repository,
            ChannelClient channelClient,
            PaymentIdGenerator ids,
            Clock clock) {
        return new PaymentApplicationService(repository, channelClient, ids, clock);
    }
}
