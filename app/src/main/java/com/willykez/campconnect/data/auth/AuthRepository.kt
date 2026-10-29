package com.willykez.campconnect.data.auth

import com.willykez.campconnect.data.model.AccountStatus
import com.willykez.campconnect.data.model.Role
import com.willykez.campconnect.data.model.UserProfile
import com.willykez.campconnect.data.repository.CampusCatalog
import com.willykez.campconnect.data.repository.SeedUsers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object AuthConfig {
  /**
   * PROTOTYPE ONLY. There is no mail server yet, so emailed codes are shown on screen.
   * A connected build must set this to false and send the code by email instead.
   */
  const val SHOW_CODES_ON_SCREEN = true

  const val CODE_TTL_MILLIS = 10 * 60 * 1000L
  const val MAX_CODE_ATTEMPTS = 5
  const val MIN_PASSWORD_LENGTH = 8
}

enum class CodePurpose { VERIFY_EMAIL, SECOND_STEP, RESET_PASSWORD }

enum class PasswordStrength { WEAK, FAIR, STRONG }

enum class PasswordProblem { TOO_SHORT, COMMON, CONTAINS_EMAIL, REPEATED }

data class PasswordCheck(val strength: PasswordStrength, val problems: List<PasswordProblem>) {
  val acceptable: Boolean get() = problems.isEmpty()
}

enum class AuthError {
  NAME_REQUIRED,
  EMAIL_INVALID,
  UNIVERSITY_REQUIRED,
  USE_EMAIL,
  EMAIL_TAKEN,
  NUMBER_INVALID,
  NUMBER_TAKEN,
  PROGRAMME_REQUIRED,
  PASSWORD_WEAK,
  WRONG_CREDENTIALS,
  ACCOUNT_NOT_ACTIVE,
  CODE_WRONG,
  CODE_EXPIRED,
  TOO_MANY_ATTEMPTS
}

sealed interface AuthOutcome {
  /** A session was started. */
  data class Success(val userId: String) : AuthOutcome

  /** A six-digit code was issued and must be entered next. */
  data class NeedCode(val purpose: CodePurpose, val email: String) : AuthOutcome

  /** Password was changed. The person now signs in with the new one. */
  object PasswordChanged : AuthOutcome

  data class Failure(val error: AuthError) : AuthOutcome
}

data class Session(val userId: String, val isDemo: Boolean, val deviceId: String)

data class DeviceSession(val id: String, val label: String, val lastActive: String)

data class Account(
  val profile: UserProfile,
  val salt: String,
  val passwordHash: String,
  val emailVerified: Boolean,
  val trustedDevices: Set<String> = emptySet()
)

/**
 * Local stand-in for the sign-in backend.
 *
 * It follows the same rules the vision describes (university email only, emailed code, registry
 * approval, second step for staff, sessions per device) so the screens can be built and tested.
 * It keeps everything in memory and is NOT secure storage. A connected build replaces this class
 * with calls to the real auth service and keeps the same outcomes.
 */
class AuthRepository(private val catalog: CampusCatalog = CampusCatalog()) {

  val thisDeviceId: String = "dev-" + UUID.randomUUID().toString().take(8)
  private val thisDeviceLabel = "This phone (Android)"

  private val random = SecureRandom()

  private val _accounts = MutableStateFlow(seedAccounts())
  val accounts: StateFlow<Map<String, Account>> = _accounts.asStateFlow()

  private val _session = MutableStateFlow<Session?>(null)
  val session: StateFlow<Session?> = _session.asStateFlow()

  private val _devices = MutableStateFlow(
    mapOf(
      SeedUsers.asha.id to listOf(
        DeviceSession("dev-seed-1", "Tecno Spark 10 (Android)", "Yesterday, 21:40")
      )
    )
  )
  val devices: StateFlow<Map<String, List<DeviceSession>>> = _devices.asStateFlow()

  /** Code to show on screen while there is no mail server (see AuthConfig.SHOW_CODES_ON_SCREEN). */
  private val _visibleCode = MutableStateFlow<String?>(null)
  val visibleCode: StateFlow<String?> = _visibleCode.asStateFlow()

  private data class IssuedCode(
    val purpose: CodePurpose,
    val email: String,
    val code: String,
    val expiresAt: Long,
    val attempts: Int = 0
  )

  private var issued: IssuedCode? = null

  /** Wired by the data repository: (userId, title, message). */
  var onNotify: (String, String, String) -> Unit = { _, _, _ -> }

  // ---------- Password rules ----------

  private val commonPasswords = setOf(
    "password", "password1", "password123", "12345678", "123456789", "1234567890",
    "qwerty123", "qwertyuiop", "iloveyou", "admin123", "welcome1", "letmein123",
    "abc12345", "11111111", "campus123", "university", "tanzania1", "dar es salaam"
  )

  fun checkPassword(password: String, email: String = ""): PasswordCheck {
    val problems = mutableListOf<PasswordProblem>()
    if (password.length < AuthConfig.MIN_PASSWORD_LENGTH) problems += PasswordProblem.TOO_SHORT
    if (password.lowercase() in commonPasswords) problems += PasswordProblem.COMMON
    val local = email.trim().substringBefore('@').lowercase()
    if (local.length >= 3 && password.lowercase().contains(local)) problems += PasswordProblem.CONTAINS_EMAIL
    if (password.isNotEmpty() && password.toSet().size <= 2) problems += PasswordProblem.REPEATED

    val strength = if (problems.isNotEmpty()) {
      PasswordStrength.WEAK
    } else {
      var score = 0
      if (password.length >= 12) score++
      if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() }) score++
      if (password.any { !it.isLetterOrDigit() }) score++
      if (score >= 2) PasswordStrength.STRONG else PasswordStrength.FAIR
    }
    return PasswordCheck(strength, problems)
  }

  // ---------- Sign up ----------

  /**
   * Sign up. The registration (or staff) number is the person's main identity; the email is only
   * for codes and password reset, and can be from any provider. Numbers have no required format
   * because every university writes them differently. Each number is unique inside its university.
   */
  fun signUp(
    universityId: String,
    fullName: String,
    email: String,
    idNumber: String,
    isStaff: Boolean,
    programmeOrDepartment: String,
    password: String
  ): AuthOutcome {
    val cleanEmail = normalizeEmail(email)
    val cleanNumber = idNumber.trim().replace(Regex("\\s+"), " ")

    if (!catalog.hasUniversity(universityId) || universityId == SeedUsers.PLATFORM_ID) {
      return AuthOutcome.Failure(AuthError.UNIVERSITY_REQUIRED)
    }
    if (fullName.trim().length < 3) return AuthOutcome.Failure(AuthError.NAME_REQUIRED)
    if (cleanNumber.length < 2) return AuthOutcome.Failure(AuthError.NUMBER_INVALID)
    if (!EMAIL_REGEX.matches(cleanEmail)) return AuthOutcome.Failure(AuthError.EMAIL_INVALID)

    // If the university has set up programmes, a student picks one. Otherwise anything goes for now.
    val known = catalog.programmesFor(universityId)
    if (!isStaff && known.isNotEmpty() && programmeOrDepartment !in known) {
      return AuthOutcome.Failure(AuthError.PROGRAMME_REQUIRED)
    }

    val all = _accounts.value.values
    if (all.any { it.profile.email.equals(cleanEmail, ignoreCase = true) }) {
      return AuthOutcome.Failure(AuthError.EMAIL_TAKEN)
    }
    if (all.any {
        it.profile.universityId == universityId &&
          (it.profile.studentNumber ?: it.profile.staffNumber).equals(cleanNumber, ignoreCase = true)
      }
    ) {
      return AuthOutcome.Failure(AuthError.NUMBER_TAKEN)
    }
    if (!checkPassword(password, cleanEmail).acceptable) return AuthOutcome.Failure(AuthError.PASSWORD_WEAK)

    // The account always starts as a PENDING student of the chosen university. Nobody can sign up
    // as lecturer or admin: `requestedRole` is only a hint for that university's registry.
    val profile = UserProfile(
      id = "usr-" + UUID.randomUUID().toString().take(8),
      fullName = fullName.trim(),
      email = cleanEmail,
      role = Role.STUDENT,
      status = AccountStatus.PENDING,
      universityId = universityId,
      studentNumber = if (isStaff) null else cleanNumber,
      staffNumber = if (isStaff) cleanNumber else null,
      programme = programmeOrDepartment.trim().ifBlank { null },
      cohort = if (isStaff) "Staff (awaiting role)" else "Awaiting registry",
      requestedRole = if (isStaff) Role.LECTURER else Role.STUDENT
    )
    val salt = UUID.randomUUID().toString()
    _accounts.value = _accounts.value + (profile.id to Account(profile, salt, hash(password, salt), emailVerified = false))

    issueCode(CodePurpose.VERIFY_EMAIL, cleanEmail)
    return AuthOutcome.NeedCode(CodePurpose.VERIFY_EMAIL, cleanEmail)
  }

  // ---------- Sign in ----------

  /** [identifier] is a registration/staff number or an email. */
  fun signIn(identifier: String, password: String): AuthOutcome {
    val matches = findByIdentifier(identifier)
    // The same number can exist at two universities. Then the person signs in with their email.
    if (matches.size > 1) {
      val withPassword = matches.filter { hash(password, it.salt) == it.passwordHash }
      if (withPassword.size != 1) return AuthOutcome.Failure(AuthError.USE_EMAIL)
      return continueSignIn(withPassword.first(), password)
    }
    val account = matches.firstOrNull() ?: return AuthOutcome.Failure(AuthError.WRONG_CREDENTIALS)
    return continueSignIn(account, password)
  }

  private fun continueSignIn(account: Account, password: String): AuthOutcome {
    if (hash(password, account.salt) != account.passwordHash) {
      return AuthOutcome.Failure(AuthError.WRONG_CREDENTIALS)
    }
    val profile = account.profile
    if (profile.status == AccountStatus.REJECTED) return AuthOutcome.Failure(AuthError.ACCOUNT_NOT_ACTIVE)

    if (!account.emailVerified) {
      issueCode(CodePurpose.VERIFY_EMAIL, profile.email)
      return AuthOutcome.NeedCode(CodePurpose.VERIFY_EMAIL, profile.email)
    }

    // Stronger sign-in for people with power:
    //  - administrators confirm a second step every time,
    //  - lecturers confirm it on every new phone.
    val needsSecondStep = profile.status == AccountStatus.APPROVED && (
      profile.role == Role.ADMIN ||
        (profile.role == Role.LECTURER && thisDeviceId !in account.trustedDevices)
      )
    if (needsSecondStep) {
      issueCode(CodePurpose.SECOND_STEP, profile.email)
      return AuthOutcome.NeedCode(CodePurpose.SECOND_STEP, profile.email)
    }
    return startSession(account)
  }

  // ---------- Codes ----------

  fun submitCode(purpose: CodePurpose, email: String, code: String): AuthOutcome {
    val error = consumeCode(purpose, email, code)
    if (error != null) return AuthOutcome.Failure(error)

    val account = findByEmail(email) ?: return AuthOutcome.Failure(AuthError.CODE_EXPIRED)
    return when (purpose) {
      CodePurpose.VERIFY_EMAIL -> {
        val verified = account.copy(emailVerified = true)
        putAccount(verified)
        startSession(verified)
      }
      CodePurpose.SECOND_STEP -> {
        val updated = if (account.profile.role == Role.LECTURER) {
          account.copy(trustedDevices = account.trustedDevices + thisDeviceId).also { putAccount(it) }
        } else {
          account // administrators confirm every time
        }
        startSession(updated)
      }
      CodePurpose.RESET_PASSWORD -> AuthOutcome.Failure(AuthError.CODE_EXPIRED) // use resetPassword()
    }
  }

  fun resendCode(purpose: CodePurpose, email: String) {
    issueCode(purpose, normalizeEmail(email))
  }

  // ---------- Forgot password ----------

  /** Always answers the same way, so the screen never reveals which emails have accounts. */
  fun requestPasswordReset(email: String): AuthOutcome {
    val clean = normalizeEmail(email)
    if (findByEmail(clean) != null) issueCode(CodePurpose.RESET_PASSWORD, clean) else issued = null
    return AuthOutcome.NeedCode(CodePurpose.RESET_PASSWORD, clean)
  }

  fun resetPassword(email: String, code: String, newPassword: String): AuthOutcome {
    val account = findByEmail(email)
    if (!checkPassword(newPassword, email).acceptable) return AuthOutcome.Failure(AuthError.PASSWORD_WEAK)
    val error = consumeCode(CodePurpose.RESET_PASSWORD, email, code)
    if (error != null || account == null) return AuthOutcome.Failure(error ?: AuthError.CODE_EXPIRED)

    val salt = UUID.randomUUID().toString()
    putAccount(account.copy(salt = salt, passwordHash = hash(newPassword, salt), trustedDevices = emptySet()))

    // A stolen session must not survive a password change: every phone is signed out.
    val id = account.profile.id
    _devices.value = _devices.value - id
    if (_session.value?.userId == id) _session.value = null
    return AuthOutcome.PasswordChanged
  }

  // ---------- Sessions ----------

  fun startDemoSession(userId: String) {
    _session.value = Session(userId = userId, isDemo = true, deviceId = thisDeviceId)
  }

  fun signOut() {
    val s = _session.value
    if (s != null && !s.isDemo) removeDevice(s.userId, thisDeviceId)
    _session.value = null
    _visibleCode.value = null
  }

  fun signOutDevice(userId: String, deviceId: String) {
    if (deviceId == thisDeviceId) {
      signOut()
    } else {
      removeDevice(userId, deviceId)
    }
  }

  fun signOutEverywhere(userId: String) {
    _devices.value = _devices.value - userId
    if (_session.value?.userId == userId) _session.value = null
  }

  // ---------- Registry actions (permission is checked by the caller) ----------

  /** True/false when the university has uploaded its list; null when it has not (nothing to check against). */
  fun registryMatches(profile: UserProfile): Boolean? {
    if (!SeedUsers.hasRegistryList(profile.universityId)) return null
    return if (profile.requestedRole == Role.LECTURER || (profile.staffNumber != null && profile.studentNumber == null)) {
      profile.staffNumber in SeedUsers.staffList
    } else {
      profile.studentNumber in SeedUsers.studentList
    }
  }

  fun approve(userId: String, role: Role, programme: String?, teaches: Set<String>): UserProfile? {
    val account = _accounts.value[userId] ?: return null
    val old = account.profile
    val updated = when (role) {
      Role.STUDENT -> {
        val programmes = catalog.catalogFor(old.universityId)
        val prog = programme ?: old.programme ?: programmes.keys.firstOrNull()
        old.copy(
          role = Role.STUDENT,
          status = AccountStatus.APPROVED,
          programme = prog,
          cohort = if (old.cohort.isNullOrBlank() || old.cohort == "Awaiting registry") "Year 1 • Semester 1" else old.cohort,
          enrolledCourses = programmes[prog].orEmpty()
        )
      }
      Role.LECTURER -> old.copy(
        role = Role.LECTURER,
        status = AccountStatus.APPROVED,
        cohort = if (old.cohort.isNullOrBlank() || old.cohort == "Staff (awaiting role)") "Lecturer" else old.cohort,
        teachesCourses = teaches,
        enrolledCourses = emptySet()
      )
      // Administrator accounts are never created from this queue.
      Role.ADMIN -> return null
    }
    putAccount(account.copy(profile = updated))
    return updated
  }

  /** Replaces the courses a lecturer teaches. The caller has already checked who may do this. */
  fun setTeaching(userId: String, courses: Set<String>): UserProfile? {
    val account = _accounts.value[userId] ?: return null
    if (account.profile.role != Role.LECTURER) return null
    val updated = account.profile.copy(teachesCourses = courses)
    putAccount(account.copy(profile = updated))
    return updated
  }

  /** The app manager creates a campus together with its first administrator. */
  fun createApprovedAdmin(
    universityId: String,
    fullName: String,
    email: String,
    staffNumber: String,
    password: String
  ): AuthError? {
    val cleanEmail = normalizeEmail(email)
    val number = staffNumber.trim()
    if (fullName.trim().length < 3) return AuthError.NAME_REQUIRED
    if (number.length < 2) return AuthError.NUMBER_INVALID
    if (!EMAIL_REGEX.matches(cleanEmail)) return AuthError.EMAIL_INVALID
    if (_accounts.value.values.any { it.profile.email.equals(cleanEmail, ignoreCase = true) }) return AuthError.EMAIL_TAKEN
    if (!checkPassword(password, cleanEmail).acceptable) return AuthError.PASSWORD_WEAK
    val profile = UserProfile(
      id = "usr-" + UUID.randomUUID().toString().take(8),
      fullName = fullName.trim(),
      email = cleanEmail,
      role = Role.ADMIN,
      status = AccountStatus.APPROVED,
      universityId = universityId,
      staffNumber = number,
      programme = "Registry",
      cohort = "Campus administrator",
      requestedRole = Role.ADMIN
    )
    val salt = UUID.randomUUID().toString()
    putAccount(Account(profile, salt, hash(password, salt), emailVerified = true))
    return null
  }

  /**
   * An administrator (or the app manager) adds a person directly. The account is approved at once and
   * the person signs in with the email and starting password they were given.
   */
  fun createApprovedUser(
    universityId: String,
    role: Role,
    fullName: String,
    email: String,
    idNumber: String,
    programme: String?,
    password: String
  ): AuthError? {
    val cleanEmail = normalizeEmail(email)
    val number = idNumber.trim()
    if (fullName.trim().length < 3) return AuthError.NAME_REQUIRED
    if (number.length < 2) return AuthError.NUMBER_INVALID
    if (!EMAIL_REGEX.matches(cleanEmail)) return AuthError.EMAIL_INVALID
    if (_accounts.value.values.any { it.profile.email.equals(cleanEmail, ignoreCase = true) }) return AuthError.EMAIL_TAKEN
    if (role == Role.STUDENT && programme.isNullOrBlank()) return AuthError.PROGRAMME_REQUIRED
    if (!checkPassword(password, cleanEmail).acceptable) return AuthError.PASSWORD_WEAK
    val profile = UserProfile(
      id = "usr-" + UUID.randomUUID().toString().take(8),
      fullName = fullName.trim(),
      email = cleanEmail,
      role = role,
      status = AccountStatus.APPROVED,
      universityId = universityId,
      studentNumber = if (role == Role.STUDENT) number else null,
      staffNumber = if (role != Role.STUDENT) number else null,
      programme = programme?.trim()?.ifBlank { null } ?: if (role == Role.ADMIN) "Registry" else null,
      cohort = when (role) {
        Role.ADMIN -> "Campus administrator"
        Role.LECTURER -> "Lecturer"
        Role.STUDENT -> null
      },
      requestedRole = role,
      enrolledCourses = if (role == Role.STUDENT) catalog.catalogFor(universityId)[programme?.trim().orEmpty()].orEmpty() else emptySet()
    )
    val salt = UUID.randomUUID().toString()
    putAccount(Account(profile, salt, hash(password, salt), emailVerified = true))
    return null
  }

  /** A new course in a programme reaches the students already in that programme. */
  fun enrollProgramme(universityId: String, programme: String, courseCode: String) {
    _accounts.value = _accounts.value.mapValues { (_, account) ->
      val p = account.profile
      if (p.universityId == universityId && p.role == Role.STUDENT && p.status == AccountStatus.APPROVED &&
        p.programme.equals(programme, ignoreCase = true)
      ) {
        account.copy(profile = p.copy(enrolledCourses = p.enrolledCourses + courseCode))
      } else account
    }
  }

  /** Removes the people of one campus (the app manager's delete). Returns how many were removed. */
  fun removeAccountsOf(universityId: String, includeAdmins: Boolean): Int {
    val doomed = _accounts.value.values
      .filter { it.profile.universityId == universityId && (includeAdmins || it.profile.role != Role.ADMIN) }
      .map { it.profile.id }
      .toSet()
    _accounts.value = _accounts.value - doomed
    _devices.value = _devices.value - doomed
    if (_session.value?.userId in doomed) _session.value = null
    return doomed.size
  }

  fun reject(userId: String): UserProfile? {
    val account = _accounts.value[userId] ?: return null
    val updated = account.profile.copy(status = AccountStatus.REJECTED)
    putAccount(account.copy(profile = updated))
    removeAllDevices(userId)
    return updated
  }

  // ---------- Internals ----------

  private fun startSession(account: Account): AuthOutcome {
    val id = account.profile.id
    val existing = _devices.value[id].orEmpty()
    val already = existing.any { it.id == thisDeviceId }
    if (!already) {
      _devices.value = _devices.value + (id to (existing + DeviceSession(thisDeviceId, thisDeviceLabel, "Just now")))
      if (existing.isNotEmpty()) {
        onNotify(id, "Signed in on a new device", "Your account was just signed in on a new phone. Not you? Open Profile > Devices and sign it out.")
      }
    }
    _session.value = Session(userId = id, isDemo = false, deviceId = thisDeviceId)
    _visibleCode.value = null
    return AuthOutcome.Success(id)
  }

  private fun issueCode(purpose: CodePurpose, email: String) {
    val code = "%06d".format(random.nextInt(1_000_000))
    issued = IssuedCode(purpose, email, code, System.currentTimeMillis() + AuthConfig.CODE_TTL_MILLIS)
    _visibleCode.value = if (AuthConfig.SHOW_CODES_ON_SCREEN) code else null
  }

  /** Returns null when the code is right and has been used up. */
  private fun consumeCode(purpose: CodePurpose, email: String, code: String): AuthError? {
    val current = issued ?: return AuthError.CODE_EXPIRED
    if (current.purpose != purpose || current.email != normalizeEmail(email)) return AuthError.CODE_EXPIRED
    if (System.currentTimeMillis() > current.expiresAt) {
      issued = null
      return AuthError.CODE_EXPIRED
    }
    if (current.attempts >= AuthConfig.MAX_CODE_ATTEMPTS) {
      issued = null
      return AuthError.TOO_MANY_ATTEMPTS
    }
    if (code.trim() != current.code) {
      issued = current.copy(attempts = current.attempts + 1)
      return AuthError.CODE_WRONG
    }
    issued = null
    _visibleCode.value = null
    return null
  }

  private fun removeDevice(userId: String, deviceId: String) {
    val list = _devices.value[userId].orEmpty().filterNot { it.id == deviceId }
    _devices.value = if (list.isEmpty()) _devices.value - userId else _devices.value + (userId to list)
  }

  private fun removeAllDevices(userId: String) {
    _devices.value = _devices.value - userId
    if (_session.value?.userId == userId) _session.value = null
  }

  private fun putAccount(account: Account) {
    _accounts.value = _accounts.value + (account.profile.id to account)
  }

  private fun findByIdentifier(identifier: String): List<Account> {
    val clean = identifier.trim()
    if (clean.isEmpty()) return emptyList()
    return _accounts.value.values.filter {
      it.profile.email.equals(clean, ignoreCase = true) ||
        it.profile.studentNumber.equals(clean, ignoreCase = true) ||
        it.profile.staffNumber.equals(clean, ignoreCase = true)
    }
  }

  private fun findByEmail(email: String): Account? {
    val clean = normalizeEmail(email)
    return _accounts.value.values.firstOrNull { it.profile.email.equals(clean, ignoreCase = true) }
  }

  private fun normalizeEmail(raw: String) = raw.trim().lowercase()

  private fun hash(password: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest((salt + password).toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun seedAccounts(): Map<String, Account> {
    return SeedUsers.directory.associate { profile ->
      val salt = "seed-" + profile.id
      profile.id to Account(
        profile = profile,
        salt = salt,
        passwordHash = hash(SeedUsers.SAMPLE_PASSWORD, salt),
        emailVerified = true
      )
    }
  }

  private companion object {
    // Any address that looks like an address. No domain is required.
    val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
  }
}
