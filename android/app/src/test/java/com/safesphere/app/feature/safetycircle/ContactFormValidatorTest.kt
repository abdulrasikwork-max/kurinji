package com.safesphere.app.feature.safetycircle

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.core.validation.FormValidator
import com.safesphere.app.feature.safetycircle.domain.ContactFormValidator
import com.safesphere.app.feature.safetycircle.domain.Relationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFormValidatorTest {

    private fun validate(
        name: String = "Asha Devi",
        phone: String = "+91 98765 43210",
        email: String = "",
        relationship: Relationship? = Relationship.FAMILY,
        priority: Int? = 2
    ) = ContactFormValidator.validate(name, phone, email, relationship, priority)

    @Test
    fun `a complete valid form has no errors`() {
        assertFalse(validate().hasErrors)
    }

    @Test
    fun `name is required`() {
        assertEquals(FieldError.REQUIRED, validate(name = "   ").name)
    }

    @Test
    fun `phone is required and must look like a phone number`() {
        assertEquals(FieldError.REQUIRED, validate(phone = "").phone)
        assertEquals(FieldError.INVALID_PHONE, validate(phone = "call me").phone)
        assertEquals(FieldError.INVALID_PHONE, validate(phone = "12345").phone)
        assertEquals(FieldError.INVALID_PHONE, validate(phone = "+1234567890123456").phone)
        assertEquals(FieldError.INVALID_PHONE, validate(phone = "98765+43210").phone)
    }

    @Test
    fun `common phone formats are accepted`() {
        assertNull(FormValidator.validatePhone("9876543210"))
        assertNull(FormValidator.validatePhone("+91 98765-43210"))
        assertNull(FormValidator.validatePhone("(022) 1234 5678"))
        assertNull(FormValidator.validatePhone("  +919876543210  "))
    }

    @Test
    fun `email is optional but must be valid when typed`() {
        assertNull(validate(email = "").email)
        assertNull(validate(email = "   ").email)
        assertNull(validate(email = "mum@example.com").email)
        assertEquals(FieldError.INVALID_EMAIL, validate(email = "not-an-email").email)
    }

    @Test
    fun `relationship must be chosen`() {
        assertEquals(FieldError.SELECTION_REQUIRED, validate(relationship = null).relationship)
    }

    @Test
    fun `priority must be between 1 and 5`() {
        assertEquals(FieldError.SELECTION_REQUIRED, validate(priority = null).priority)
        assertEquals(FieldError.SELECTION_REQUIRED, validate(priority = 0).priority)
        assertEquals(FieldError.SELECTION_REQUIRED, validate(priority = 6).priority)
        assertNull(validate(priority = 1).priority)
        assertNull(validate(priority = 5).priority)
    }

    @Test
    fun `several problems are all reported at once`() {
        val errors = validate(name = "", phone = "x", email = "bad", relationship = null, priority = null)
        assertTrue(errors.hasErrors)
        assertEquals(FieldError.REQUIRED, errors.name)
        assertEquals(FieldError.INVALID_PHONE, errors.phone)
        assertEquals(FieldError.INVALID_EMAIL, errors.email)
        assertEquals(FieldError.SELECTION_REQUIRED, errors.relationship)
        assertEquals(FieldError.SELECTION_REQUIRED, errors.priority)
    }
}
