package com.willykez.campconnect.data.auth

import com.willykez.campconnect.BuildConfig
import com.willykez.campconnect.data.model.AccountStatus
import com.willykez.campconnect.data.model.Role
import com.willykez.campconnect.data.model.UserProfile
import com.willykez.campconnect.data.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Real sign-in against Supabase (step 1 of going live).
 *
 * Email + password go to Supabase Auth; the person's row in `profiles` is then read with their own
 * token (row-level security decides what they may see) and turned into a [UserProfile].
 * No password is stored on the phone. The rest of the app still uses local data until the
 * repositories are migrated.
 */
class LiveAuth(private val client: SupabaseClient = SupabaseClient()) {

  sealed interface Outcome {
    data class Ok(val profile: UserProfile) : Outcome
    data class Fail(val error: AuthError) : Outcome
  }

  @Volatile private var accessToken: String? = null
  @Volatile private var refreshToken: String? = null

  /** True when this build has real Supabase settings and is not a demo build. */
  val isAvailable: Boolean get() = client.isConfigured && !BuildConfig.DEMO_MODE_ENABLED

  suspend fun signIn(email: String, password: String): Outcome {
    // Supabase signs in with an email address. Registration numbers need a lookup we have not built yet.
    if (!email.contains("@")) return Outcome.Fail(AuthError.USE_EMAIL)
    return try {
      val login = client.signIn(email, password)
      if (!login.isSuccessful) {
        val code = runCatching { login.jsonObject().optString("error_code") }.getOrDefault("")
        return Outcome.Fail(
          when {
            code == "email_not_confirmed" -> AuthError.EMAIL_NOT_CONFIRMED
            login.code == 429 -> AuthError.TOO_MANY_ATTEMPTS
            login.code == 400 || login.code == 401 || login.code == 422 -> AuthError.WRONG_CREDENTIALS
            else -> AuthError.NETWORK
          }
        )
      }
      val body = login.jsonObject()
      val token = body.getString("access_token")
      val userId = body.getJSONObject("user").getString("id")

      val profileResult = client.getProfile(token, userId)
      if (!profileResult.isSuccessful) return Outcome.Fail(AuthError.NETWORK)
      val rows = profileResult.jsonArray()
      // Signed in to Auth but no profile row: the account was never completed. Do not open the app.
      if (rows.length() == 0) return Outcome.Fail(AuthError.ACCOUNT_NOT_ACTIVE)
      val row = rows.getJSONObject(0)

      val status = when (row.str("status")) {
        "approved" -> AccountStatus.APPROVED
        "rejected" -> AccountStatus.REJECTED
        else -> AccountStatus.PENDING
      }
      if (status == AccountStatus.REJECTED) return Outcome.Fail(AuthError.ACCOUNT_NOT_ACTIVE)

      val isPlatform = row.optBoolean("is_platform_admin", false)
      val universityCode = universityCode(token, row.str("university_id"), isPlatform)

      accessToken = token
      refreshToken = body.str("refresh_token")

      Outcome.Ok(
        UserProfile(
          id = userId,
          fullName = row.str("full_name") ?: email.substringBefore("@"),
          email = row.str("email") ?: email.trim(),
          role = roleOf(row.str("role")),
          status = status,
          universityId = universityCode,
          studentNumber = row.str("student_number"),
          staffNumber = row.str("staff_number"),
          programme = row.str("department"),
          cohort = row.str("cohort"),
          requestedRole = roleOf(row.str("requested_role")),
          isPlatformAdmin = isPlatform
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Outcome.Fail(AuthError.NETWORK)
    }
  }

  /** Ends the Supabase session. Failures are ignored: the phone forgets the tokens either way. */
  suspend fun signOutQuietly() {
    val token = accessToken
    accessToken = null
    refreshToken = null
    if (token == null) return
    try { client.signOut(token) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* offline */ }
  }

  private suspend fun universityCode(token: String, universityId: String?, isPlatform: Boolean): String {
    if (isPlatform) return "platform"
    if (universityId == null) return ""
    return try {
      val r = client.select("universities", "id=eq.$universityId&select=code", token)
      val rows = if (r.isSuccessful) r.jsonArray() else null
      if (rows != null && rows.length() > 0) rows.getJSONObject(0).optString("code").lowercase() else ""
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ""
    }
  }

  private fun roleOf(value: String?): Role = when (value) {
    "admin" -> Role.ADMIN
    "lecturer" -> Role.LECTURER
    else -> Role.STUDENT
  }

  /** JSON null and blank both become null (org.json would otherwise return the text "null"). */
  private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
