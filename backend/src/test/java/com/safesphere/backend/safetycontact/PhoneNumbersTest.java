package com.safesphere.backend.safetycontact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.safetycontact.service.PhoneNumbers;
import org.junit.jupiter.api.Test;

/** Plain unit test: how typed phone numbers become one canonical form. */
class PhoneNumbersTest {

    @Test
    void removesSeparatorsAndKeepsLeadingPlus() {
        assertEquals("+919876543210", PhoneNumbers.normalize("+91 98765-43210"));
        assertEquals("02212345678", PhoneNumbers.normalize("(022) 1234 5678"));
        assertEquals("9876543210", PhoneNumbers.normalize("  98765.43210 "));
    }

    @Test
    void differentFormattingsOfTheSameNumberBecomeEqual() {
        assertEquals(PhoneNumbers.normalize("+91 98765 43210"), PhoneNumbers.normalize("+91-98765-43210"));
    }

    @Test
    void rejectsLettersTooShortAndTooLongNumbers() {
        assertThrows(InvalidRequestException.class, () -> PhoneNumbers.normalize("call-me-maybe"));
        assertThrows(InvalidRequestException.class, () -> PhoneNumbers.normalize("12345"));
        assertThrows(InvalidRequestException.class, () -> PhoneNumbers.normalize("+1234567890123456"));
        assertThrows(InvalidRequestException.class, () -> PhoneNumbers.normalize("98765+43210"));
    }
}
