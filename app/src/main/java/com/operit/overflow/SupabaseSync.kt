package com.operit.overflow

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supabase backend sync for pet state.
 * Replace SUPABASE_URL and SUPABASE_KEY with your own values.
 * Alternatively, swap this with any REST backend.
 */
class SupabaseSync {

    // TODO: Replace with your Supabase project URL and anon key
    companion object {
        private const val SUPABASE_URL = "https://your-project.supabase.co"
        private const val SUPABASE_KEY = "your-anon-key"
        private const val ENABLED = false // Set to true after configuring
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun reportGesture(type: String) {
        if (!ENABLED) return
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("gesture_type", type)
                    put("x", 0)
                    put("y", 0)
                }
                postToSupabase("gesture_log", body)
            } catch (_: Exception) {}
        }
    }

    fun reportAppUsage(packageName: String) {
        if (!ENABLED) return
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("package_name", packageName)
                }
                postToSupabase("app_usage", body)
            } catch (_: Exception) {}
        }
    }

    suspend fun pollState(): Map<String, String>? {
        if (!ENABLED) return null
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/pet_state?order=updated_at.desc&limit=5")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("Content-Type", "application/json")

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                val arr = org.json.JSONArray(response)
                val result = mutableMapOf<String, String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    result[obj.getString("state_key")] = obj.optString("state_value", "")
                }
                result
            } catch (_: Exception) { null }
        }
    }

    private fun postToSupabase(table: String, body: JSONObject) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/$table")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}