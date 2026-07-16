package io.paysre.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.contracts.Money;
import io.paysre.payment.application.PaymentRepository;
import io.paysre.payment.domain.PaymentOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "paysre.channel.base-url=http://channel.test")
class PostgreSqlMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("paysre")
                    .withUsername("paysre")
                    .withPassword("paysre");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentRepository repository;

    @Test
    void appliesThePaymentSchemaIncludingThePartialOutboxIndex() {
        var tables = jdbc.queryForObject("""
                select count(*)
                  from information_schema.tables
                 where table_schema = 'public'
                   and table_name in (
                       'payment_order', 'payment_attempt', 'payment_event', 'outbox_event')
                """, Integer.class);
        var indexDefinition = jdbc.queryForObject("""
                select indexdef
                  from pg_indexes
                 where schemaname = 'public'
                   and indexname = 'idx_outbox_unpublished'
                """, String.class);

        assertThat(tables).isEqualTo(4);
        assertThat(indexDefinition).containsIgnoringCase("where (published_at is null)");

        var first = processingPayment("P10001");
        repository.save(first, "IDEMPOTENCY-1");
        var duplicate = repository.save(processingPayment("P10002"), "IDEMPOTENCY-1");
        assertThat(duplicate.paymentId()).isEqualTo("P10001");
    }

    private PaymentOrder processingPayment(String paymentId) {
        var payment = PaymentOrder.create(
                paymentId,
                "O10001",
                "M001",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")),
                Instant.EPOCH);
        payment.start("CHANNEL_A", "route-v1", Instant.EPOCH);
        return payment;
    }
}
