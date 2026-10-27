package com.safesphere.backend.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Phase 17B: the one number rule (the Android PhonePolicyTest uses the same cases). */
class PhoneNumbersTest {

    @Test
    void commonWaysOfTypingAnIndianMobileNumber_becomeE164() {
        assertThat(PhoneNumbers.normalize("9876543210")).isEqualTo("+919876543210");
        assertThat(PhoneNumbers.normalize("+91 98765 43210")).isEqualTo("+919876543210");
        assertThat(PhoneNumbers.normalize("+91-98765-43210")).isEqualTo("+919876543210");
        assertThat(PhoneNumbers.normalize("919876543210")).isEqualTo("+919876543210");
        assertThat(PhoneNumbers.normalize("09876543210")).isEqualTo("+919876543210");
        assertThat(PhoneNumbers.normalize(" (987) 654-3210 ")).isEqualTo("+919876543210");
        assertThat(PhoneNumbers.normalize("6000000000")).isEqualTo("+916000000000");
    }

    @Test
    void anythingElseIsRefused() {
        assertThat(PhoneNumbers.normalize(null)).isNull();
        assertThat(PhoneNumbers.normalize("")).isNull();
        assertThat(PhoneNumbers.normalize("12345")).isNull();
        assertThat(PhoneNumbers.normalize("5876543210")).isNull(); // Indian mobiles start with 6-9
        assertThat(PhoneNumbers.normalize("98765432101")).isNull();
        assertThat(PhoneNumbers.normalize("+1 415 555 0100")).isNull(); // other countries: not yet
        assertThat(PhoneNumbers.normalize("98765abc10")).isNull();
        assertThat(PhoneNumbers.normalize("+91987654321")).isNull();
    }

    @Test
    void theHintShowsOnlyTheLastFourDigits() {
        assertThat(PhoneNumbers.hint("+919876543210")).isEqualTo("••••••3210");
        assertThat(PhoneNumbers.hint("garbage")).isNull();
        assertThat(PhoneNumbers.national("+919876543210")).isEqualTo("9876543210");
    }
}
