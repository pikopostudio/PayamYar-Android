package ir.pikopo.payamyar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** App-private, no-backup journal: the native side never retries an ambiguous in-flight message. */
class CampaignStore(context: Context) {
    private val prefs = context.getSharedPreferences("campaign_journal", Context.MODE_PRIVATE)
    private var accountId: Long = 0
    private var rows = JSONArray()
    private var name = ""
    init { restore() }

    @Synchronized private fun restore() {
        val data = try { JSONObject(prefs.getString("journal", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        accountId = data.optLong("accountId")
        name = data.optString("name")
        rows = data.optJSONArray("rows") ?: JSONArray()
        // A previous process may have quit while waiting for TDLib's response.
        // Never resend such a request automatically: its server outcome is unknown.
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("status") == "submitting") row.put("status", "verify")
        }
        persist()
    }
    @Synchronized fun forAccount(id: Long) {
        if (id != accountId) {
            accountId = id; rows = JSONArray(); name = ""; persist()
        }
    }
    @Synchronized fun replace(campaignName: String, newRows: JSONArray) {
        name = campaignName; rows = JSONArray(newRows.toString()); persist()
    }
    @Synchronized fun count(): Int = rows.length()
    @Synchronized fun item(index: Int): JSONObject = JSONObject(rows.getJSONObject(index).toString())
    @Synchronized fun set(index: Int, status: String, chatId: Long = 0, messageId: Long = 0, error: String = "") {
        val row = rows.getJSONObject(index)
        row.put("status", status)
        if (chatId != 0L) row.put("chatId", chatId.toString())
        if (messageId != 0L) row.put("messageId", messageId.toString())
        if (error.isNotBlank()) row.put("error", error.take(240))
        persist()
    }
    @Synchronized fun cancelPending() {
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString("status") == "pending")
            rows.getJSONObject(i).put("status", "cancelled")
        persist()
    }
    @Synchronized fun refreshTemporalStatuses() {
        var changed = false
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("status") == "scheduled" && row.optLong("epoch") * 1000L <= System.currentTimeMillis()) {
                // Elapsed is NOT proof of delivery. Never delete a potentially already sent message.
                row.put("status", "elapsed"); changed = true
            }
        }
        if (changed) persist()
    }
    @Synchronized fun isActive(): Boolean {
        refreshTemporalStatuses()
        return (0 until rows.length()).any {
        rows.getJSONObject(it).optString("status") in setOf("pending", "submitting", "verify", "scheduled")
        }
    }
    @Synchronized fun findPending(): Int? = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("status") == "pending" }
    @Synchronized fun summary(): JSONObject {
        refreshTemporalStatuses()
        val counts = JSONObject()
        for (i in 0 until rows.length()) {
            val s = rows.getJSONObject(i).optString("status")
            counts.put(s, counts.optInt(s) + 1)
        }
        return JSONObject().put("name", name).put("total", rows.length()).put("counts", counts)
            .put("rows", JSONArray(rows.toString()))
    }
    @Synchronized fun clear() { accountId = 0; name = ""; rows = JSONArray(); persist() }
    @Synchronized private fun persist() {
        prefs.edit().putString("journal", JSONObject().put("accountId", accountId.toString())
            .put("name", name).put("rows", rows).toString()).apply()
    }
}
