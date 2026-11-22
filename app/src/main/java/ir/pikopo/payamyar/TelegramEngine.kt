package ir.pikopo.payamyar

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Personal Telegram client using TDLib. No bots, scraping, or evasion behavior.
 * Submission is sequential. Messages are scheduled by Telegram's own server.
 */
class TelegramEngine(
    private val context: Context,
    private val store: CampaignStore,
    private val emit: (String, JSONObject) -> Unit
) {
    private var client: Client? = null
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var ready = false
    private var credentials: Pair<Int, String>? = null
    private var pendingPhone: String? = null
    // All authentication state and requests are serialized on the Android main thread.
    // A TDLib auth request must not be issued again while its stage is in progress.
    private var authStage = "initial"
    private var authInFlight: String? = null
    private var accountId: Long = 0
    private var stopped = false
    private var sentThisRun = 0

    fun isReady() = ready
    fun start(id: Int, hash: String, phone: String = "") {
        if (id <= 0 || hash.length !in 20..128) { error("شناسه API یا API Hash نامعتبر است."); return }
        if (client != null) {
            if (credentials != (id to hash)) {
                error("اطلاعات API با نشست در حال اجرا تفاوت دارد. ابتدا اتصال فعلی را بازنشانی کن.")
                return
            }
            // A second press of Connect can only queue the phone number, never
            // start a second authorization query before TDLib asks for it.
            if (phone.isNotBlank()) sendPhone(phone)
            else emit("auth", JSONObject().put("stage", authStage))
            return
        }
        credentials = id to hash
        pendingPhone = phone.takeIf { it.isNotBlank() }
        authStage = "initial"
        authInFlight = null
        try {
            System.loadLibrary("tdjni")
            client = Client.create({ update -> onUpdate(update) }, null, null)
            emit("info", JSONObject().put("message", "در حال برقراری اتصال امن به تلگرام..."))
        } catch (ex: Throwable) {
            error("کتابخانه TDLib اجرا نشد: ${ex.message ?: "خطای ناشناخته"}")
        }
    }
    private fun send(request: TdApi.Function<*>, callback: (TdApi.Object) -> Unit) {
        val c = client ?: return error("اتصال تلگرام برقرار نیست")
        c.send(request) { result -> callback(result) }
    }
    private fun onUpdate(update: TdApi.Object) {
        // TDLib callbacks may run on different threads. Keep login transitions
        // ordered with the UI bridge, which is also dispatched to the main thread.
        handler.post {
            when (update) {
                is TdApi.UpdateAuthorizationState -> onAuth(update.authorizationState)
                is TdApi.UpdateConnectionState -> emit("connection", JSONObject().put("state", update.state.javaClass.simpleName))
            }
        }
    }
    private fun onAuth(state: TdApi.AuthorizationState) {
        val nextStage = when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> "parameters"
            is TdApi.AuthorizationStateWaitPhoneNumber -> "phone"
            is TdApi.AuthorizationStateWaitCode -> "code"
            is TdApi.AuthorizationStateWaitPassword -> "password"
            is TdApi.AuthorizationStateWaitEmailAddress -> "email"
            is TdApi.AuthorizationStateWaitEmailCode -> "emailCode"
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> "device"
            is TdApi.AuthorizationStateWaitRegistration -> "registration"
            is TdApi.AuthorizationStateReady -> "ready"
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> "disconnected"
            else -> "other"
        }
        if (authStage != nextStage) {
            authStage = nextStage
            authInFlight = null
        }
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val cfg = credentials ?: return error("اطلاعات اتصال پیدا نشد")
                val p = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = File(context.filesDir, "tdlib/db").absolutePath
                    filesDirectory = File(context.filesDir, "tdlib/files").absolutePath
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = cfg.first
                    apiHash = cfg.second
                    systemLanguageCode = "fa"
                    deviceModel = Build.MODEL
                    systemVersion = "Android ${Build.VERSION.RELEASE}"
                    applicationVersion = "1.1.0-beta"
                }
                beginAuth("parameters", p)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                emit("auth", JSONObject().put("stage", "phone"))
                pendingPhone?.let { number ->
                    pendingPhone = null
                    sendPhone(number)
                }
            }
            is TdApi.AuthorizationStateWaitCode -> emit("auth", JSONObject().put("stage", "code"))
            is TdApi.AuthorizationStateWaitPassword -> emit("auth", JSONObject().put("stage", "password"))
            is TdApi.AuthorizationStateWaitEmailAddress -> emit("auth", JSONObject().put("stage", "email"))
            is TdApi.AuthorizationStateWaitEmailCode -> emit("auth", JSONObject().put("stage", "emailCode"))
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> emit("auth", JSONObject()
                .put("stage", "device").put("link", state.link))
            is TdApi.AuthorizationStateWaitRegistration -> emit("auth", JSONObject().put("stage", "registration"))
            is TdApi.AuthorizationStateReady -> {
                ready = true
                send(TdApi.GetMe()) { result ->
                    if (result is TdApi.User) {
                        accountId = result.id
                        store.forAccount(accountId)
                        emit("ready", JSONObject().put("accountId", result.id.toString())
                            .put("name", (result.firstName + " " + result.lastName).trim()))
                        emit("campaign", store.summary())
                        loadContacts()
                    } else handleError(result)
                }
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> {
                ready = false
                emit("auth", JSONObject().put("stage", "disconnected"))
            }
            is TdApi.AuthorizationStateClosed -> {
                ready = false; client = null; credentials = null
                pendingPhone = null
                authInFlight = null
                emit("auth", JSONObject().put("stage", "disconnected"))
            }
            else -> emit("auth", JSONObject().put("stage", "other").put("detail", state.javaClass.simpleName))
        }
    }
    private fun beginAuth(stage: String, request: TdApi.Function<*>) {
        if (authStage != stage) {
            error("تلگرام هنوز آماده دریافت این اطلاعات نیست. مرحله ورود فعلی را بررسی کن.")
            return
        }
        if (authInFlight != null) {
            emit("info", JSONObject().put("message", "درخواست ورود قبلی در حال بررسی است؛ دوباره ارسال نکن."))
            return
        }
        authInFlight = stage
        send(request) { response ->
            handler.post {
                // A successful query advances the authorization state. Keep the
                // guard held until TDLib actually reports the next state.
                if (response is TdApi.Error && authStage == stage && authInFlight == stage) {
                    authInFlight = null
                    if (response.message.contains("Another authorization query has started", ignoreCase = true)) {
                        error("درخواست ورود قبلی هنوز در تلگرام در حال پردازش است. چند لحظه بعد دوباره امتحان کن.")
                    } else {
                        handleError(response)
                    }
                    emit("auth", JSONObject().put("stage", stage))
                }
            }
        }
    }

    fun sendPhone(phone: String) {
        val normalized = phone.trim()
        if (!normalized.matches(Regex("\\+[1-9][0-9]{6,14}"))) {
            error("شماره را با کد کشور، مثل +98912... وارد کن.")
            return
        }
        if (authStage == "initial" || authStage == "parameters") {
            pendingPhone = normalized
            emit("info", JSONObject().put("message", "شماره ثبت شد؛ پس از آماده‌شدن تلگرام ارسال می‌شود."))
            return
        }
        beginAuth("phone", TdApi.SetAuthenticationPhoneNumber(normalized, null))
    }
    fun sendCode(code: String) {
        if (code.isBlank()) return error("کد تأیید را وارد کن.")
        beginAuth("code", TdApi.CheckAuthenticationCode(code.trim()))
    }
    fun sendPassword(password: String) {
        if (password.isBlank()) return error("رمز دومرحله‌ای را وارد کن.")
        beginAuth("password", TdApi.CheckAuthenticationPassword(password))
    }
    fun sendEmail(email: String) {
        if (email.isBlank()) return error("ایمیل را وارد کن.")
        beginAuth("email", TdApi.SetAuthenticationEmailAddress(email.trim()))
    }
    fun sendEmailCode(code: String) {
        if (code.isBlank()) return error("کد ایمیل را وارد کن.")
        beginAuth("emailCode", TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code.trim())))
    }

    /**
     * Sync both Telegram contacts AND people from existing private dialogs.
     * The v1 UI only used GetContacts, which misses people the user has chatted with
     * but never saved to Telegram contacts. All requests run through TDLib; the
     * phone address book is not read or uploaded.
     */
    private val syncGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    fun loadContacts() {
        if (!ready) { error("ابتدا به حساب تلگرام وارد شو."); return }
        val generation = syncGeneration.incrementAndGet()
        emit("loading", JSONObject().put("kind", "contacts").put("message", "در حال دریافت مخاطبان و گفتگوها..."))
        send(TdApi.GetContacts()) { contactResult ->
            if (generation != syncGeneration.get()) return@send
            val savedIds = if (contactResult is TdApi.Users) contactResult.userIds.distinct().take(3000) else emptyList()
            // Prime the main-chat cache so recent conversations are discoverable
            // even on a freshly authorized device.
            send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { _ ->
            send(TdApi.GetChats(TdApi.ChatListMain(), 500)) { chatResult ->
                if (generation != syncGeneration.get()) return@send
                val chatIds = if (chatResult is TdApi.Chats) chatResult.chatIds.distinct().take(500) else emptyList()
                val priorIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
                // A bounded number of TDLib callbacks are in flight at once.
                parallelWalk(chatIds.size, 8, { index, done ->
                    send(TdApi.GetChat(chatIds[index])) { chat ->
                        if (chat is TdApi.Chat && chat.type is TdApi.ChatTypePrivate) {
                            val userId = (chat.type as TdApi.ChatTypePrivate).userId
                            if (userId != accountId) priorIds.add(userId)
                        }
                        done()
                    }
                }) {
                    if (generation != syncGeneration.get()) return@parallelWalk
                    val contactSet = savedIds.toHashSet()
                    val allIds = (savedIds + priorIds).distinct().take(3500)
                    val found = java.util.concurrent.ConcurrentHashMap<Long, JSONObject>()
                    parallelWalk(allIds.size, 10, { index, done ->
                        val id = allIds[index]
                        send(TdApi.GetUser(id)) { user ->
                            if (user is TdApi.User && user.type !is TdApi.UserTypeBot &&
                                user.type !is TdApi.UserTypeDeleted && user.id != accountId) {
                                val name = (user.firstName + " " + user.lastName).trim()
                                    .ifBlank { "مخاطب ${user.id}" }
                                found[user.id] = JSONObject()
                                    .put("id", user.id.toString())
                                    .put("name", name)
                                    .put("source", if (contactSet.contains(user.id)) "contact" else "chat")
                            }
                            done()
                        }
                    }) {
                        if (generation != syncGeneration.get()) return@parallelWalk
                        val ordered = found.values.sortedWith(
                            compareBy<JSONObject> { it.optString("source") != "contact" }
                                .thenBy { it.optString("name").lowercase(Locale.getDefault()) }
                        )
                        val result = JSONArray()
                        ordered.forEach { result.put(it) }
                        emit("contacts", JSONObject().put("items", result)
                            .put("savedCount", ordered.count { it.optString("source") == "contact" })
                            .put("chatCount", ordered.count { it.optString("source") == "chat" }))
                        if (contactResult is TdApi.Error && chatResult is TdApi.Error) {
                            error("دریافت مخاطبان و گفتگوها ناموفق بود. اتصال اینترنت و وضعیت ورود را بررسی و دوباره همگام‌سازی کن.")
                        }
                    }
                }
            }
            }
        }
    }

    /** Execute asynchronous TDLib lookups with bounded concurrency, then finish once. */
    private fun parallelWalk(
        size: Int,
        width: Int,
        action: (Int, () -> Unit) -> Unit,
        onComplete: () -> Unit
    ) {
        if (size == 0) { onComplete(); return }
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        fun launchOne(continuation: () -> Unit) {
            val index = next.getAndIncrement()
            if (index >= size) return
            action(index) {
                if (completed.incrementAndGet() == size) onComplete()
                else continuation()
            }
        }
        // The callbacks recurse only once the previous operation has completed.
        lateinit var worker: () -> Unit
        worker = { launchOne(worker) }
        repeat(minOf(width, size)) { worker() }
    }

    /** Input rows: [{id:string,name:string,text:string}], startMs >= 5 minutes in future, rate 1..20. */
    fun submitCampaign(payload: JSONObject) {
        if (!ready) { error("حساب تلگرام متصل نیست."); return }
        if (busy.get() || store.isActive()) { error("یک کمپین قبلی هنوز فعال است؛ ابتدا وضعیت یا لغو آن را بررسی کن."); return }
        if (!payload.optBoolean("confirmed", false)) { error("تأیید نهایی ارسال دریافت نشده است."); return }
        if (!payload.optBoolean("permissionConfirmed", false)) { error("تأیید انتظار دریافت پیام توسط مخاطبان لازم است."); return }
        val rate = payload.optInt("rate", 0)
        if (rate !in 1..20) { error("سقف در ساعت باید بین ۱ تا ۲۰ باشد."); return }
        val items = payload.optJSONArray("rows") ?: return error("فهرست مخاطبان خالی است.")
        if (items.length() !in 1..500) { error("در هر کمپین ۱ تا ۵۰۰ مخاطب پشتیبانی می‌شود."); return }
        val start = payload.optLong("startMs", 0)
        val now = System.currentTimeMillis()
        if (start < now + 5 * 60_000L || start > now + 300L * 24 * 60 * 60 * 1000) {
            error("زمان شروع باید حداقل ۵ دقیقه بعد و حداکثر ۳۰۰ روز آینده باشد."); return
        }
        val outgoing = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until items.length()) {
            val src = items.optJSONObject(i) ?: return error("اطلاعات مخاطب ناقص است")
            val id = src.optString("id")
            val message = src.optString("text").trim()
            if (!id.matches(Regex("[1-9][0-9]{2,19}")) || !seen.add(id)) {
                error("شناسه تکراری یا نامعتبر در مخاطبان یافت شد."); return
            }
            if (message.isEmpty() || message.length > 4096) { error("طول متن باید بین ۱ تا ۴۰۹۶ نویسه باشد."); return }
            val epoch = (start + i * 3_600_000L / rate) / 1000L
            outgoing.put(JSONObject().put("id", id).put("name", src.optString("name"))
                .put("text", message).put("epoch", epoch).put("status", "pending"))
        }
        if (start + items.length() * 3_600_000L / rate > now + 310L * 24 * 60 * 60 * 1000) {
            error("پایان صف از محدوده مجاز زمانی فراتر می‌رود."); return
        }
        store.replace(payload.optString("name", "کمپین جدید"), outgoing)
        busy.set(true); stopped = false; sentThisRun = 0
        emit("campaign", store.summary())
        handler.post { processNext() }
    }
    private fun processNext() {
        if (stopped || !busy.get() || !ready) { busy.set(false); return }
        val index = store.findPending() ?: run { busy.set(false); emit("campaign", store.summary()); return }
        val item = store.item(index)
        val recipient = item.getString("id").toLongOrNull() ?: return fail(index, "شناسه مخاطب نامعتبر است")
        val text = item.getString("text")
        val epoch = item.getLong("epoch").toInt()
        if (epoch * 1000L <= System.currentTimeMillis() + 60_000L) {
            return fail(index, "زمان این پیام گذشته است؛ هیچ ارسال فوری انجام نشد.")
        }
        store.set(index, "submitting")
        emit("campaign", store.summary())
        send(TdApi.CreatePrivateChat(recipient, false)) { result ->
            if (result !is TdApi.Chat) { fail(index, "دریافت گفتگوی خصوصی ناموفق بود: ${getError(result)}"); return@send }
            val options = TdApi.MessageSendOptions().apply {
                schedulingState = TdApi.MessageSchedulingStateSendAtDate().apply {
                    sendDate = epoch
                    repeatPeriod = 0
                }
            }
            val content = TdApi.InputMessageText(TdApi.FormattedText(text, null), null, true)
            send(TdApi.SendMessage(result.id, null, null, options, null, content)) { outcome ->
                if (outcome is TdApi.Message) {
                    store.set(index, "scheduled", result.id, outcome.id)
                    sentThisRun++
                    if (stopped) {
                        // Cancellation can race with an in-flight scheduling request.
                        // Remove any server-side item that was accepted after cancellation.
                        send(TdApi.DeleteMessages(result.id, longArrayOf(outcome.id), true)) { deleted ->
                            if (deleted is TdApi.Ok) store.set(index, "cancelled")
                            else store.set(index, "verify", error = "لغو ثبت در حال اجرا تأیید نشد")
                            emit("campaign", store.summary())
                        }
                        return@send
                    }
                    emit("campaign", store.summary())
                    // Throttle *registration requests*, not the actual Telegram delivery schedule.
                    handler.postDelayed({ processNext() }, 1500)
                } else fail(index, "تلگرام زمان‌بندی را نپذیرفت: ${getError(outcome)}")
            }
        }
    }
    private fun fail(index: Int, reason: String) {
        store.set(index, "failed", error = reason)
        stopped = true
        busy.set(false)
        emit("error", JSONObject().put("message", "$reason — صف متوقف شد؛ پیام‌های تأییدشده قبلی همچنان زمان‌بندی هستند."))
        emit("campaign", store.summary())
    }
    /** Stop submitting AND attempt to delete scheduled server-side messages from this campaign. */
    fun cancelCampaign() {
        stopped = true
        busy.set(false)
        store.cancelPending()
        val scheduled = (0 until store.count()).filter { store.item(it).optString("status") == "scheduled" }
        if (!ready || scheduled.isEmpty()) { emit("campaign", store.summary()); return }
        for (index in scheduled) {
            val row = store.item(index)
            val chat = row.optString("chatId").toLongOrNull()
            val msg = row.optString("messageId").toLongOrNull()
            if (chat == null || msg == null) { store.set(index, "verify", error = "شناسه حذف کامل نیست"); continue }
            send(TdApi.DeleteMessages(chat, longArrayOf(msg), true)) { result ->
                if (result is TdApi.Ok) store.set(index, "cancelled")
                else store.set(index, "verify", error = "حذف تأیید نشد: ${getError(result)}")
                emit("campaign", store.summary())
            }
        }
        emit("campaign", store.summary())
    }
    fun summary() = emit("campaign", store.summary())
    fun resetConnection(clearCredentials: () -> Unit) {
        if (busy.get() || store.isActive()) {
            error("تا وقتی پیام زمان‌بندی‌شده فعال است، اتصال را بازنشانی نکن."); return
        }
        stopped = true
        clearCredentials()
        ready = false
        emit("logout", JSONObject())
        if (client != null) send(TdApi.Close()) { handleError(it) }
        else { credentials = null; emit("auth", JSONObject().put("stage", "disconnected")) }
    }
    fun logout(clearSensitiveData: () -> Unit) {
        if (busy.get() || store.isActive()) {
            error("ابتدا زمان‌بندی‌های فعال را لغو یا تعیین تکلیف کن؛ خروج، امکان مدیریت صف فعال را از بین می‌برد."); return
        }
        stopped = true; busy.set(false); ready = false
        store.clear(); clearSensitiveData()
        emit("logout", JSONObject())
        send(TdApi.LogOut()) { handleError(it) }
    }
    private fun getError(o: TdApi.Object): String = if (o is TdApi.Error) "${o.code}: ${o.message}" else o.javaClass.simpleName
    private fun handleError(o: TdApi.Object) { if (o is TdApi.Error) error("تلگرام: ${o.code} — ${o.message}") }
    private fun error(message: String) = emit("error", JSONObject().put("message", message))
}
