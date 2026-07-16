package io.paysre.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.payment.application.PaymentApplicationService;
import io.paysre.payment.application.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:payment-app;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false",
            "paysre.channel.base-url=http://channel.test"
        })
class PaymentServiceApplicationTest {

    @Autowired
    private PaymentApplicationService applicationService;

    @Autowired
    private PaymentRepository repository;

    @Test
    void wiresTheApplicationWithoutExternalServices() {
        assertThat(applicationService).isNotNull();
        assertThat(repository).isNotNull();
    }
}
