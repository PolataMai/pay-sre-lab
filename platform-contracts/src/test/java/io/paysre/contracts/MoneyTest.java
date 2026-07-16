package io.paysre.contracts;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.01"), Currency.getInstance("CNY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must not be negative");
    }

    @Test
    void rejectsScaleBeyondCurrencyFractionDigits() {
        assertThatThrownBy(() -> new Money(new BigDecimal("1.001"), Currency.getInstance("CNY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount scale exceeds currency fraction digits");
    }
}
