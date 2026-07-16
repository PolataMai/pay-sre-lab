package io.paysre.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Maps stable payment inputs to a reproducible sample so failure scenarios can be replayed.
 */
public final class FaultDecider {

    private static final BigDecimal SAMPLE_SPACE = BigDecimal.valueOf(1L << 32);

    public boolean applies(String paymentId, FaultRule rule) {
        var input = paymentId + ":" + rule.channel() + ":" + rule.randomSeed();
        byte[] digest = sha256(input);
        long positive = Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt());
        BigDecimal sample = BigDecimal.valueOf(positive)
                .divide(SAMPLE_SPACE, 12, RoundingMode.HALF_UP);
        return sample.compareTo(rule.probability()) < 0;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
