package com.example.westcon

import com.example.westcon.data.SecurityPolicy
import org.junit.Assert.*
import org.junit.Test

class SecurityPolicyTest {
    @Test fun restrictsSchoolEmail() {
        assertTrue(SecurityPolicy.schoolEmail("student@wvsu.edu.ph"))
        assertFalse(SecurityPolicy.schoolEmail("student@wvsu.edu.ph.evil.invalid"))
        assertFalse(SecurityPolicy.schoolEmail("student@example.com"))
        assertFalse(SecurityPolicy.schoolEmail("@wvsu.edu.ph"))
    }
    @Test fun rejectsUnsafeDocumentPaths() {
        assertTrue(SecurityPolicy.documentId("a-document-id"))
        for (value in listOf("", "../users", "users/other", "..")) assertFalse(SecurityPolicy.documentId(value))
    }
    @Test fun rejectsInvalidRatingsAndOutsiders() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 6.0)) {
            try { SecurityPolicy.requireRating(value); fail("Rating accepted") } catch (_: IllegalArgumentException) { }
        }
        SecurityPolicy.requireRating(5.0)
        try { SecurityPolicy.requireParticipant("alice", "bob", "eve"); fail("Outsider accepted") } catch (_: IllegalArgumentException) { }
    }
}
