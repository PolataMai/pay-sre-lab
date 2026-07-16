package io.paysre.control.incident;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

public final class UlidIncidentIdGenerator implements IncidentIdGenerator {

    private static final char[] CROCKFORD =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final BigInteger MASK = BigInteger.valueOf(31);

    private final Clock clock;
    private final SecureRandom random;

    public UlidIncidentIdGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    UlidIncidentIdGenerator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String nextIncidentId() {
        byte[] value = new byte[16];
        long timestamp = clock.millis();
        for (int index = 5; index >= 0; index--) {
            value[index] = (byte) timestamp;
            timestamp >>>= 8;
        }
        byte[] entropy = new byte[10];
        random.nextBytes(entropy);
        System.arraycopy(entropy, 0, value, 6, entropy.length);
        return "INC-" + encode(value);
    }

    private String encode(byte[] bytes) {
        var number = new BigInteger(1, bytes);
        char[] encoded = new char[26];
        for (int index = encoded.length - 1; index >= 0; index--) {
            encoded[index] = CROCKFORD[number.and(MASK).intValue()];
            number = number.shiftRight(5);
        }
        return new String(encoded);
    }
}
