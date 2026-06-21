package com.tms.kernel.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates IBAN format: 2-letter country code + 2 check digits + up to 30 alphanumeric chars.
 * Full mod-97 checksum verification included.
 */
public class IbanValidator implements ConstraintValidator<ValidIban, String> {

    private boolean required;

    @Override
    public void initialize(ValidIban annotation) {
        this.required = annotation.required();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return !required;
        }

        String iban = value.replaceAll("\\s", "").toUpperCase();

        if (!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}")) {
            return false;
        }

        return mod97(iban) == 1;
    }

    private int mod97(String iban) {
        // Move first 4 chars to end, replace letters with digits, compute mod 97
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isLetter(c)) {
                numeric.append(c - 'A' + 10);
            } else {
                numeric.append(c);
            }
        }

        // Process in chunks to avoid BigInteger overhead on hot path
        int remainder = 0;
        for (char c : numeric.toString().toCharArray()) {
            remainder = (remainder * 10 + (c - '0')) % 97;
        }
        return remainder;
    }
}
