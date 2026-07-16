package io.paysre.payment.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import io.paysre.payment.application.PaymentRepository;
import io.paysre.payment.application.UnknownPaymentSummary;
import io.paysre.payment.domain.PaymentEvent;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private static final String ORDER_COLUMNS = """
            payment_id, order_id, merchant_id, amount, currency, status,
            selected_channel, route_version, version, created_at, updated_at
            """;
    private static final String POSTGRES_IDEMPOTENT_INSERT = """
            insert into payment_order (
                payment_id, order_id, merchant_id, amount, currency, status,
                selected_channel, route_version, idempotency_key, version,
                created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (merchant_id, idempotency_key) do nothing
            """;
    private static final String H2_IDEMPOTENT_INSERT = """
            merge into payment_order as target
            using (values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)) as source(
                payment_id, order_id, merchant_id, amount, currency, status,
                selected_channel, route_version, idempotency_key, version,
                created_at, updated_at)
               on target.merchant_id = source.merchant_id
              and target.idempotency_key = source.idempotency_key
            when not matched then
                insert (
                    payment_id, order_id, merchant_id, amount, currency, status,
                    selected_channel, route_version, idempotency_key, version,
                    created_at, updated_at)
                values (
                    source.payment_id, source.order_id, source.merchant_id,
                    source.amount, source.currency, source.status,
                    source.selected_channel, source.route_version,
                    source.idempotency_key, source.version,
                    source.created_at, source.updated_at)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String idempotentInsertSql;

    public JdbcPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.idempotentInsertSql = jdbc.execute((ConnectionCallback<String>) connection ->
                "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                        ? H2_IDEMPOTENT_INSERT
                        : POSTGRES_IDEMPOTENT_INSERT);
    }

    @Override
    public Optional<PaymentOrder> findByMerchantAndIdempotencyKey(
            String merchantId, String idempotencyKey) {
        return findOne(
                "select " + ORDER_COLUMNS
                        + " from payment_order where merchant_id = ? and idempotency_key = ?",
                merchantId,
                idempotencyKey);
    }

    @Override
    public Optional<PaymentOrder> findByPaymentId(String paymentId) {
        return findOne(
                "select " + ORDER_COLUMNS + " from payment_order where payment_id = ?",
                paymentId);
    }

    @Override
    public List<UnknownPaymentSummary> findUnknown(Instant from, Instant to, int size) {
        return jdbc.query("""
                        select payment_id, order_id, amount, currency,
                               selected_channel, status, updated_at
                          from payment_order
                         where status = 'UNKNOWN'
                           and updated_at >= ?
                           and updated_at < ?
                         order by updated_at, payment_id
                         limit ?
                        """,
                (resultSet, rowNumber) -> {
                    var currency = Currency.getInstance(
                            resultSet.getString("currency").trim());
                    return new UnknownPaymentSummary(
                            resultSet.getString("payment_id"),
                            resultSet.getString("order_id"),
                            resultSet.getBigDecimal("amount").setScale(
                                    currency.getDefaultFractionDigits(),
                                    RoundingMode.UNNECESSARY),
                            currency.getCurrencyCode(),
                            resultSet.getString("selected_channel"),
                            PaymentStatus.valueOf(resultSet.getString("status")),
                            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
                },
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(to, ZoneOffset.UTC),
                size);
    }

    @Override
    @Transactional
    public PaymentOrder save(PaymentOrder payment, String idempotencyKey) {
        if (!payment.persisted()) {
            return insert(payment, idempotencyKey);
        }
        return update(payment);
    }

    private PaymentOrder insert(PaymentOrder payment, String idempotencyKey) {
        int inserted = jdbc.update(idempotentInsertSql,
                payment.paymentId(),
                payment.orderId(),
                payment.merchantId(),
                payment.money().amount(),
                payment.money().currency().getCurrencyCode(),
                payment.status().name(),
                payment.channel(),
                payment.routeVersion(),
                idempotencyKey,
                0L,
                OffsetDateTime.ofInstant(payment.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(payment.updatedAt(), ZoneOffset.UTC));
        if (inserted == 0) {
            return findByMerchantAndIdempotencyKey(payment.merchantId(), idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotent payment exists but cannot be loaded"));
        }
        appendEvents(payment.paymentId(), payment.unpersistedEvents());
        payment.markPersisted(0L);
        return payment;
    }

    private PaymentOrder update(PaymentOrder payment) {
        long nextVersion = payment.version() + 1;
        int updated = jdbc.update("""
                        update payment_order
                           set status = ?, selected_channel = ?, route_version = ?,
                               version = ?, updated_at = ?
                         where payment_id = ? and version = ?
                        """,
                payment.status().name(),
                payment.channel(),
                payment.routeVersion(),
                nextVersion,
                OffsetDateTime.ofInstant(payment.updatedAt(), ZoneOffset.UTC),
                payment.paymentId(),
                payment.version());
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "payment version changed: " + payment.paymentId());
        }
        appendEvents(payment.paymentId(), payment.unpersistedEvents());
        payment.markPersisted(nextVersion);
        return payment;
    }

    private Optional<PaymentOrder> findOne(String sql, Object... arguments) {
        var rows = jdbc.query(sql, this::mapOrderRow, arguments);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.get(0);
        var events = loadEvents(row.paymentId());
        return Optional.of(PaymentOrder.restore(
                row.paymentId(),
                row.orderId(),
                row.merchantId(),
                row.money(),
                row.status(),
                row.channel(),
                row.routeVersion(),
                row.version(),
                row.createdAt().toInstant(),
                row.updatedAt().toInstant(),
                events));
    }

    private OrderRow mapOrderRow(ResultSet resultSet, int rowNumber) throws SQLException {
        var currency = Currency.getInstance(resultSet.getString("currency").trim());
        return new OrderRow(
                resultSet.getString("payment_id"),
                resultSet.getString("order_id"),
                resultSet.getString("merchant_id"),
                new Money(
                        resultSet.getBigDecimal("amount").setScale(
                                currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY),
                        currency),
                PaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("selected_channel"),
                resultSet.getString("route_version"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private List<PaymentEvent> loadEvents(String paymentId) {
        return jdbc.query("""
                        select event_time, source, details
                          from payment_event
                         where payment_id = ?
                         order by sequence_id
                        """,
                (resultSet, rowNumber) -> {
                    JsonNode details = parseDetails(resultSet.getString("details"));
                    return new PaymentEvent(
                            resultSet.getObject("event_time", OffsetDateTime.class).toInstant(),
                            statusOrNull(details.path("fromStatus")),
                            PaymentStatus.valueOf(details.path("toStatus").asText()),
                            resultSet.getString("source"),
                            details.path("reasonCode").asText());
                },
                paymentId);
    }

    private void appendEvents(String paymentId, List<PaymentEvent> events) {
        for (PaymentEvent event : events) {
            jdbc.update("""
                            insert into payment_event (
                                payment_id, event_type, event_time, source, trace_id, details)
                            values (?, ?, ?, ?, ?, cast(? as jsonb))
                            """,
                    paymentId,
                    event.fromStatus() == null ? "PAYMENT_CREATED" : "PAYMENT_STATUS_CHANGED",
                    OffsetDateTime.ofInstant(event.eventTime(), ZoneOffset.UTC),
                    event.source(),
                    null,
                    serializeDetails(event));
        }
    }

    private String serializeDetails(PaymentEvent event) {
        var details = objectMapper.createObjectNode();
        if (event.fromStatus() == null) {
            details.putNull("fromStatus");
        } else {
            details.put("fromStatus", event.fromStatus().name());
        }
        details.put("toStatus", event.toStatus().name());
        details.put("reasonCode", event.reasonCode());
        return details.toString();
    }

    private JsonNode parseDetails(String value) {
        try {
            JsonNode details = objectMapper.readTree(value);
            return details.isTextual() ? objectMapper.readTree(details.asText()) : details;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid payment event details", exception);
        }
    }

    private PaymentStatus statusOrNull(JsonNode value) {
        return value.isNull() || value.isMissingNode()
                ? null
                : PaymentStatus.valueOf(value.asText());
    }

    private record OrderRow(
            String paymentId,
            String orderId,
            String merchantId,
            Money money,
            PaymentStatus status,
            String channel,
            String routeVersion,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
