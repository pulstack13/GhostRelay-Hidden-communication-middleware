package dev.nook.messenger

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.NotificationManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val Ink = Color(0xFFF1F5FB)
private val Muted = Color(0xFF9AA9BC)
private val Blue = Color(0xFF3D8EF2)
private val Paper = Color(0xFF111923)
private val Panel = Color(0xFF18222E)
private val Incoming = Color(0xFF222E3B)
private val io = Executors.newSingleThreadExecutor()
private val main = Handler(Looper.getMainLooper())
private data class Attachment(val id: String, val name: String, val mimeType: String, val size: Long, val url: String)
private data class Message(val id: String, val text: String, val mine: Boolean, val time: String, val attachment: Attachment? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nook", MODE_PRIVATE)
        val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
        getSystemService(NotificationManager::class.java).apply {
            cancelAll()
            if (Build.VERSION.SDK_INT >= 26) deleteNotificationChannel("nook_messages")
        }
        val cloud = makeSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        setContent { NookApp(prefs.getString("server", "http://10.0.2.2:8787") ?: "http://10.0.2.2:8787", deviceId, { prefs.edit().putString("server", it).apply() }, cloud) }
    }
}

@Composable
private fun NookApp(initialServer: String, deviceId: String, saveServer: (String) -> Unit, cloud: io.github.jan.supabase.SupabaseClient?) {
    val repository = remember(cloud) { cloud?.let(::SupabaseRepository) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("chat") }
    var activated by remember { mutableStateOf(false) }
    var activationCode by remember { mutableStateOf("") }
    var activationError by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(initialServer) }
    var text by remember { mutableStateOf("") }
    var call by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf("Connecting to laptop server…") }
    var userId by remember { mutableStateOf<String?>(null) }
    var sessionReady by remember { mutableStateOf(false) }
    var selectedFriend by remember { mutableStateOf<FriendProfile?>(null) }
    var cloudMessages by remember { mutableStateOf<List<CloudMessageRow>>(emptyList()) }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf("") }
    var liveLocation by remember { mutableStateOf<LiveLocationRow?>(null) }
    var sharingLocation by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<Message>() }
    val context = LocalContext.current

    LaunchedEffect(selectedFriend, userId) {
        sharingLocation = selectedFriend?.let { friend ->
            context.getSharedPreferences("nook", Context.MODE_PRIVATE).getString("sharingFriend", null) == friend.id
        } ?: false
        liveLocation = null
    }

    DisposableEffect(repository, userId, selectedFriend) {
        val currentRepository = repository
        val currentUser = userId
        val friend = selectedFriend
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    LiveLocationService.ACTION_LOCATION -> {
                        val lat = intent.getDoubleExtra(LiveLocationService.EXTRA_LATITUDE, Double.NaN)
                        val lon = intent.getDoubleExtra(LiveLocationService.EXTRA_LONGITUDE, Double.NaN)
                        val accuracy = intent.getFloatExtra(LiveLocationService.EXTRA_ACCURACY, 0f)
                        if (lat.isFinite() && lon.isFinite() && currentRepository != null && currentUser != null && friend != null) {
                            scope.launch {
                                runCatching { currentRepository.updateSharedLocation(currentUser, friend.id, lat, lon, accuracy) }
                                    .onFailure { cloudError = friendlySupabaseError(it, "Could not update your shared location.") }
                            }
                        }
                    }
                    LiveLocationService.ACTION_STOPPED -> {
                        val prefs = context.getSharedPreferences("nook", Context.MODE_PRIVATE)
                        val target = prefs.getString("sharingFriend", null)
                        prefs.edit().remove("sharingFriend").apply()
                        sharingLocation = false
                        if (target != null && currentRepository != null && currentUser != null) {
                            scope.launch { runCatching { currentRepository.stopLocationShare(currentUser, target) } }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LiveLocationService.ACTION_LOCATION)
            addAction(LiveLocationService.ACTION_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val friend = selectedFriend
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            if (friend != null) {
                context.getSharedPreferences("nook", Context.MODE_PRIVATE).edit().putString("sharingFriend", friend.id).apply()
                sharingLocation = true
                val serviceIntent = Intent(context, LiveLocationService::class.java).putExtra(LiveLocationService.EXTRA_FRIEND_NAME, friend.displayName)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
            }
        } else cloudError = "Location permission is needed to share your live location."
    }

    fun startLocationSharing() {
        val friend = selectedFriend ?: return
        val permissions = mutableListOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val locationGranted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationNeeded = Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (locationGranted && !notificationNeeded) {
            context.getSharedPreferences("nook", Context.MODE_PRIVATE).edit().putString("sharingFriend", friend.id).apply()
            sharingLocation = true
            val serviceIntent = Intent(context, LiveLocationService::class.java).putExtra(LiveLocationService.EXTRA_FRIEND_NAME, friend.displayName)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
        } else locationPermissionLauncher.launch(permissions.toTypedArray())
    }

    fun stopLocationSharing() {
        val friend = selectedFriend
        context.stopService(Intent(context, LiveLocationService::class.java))
        context.getSharedPreferences("nook", Context.MODE_PRIVATE).edit().remove("sharingFriend").apply()
        sharingLocation = false
        if (friend != null && repository != null && userId != null) scope.launch {
            runCatching { repository.stopLocationShare(userId!!, friend.id) }
                .onFailure { cloudError = friendlySupabaseError(it, "Could not stop location sharing on the server.") }
        }
    }

    LaunchedEffect(repository, userId, selectedFriend) {
        val currentRepository = repository
        val currentUser = userId
        val friend = selectedFriend
        if (currentRepository != null && currentUser != null && friend != null) {
            while (true) {
                runCatching { currentRepository.sharedLocation(currentUser, friend.id) }
                    .onSuccess { row -> liveLocation = row?.takeIf { runCatching { java.time.Instant.parse(it.updatedAt).isAfter(java.time.Instant.now().minusSeconds(180)) }.getOrDefault(false) } }
                delay(15_000)
            }
        }
    }

    LaunchedEffect(repository, activated) {
        if (activated && repository != null && !sessionReady) {
            runCatching { repository.currentUserId() }.onSuccess { userId = it }
                .onFailure { cloudError = friendlySupabaseError(it, "Could not restore the saved login. Please sign in again.") }
            sessionReady = true
        }
    }

    fun refresh() {
        val base = serverUrl.trim().trimEnd('/')
        io.execute {
            try {
                val conn = (URL("$base/messages").openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000; requestMethod = "GET" }
                val data = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val array = JSONObject(data).getJSONArray("messages")
                val loaded = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    val mine = item.optString("senderDeviceId") == deviceId
                    val time = runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date.from(java.time.Instant.parse(item.getString("createdAt")))) }.getOrDefault("")
                    val attachmentJson = item.optJSONObject("attachment")
                    val attachment = attachmentJson?.let { Attachment(it.optString("id"), it.optString("name"), it.optString("mimeType", "application/octet-stream"), it.optLong("size"), it.optString("url")) }
                    Message(item.optString("id"), item.optString("text"), mine, time, attachment)
                }
                main.post {
                    messages.clear(); messages.addAll(loaded); connection = "Connected to laptop server"
                }
            } catch (_: Exception) { main.post { connection = "Server not reachable · check address and Wi-Fi" } }
        }
    }

    fun sendMessage(value: String) {
        val currentUser = userId
        val friend = selectedFriend
        if (repository != null && currentUser != null && friend != null) {
            scope.launch {
                runCatching { repository.sendMessage(currentUser, friend.id, value) }
                    .onSuccess { cloudMessages = repository.messages(currentUser, friend.id); cloudError = "" }
                    .onFailure { cloudError = friendlySupabaseError(it, "Message could not be sent. Please try again.") }
            }
            return
        }
        val base = serverUrl.trim().trimEnd('/')
        io.execute {
            try {
                val conn = (URL("$base/messages").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000; readTimeout = 4000; requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(JSONObject().put("senderDeviceId", deviceId).put("text", value).toString().toByteArray()) }
                if (conn.responseCode !in 200..299) error("server rejected message")
                conn.disconnect()
                main.post { connection = "Connected to laptop server"; refresh() }
            } catch (_: Exception) { main.post { connection = "Send failed · check server and Wi-Fi" } }
        }
    }

    fun uploadAttachment(uri: Uri) {
        val currentUser = userId
        val friend = selectedFriend
        if (repository != null && currentUser != null && friend != null) {
            scope.launch {
                runCatching {
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "attachment"
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: error("cannot read attachment")
                    if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) error("Choose a file smaller than 12 MB")
                    repository.sendAttachment(currentUser, friend.id, name, mimeType, bytes)
                    repository.messages(currentUser, friend.id)
                }.onSuccess { cloudMessages = it; cloudError = "" }
                    .onFailure { cloudError = friendlySupabaseError(it, "Attachment could not be uploaded. Please try again.") }
            }
            return
        }
        val base = serverUrl.trim().trimEnd('/')
        io.execute {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "attachment"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read attachment")
                if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) error("Choose a file smaller than 12 MB")
                val upload = (URL("$base/attachments").openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 20000; requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
                val payload = JSONObject().put("senderDeviceId", deviceId).put("name", name).put("mimeType", mimeType).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                upload.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (upload.responseCode !in 200..299) error("upload failed (${upload.responseCode})")
                val uploaded = JSONObject(upload.inputStream.bufferedReader().use { it.readText() }).getJSONObject("attachment")
                upload.disconnect()
                val message = JSONObject().put("senderDeviceId", deviceId).put("text", "").put("attachment", uploaded)
                val post = (URL("$base/messages").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
                post.outputStream.use { it.write(message.toString().toByteArray()) }
                if (post.responseCode !in 200..299) error("server rejected attachment message")
                post.disconnect()
                main.post { connection = "Connected to laptop server"; refresh() }
            } catch (error: Exception) { main.post { connection = "Attachment failed · ${error.message ?: "check server"}" } }
        }
    }

    val anyFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::uploadAttachment) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::uploadAttachment) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::uploadAttachment) }

    LaunchedEffect(serverUrl, activated, userId, selectedFriend, repository) {
        if (!activated) return@LaunchedEffect
        while (true) {
            val currentUser = userId
            val friend = selectedFriend
            if (repository != null && currentUser != null && friend != null) {
                runCatching { repository.messages(currentUser, friend.id) }
                    .onSuccess { fresh -> cloudMessages = fresh }
                    .onFailure { cloudError = friendlySupabaseError(it, "Could not load this conversation. Check your connection and try again.") }
            } else if (repository == null) refresh()
            delay(4000)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Paper, surface = Panel, onSurface = Ink)) {
        Surface(Modifier.fillMaxSize().imePadding().lockOnThreeFingerTap {
            activated = false; activationCode = ""; activationError = false; call = false; page = "chat"
        }, color = Paper) {
            when {
                !activated -> ActivationScreen(activationCode, { activationCode = it; activationError = false }, activationError, {
                    if (activationCode.trim() == BuildConfig.APP_LOCK_CODE) { activated = true } else activationError = true
                }, { message -> if (message.isNotBlank()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show() })
                repository == null -> SupabaseSetupScreen()
                !sessionReady -> SessionRestoreScreen()
                userId == null -> AuthScreen(cloudBusy, cloudError, { cloudError = it }, { cloudBusy = it }, { id -> userId = id; page = "friends" }, repository, scope)
                page == "friends" -> FriendsScreen(repository = repository!!, userId = userId!!, error = cloudError, setError = { cloudError = it }, onSelect = { friend: FriendProfile -> selectedFriend = friend; cloudMessages = emptyList(); page = "chat" }, onSignOut = { scope.launch { runCatching { repository!!.signOut() }.onSuccess { userId = null; selectedFriend = null } } }, scope = scope)
                call -> CallScreen(onEnd = { call = false })
                page == "settings" -> SettingsScreen(serverUrl, connection, { value -> serverUrl = value }, { saveServer(serverUrl); page = "chat" }, { page = "chat" })
                page == "pair" -> PairScreen(onBack = { page = "chat" })
                else -> Column(Modifier.fillMaxSize().imePadding()) {
                    Header(if (selectedFriend == null) "Choose a friend" else "Connected to Supabase", onSettings = { page = "settings" }, onCall = { call = true })
                    Row(Modifier.fillMaxWidth().background(Panel).clickable { page = "friends" }.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Friends", tint = Blue, modifier = Modifier.size(17.dp))
                        Text("  Friends", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f)); Text(selectedFriend?.displayName ?: "Select a friend", color = Muted, fontSize = 12.sp)
                    }
                    if (selectedFriend != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { if (sharingLocation) stopLocationSharing() else startLocationSharing() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
                                Icon(if (sharingLocation) Icons.Default.LocationOn else Icons.Default.ShareLocation, null, modifier = Modifier.size(17.dp))
                                Text(if (sharingLocation) "  Stop live location" else "  Share live location", fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(9.dp))
                            val freshLocation = liveLocation
                            if (freshLocation != null) {
                                TextButton(onClick = {
                                    val map = Uri.parse("geo:${freshLocation.latitude},${freshLocation.longitude}?q=${freshLocation.latitude},${freshLocation.longitude}(${Uri.encode(selectedFriend?.displayName ?: "Friend")})")
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, map)) }
                                }) { Icon(Icons.Default.Map, null, modifier = Modifier.size(16.dp)); Text("  Open map") }
                            } else Text(if (sharingLocation) "Waiting for GPS…" else "Location is private", color = Muted, fontSize = 11.sp)
                        }
                    }
                    TrustBanner(connection)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
                        item { DayDivider() }
                        val visibleMessages = if (selectedFriend != null) cloudMessages.map { row ->
                            val attachment = row.attachmentPath?.let { Attachment(it, row.attachmentName ?: "Attachment", row.attachmentMime ?: "application/octet-stream", 0L, "storage:$it") }
                            Message(row.id, row.text, row.senderId == userId, runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date.from(java.time.Instant.parse(row.createdAt))) }.getOrDefault(""), attachment)
                        } else messages
                        if (visibleMessages.isEmpty()) item { EmptyConversation() }
                        items(visibleMessages) { message -> Bubble(message) {
                            message.attachment?.let { attachment ->
                                if (attachment.url.startsWith("storage:") && repository != null) {
                                    scope.launch {
                                        runCatching { repository.signedAttachmentUrl(attachment.url.removePrefix("storage:")) }.onSuccess { url ->
                                            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), attachment.mimeType).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Open ${attachment.name}")) }
                                                .onFailure { cloudError = "No app can open this attachment" }
                                        }.onFailure { cloudError = friendlySupabaseError(it, "Could not open this attachment. Please try again.") }
                                    }
                                } else runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(serverUrl.trim().trimEnd('/') + attachment.url), attachment.mimeType).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(Intent.createChooser(intent, "Open ${attachment.name}"))
                                }.onFailure { connection = "No app can open this attachment" }
                            }
                        } }
                    }
                    if (cloudError.isNotBlank()) Text(cloudError, color = Color(0xFFFF8E8E), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                    Composer(value = text, onValue = { text = it }, onSend = {
                        val outgoing = text.trim()
                        if (outgoing.isNotEmpty() && selectedFriend != null) { text = ""; sendMessage(outgoing) }
                    }, onAttach = { anyFilePicker.launch("*/*") }, onCamera = { imagePicker.launch("image/*") }, onMic = { audioPicker.launch("audio/*") })
                }
            }
        }
    }
}

@Composable private fun SupabaseSetupScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.CloudOff, null, tint = Blue, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(18.dp)); Text("Connect your Supabase project", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text("Create store.properties from store.properties.example with your Supabase URL, publishable key, and app lock code. Then run the SQL migration in supabase/migrations and rebuild the app.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(14.dp)); Text("Never put a service_role key in the Android app.", color = Color(0xFFE4B86D), fontSize = 12.sp)
    }
}

@Composable private fun SessionRestoreScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Text("Restoring your saved login…", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

private fun friendlySupabaseError(error: Throwable, fallback: String): String {
    val detail = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return when {
        "invalid_credentials" in detail || "invalid login credentials" in detail ->
            "Email or password is incorrect. New here? Tap ‘Create an account’ first."
        "email_not_confirmed" in detail || "email not confirmed" in detail ->
            "Please confirm your email from your inbox, then sign in again."
        "user_already_exists" in detail || "already registered" in detail || "user already registered" in detail ->
            "This email already has an account. Switch to Sign in."
        "email_address_invalid" in detail || "invalid email" in detail ->
            "Enter a valid email address."
        "weak_password" in detail || "password should be" in detail || "password is too short" in detail ->
            "Choose a stronger password with at least 6 characters."
        ("username" in detail && ("duplicate" in detail || "unique" in detail || "already exists" in detail)) ->
            "That username is taken. Choose another username."
        "row-level security" in detail || "permission denied" in detail || "schema cache" in detail ||
            "does not exist" in detail || "could not find the table" in detail || "profiles" in detail && "relation" in detail ->
            "Store database setup is incomplete. Run the Supabase migration, then try again."
        "rate limit" in detail || "too many requests" in detail ->
            "Too many attempts. Wait a little and try again."
        "unknownhost" in detail || "timeout" in detail || "connectexception" in detail || "connection reset" in detail ->
            "Could not reach Supabase. Check your internet connection and try again."
        else -> fallback
    }
}

@Composable private fun AuthScreen(
    busy: Boolean, error: String, setError: (String) -> Unit, setBusy: (Boolean) -> Unit,
    onSignedIn: (String) -> Unit, repository: SupabaseRepository, scope: kotlinx.coroutines.CoroutineScope
) {
    var createAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }; var displayName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("Store", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp)); Text(if (createAccount) "Create your account" else "Welcome back", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Sign in to sync your friends and messages", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
        if (createAccount) {
            OutlinedTextField(displayName, { displayName = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp)); OutlinedTextField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(24) }, label = { Text("Username (3–24 chars)") }, prefix = { Text("@") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp)); OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = if (error.startsWith("Account created")) Color(0xFF65D7A1) else Color(0xFFFF8E8E), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 9.dp))
        Button(enabled = !busy, onClick = {
            if (email.isBlank() || password.length < 6 || (createAccount && (username.length < 3 || displayName.isBlank()))) { setError("Enter valid details. Password must be at least 6 characters."); return@Button }
            setBusy(true); setError("")
            scope.launch {
                runCatching {
                    if (createAccount) repository.signUp(email, password, username, displayName) else repository.signIn(email, password)
                }.onSuccess { id ->
                    setBusy(false)
                    if (id == null) {
                        createAccount = false
                        setError("Account created. Verify your email, then sign in here.")
                    } else onSignedIn(id)
                }.onFailure {
                    setBusy(false)
                    setError(friendlySupabaseError(it, if (createAccount) "Could not create your account. Check the details and try again." else "Could not sign in. Try again."))
                }
            }
        }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp), shape = RoundedCornerShape(15.dp)) { Text(if (busy) "Please wait…" else if (createAccount) "Create account" else "Sign in") }
        TextButton(onClick = { createAccount = !createAccount; setError("") }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (createAccount) "Already have an account? Sign in" else "New here? Create an account", color = Blue) }
    }
}

@Composable private fun FriendsScreen(repository: SupabaseRepository, userId: String, error: String, setError: (String) -> Unit, onSelect: (FriendProfile) -> Unit, onSignOut: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    var ownProfile by remember { mutableStateOf<FriendProfile?>(null) }; var friends by remember { mutableStateOf<List<FriendProfile>>(emptyList()) }
    var requests by remember { mutableStateOf<List<Pair<FriendRequestRow, FriendProfile>>>(emptyList()) }
    var query by remember { mutableStateOf("") }; var loading by remember { mutableStateOf(true) }
    fun reload() {
        scope.launch {
            runCatching {
                val incoming = repository.incomingRequests(userId)
                Triple(repository.profile(userId), repository.friends(userId), incoming.mapNotNull { request -> repository.profile(request.fromUser)?.let { request to it } })
            }.onSuccess { (profile, list, pending) -> ownProfile = profile; friends = list; requests = pending; setError("") }
                .onFailure { setError(friendlySupabaseError(it, "Could not load your friends. Please try again.")) }
            loading = false
        }
    }
    LaunchedEffect(userId) { reload() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your people", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(ownProfile?.let { "@${it.username}" } ?: if (loading) "Loading profile…" else "Profile unavailable", color = Muted, fontSize = 13.sp) }
            IconButton(onClick = onSignOut) { Icon(Icons.Default.Logout, "Sign out", tint = Muted) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("ADD A FRIEND", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), label = { Text("Search by username") }, singleLine = true, prefix = { Text("@") })
                    Spacer(Modifier.width(8.dp)); IconButton(enabled = query.trim().length >= 3, onClick = {
                        scope.launch {
                            runCatching { repository.searchUsername(query, userId) }.onSuccess { profile ->
                                if (profile == null) setError("No other user found with that username")
                                else runCatching { repository.sendFriendRequest(userId, profile.id) }.onSuccess { setError("Friend request sent to @${profile.username}") }.onFailure { setError(friendlySupabaseError(it, "Could not send the friend request.")) }
                            }.onFailure { setError(friendlySupabaseError(it, "Could not search for that username.")) }
                        }
                    }) { Icon(Icons.Default.PersonAdd, "Find friend", tint = Blue) }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = if (error.startsWith("Friend request sent")) Color(0xFF65D7A1) else Color(0xFFFF8E8E), fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        if (requests.isNotEmpty()) {
            Text("FRIEND REQUESTS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            requests.forEach { (request, profile) ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(profile.displayName, color = Ink); Text("@${profile.username}", color = Muted, fontSize = 11.sp) }
                        TextButton(onClick = { scope.launch { runCatching { repository.respondToRequest(request.id, userId, false) }.onSuccess { reload() }.onFailure { setError(friendlySupabaseError(it, "Could not decline this request.")) } } }) { Text("Decline") }
                        Button(onClick = { scope.launch { runCatching { repository.respondToRequest(request.id, userId, true) }.onSuccess { reload() }.onFailure { setError(friendlySupabaseError(it, "Could not accept this request.")) } } }, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp)) { Text("Accept") }
                    }
                }
            }
        }
        Text("FRIENDS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        if (loading) CircularProgressIndicator(Modifier.size(25.dp), color = Blue)
        else if (friends.isEmpty() && error.isBlank()) Text("No friends yet. Search a username above to connect.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(friends) { friend ->
                Card(Modifier.fillMaxWidth().clickable { onSelect(friend) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF263D56)), contentAlignment = Alignment.Center) { Text(friend.displayName.take(1).uppercase(), color = Color(0xFFDDEAFF), fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(friend.displayName, color = Ink, fontWeight = FontWeight.SemiBold); Text("@${friend.username}", color = Muted, fontSize = 12.sp) }
                        Icon(Icons.AutoMirrored.Filled.Send, null, tint = Blue, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun Header(status: String, onSettings: () -> Unit, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).statusBarsPadding().padding(horizontal = 7.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {}, modifier = Modifier.size(42.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(0xFF91A8C3)) }
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF30445A)), contentAlignment = Alignment.Center) {
            Text("M", color = Color(0xFFDDEAFF), fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
            Box(Modifier.align(Alignment.BottomEnd).padding(1.dp).size(12.dp).clip(CircleShape).background(if (status.startsWith("Connected")) Color(0xFF35C98A) else Color(0xFFDAA854)).border(2.dp, Panel, CircleShape))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("Mira", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(if (status.startsWith("Connected")) "Laptop server connected" else "Connecting to laptop…", fontSize = 11.sp, color = Muted)
        }
        IconButton(onClick = onCall, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Videocam, "Video call preview", tint = Color(0xFF9BB5D2), modifier = Modifier.size(22.dp)) }
        IconButton(onClick = onCall, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Call, "Voice call preview", tint = Color(0xFF9BB5D2), modifier = Modifier.size(20.dp)) }
        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "Settings", tint = Color(0xFF9BB5D2)) }
    }
}

@Composable private fun TrustBanner(status: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF30291F)).padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Lock, null, tint = Color(0xFFE4B86D), modifier = Modifier.size(15.dp))
        Text("  Prototype · messages aren't encrypted", color = Color(0xFFD7C29F), fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(if (status.startsWith("Connected")) "LIVE" else "WAIT", color = if (status.startsWith("Connected")) Color(0xFF65D7A1) else Color(0xFFE4B86D), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable private fun DayDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.width(30.dp), color = Color(0xFF354455))
        Text("  TODAY  ", color = Muted, fontSize = 10.sp, letterSpacing = 1.1.sp)
        HorizontalDivider(Modifier.width(30.dp), color = Color(0xFF354455))
    }
}

@Composable private fun EmptyConversation() {
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(25.dp)).background(Color(0xFF1C2E41)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Forum, null, tint = Blue, modifier = Modifier.size(31.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Your little corner", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("Send a hello to start the conversation.", color = Muted, fontSize = 13.sp)
    }
}

@Composable private fun ActivationScreen(code: String, onCode: (String) -> Unit, invalid: Boolean, onUnlock: () -> Unit, onSelfieStatus: (String) -> Unit) {
    var showActivation by remember { mutableStateOf(false) }
    var showSelfieConsent by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) scope.launch(Dispatchers.IO) {
            val result = runCatching { saveSelfieToGallery(context, bitmap) }
            withContext(Dispatchers.Main) {
                onSelfieStatus(if (result.isSuccess) "Selfie saved to Gallery." else "Could not save the selfie to Gallery.")
            }
        } else onSelfieStatus("No selfie was saved.")
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null) else onSelfieStatus("Gallery permission was declined; no selfie was saved.")
    }
    LaunchedEffect(invalid) { if (invalid) showSelfieConsent = true }
    val oldText = Color(0xFF090909)
    Box(Modifier.fillMaxSize().background(Color(0xFF777977)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Icon(Icons.Default.Android, "Android", tint = Color(0xFF739341), modifier = Modifier.padding(top = 4.dp).size(27.dp))
            Text("Webpage not available", color = oldText, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            Text("The webpage at file:///android_asset/www/home/home.html?userid=91 might be temporarily down or it may have moved permanently to a new web address.", color = oldText, fontSize = 17.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 22.dp))
            Text("Suggestions:", color = oldText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
            Text("•  Make sure you have a data connection\n•  Reload this webpage later\n•  Check the address you entered", color = oldText, fontSize = 16.sp, lineHeight = 28.sp, modifier = Modifier.padding(start = 35.dp, top = 17.dp))
        }
        Box(Modifier.fillMaxSize().background(Color(0x76000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 34.dp).border(1.dp, Color(0xFF777777), RoundedCornerShape(2.dp)).background(Color(0xFF050505)).padding(top = 0.dp)) {
                Text(if (showActivation) "Premium Activation" else "Application Error", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Normal, modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 13.dp))
                HorizontalDivider(color = Color(0xFF282828), thickness = 1.dp)
                if (showActivation) {
                    Text("Enter your premium activation code to continue.", color = Color.White, fontSize = 18.sp, lineHeight = 24.sp, modifier = Modifier.padding(horizontal = 21.dp, vertical = 18.dp))
                    OutlinedTextField(value = code, onValueChange = onCode, label = { Text("Activation code", color = Color.LightGray) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), shape = RoundedCornerShape(2.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.LightGray, unfocusedBorderColor = Color.Gray, cursorColor = Color.White), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    if (invalid) Text("Invalid activation code.", color = Color(0xFFFF9B9B), fontSize = 13.sp, modifier = Modifier.padding(start = 21.dp, top = 6.dp))
                } else {
                    Text("A network error occurred. (file:///android_asset/www/home/home.html?userid=91)", color = Color.White, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(horizontal = 21.dp, vertical = 20.dp).padding(bottom = 13.dp))
                }
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFFBDBDBD), Color(0xFF929292)))).padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.fillMaxWidth().height(43.dp).border(1.dp, Color(0xFF777777), RoundedCornerShape(2.dp)).background(Brush.verticalGradient(listOf(Color(0xFFE8E8E8), Color(0xFFCECECE)))).clickable {
                        if (showActivation) onUnlock() else showActivation = true
                    }, contentAlignment = Alignment.Center) {
                        Text("OK", color = Color.Black, fontSize = 17.sp)
                    }
                }
            }
        }
        if (showSelfieConsent) {
            AlertDialog(
                onDismissRequest = { showSelfieConsent = false },
                title = { Text("Wrong activation code") },
                text = { Text("Would you like to open the camera and take a selfie? It will be saved to this device’s Gallery only if you take the photo. Nothing is captured unless you continue.") },
                confirmButton = {
                    TextButton(onClick = {
                        showSelfieConsent = false
                        onSelfieStatus("")
                        if (Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else cameraLauncher.launch(null)
                    }) { Text("Take selfie") }
                },
                dismissButton = { TextButton(onClick = { showSelfieConsent = false }) { Text("Not now") } },
                containerColor = Panel,
                titleContentColor = Ink,
                textContentColor = Muted
            )
        }
    }
}

private fun saveSelfieToGallery(context: Context, bitmap: Bitmap): Uri {
    val timestamp = System.currentTimeMillis()
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Store-selfie-$timestamp.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Store")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Gallery could not create an image")
    try {
        resolver.openOutputStream(uri)?.use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) error("Image encoding failed")
        } ?: error("Gallery could not open the image")
        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

@Composable private fun Bubble(message: Message, onAttachment: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
        Box(Modifier.widthIn(max = 310.dp).clip(if (message.mine) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)).background(if (message.mine) Brush.verticalGradient(listOf(Color(0xFF4299F5), Color(0xFF355FE2))) else Brush.verticalGradient(listOf(Incoming, Incoming))).padding(horizontal = 15.dp, vertical = 12.dp)) {
            Column {
                if (message.text.isNotBlank()) Text(message.text, color = Ink, fontSize = 15.sp, lineHeight = 21.sp)
                message.attachment?.let { attachment ->
                    if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x263B81D9)).clickable(onClick = onAttachment).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when { attachment.mimeType.startsWith("image/") -> Icons.Default.Image; attachment.mimeType.startsWith("audio/") -> Icons.Default.AudioFile; else -> Icons.AutoMirrored.Filled.InsertDriveFile }, null, tint = Color.White, modifier = Modifier.size(25.dp))
                        Column(Modifier.padding(start = 9.dp).weight(1f)) {
                            Text(attachment.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text("${attachment.mimeType.substringBefore('/')} · ${formatBytes(attachment.size)} · tap to open", color = Color(0xFFD9E8FF), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Text(message.time, Modifier.padding(top = 4.dp, start = 5.dp, end = 5.dp), color = Muted, fontSize = 10.sp)
    }
}

private fun formatBytes(size: Long): String = if (size < 1024) "$size B" else if (size < 1024 * 1024) "${size / 1024} KB" else String.format(Locale.getDefault(), "%.1f MB", size / (1024f * 1024f))

private fun Modifier.lockOnThreeFingerTap(onLock: () -> Unit): Modifier = pointerInput(onLock) {
    awaitEachGesture {
        var alreadyLocked = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!alreadyLocked && event.changes.count { it.pressed } >= 3) {
                alreadyLocked = true
                onLock()
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable private fun Composer(value: String, onValue: (String) -> Unit, onSend: () -> Unit, onAttach: () -> Unit, onCamera: () -> Unit, onMic: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).border(1.dp, Color(0xFF344254), RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1A2532)).padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAttach, modifier = Modifier.size(39.dp)) { Icon(Icons.Default.Add, "Attach a file", tint = Muted) }
            IconButton(onClick = onCamera, modifier = Modifier.size(39.dp)) { Icon(Icons.Default.CameraAlt, "Attach an image", tint = Muted, modifier = Modifier.size(20.dp)) }
            BasicTextField(value, onValue, Modifier.weight(1f).padding(horizontal = 5.dp), textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 15.sp), decorationBox = { inner -> if (value.isEmpty()) Text("Message", color = Muted, fontSize = 15.sp); inner() })
            IconButton(onClick = onMic, modifier = Modifier.size(39.dp)) { Icon(Icons.Default.MicNone, "Attach audio", tint = Muted, modifier = Modifier.size(21.dp)) }
        }
        Spacer(Modifier.width(9.dp)); IconButton(onClick = onSend, modifier = Modifier.size(48.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF4299F5), Color(0xFF355FE2))))) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}

@Composable private fun BottomBar(onPair: () -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Chats", color = Blue, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Spacer(Modifier.weight(1f))
        Text("PAIR DEVICE", Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onPair).padding(horizontal = 12.dp, vertical = 9.dp), color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable private fun PairScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("‹  Back", Modifier.clickable(onClick = onBack), color = Blue, fontSize = 16.sp); Spacer(Modifier.height(40.dp))
        Text("Pair a device", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink); Spacer(Modifier.height(10.dp))
        Text("Private pairing and device verification are not set up yet.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(28.dp)); Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(22.dp)) { Text("Pairing will be added after secure identity setup.", color = Ink) }
    }
}

@Composable private fun SettingsScreen(server: String, status: String, onServer: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("‹  Back", Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 8.dp), color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(26.dp))
        Text("Make it yours", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Connect this app to your laptop", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, null, tint = Blue, modifier = Modifier.size(20.dp))
                    Text("  LAPTOP SERVER", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                }
                Spacer(Modifier.height(13.dp))
                Text("On your phone, use your laptop's Wi-Fi address:", color = Ink, fontSize = 13.sp)
                Text("http://192.168.1.35:8787", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                OutlinedTextField(value = server, onValueChange = onServer, label = { Text("Server URL") }, singleLine = true, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSave, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Save and connect") }
                Text(status, color = if (status.startsWith("Connected")) Color(0xFF55D69A) else Color(0xFFE4B86D), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF30291F)), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, null, tint = Color(0xFFE4B86D), modifier = Modifier.size(18.dp))
                Text("Prototype only: messages are readable on the network and server. Keep testing on trusted Wi-Fi; pairing and encryption are not connected yet.", color = Color(0xFFD7C29F), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable private fun CallScreen(onEnd: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF17221E)).statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp)); Box(Modifier.size(94.dp).clip(CircleShape).background(Color(0xFF344B41)), contentAlignment = Alignment.Center) { Text("M", fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.Light) }
        Spacer(Modifier.height(18.dp)); Text("Mira", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium); Text("Call preview · not connected", color = Color(0xFFB2C3BA), fontSize = 14.sp)
        Spacer(Modifier.weight(1f)); Row(horizontalArrangement = Arrangement.spacedBy(25.dp)) { CallControl(Icons.Default.MicOff, "Mute"); CallControl(Icons.AutoMirrored.Filled.VolumeUp, "Speaker"); CallControl(Icons.Default.Videocam, "Video") }
        Spacer(Modifier.height(36.dp)); IconButton(onClick = onEnd, modifier = Modifier.size(62.dp).clip(CircleShape).background(Color(0xFFD95757))) { Icon(Icons.Default.CallEnd, "End call", tint = Color.White) }; Spacer(Modifier.height(26.dp))
    }
}

@Composable private fun CallControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF344B41)), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White) }; Text(label, color = Color(0xFFB2C3BA), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
}
