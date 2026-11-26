package dev.nook.messenger

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun makeSupabaseClient(url: String, publishableKey: String): SupabaseClient? {
    if (!url.startsWith("https://") || publishableKey.isBlank()) return null
    return createSupabaseClient(url, publishableKey) {
        install(Auth) {
            autoLoadFromStorage = true
            autoSaveToStorage = true
            alwaysAutoRefresh = true
        }
        install(Postgrest)
        install(Storage)
    }
}

@Serializable
internal data class FriendProfile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String
)

@Serializable
internal data class FriendRequestRow(
    val id: String,
    @SerialName("from_user") val fromUser: String,
    @SerialName("to_user") val toUser: String,
    val status: String,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
internal data class FriendshipRow(
    @SerialName("user_id") val userId: String,
    @SerialName("friend_id") val friendId: String
)

@Serializable
internal data class FriendRequestInsert(
    @SerialName("from_user") val fromUser: String,
    @SerialName("to_user") val toUser: String
)

@Serializable
internal data class CloudMessageRow(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    val text: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("attachment_path") val attachmentPath: String? = null,
    @SerialName("attachment_name") val attachmentName: String? = null,
    @SerialName("attachment_mime") val attachmentMime: String? = null
)

@Serializable
internal data class CloudMessageInsert(
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    val text: String,
    @SerialName("attachment_path") val attachmentPath: String? = null,
    @SerialName("attachment_name") val attachmentName: String? = null,
    @SerialName("attachment_mime") val attachmentMime: String? = null
)

@Serializable
internal data class LiveLocationRow(
    @SerialName("user_id") val userId: String,
    @SerialName("friend_id") val friendId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Float,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
internal data class LiveLocationInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("friend_id") val friendId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Float,
    @SerialName("updated_at") val updatedAt: String
)

internal class SupabaseRepository(private val client: SupabaseClient) {
    private val locationWriteMutex = Mutex()
    suspend fun signIn(email: String, password: String): String {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        return client.auth.currentUserOrNull()?.id ?: error("Sign in completed but no user session was returned")
    }

    suspend fun signUp(email: String, password: String, username: String, displayName: String): String? {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("username", username.trim().lowercase())
                put("display_name", displayName.trim())
            }
        }
        return client.auth.currentUserOrNull()?.id
    }

    suspend fun currentUserId(): String? {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
    }

    suspend fun profile(id: String): FriendProfile? = client.from("profiles").select {
        filter { eq("id", id) }
    }.decodeList<FriendProfile>().firstOrNull()

    suspend fun signOut() = client.auth.signOut()

    suspend fun searchUsername(username: String, ownId: String): FriendProfile? {
        if (username.isBlank()) return null
        return client.from("profiles").select {
            filter { eq("username", username.trim().removePrefix("@").lowercase()) }
        }.decodeList<FriendProfile>().firstOrNull { it.id != ownId }
    }

    suspend fun sendFriendRequest(fromUser: String, toUser: String) {
        client.from("friend_requests").insert(FriendRequestInsert(fromUser, toUser))
    }

    suspend fun incomingRequests(userId: String): List<FriendRequestRow> = client.from("friend_requests").select {
        filter {
            eq("to_user", userId)
            eq("status", "pending")
        }
    }.decodeList()

    suspend fun respondToRequest(requestId: String, userId: String, accept: Boolean) {
        val newStatus = if (accept) "accepted" else "declined"
        client.from("friend_requests").update({ set("status", newStatus) }) {
            filter {
                eq("id", requestId)
                eq("to_user", userId)
                eq("status", "pending")
            }
        }
    }

    suspend fun friends(userId: String): List<FriendProfile> {
        val relations = client.from("friendships").select {
            filter { eq("user_id", userId) }
        }.decodeList<FriendshipRow>()
        if (relations.isEmpty()) return emptyList()
        val ids = relations.map { it.friendId }
        val friends = client.from("profiles").select {
            filter { filter("id", FilterOperator.IN, "(${ids.joinToString(",") { "'$it'" }})") }
        }.decodeList<FriendProfile>()
        return friends.sortedBy { it.displayName.lowercase() }
    }

    suspend fun messages(userId: String, friendId: String): List<CloudMessageRow> {
        val rows = client.from("messages").select().decodeList<CloudMessageRow>()
        return rows.filter { (it.senderId == userId && it.recipientId == friendId) || (it.senderId == friendId && it.recipientId == userId) }
            .sortedBy { it.createdAt }
    }

    suspend fun sendMessage(userId: String, friendId: String, text: String) {
        client.from("messages").insert(CloudMessageInsert(userId, friendId, text))
    }

    suspend fun sendAttachment(userId: String, friendId: String, name: String, mime: String, bytes: ByteArray) {
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(90).ifBlank { "attachment" }
        val path = "$userId/$friendId/${System.currentTimeMillis()}_$safeName"
        client.storage.from("chat-attachments").upload(path, bytes) { upsert = false }
        client.from("messages").insert(CloudMessageInsert(userId, friendId, "", path, name.take(180), mime.take(120)))
    }

    suspend fun signedAttachmentUrl(path: String): String = client.storage.from("chat-attachments").createSignedUrl(path, 1.hours)

    suspend fun sharedLocation(userId: String, friendId: String): LiveLocationRow? = client.from("live_locations").select {
        filter { eq("user_id", friendId); eq("friend_id", userId) }
    }.decodeList<LiveLocationRow>().firstOrNull()

    suspend fun myLocationShare(userId: String, friendId: String): LiveLocationRow? = client.from("live_locations").select {
        filter { eq("user_id", userId); eq("friend_id", friendId) }
    }.decodeList<LiveLocationRow>().firstOrNull()

    suspend fun updateSharedLocation(userId: String, friendId: String, latitude: Double, longitude: Double, accuracy: Float) {
        locationWriteMutex.withLock {
            val now = java.time.Instant.now().toString()
            val existing = myLocationShare(userId, friendId)
            if (existing == null) {
                client.from("live_locations").insert(LiveLocationInsert(userId, friendId, latitude, longitude, accuracy, now))
            } else {
                client.from("live_locations").update({
                    set("latitude", latitude)
                    set("longitude", longitude)
                    set("accuracy_meters", accuracy)
                    set("updated_at", now)
                }) { filter { eq("user_id", userId); eq("friend_id", friendId) } }
            }
        }
    }

    suspend fun stopLocationShare(userId: String, friendId: String) {
        client.from("live_locations").delete { filter { eq("user_id", userId); eq("friend_id", friendId) } }
    }
}
