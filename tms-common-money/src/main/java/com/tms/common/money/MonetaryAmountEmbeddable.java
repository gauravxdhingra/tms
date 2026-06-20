package com.tms.common.money;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import javax.money.MonetaryAmount;
import java.math.BigDecimal;

/**
 * Embeddable that stores amount + currency as two DB columns.
 * Usage in entity: @Embedded MonetaryAmountEmbeddable amount;
 * Columns: amount_value NUMERIC(38,10), amount_currency CHAR(3)
 */
@Embeddable
public class MonetaryAmountEmbeddable {

    @Column(name = "amount_value", precision = 38, scale = 10, nullable = false)
    private BigDecimal value;

    @Column(name = "amount_currency", length = 3, nullable = false)
    private String currency;

    protected MonetaryAmountEmbeddable() {}

    public MonetaryAmountEmbeddable(MonetaryAmount amount) {
        this.value    = amount.getNumber().numberValue(BigDecimal.class);
        this.currency = amount.getCurrency().getCurrencyCode();
    }

    public MonetaryAmount toMonetaryAmount() {
        return TmsMoney.of(value, currency);
    }

    public BigDecimal getValue()    { return value; }
    public String getCurrency()     { return currency; }
}
