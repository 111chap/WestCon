package com.example.westcon.data

import com.example.westcon.data.FirestoreCollections
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.Timestamp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val usersCollection by lazy { db.collection(FirestoreCollections.USERS) }
    private val skillsCollection by lazy { db.collection(FirestoreCollections.SKILLS) }
    private val freedomWallCollection by lazy { db.collection(FirestoreCollections.FREEDOM_WALL) }
    private val messagesCollection by lazy { db.collection(FirestoreCollections.MESSAGES) }
    private val chatSummariesCollection by lazy { db.collection(FirestoreCollections.CHAT_SUMMARIES) }
    private val notificationsCollection by lazy { db.collection(FirestoreCollections.NOTIFICATIONS) }

    private val ratingsCollection by lazy { db.collection("exchange_ratings") }
    private fun memberUid(): String {
        val user = auth.currentUser ?: throw SecurityException("Sign in first")
        require(user.isEmailVerified && SecurityPolicy.schoolEmail(user.email ?: "")) { "Verify your WVSU email first" }
        return user.uid
    }

    // --- Authentication ---
    fun getCurrentUser() = auth.currentUser
    fun isUserLoggedIn() = auth.currentUser != null

    suspend fun signUp(email: String, pass: String): Result<String> {
        return try {
            require(SecurityPolicy.schoolEmail(email) && pass.length in 8..128) { "Use a WVSU email and an 8–128 character password" }
            val result = auth.createUserWithEmailAndPassword(email.trim().lowercase(), pass).await()
            Result.success(result.user?.uid ?: "")
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun login(email: String, pass: String): Result<String> {
        return try {
            require(SecurityPolicy.schoolEmail(email) && pass.length in 1..128) { "Invalid login details" }
            val result = auth.signInWithEmailAndPassword(email.trim().lowercase(), pass).await()
            Result.success(result.user?.uid ?: "")
        } catch (e: Exception) { Result.failure(e) }
    }

    fun logout() = auth.signOut()

    suspend fun sendEmailVerification(): Result<Unit> {
        return try { auth.currentUser?.sendEmailVerification()?.await(); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun reloadUser(): Result<Unit> {
        return try { val user = auth.currentUser ?: throw SecurityException("Sign in first"); user.reload().await(); user.getIdToken(true).await(); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    fun isEmailVerified(): Boolean = auth.currentUser?.let { it.isEmailVerified && SecurityPolicy.schoolEmail(it.email ?: "") } ?: false

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try { auth.sendPasswordResetEmail(email).await(); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    // --- User Profile ---
    suspend fun checkUsernameExists(username: String): Boolean {
        return try { val query = usersCollection.whereEqualTo("email", "").whereEqualTo("name", username).get().await(); !query.isEmpty } catch (e: Exception) { false }
    }

    fun getUserProfileFlow(uid: String): Flow<UserProfile?> = callbackFlow {
        var profile: UserProfile? = null
        var ratings = emptyList<ExchangeRating>()
        fun publish() { trySend(profile?.withPeerRatings(ratings)) }
        val subscription = usersCollection.document(uid).addSnapshotListener { snapshot, error ->
            profile = if (error == null) snapshot?.toObject(UserProfile::class.java)?.copy(uid = uid, email = "") else null
            publish()
        }
        val ratingSubscription = ratingsCollection.whereEqualTo("targetUid", uid).addSnapshotListener { snapshot, _ ->
            ratings = snapshot?.toObjects(ExchangeRating::class.java) ?: emptyList()
            publish()
        }
        awaitClose { subscription.remove(); ratingSubscription.remove() }
    }

    suspend fun saveUserProfile(profile: UserProfile): Result<Unit> = runCatching {
        val uid = memberUid()
        require(profile.uid == uid) { "Cannot edit another account" }
        SecurityPolicy.requireText(profile.name, 80)
        require(profile.about.length <= 2000 && profile.skillsToTeach.size <= 30 && profile.skillsLearning.size <= 30)
        val publicProfile = profile.copy(email = "", rating = 0.0, swaps = 0,
            skillsToTeach = profile.skillsToTeach.map { it.copy(averageRating = 0.0, totalRatings = 0, level = 1) },
            skillsLearning = profile.skillsLearning.map { it.copy(rating = 0.0) })
        usersCollection.document(uid).set(publicProfile).await()
        Unit
    }

    suspend fun isProfileComplete(uid: String): Boolean {
        return try { usersCollection.document(uid).get().await().exists() } catch (e: Exception) { false }
    }

    suspend fun getUserProfile(uid: String): UserProfile? {
        return try {
            val snapshot = usersCollection.document(uid).get().await()
            if (!snapshot.exists()) return null
            val data = snapshot.data ?: return null
            
            val skillsLearningData = data["skillsLearning"]
            val migratedSkillsLearning = mutableListOf<LearningSkill>()
            
            if (skillsLearningData is Map<*, *>) {
                skillsLearningData.forEach { (key, _) -> if (key is String) migratedSkillsLearning.add(LearningSkill(skillName = key)) }
            } else if (skillsLearningData is List<*>) {
                skillsLearningData.forEach { item ->
                    if (item is Map<*, *>) {
                        migratedSkillsLearning.add(LearningSkill(
                            skillName = item["skillName"] as? String ?: "",
                            rating = (item["rating"] as? Number)?.toDouble() ?: 0.0,
                            isDone = item["isDone"] as? Boolean ?: false,
                            exchangeId = item["exchangeId"] as? String
                        ))
                    }
                }
            }

            val profile = UserProfile(
                uid = uid,
                name = data["name"] as? String ?: "",
                email = "",
                profileIconName = data["profileIconName"] as? String ?: "Person",
                department = data["department"] as? String ?: "",
                course = data["course"] as? String ?: "",
                year = data["year"] as? String ?: "",
                rating = (data["rating"] as? Number)?.toDouble() ?: 0.0,
                swaps = (data["swaps"] as? Number)?.toInt() ?: 0,
                about = data["about"] as? String ?: "",
                skillsToTeach = (data["skillsToTeach"] as? List<*>)?.mapNotNull { item ->
                    if (item is Map<*, *>) {
                        SkillMastery(
                            skillName = item["skillName"] as? String ?: "",
                            averageRating = (item["averageRating"] as? Number)?.toDouble() ?: 0.0,
                            totalRatings = (item["totalRatings"] as? Number)?.toInt() ?: 0,
                            level = (item["level"] as? Number)?.toInt() ?: 1
                        )
                    } else null
                } ?: emptyList(),
                skillsLearning = migratedSkillsLearning
            )
            val ratings = ratingsCollection.whereEqualTo("targetUid", uid).get().await().toObjects(ExchangeRating::class.java)
            profile.withPeerRatings(ratings)
        } catch (e: Exception) { null }
    }

    fun getAllUserProfiles(): Flow<List<UserProfile>> = callbackFlow {
        var profiles = emptyList<UserProfile>()
        var ratings = emptyList<ExchangeRating>()
        fun publish() { trySend(profiles.map { it.withPeerRatings(ratings) }) }
        val subscription = usersCollection.whereEqualTo("email", "").addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        val skillsLearningData = data["skillsLearning"]
                        val migratedSkillsLearning = mutableListOf<LearningSkill>()
                        if (skillsLearningData is List<*>) {
                            skillsLearningData.forEach { item ->
                                if (item is Map<*, *>) {
                                    migratedSkillsLearning.add(LearningSkill(
                                        skillName = item["skillName"] as? String ?: "",
                                        rating = (item["rating"] as? Number)?.toDouble() ?: 0.0,
                                        isDone = item["isDone"] as? Boolean ?: false,
                                        exchangeId = item["exchangeId"] as? String
                                    ))
                                }
                            }
                        }
                        UserProfile(
                            uid = doc.id,
                            name = data["name"] as? String ?: "",
                            email = "",
                            profileIconName = data["profileIconName"] as? String ?: "Person",
                            department = data["department"] as? String ?: "",
                            course = data["course"] as? String ?: "",
                            year = data["year"] as? String ?: "",
                            rating = (data["rating"] as? Number)?.toDouble() ?: 0.0,
                            swaps = (data["swaps"] as? Number)?.toInt() ?: 0,
                            about = data["about"] as? String ?: "",
                            skillsToTeach = (data["skillsToTeach"] as? List<*>)?.mapNotNull { item ->
                                if (item is Map<*, *>) {
                                    SkillMastery(
                                        skillName = item["skillName"] as? String ?: "",
                                        averageRating = (item["averageRating"] as? Number)?.toDouble() ?: 0.0,
                                        totalRatings = (item["totalRatings"] as? Number)?.toInt() ?: 0,
                                        level = (item["level"] as? Number)?.toInt() ?: 1
                                    )
                                } else null
                            } ?: emptyList(),
                            skillsLearning = migratedSkillsLearning
                        )
                    } catch (e: Exception) { null }
                }
                profiles = list
                publish()
            }
        }
        val ratingSubscription = ratingsCollection.addSnapshotListener { snapshot, _ ->
            ratings = snapshot?.toObjects(ExchangeRating::class.java) ?: emptyList()
            publish()
        }
        awaitClose { subscription.remove(); ratingSubscription.remove() }
    }

    // --- Skill Exchanges ---
    private val exchangesCollection by lazy { db.collection(FirestoreCollections.EXCHANGES) }

    fun getChatId(uid1: String, uid2: String): String {
        return if (uid1 < uid2) "${uid1}_${uid2}" else "${uid2}_$uid1"
    }

    suspend fun getActiveExchange(uid1: String, uid2: String): SkillExchange? {
        return try {
            val query1 = exchangesCollection.whereEqualTo("requesterUid", uid1).whereEqualTo("responderUid", uid2).whereEqualTo("status", "ACTIVE").get().await()
            if (!query1.isEmpty) return query1.documents[0].toObject(SkillExchange::class.java)
            val query2 = exchangesCollection.whereEqualTo("requesterUid", uid2).whereEqualTo("responderUid", uid1).whereEqualTo("status", "ACTIVE").get().await()
            if (!query2.isEmpty) return query2.documents[0].toObject(SkillExchange::class.java)
            null
        } catch (e: Exception) { null }
    }

    suspend fun markExchangeDone(exchangeId: String, uid: String): Result<Unit> = runCatching {
        require(uid == memberUid() && SecurityPolicy.documentId(exchangeId)) { "Invalid exchange owner" }
        val ref = exchangesCollection.document(exchangeId)
        db.runTransaction { transaction ->
            val exchange = transaction.get(ref).toObject(SkillExchange::class.java) ?: error("Exchange not found")
            SecurityPolicy.requireParticipant(exchange.requesterUid, exchange.responderUid, uid)
            val requester = uid == exchange.requesterUid
            val done = if (requester) exchange.responderMarkedDone else exchange.requesterMarkedDone
            transaction.update(ref, if (requester) "requesterMarkedDone" else "responderMarkedDone", true,
                "status", if (done) "DONE" else "ACTIVE")
        }.await()
        Unit
    }

    suspend fun submitExchangeRating(
        exchangeId: String, targetUid: String, teachingRating: Double, learningRating: Double,
        taughtSkillName: String, learnedSkillName: String
    ): Result<Unit> = runCatching {
        val uid = memberUid()
        require(SecurityPolicy.documentId(exchangeId))
        SecurityPolicy.requireRating(teachingRating); SecurityPolicy.requireRating(learningRating)
        val ref = exchangesCollection.document(exchangeId)
        val ratingRef = ratingsCollection.document(exchangeId + "_" + uid)
        db.runTransaction { transaction ->
            val exchange = transaction.get(ref).toObject(SkillExchange::class.java) ?: error("Exchange not found")
            SecurityPolicy.requireParticipant(exchange.requesterUid, exchange.responderUid, uid)
            require(exchange.status == "DONE") { "Complete the exchange before rating" }
            val requester = uid == exchange.requesterUid
            require(targetUid == if (requester) exchange.responderUid else exchange.requesterUid) { "Invalid rating target" }
            require(!(if (requester) exchange.requesterRated else exchange.responderRated)) { "Already rated" }
            val taught = if (requester) exchange.skillWanted else exchange.skillOffered
            val learned = if (requester) exchange.skillOffered else exchange.skillWanted
            val record = ExchangeRating(exchangeId, uid, targetUid, teachingRating, learningRating, taught, learned)
            transaction.set(ratingRef, record)
            transaction.update(ratingRef, "timestamp", FieldValue.serverTimestamp())
            transaction.update(ref, mapOf(
                (if (requester) "requesterRated" else "responderRated") to true,
                (if (requester) "responderTeachingRating" else "requesterTeachingRating") to teachingRating,
                (if (requester) "responderLearningRating" else "requesterLearningRating") to learningRating))
        }.await()
        Unit
    }

    fun getRelevantExchangeFlow(currentUid: String, otherUid: String): Flow<SkillExchange?> = callbackFlow {
        var exchanges1: List<SkillExchange>? = null
        var exchanges2: List<SkillExchange>? = null

        fun sendRelevant() {
            if (exchanges1 == null || exchanges2 == null) return
            val combined = (exchanges1!! + exchanges2!!)
            
            // Priority Logic:
            // 1. The latest ACTIVE exchange (Ongoing session)
            // 2. The latest DONE exchange that hasn't been rated by the current user
            // 3. The absolute latest exchange (Fallback)
            
            val active = combined.filter { it.status == "ACTIVE" }.sortedByDescending { it.timestamp }.firstOrNull()
            if (active != null) {
                trySend(active)
                return
            }
            
            val unrated = combined.filter { 
                it.status == "DONE" && (if (it.requesterUid == currentUid) !it.requesterRated else !it.responderRated) 
            }.sortedByDescending { it.timestamp }.firstOrNull()
            
            if (unrated != null) {
                trySend(unrated)
                return
            }
            
            trySend(combined.sortedByDescending { it.timestamp }.firstOrNull())
        }

        val listener1 = exchangesCollection
            .whereEqualTo("requesterUid", currentUid)
            .whereEqualTo("responderUid", otherUid)
            .addSnapshotListener { snapshot, error ->
                if (error == null) {
                    exchanges1 = snapshot?.documents?.mapNotNull { it.toObject(SkillExchange::class.java)?.copy(id = it.id) } ?: emptyList()
                    sendRelevant()
                }
            }

        val listener2 = exchangesCollection
            .whereEqualTo("requesterUid", otherUid)
            .whereEqualTo("responderUid", currentUid)
            .addSnapshotListener { snapshot, error ->
                if (error == null) {
                    exchanges2 = snapshot?.documents?.mapNotNull { it.toObject(SkillExchange::class.java)?.copy(id = it.id) } ?: emptyList()
                    sendRelevant()
                }
            }
        
        awaitClose { 
            listener1.remove()
            listener2.remove()
        }
    }

    suspend fun acceptExchangeRequest(notification: Notification): Result<Unit> = runCatching {
        val uid = memberUid()
        require(SecurityPolicy.documentId(notification.id))
        val notificationRef = notificationsCollection.document(notification.id)
        // Stable ID makes duplicate acceptance conflict on the same exchange document.
        val ref = exchangesCollection.document(notification.id)
        db.runTransaction { transaction ->
            val request = transaction.get(notificationRef).toObject(Notification::class.java) ?: error("Request not found")
            val sender = request.senderUid ?: error("Sender missing")
            require(request.receiverUid == uid && sender != uid && request.type == "SKILL_EXCHANGE") { "Not your exchange request" }
            require(!transaction.get(ref).exists()) { "Already accepted" }
            val exchange = SkillExchange(id = ref.id, requesterUid = sender, responderUid = uid,
                skillOffered = request.skillOffered ?: "", skillWanted = request.skillWanted ?: "")
            transaction.set(ref, exchange)
            transaction.update(ref, "timestamp", FieldValue.serverTimestamp())
            transaction.delete(notificationRef)
        }.await()
        Unit
    }

    // --- Presence ---
    fun updateOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).update("online", isOnline, "lastActive", com.google.firebase.Timestamp.now())
    }

    fun getUserOnlineStatus(uid: String): Flow<Boolean> = callbackFlow {
        val subscription = db.collection("users").document(uid).addSnapshotListener { snapshot, error ->
            if (error == null && snapshot != null) {
                trySend(snapshot.getBoolean("online") ?: false)
            }
        }
        awaitClose { subscription.remove() }
    }

    // --- Skill Marketplace ---
    suspend fun postSkill(post: SkillPost): Result<Unit> = runCatching {
        val uid = memberUid()
        val profile = getUserProfile(uid) ?: error("Complete your profile")
        SecurityPolicy.requireText(post.title, 120); SecurityPolicy.requireText(post.description, 2000)
        val ref = skillsCollection.document()
        val batch = db.batch()
        batch.set(ref, post.copy(id = ref.id, authorUid = uid, authorName = profile.name,
            authorIconName = profile.profileIconName, authorMastery = 1, department = profile.department))
        batch.update(ref, "timestamp", FieldValue.serverTimestamp()); batch.commit().await()
        Unit
    }

    suspend fun deleteSkillPost(postId: String): Result<Unit> = runCatching {
        require(SecurityPolicy.documentId(postId))
        val ref = skillsCollection.document(postId)
        require(ref.get().await().getString("authorUid") == memberUid()) { "Not your post" }
        ref.delete().await()
        Unit
    }

    fun getSkillPosts(): Flow<List<SkillPost>> = callbackFlow {
        val subscription = skillsCollection.orderBy("timestamp", Query.Direction.DESCENDING).addSnapshotListener { snapshot, error -> if (error == null && snapshot != null) trySend(snapshot.documents.mapNotNull { it.toObject(SkillPost::class.java)?.copy(id = it.id) }) }
        awaitClose { subscription.remove() }
    }

    // --- Freedom Wall ---
    suspend fun postToFreedomWall(post: FreedomPost): Result<Unit> = runCatching {
        val uid = memberUid()
        SecurityPolicy.requireText(post.content, 2000)
        val profile = getUserProfile(uid) ?: error("Complete your profile")
        val ref = freedomWallCollection.document()
        val batch = db.batch()
        batch.set(ref, post.copy(id = ref.id, authorUid = uid, authorName = if (post.anonymous) "Anonymous Taga-West" else profile.name,
            authorIconName = if (post.anonymous) "VisibilityOff" else profile.profileIconName, likes = 0, likedBy = emptyList(), commentCount = 0, topComment = null))
        batch.update(ref, "timestamp", FieldValue.serverTimestamp()); batch.commit().await()
        Unit
    }

    suspend fun deleteFreedomPost(postId: String): Result<Unit> = runCatching {
        require(SecurityPolicy.documentId(postId))
        val ref = freedomWallCollection.document(postId)
        require(ref.get().await().getString("authorUid") == memberUid()) { "Not your post" }
        ref.delete().await()
        Unit
    }

    fun getFreedomPosts(): Flow<List<FreedomPost>> = callbackFlow {
        val subscription = freedomWallCollection.orderBy("timestamp", Query.Direction.DESCENDING).addSnapshotListener { snapshot, error -> if (error == null && snapshot != null) trySend(snapshot.documents.mapNotNull { it.toObject(FreedomPost::class.java)?.copy(id = it.id) }) }
        awaitClose { subscription.remove() }
    }

    suspend fun toggleLikeFreedomPost(postId: String): Result<Unit> = runCatching {
        val uid = memberUid(); require(SecurityPolicy.documentId(postId))
        val ref = freedomWallCollection.document(postId)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            val post = snapshot.toObject(FreedomPost::class.java) ?: error("Post missing")
            val liked = post.likedBy.toMutableSet()
            if (!liked.remove(uid)) liked.add(uid)
            transaction.update(ref, "likedBy", liked.toList(), "likes", liked.size)
        }.await()
        Unit
    }

    suspend fun postComment(comment: FreedomComment): Result<Unit> {
        return try {
            val postRef = freedomWallCollection.document(comment.postId); val commentRef = postRef.collection("comments").document(); val authorUid = memberUid(); SecurityPolicy.requireText(comment.content, 2000); val profile = if (!comment.anonymous) getUserProfile(authorUid) else null
            val finalComment = comment.copy(id = commentRef.id, authorUid = authorUid, authorName = if (comment.anonymous) "Anonymous Taga-West" else (profile?.name ?: "User"), authorIconName = if (comment.anonymous) "VisibilityOff" else (profile?.profileIconName ?: "Person"))
            db.runTransaction { transaction -> val snapshot = transaction.get(postRef); val currentCommentCount = snapshot.getLong("commentCount") ?: 0; transaction.set(commentRef, finalComment); transaction.update(commentRef, "timestamp", FieldValue.serverTimestamp()); transaction.update(postRef, "commentCount", currentCommentCount + 1); transaction.update(postRef, "topComment", finalComment.content); transaction.update(postRef, "lastCommentId", commentRef.id) }.await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getComments(postId: String): Flow<List<FreedomComment>> = callbackFlow {
        val subscription = freedomWallCollection.document(postId).collection("comments").orderBy("timestamp", Query.Direction.ASCENDING).addSnapshotListener { snapshot, error -> if (error == null && snapshot != null) trySend(snapshot.documents.mapNotNull { it.toObject(FreedomComment::class.java)?.copy(id = it.id) }) }
        awaitClose { subscription.remove() }
    }

    // --- Messaging ---
    suspend fun sendMessage(msg: Message, chatId: String): Result<Unit> = runCatching {
        val uid = memberUid()
        require(msg.senderUid == uid && msg.receiverUid != uid && SecurityPolicy.documentId(msg.receiverUid)) { "Invalid sender or recipient" }
        require(chatId == getChatId(uid, msg.receiverUid)) { "Invalid chat" }
        val text = SecurityPolicy.requireText(msg.text, 4000)
        val ref = messagesCollection.document(chatId).collection("history").document()
        val sender = getUserProfile(uid) ?: error("Profile missing")
        val recipient = getUserProfile(msg.receiverUid) ?: error("Recipient missing")
        val batch = db.batch()
        batch.set(messagesCollection.document(chatId), mapOf("members" to listOf(uid, msg.receiverUid).sorted()), SetOptions.merge())
        batch.set(ref, msg.copy(id = ref.id, senderUid = uid, text = text, isRead = false, readCompat = false))
        batch.update(ref, "timestamp", FieldValue.serverTimestamp())
        for ((owner, other, profile) in listOf(Triple(uid, msg.receiverUid, recipient), Triple(msg.receiverUid, uid, sender))) {
            val summaryRef = chatSummariesCollection.document(owner).collection("chats").document(other)
            batch.set(summaryRef, ChatSummary(otherUserUid = other, otherUserName = profile.name,
                otherUserIconName = profile.profileIconName, otherUserDept = profile.department,
                lastMessage = text, unreadCount = if (owner == uid) 0 else 1,
                lastMessageSenderUid = uid, lastMessageRead = owner == uid, isRead = owner == uid))
            batch.update(summaryRef, mapOf("timestamp" to FieldValue.serverTimestamp(), "lastMessageId" to ref.id, "chatId" to chatId))
        }
        batch.commit().await()
        Unit
    }

    suspend fun markChatAsRead(otherUid: String): Result<Unit> {
        return try { val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Not logged in")); chatSummariesCollection.document(uid).collection("chats").document(otherUid).update("unreadCount", 0, "lastMessageRead", true, "isRead", true).await(); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun markChatMessagesAsRead(chatId: String): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Not logged in"))
            val snapshotIsRead = messagesCollection.document(chatId).collection("history").whereEqualTo("receiverUid", uid).whereEqualTo("isRead", false).get().await()
            val snapshotRead = messagesCollection.document(chatId).collection("history").whereEqualTo("receiverUid", uid).whereEqualTo("read", false).get().await()
            val docsById = linkedMapOf<String, com.google.firebase.firestore.DocumentSnapshot>(); for (doc in snapshotIsRead.documents) docsById[doc.id] = doc; for (doc in snapshotRead.documents) docsById[doc.id] = doc
            if (docsById.isEmpty()) return Result.success(Unit)
            val batch = db.batch(); for ((_, doc) in docsById) batch.update(doc.reference, "isRead", true, "read", true); batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }



    fun getMessages(chatId: String): Flow<List<Message>> = callbackFlow {
        val subscription = messagesCollection.document(chatId).collection("history").orderBy("timestamp", Query.Direction.ASCENDING).addSnapshotListener { snapshot, error -> if (error == null && snapshot != null) trySend(snapshot.documents.mapNotNull { doc -> doc.toObject(Message::class.java)?.copy(id = doc.id)?.apply { val data = doc.data; isRead = when { data?.get("isRead") is Boolean -> data["isRead"] as Boolean; data?.get("read") is Boolean -> data["read"] as Boolean; else -> isRead }; readCompat = isRead } }) }
        awaitClose { subscription.remove() }
    }

    fun getChatSummaries(): Flow<List<ChatSummary>> = callbackFlow {
        val uid = auth.currentUser?.uid ?: return@callbackFlow
        val subscription = chatSummariesCollection.document(uid).collection("chats").orderBy("timestamp", Query.Direction.DESCENDING).addSnapshotListener { snapshot, error -> if (error == null && snapshot != null) trySend(snapshot.documents.mapNotNull { doc -> doc.toObject(ChatSummary::class.java)?.copy(otherUserUid = doc.id)?.apply { val data = doc.data; isRead = when { data?.get("isRead") is Boolean -> data["isRead"] as Boolean; data?.get("lastMessageRead") is Boolean -> data["lastMessageRead"] as Boolean; else -> isRead }; lastMessageRead = isRead } }) }
        awaitClose { subscription.remove() }
    }

    suspend fun setUserTypingStatus(otherUid: String, isTyping: Boolean): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Not logged in"))
            // We update the typing status in the OTHER user's summary of this chat
            chatSummariesCollection.document(otherUid).collection("chats").document(uid)
                .update("typing", isTyping).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getTypingStatus(otherUid: String): Flow<Boolean> = callbackFlow {
        val uid = auth.currentUser?.uid ?: return@callbackFlow
        // We listen to our own summary of this chat to see if the other user is typing
        val subscription = chatSummariesCollection.document(uid).collection("chats").document(otherUid)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    val typing = snapshot.getBoolean("typing") ?: false
                    trySend(typing)
                }
            }
        awaitClose { subscription.remove() }
    }

    // --- Notifications ---
    suspend fun sendNotification(notification: Notification): Result<Unit> = runCatching {
        val uid = memberUid()
        require(notification.type == "SKILL_EXCHANGE" && notification.receiverUid != uid && SecurityPolicy.documentId(notification.receiverUid))
        SecurityPolicy.requireText(notification.content, 2000)
        val profile = getUserProfile(uid) ?: error("Profile missing")
        val ref = notificationsCollection.document()
        val batch = db.batch()
        batch.set(ref, notification.copy(id = ref.id, senderUid = uid, senderName = profile.name,
            senderIconName = profile.profileIconName, senderDept = profile.department, isRead = false, readCompat = false))
        batch.update(ref, "timestamp", FieldValue.serverTimestamp()); batch.commit().await()
        Unit
    }

    suspend fun deleteNotification(id: String): Result<Unit> { return try { notificationsCollection.document(id).delete().await(); Result.success(Unit) } catch (e: Exception) { Result.failure(e) } }
    suspend fun markNotificationAsRead(id: String): Result<Unit> { return try { notificationsCollection.document(id).update("isRead", true, "read", true).await(); Result.success(Unit) } catch (e: Exception) { Result.failure(e) } }
    suspend fun markAllNotificationsAsRead(): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Not logged in")); val snapshotIsRead = notificationsCollection.whereEqualTo("receiverUid", uid).whereEqualTo("isRead", false).get().await(); val snapshotRead = notificationsCollection.whereEqualTo("receiverUid", uid).whereEqualTo("read", false).get().await()
            val docsById = linkedMapOf<String, com.google.firebase.firestore.DocumentSnapshot>(); for (doc in snapshotIsRead.documents) docsById[doc.id] = doc; for (doc in snapshotRead.documents) docsById[doc.id] = doc
            if (docsById.isEmpty()) return Result.success(Unit)
            val batch = db.batch(); for ((_, doc) in docsById) batch.update(doc.reference, "isRead", true, "read", true); batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getNotifications(): Flow<List<Notification>> = callbackFlow {
        val uid = auth.currentUser?.uid ?: return@callbackFlow
        var sub: com.google.firebase.firestore.ListenerRegistration? = null
        sub = notificationsCollection.whereEqualTo("receiverUid", uid).orderBy("timestamp", Query.Direction.DESCENDING).addSnapshotListener { snapshot, error ->
            if (error != null && (error.message?.contains("index") == true)) {
                sub?.remove(); sub = notificationsCollection.whereEqualTo("receiverUid", uid).addSnapshotListener { snap2, err2 -> if (err2 == null && snap2 != null) trySend(snap2.documents.mapNotNull { doc -> doc.toObject(Notification::class.java)?.copy(id = doc.id)?.apply { val docMap = doc.data; readCompat = if (docMap?.get("read") is Boolean) docMap["read"] as Boolean else if (docMap?.get("isRead") is Boolean) docMap["isRead"] as Boolean else readCompat } }.sortedByDescending { it.timestamp }) }
            } else if (snapshot != null) trySend(snapshot.documents.mapNotNull { doc -> doc.toObject(Notification::class.java)?.copy(id = doc.id)?.apply { val docMap = doc.data; readCompat = if (docMap?.get("read") is Boolean) docMap["read"] as Boolean else if (docMap?.get("isRead") is Boolean) docMap["isRead"] as Boolean else readCompat } })
        }
        awaitClose { sub?.remove() }
    }
}

