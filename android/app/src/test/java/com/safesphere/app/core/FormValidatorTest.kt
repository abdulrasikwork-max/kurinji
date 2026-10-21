package com.safesphere.app.core

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.core.validation.FormValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormValidatorTest {

    @Test
    fun `required rejects blank values`() {
        assertEquals(FieldError.REQUIRED, FormValidator.validateRequired(""))
        assertEquals(FieldError.REQUIRED, FormValidator.validateRequired("   "))
        assertNull(FormValidator.validateRequired("x"))
    }

    @Test
    fun `email must be present and well formed`() {
        assertEquals(FieldError.REQUIRED, FormValidator.validateEmail(""))
        assertEquals(FieldError.INVALID_EMAIL, FormValidator.validateEmail("plainaddress"))
        assertEquals(FieldError.INVALID_EMAIL, FormValidator.validateEmail("missing@tld"))
        assertEquals(FieldError.INVALID_EMAIL, FormValidator.validateEmail("two@@example.com"))
        assertNull(FormValidator.validateEmail("student@example.com"))
        assertNull(FormValidator.validateEmail("  student.name+tag@college.ac.in  "))
    }

    @Test
    fun `full name must have at least two characters after trimming`() {
        assertEquals(FieldError.REQUIRED, FormValidator.validateFullName("  "))
        assertEquals(FieldError.NAME_TOO_SHORT, FormValidator.validateFullName(" A "))
        assertNull(FormValidator.validateFullName("Asha"))
    }

    @Test
    fun `new password needs minimum length and is never trimmed`() {
        assertEquals(FieldError.REQUIRED, FormValidator.validateNewPassword(""))
        assertEquals(FieldError.PASSWORD_TOO_SHORT, FormValidator.validateNewPassword("1234567"))
        assertNull(FormValidator.validateNewPassword("12345678"))
        // Eight spaces are eight characters: spaces are valid password characters.
        assertNull(FormValidator.validateNewPassword("        "))
    }

    @Test
    fun `confirmation must be present and identical`() {
        assertEquals(FieldError.REQUIRED, FormValidator.validateConfirmPassword("abcdefgh", ""))
        assertEquals(
            FieldError.PASSWORDS_DO_NOT_MATCH,
            FormValidator.validateConfirmPassword("abcdefgh", "abcdefgH")
        )
        assertNull(FormValidator.validateConfirmPassword("abcdefgh", "abcdefgh"))
    }
}
