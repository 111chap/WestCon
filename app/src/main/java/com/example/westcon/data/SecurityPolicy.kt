package com.example.westcon.data

/** Client validation supplements (and never replaces) the deployed Firestore rules. */
object SecurityPolicy {
    fun schoolEmail(value: String): Boolean =
        Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@wvsu\\.edu\\.ph$", RegexOption.IGNORE_CASE).matches(value.trim())

    fun documentId(value: String): Boolean = value.isNotBlank() && value.length <= 128 &&
        !value.contains('/') && value != "." && value != ".."

    fun requireText(value: String, max: Int): String {
        require(value.isNotBlank() && value.length <= max) { "Text must contain 1–$max characters" }
        return value.trim()
    }

    fun requireParticipant(requester: String, responder: String, caller: String) {
        require(caller == requester || caller == responder) { "Not an exchange participant" }
    }

    fun requireRating(value: Double) {
        require(value.isFinite() && value in 1.0..5.0) { "Ratings must be between 1 and 5" }
    }
}
