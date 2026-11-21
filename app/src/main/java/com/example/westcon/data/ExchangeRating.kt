package com.example.westcon.data

import com.google.firebase.Timestamp

data class ExchangeRating(
    val exchangeId: String = "", val raterUid: String = "", val targetUid: String = "",
    val teachingRating: Double = 0.0, val learningRating: Double = 0.0,
    val taughtSkillName: String = "", val learnedSkillName: String = "",
    val timestamp: Timestamp = Timestamp.now()
)

/** Display scores are derived from immutable, rules-validated peer ratings, not profile fields. */
fun UserProfile.withPeerRatings(all: List<ExchangeRating>): UserProfile {
    val ratings = all.filter { it.targetUid == uid }
    val teaching = skillsToTeach.map { skill ->
        val values = ratings.filter { it.taughtSkillName.equals(skill.skillName, true) }.map { it.teachingRating }
        val average = if (values.isEmpty()) 0.0 else values.average()
        val level = when {
            average >= 4.5 && values.size >= 15 -> 5
            average >= 4.0 && values.size >= 10 -> 4
            average >= 3.5 && values.size >= 6 -> 3
            average >= 3.0 && values.size >= 3 -> 2
            else -> 1
        }
        skill.copy(averageRating = average, totalRatings = values.size, level = level)
    }
    val learning = skillsLearning.map { skill ->
        val values = ratings.filter { it.learnedSkillName.equals(skill.skillName, true) }.map { it.learningRating }
        skill.copy(rating = if (values.isEmpty()) 0.0 else values.average())
    }.toMutableList()
    for (rating in ratings) if (learning.none { it.skillName.equals(rating.learnedSkillName, true) }) {
        learning.add(LearningSkill(rating.learnedSkillName, rating.learningRating, true, rating.exchangeId))
    }
    return copy(email = "", rating = if (ratings.isEmpty()) 0.0 else ratings.flatMap { listOf(it.teachingRating, it.learningRating) }.average(),
        swaps = ratings.map { it.exchangeId }.distinct().size, skillsToTeach = teaching, skillsLearning = learning)
}
