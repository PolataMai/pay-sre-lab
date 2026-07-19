package io.paysre.payment.domain;

import io.paysre.contracts.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Payment aggregate whose transition history is the business source of truth.
 */
public final class PaymentOrder {

    private final String paymentId;
    private final String orderId;
    private final String merchantId;
    private final Money money;
    private final List<PaymentEvent> events = new ArrayList<>();
    private PaymentStatus status;
    private String channel;
    private String routeVersion;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;
    private boolean persisted;
    private int persistedEventCount;

    private PaymentOrder(
            String paymentId,
            String orderId,
            String merchantId,
            Money money,
            Instant createdAt) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.money = Objects.requireNonNull(money, "money");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
        this.status = PaymentStatus.INIT;
    }

    public static PaymentOrder create(
            String paymentId, String orderId, String merchantId, Money money, Instant now) {
        var payment = new PaymentOrder(paymentId, orderId, merchantId, money, now);
        payment.events.add(new PaymentEvent(
                now, null, PaymentStatus.INIT, "PAYMENT_SERVICE", "CREATED"));
        return payment;
    }

    public static PaymentOrder restore(
            String paymentId,
            String orderId,
            String merchantId,
            Money money,
            PaymentStatus status,
            String channel,
            String routeVersion,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<PaymentEvent> history) {
        var payment = new PaymentOrder(paymentId, orderId, merchantId, money, createdAt);
        payment.status = Objects.requireNonNull(status, "status");
        payment.channel = channel;
        payment.routeVersion = routeVersion;
        payment.version = version;
        payment.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        payment.events.addAll(List.copyOf(history));
        payment.persisted = true;
        payment.persistedEventCount = history.size();
        return payment;
    }

    public void start(String channel, String routeVersion, Instant now) {
        require(PaymentStatus.INIT);
        this.channel = Objects.requireNonNull(channel, "channel");
        this.routeVersion = Objects.requireNonNull(routeVersion, "routeVersion");
        transition(PaymentStatus.PROCESSING, "PAYMENT_SERVICE", "CHANNEL_SELECTED", now);
    }

    public void markSuccess(String channelCode, Instant now) {
        require(PaymentStatus.PROCESSING);
        transition(PaymentStatus.SUCCESS, "CHANNEL_RESPONSE", channelCode, now);
    }

    public void markFailed(String channelCode, Instant now) {
        require(PaymentStatus.PROCESSING);
        transition(PaymentStatus.FAILED, "CHANNEL_RESPONSE", channelCode, now);
    }

    public void markUnknown(String reasonCode, Instant now) {
        require(PaymentStatus.PROCESSING);
        transition(PaymentStatus.UNKNOWN, "CHANNEL_CALL", reasonCode, now);
    }

    public void confirmUnknownSuccess(String channelCode, Instant now) {
        require(PaymentStatus.UNKNOWN);
        transition(PaymentStatus.SUCCESS, "CHANNEL_QUERY", channelCode, now);
    }

    public void confirmUnknownFailure(String channelCode, Instant now) {
        require(PaymentStatus.UNKNOWN);
        transition(PaymentStatus.FAILED, "CHANNEL_QUERY", channelCode, now);
    }

    /**
     * Records a sync attempt whose channel return code was not in the
     * configured mapping. The payment stays UNKNOWN (no terminal state
     * is guessed) but the event is appended so audit / control plane
     * can surface the misconfiguration.
     */
    public void recordUnmappedCode(String reasonCode, Instant now) {
        require(PaymentStatus.UNKNOWN);
        events.add(new PaymentEvent(now, status, status, "CHANNEL_QUERY", reasonCode));
        updatedAt = now;
    }

    private void require(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + status);
        }
    }

    private void transition(PaymentStatus target, String source, String reason, Instant now) {
        var previous = status;
        status = target;
        updatedAt = now;
        events.add(new PaymentEvent(now, previous, target, source, reason));
    }

    public List<PaymentEvent> unpersistedEvents() {
        return List.copyOf(events.subList(persistedEventCount, events.size()));
    }

    public void markPersisted(long newVersion) {
        version = newVersion;
        persisted = true;
        persistedEventCount = events.size();
    }

    public String paymentId() {
        return paymentId;
    }

    public String orderId() {
        return orderId;
    }

    public String merchantId() {
        return merchantId;
    }

    public Money money() {
        return money;
    }

    public PaymentStatus status() {
        return status;
    }

    public String channel() {
        return channel;
    }

    public String routeVersion() {
        return routeVersion;
    }

    public List<PaymentEvent> events() {
        return List.copyOf(events);
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean persisted() {
        return persisted;
    }
}
