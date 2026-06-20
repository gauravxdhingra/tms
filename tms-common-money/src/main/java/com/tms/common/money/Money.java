package com.tms.common.money;

import javax.money.CurrencyUnit;
import javax.money.Monetary;
import javax.money.MonetaryAmount;
import org.javamoney.moneta.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Factory and utility methods for creating MonetaryAmount instances.
 *
 * Rule: amounts always arrive as strings from external systems (API, Avro, DB).
 * They are parsed here and never cast to double/float anywhere in the codebase.
 */
public final class TmsMoney {

    private TmsMoney() {}

    public static MonetaryAmount of(String amount, String currencyCode) {
        CurrencyUnit currency = Monetary.getCurrency(currencyCode);
        return Money.of(new BigDecimal(amount), currency);
    }

    public static MonetaryAmount of(BigDecimal amount, String currencyCode) {
        return Money.of(amount, Monetary.getCurrency(currencyCode));
    }

    public static MonetaryAmount zero(String currencyCode) {
        return Money.of(BigDecimal.ZERO, Monetary.getCurrency(currencyCode));
    }

    /** Serialises to a plain decimal string for API responses and Avro events. */
    public static String toAmountString(MonetaryAmount amount) {
        return amount.getNumber().numberValue(BigDecimal.class)
                     .toPlainString();
    }

    /** Rounds to the standard number of fraction digits for the currency. */
    public static MonetaryAmount round(MonetaryAmount amount) {
        int fractionDigits = amount.getCurrency().getDefaultFractionDigits();
        BigDecimal rounded = amount.getNumber()
                                   .numberValue(BigDecimal.class)
                                   .setScale(fractionDigits, RoundingMode.HALF_UP);
        return Money.of(rounded, amount.getCurrency());
    }
}
