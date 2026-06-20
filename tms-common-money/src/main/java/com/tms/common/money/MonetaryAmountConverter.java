package com.tms.common.money;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import javax.money.MonetaryAmount;

/**
 * JPA converter: stores MonetaryAmount as two columns via @Column on
 * separate amount/currency fields. This converter handles AMOUNT column only.
 * Currency is stored on a sibling @Column. Use @Embedded + @Embeddable pattern
 * in the entity rather than this converter directly.
 */
@Converter
public class MonetaryAmountConverter implements AttributeConverter<MonetaryAmount, String> {

    @Override
    public String convertToDatabaseColumn(MonetaryAmount attribute) {
        if (attribute == null) return null;
        return TmsMoney.toAmountString(attribute);
    }

    @Override
    public MonetaryAmount convertToEntityAttribute(String dbData) {
        return null; // currency context required — use MonetaryAmountEmbeddable instead
    }
}
