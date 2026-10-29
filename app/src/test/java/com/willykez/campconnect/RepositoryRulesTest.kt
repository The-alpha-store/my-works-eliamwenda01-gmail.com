package com.willykez.campconnect

import com.willykez.campconnect.data.auth.AuthError
import com.willykez.campconnect.data.auth.AuthOutcome
import com.willykez.campconnect.data.auth.CodePurpose
import com.willykez.campconnect.data.model.AccountStatus
import com.willykez.campconnect.data.model.AnnouncementPriority
import com.willykez.campconnect.data.model.AttachmentFile
import com.willykez.campconnect.data.model.AudienceType
import com.willykez.campconnect.data.model.Role
import com.willykez.campconnect.data.model.UserProfile
import com.willykez.campconnect.data.repository.AssignLecturersError
import com.willykez.campconnect.data.repository.CampusRepository
import com.willykez.campconnect.data.repository.PostError
import com.willykez.campconnect.data.repository.SessionError
import com.willykez.campconnect.data.repository.WipeScope
import com.willykez.campconnect.data.repository.SeedUsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who may see and do what. These are the rules the database must also enforce. */
class RepositoryRulesTest {

  private fun signInAs(repo: CampusRepository, user: UserProfile) {
    if (repo.session.value != null) repo.signOut()
    var outcome = repo.auth.signIn(user.email, SeedUsers.SAMPLE_PASSWORD)
    if (outcome is AuthOutcome.NeedCode) {
      val code = repo.auth.visibleCode.value!!
      outcome = repo.auth.submitCode(outcome.purpose, outcome.email, code)
    }
    assertTrue("sign-in should succeed for ${user.email}", outcome is AuthOutcome.Success)
  }

  private val doc = AttachmentFile(fileName = "work.pdf", fileSize = "1 MB", mimeType = "application/pdf")

  @Test
  fun `signed out person sees nothing`() {
    val repo = CampusRepository()
    assertTrue(repo.sessions.value.isEmpty())
    assertTrue(repo.announcements.value.isEmpty())
    assertTrue(repo.notifications.value.isEmpty())
    assertTrue(repo.auditLogs.value.isEmpty())
  }

  @Test
  fun `persona switcher works only in demo mode`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.asha)
    repo.switchDemoPersona(SeedUsers.emanuel)
    assertEquals(Role.STUDENT, repo.currentUser.value.role) // ignored in a real session

    repo.signOut()
    repo.startDemo()
    assertTrue(repo.isDemoMode.value)
    repo.switchDemoPersona(SeedUsers.mushi)
    assertEquals(Role.LECTURER, repo.currentUser.value.role)
  }

  @Test
  fun `lecturer and admin need a second step`() {
    val repo = CampusRepository()
    val outcome = repo.auth.signIn(SeedUsers.emanuel.email, SeedUsers.SAMPLE_PASSWORD)
    assertTrue(outcome is AuthOutcome.NeedCode)
    assertEquals(CodePurpose.SECOND_STEP, (outcome as AuthOutcome.NeedCode).purpose)
    assertNull(repo.session.value)
    val wrong = repo.auth.submitCode(CodePurpose.SECOND_STEP, SeedUsers.emanuel.email, "000000")
    assertTrue(wrong is AuthOutcome.Failure)
  }

  @Test
  fun `wrong password gives the same error for unknown and known emails`() {
    val repo = CampusRepository()
    val a = repo.auth.signIn("nobody@campus.ac.tz", "whatever1") as AuthOutcome.Failure
    val b = repo.auth.signIn(SeedUsers.asha.email, "wrong-pass1") as AuthOutcome.Failure
    assertEquals(AuthError.WRONG_CREDENTIALS, a.error)
    assertEquals(AuthError.WRONG_CREDENTIALS, b.error)
  }

  @Test
  fun `sign up needs a real university and a strong enough password`() {
    val repo = CampusRepository()
    val outside = repo.auth.signUp("nowhere", "Test Person", "test@gmail.com", "T25-03-00001", false, "BSc Computer Science", "Str0ng#Pass99")
    assertEquals(AuthError.UNIVERSITY_REQUIRED, (outside as AuthOutcome.Failure).error)
    val weak = repo.auth.signUp("cuom", "Test Person", "t.person@gmail.com", "T25-03-00001", false, "BSc Computer Science", "password1")
    assertEquals(AuthError.PASSWORD_WEAK, (weak as AuthOutcome.Failure).error)
  }

  @Test
  fun `nobody can sign up as lecturer or admin and pending accounts see nothing`() {
    val repo = CampusRepository()
    val out = repo.auth.signUp("cuom", "Eng. New Staff", "n.staff@gmail.com", "STF-CS-099", true, "Computer Science", "Str0ng#Pass99")
    assertTrue(out is AuthOutcome.NeedCode)
    val verified = repo.auth.submitCode(CodePurpose.VERIFY_EMAIL, "n.staff@gmail.com", repo.auth.visibleCode.value!!)
    assertTrue(verified is AuthOutcome.Success)

    val me = repo.currentUser.value
    assertEquals(Role.STUDENT, me.role) // staff request is only a hint
    assertEquals(Role.LECTURER, me.requestedRole)
    assertEquals(AccountStatus.PENDING, me.status)
    assertTrue(repo.sessions.value.isEmpty())
    assertTrue(repo.announcements.value.isEmpty())
    assertTrue(repo.materials.value.isEmpty())
    assertTrue(repo.deadlines.value.isEmpty())
    assertEquals(SeedUsers.minja.id, repo.pendingUsers.value.first { it.id == SeedUsers.minja.id }.id)
  }

  @Test
  fun `only an administrator can approve and a student sees only their own courses`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.mushi)
    repo.approveUser(SeedUsers.baraka.id, Role.STUDENT, "BSc Information Systems", emptySet())
    assertEquals(AccountStatus.PENDING, repo.auth.accounts.value.getValue(SeedUsers.baraka.id).profile.status)

    signInAs(repo, SeedUsers.emanuel)
    repo.approveUser(SeedUsers.baraka.id, Role.STUDENT, "BSc Information Systems", emptySet())
    assertTrue(repo.auditLogs.value.any { it.action == "ACCOUNT_APPROVED" })

    signInAs(repo, SeedUsers.baraka)
    val codes = repo.sessions.value.map { it.courseCode.substringBefore('-') }.toSet()
    assertTrue(codes.isNotEmpty())
    assertFalse("CS210" in codes)
    assertTrue(repo.notifications.value.any { it.title.startsWith("Karibu") })
  }

  @Test
  fun `done ticks are private to the student`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.kelvin)
    repo.toggleDeadlineCompleted("dl-01")
    assertTrue(repo.deadlines.value.first { it.id == "dl-01" }.isCompleted)

    signInAs(repo, SeedUsers.asha)
    assertFalse(repo.deadlines.value.first { it.id == "dl-01" }.isCompleted)

    signInAs(repo, SeedUsers.mushi)
    assertFalse(repo.deadlines.value.first { it.id == "dl-01" }.isCompleted)
  }

  @Test
  fun `a student never sees another students submission`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.kelvin)
    assertTrue(repo.submissions.value.isNotEmpty())
    assertTrue(repo.submissions.value.all { it.studentId == SeedUsers.kelvin.id })

    signInAs(repo, SeedUsers.mushi)
    assertTrue(repo.submissions.value.size >= 2)
  }

  @Test
  fun `student submits and lecturer grades`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.asha)
    repo.submitStudentWork("dl-01", "CS210", "Programming Assignment 2", "notes", doc)
    val mine = repo.submissions.value.first()

    signInAs(repo, SeedUsers.mushi)
    assertTrue(repo.notifications.value.any { it.title.startsWith("Work submitted") })
    repo.gradeStudentWork(mine.id, "98/100", "Good")
    assertEquals("98/100", repo.submissions.value.first { it.id == mine.id }.grade)

    signInAs(repo, SeedUsers.asha)
    assertTrue(repo.notifications.value.any { it.title.startsWith("Assignment Graded") })
  }

  @Test
  fun `lecturer cannot post campus-wide or to a course they do not teach`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.mushi)
    assertEquals(
      PostError.CAMPUS_REGISTRY_ONLY,
      repo.publishAnnouncement("Hi", "All", AudienceType.CAMPUS, "Whole campus", "", AnnouncementPriority.NORMAL)
    )
    assertEquals(
      PostError.AUDIENCE_NOT_YOURS,
      repo.publishAnnouncement("Hi", "All", AudienceType.COURSE, "IS202", "IS202", AnnouncementPriority.NORMAL)
    )
    assertNull(repo.publishAnnouncement("Hi", "Class", AudienceType.COURSE, "CS210", "CS210", AnnouncementPriority.NORMAL))
    assertTrue(repo.announcements.value.any { it.title == "Hi" })
  }

  @Test
  fun `urgent is limited per lecturer per day but free for the registry`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.mushi)
    repeat(CampusRepository.DAILY_URGENT_LIMIT) {
      assertNull(repo.publishAnnouncement("U$it", "x", AudienceType.COURSE, "CS210", "CS210", AnnouncementPriority.URGENT))
    }
    assertEquals(
      PostError.URGENT_LIMIT_REACHED,
      repo.publishAnnouncement("U9", "x", AudienceType.COURSE, "CS210", "CS210", AnnouncementPriority.URGENT)
    )
    assertEquals(
      PostError.URGENT_NOT_ALLOWED,
      repo.publishAnnouncement("P", "x", AudienceType.PROGRAMME, "BSc Computer Science", "BSc Computer Science", AnnouncementPriority.URGENT)
    )

    signInAs(repo, SeedUsers.emanuel)
    repeat(5) {
      assertNull(repo.publishAnnouncement("R$it", "x", AudienceType.CAMPUS, "Whole campus", "", AnnouncementPriority.URGENT))
    }
    assertTrue(repo.auditLogs.value.any { it.action == "URGENT_BLOCKED" })
  }

  @Test
  fun `posts reach only the audience and retract hides them`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.emanuel)
    repo.approveUser(SeedUsers.baraka.id, Role.STUDENT, "BSc Information Systems", emptySet())

    signInAs(repo, SeedUsers.mushi)
    assertNull(repo.publishAnnouncement("CS only", "x", AudienceType.COURSE, "CS210", "CS210", AnnouncementPriority.NORMAL))
    val id = repo.announcements.value.first { it.title == "CS only" }.id

    signInAs(repo, SeedUsers.baraka)
    assertFalse(repo.announcements.value.any { it.id == id })
    assertFalse(repo.notifications.value.any { it.title == "CS only" })

    signInAs(repo, SeedUsers.asha)
    assertTrue(repo.announcements.value.any { it.id == id })
    assertTrue(repo.notifications.value.any { it.title == "CS only" })

    signInAs(repo, SeedUsers.mushi)
    assertTrue(repo.retractAnnouncement(id))

    signInAs(repo, SeedUsers.asha)
    assertFalse(repo.announcements.value.any { it.id == id })
    assertTrue(repo.notifications.value.any { it.title.startsWith("Correction") })
  }

  @Test
  fun `read counts reach the sender and only students are counted`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.mushi)
    assertNull(repo.publishAnnouncement("Count me", "x", AudienceType.COURSE, "CS210", "CS210", AnnouncementPriority.NORMAL))
    val id = repo.announcements.value.first { it.title == "Count me" }.id
    repo.markAnnouncementRead(id) // lecturer opening own notice is not counted
    assertEquals(0, repo.announcements.value.first { it.id == id }.readCount)

    signInAs(repo, SeedUsers.asha)
    repo.markAnnouncementRead(id)
    repo.markAnnouncementRead(id)

    signInAs(repo, SeedUsers.mushi)
    assertEquals(1, repo.announcements.value.first { it.id == id }.readCount)
    assertEquals(1, repo.remindUnread(id)) // Kelvin has not opened it
    assertEquals(0, repo.remindUnread(id)) // one nudge only
  }

  @Test
  fun `lecturer can only upload and set deadlines for own courses`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.mushi)
    assertFalse(repo.uploadMaterial("Notes", "IS202", "Info Systems", "d", doc))
    assertTrue(repo.uploadMaterial("Notes", "CS210", "Algorithms", "d", doc))
    assertFalse(repo.addDeadline("IS202", "IS", "T", "i", "Friday", ""))

    signInAs(repo, SeedUsers.asha)
    assertFalse(repo.uploadMaterial("Notes", "CS210", "Algorithms", "d", doc))
  }

  @Test
  fun `only admins read the audit log and non-owners cannot edit`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.asha)
    assertTrue(repo.auditLogs.value.isEmpty())
    assertFalse(repo.canEditOrDelete(SeedUsers.mushi.id))
    assertFalse(repo.canApproveOrDisapprove())

    signInAs(repo, SeedUsers.sarah)
    assertTrue(repo.auditLogs.value.isNotEmpty())
    assertTrue(repo.canEditOrDelete(SeedUsers.mushi.id))
  }

  @Test
  fun `password reset signs every phone out`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.asha)
    repo.auth.requestPasswordReset(SeedUsers.asha.email)
    val result = repo.auth.resetPassword(SeedUsers.asha.email, repo.auth.visibleCode.value!!, "Brand#New2026x")
    assertTrue(result is AuthOutcome.PasswordChanged)
    assertNull(repo.session.value)
    assertTrue(repo.auth.signIn(SeedUsers.asha.email, SeedUsers.SAMPLE_PASSWORD) is AuthOutcome.Failure)
    assertTrue(repo.auth.signIn(SeedUsers.asha.email, "Brand#New2026x") is AuthOutcome.Success)
  }

  @Test
  fun `any email works and a registration number in any format can sign in`() {
    val repo = CampusRepository()
    val out = repo.auth.signUp("ifm", "Amina Said", "amina.said@yahoo.com", "ifm/2024/bsc/0042", false, "Bachelor of Accounting", "Walk-to-school-9")
    assertTrue(out is AuthOutcome.NeedCode)
    repo.auth.submitCode(CodePurpose.VERIFY_EMAIL, "amina.said@yahoo.com", repo.auth.visibleCode.value!!)
    repo.signOut()

    // Sign in with the registration number alone, typed in a different case.
    val again = repo.auth.signIn("IFM/2024/BSC/0042", "Walk-to-school-9")
    assertTrue(again is AuthOutcome.Success)
    assertEquals("ifm", repo.currentUser.value.universityId)
  }

  @Test
  fun `a registration number is unique inside its university but can repeat across universities`() {
    val repo = CampusRepository()
    val same = repo.auth.signUp("cuom", "Someone Else", "else@gmail.com", "T22-03-08492", false, "BSc Computer Science", "Another-pass-77")
    assertEquals(AuthError.NUMBER_TAKEN, (same as AuthOutcome.Failure).error)
    val other = repo.auth.signUp("udsm", "Someone Else", "else@gmail.com", "T22-03-08492", false, "", "Another-pass-77")
    assertTrue(other is AuthOutcome.NeedCode)
  }

  @Test
  fun `universities are kept apart`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.ifmRegistry)
    // The IFM registry sees no Cuom sessions, notices, accounts or audit entries.
    assertTrue(repo.sessions.value.isEmpty())
    assertTrue(repo.announcements.value.isEmpty())
    assertTrue(repo.pendingUsers.value.none { it.universityId != "ifm" })
    assertTrue(repo.auditLogs.value.none { it.universityId != "ifm" })
    // ...and cannot approve a Cuom account.
    repo.approveUser(SeedUsers.baraka.id, Role.STUDENT, "BSc Information Systems", emptySet())
    assertEquals(AccountStatus.PENDING, repo.auth.accounts.value.getValue(SeedUsers.baraka.id).profile.status)

    // A Cuom campus-wide notice never reaches an IFM student.
    signInAs(repo, SeedUsers.emanuel)
    assertNull(repo.publishAnnouncement("Cuom only", "x", AudienceType.CAMPUS, "Whole campus", "", AnnouncementPriority.NORMAL))
    signInAs(repo, SeedUsers.ifmStudent)
    assertTrue(repo.announcements.value.none { it.title == "Cuom only" })
    assertTrue(repo.notifications.value.none { it.title == "Cuom only" })
  }

  @Test
  fun `the campus administrator builds the catalogue and new courses reach enrolled students`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.emanuel)
    assertTrue(repo.addProgramme("BSc Cyber Security"))
    assertFalse(repo.addProgramme("bsc cyber security")) // same name
    assertTrue(repo.addCourse("cs301", "Network Security", "BSc Computer Science"))
    val imported = repo.importCourses("CS302, Cryptography, BSc Computer Science\nbad line\nCS303; Ethics")
    assertEquals(2, imported.added)
    assertEquals(1, imported.skipped)
    assertTrue(repo.addRoom("Lab 9"))

    signInAs(repo, SeedUsers.asha)
    assertTrue("CS301" in repo.currentUser.value.enrolledCourses)
    assertTrue("CS302" in repo.currentUser.value.enrolledCourses)

    // A student and a lecturer cannot change the catalogue; another campus never sees it.
    assertFalse(repo.addCourse("XX100", "Nope", null))
    signInAs(repo, SeedUsers.ifmRegistry)
    assertTrue(repo.catalog.coursesFor("ifm").isEmpty())
    assertTrue(repo.addCourse("ACC101", "Financial Accounting", null))
    assertFalse("ACC101" in repo.catalog.coursesFor("cuom"))
  }

  @Test
  fun `scheduling checks the room, the lecturer and the subject`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.mushi)
    assertTrue(repo.schedulableCourses().map { it.first }.toSet() == setOf("CS210", "CS215"))
    assertEquals(listOf(SeedUsers.mushi.id), repo.lecturersFor("CS210").map { it.id })

    assertNull(repo.addSession("CS210", SeedUsers.mushi.id, 5, "14:00", "16:00", "LTB Room 2"))
    // Same room, overlapping time
    assertEquals(SessionError.ROOM_BUSY, repo.addSession("CS215", SeedUsers.mushi.id, 5, "15:00", "17:00", "LTB Room 2"))
    // Same lecturer, overlapping time, other room
    assertEquals(SessionError.LECTURER_BUSY, repo.addSession("CS215", SeedUsers.mushi.id, 5, "15:00", "17:00", "LTB Room 1"))
    assertEquals(SessionError.TIME_INVALID, repo.addSession("CS215", SeedUsers.mushi.id, 5, "17:00", "16:00", "LTB Room 1"))
    // Not his subject, and not a lecturer of the subject
    assertEquals(SessionError.NOT_ALLOWED, repo.addSession("IS202", SeedUsers.kweka.id, 4, "08:00", "10:00", "LTB Room 3"))
    assertEquals(SessionError.LECTURER_NOT_FOR_COURSE, repo.addSession("CS210", SeedUsers.kweka.id, 4, "08:00", "10:00", "LTB Room 3"))

    val rooms = repo.roomsWithAvailability(5, "14:30", "15:30")
    assertEquals("CS210", rooms.first { it.name == "LTB Room 2" }.busyWith)
    assertNull(rooms.first { it.name == "LTB Room 1" }.busyWith)

    signInAs(repo, SeedUsers.asha)
    assertTrue(repo.notifications.value.any { it.title == "New class: CS210" })

    // Another campus does not see it
    signInAs(repo, SeedUsers.ifmStudent)
    assertTrue(repo.sessions.value.isEmpty())
  }

  @Test
  fun `a notice can name the responsible lecturer`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.emanuel)
    assertNull(repo.publishAnnouncement("Test moved", "x", AudienceType.COURSE, "CS210", "CS210", AnnouncementPriority.NORMAL, null, SeedUsers.mushi.id))
    assertEquals(PostError.RESPONSIBLE_NOT_FOR_COURSE,
      repo.publishAnnouncement("Wrong", "x", AudienceType.COURSE, "CS210", "CS210", AnnouncementPriority.NORMAL, null, SeedUsers.kweka.id))
    signInAs(repo, SeedUsers.asha)
    val ann = repo.announcements.value.first { it.title == "Test moved" }
    assertEquals(SeedUsers.mushi.fullName, ann.responsibleLecturerName)
  }

  @Test
  fun `the app manager creates and deletes campuses but sees no campus content`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.platformManager)
    // Sees nothing a campus owns
    assertTrue(repo.sessions.value.isEmpty() && repo.announcements.value.isEmpty() && repo.materials.value.isEmpty())
    assertTrue(repo.pendingUsers.value.isEmpty())

    val created = repo.createCampus("UDOM", "University of Dodoma", "Amos Lyimo", "amos@gmail.com", "UD-ADM-1", "Dodoma-Pass-55")
    assertTrue(created.ok)
    assertTrue(repo.catalog.hasUniversity("udom"))
    assertFalse(repo.createCampus("udom", "Again", "Amos Lyimo", "other@gmail.com", "X1", "Dodoma-Pass-55").ok)
    val summary = repo.campuses.value.first { it.university.id == "udom" }
    assertEquals(1, summary.admins)

    // The new campus administrator signs in with a number and works inside their campus
    repo.signOut()
    var step = repo.auth.signIn("UD-ADM-1", "Dodoma-Pass-55") // by staff number
    if (step is AuthOutcome.NeedCode) step = repo.auth.submitCode(step.purpose, step.email, repo.auth.visibleCode.value!!)
    assertTrue(step is AuthOutcome.Success)
    assertEquals("udom", repo.currentUser.value.universityId)
    assertTrue(repo.addProgramme("BSc Education"))

    // Campus administrators cannot do the manager's work
    assertFalse(repo.createCampus("ZZZ", "Zed", "Zed Person", "z@gmail.com", "Z1", "Dodoma-Pass-55").ok)
    assertFalse(repo.wipeCampus("cuom", WipeScope.EVERYTHING))

    signInAs(repo, SeedUsers.platformManager)
    assertTrue(repo.wipeCampus("cuom", WipeScope.ANNOUNCEMENTS))
    signInAs(repo, SeedUsers.asha)
    assertTrue(repo.announcements.value.isEmpty())
    assertTrue(repo.sessions.value.isNotEmpty()) // only announcements were deleted

    signInAs(repo, SeedUsers.platformManager)
    assertTrue(repo.deleteCampus("cuom"))
    assertFalse(repo.catalog.hasUniversity("cuom"))
    assertTrue(repo.auth.accounts.value.values.none { it.profile.universityId == "cuom" })
    assertTrue(repo.auditLogs.value.any { it.action == "CAMPUS_DELETED" })
  }

  @Test
  fun `nobody can sign up into the app manager's campus`() {
    val repo = CampusRepository()
    val out = repo.auth.signUp("platform", "Mallory Plat", "m@gmail.com", "M-1", true, "x", "Sneaky-pass-77")
    assertEquals(AuthError.UNIVERSITY_REQUIRED, (out as AuthOutcome.Failure).error)
  }

  @Test
  fun `the campus administrator assigns the lecturers of a course`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.emanuel)
    assertEquals(listOf(SeedUsers.mushi.id), repo.lecturersFor("CS210").map { it.id })
    assertTrue(repo.campusLecturers().any { it.id == SeedUsers.kweka.id })

    // A second lecturer joins; the first stays, and nobody's other courses change.
    assertNull(repo.setCourseLecturers("CS210", setOf(SeedUsers.mushi.id, SeedUsers.kweka.id)).error)
    assertEquals(setOf(SeedUsers.mushi.id, SeedUsers.kweka.id), repo.lecturersFor("CS210").map { it.id }.toSet())
    assertTrue("CS215" in repo.auth.accounts.value.getValue(SeedUsers.mushi.id).profile.teachesCourses)
    assertTrue("IS202" in repo.auth.accounts.value.getValue(SeedUsers.kweka.id).profile.teachesCourses)

    // The change is logged once; saving the same list again is harmless and not logged again.
    val logged = repo.auditLogs.value.count { it.action == "COURSE_LECTURERS_SET" }
    assertEquals(1, logged)
    assertNull(repo.setCourseLecturers("CS210", setOf(SeedUsers.mushi.id, SeedUsers.kweka.id)).error)
    assertEquals(logged, repo.auditLogs.value.count { it.action == "COURSE_LECTURERS_SET" })

    // A lecturer who still has a live class in the course cannot be removed from it.
    val blocked = repo.setCourseLecturers("CS210", setOf(SeedUsers.kweka.id))
    assertEquals(AssignLecturersError.HAS_CLASSES, blocked.error)
    assertTrue(blocked.names.contains("Mushi"))
    assertEquals(2, repo.lecturersFor("CS210").size)

    // The new lecturer is told and may now act on the course.
    signInAs(repo, SeedUsers.kweka)
    assertTrue(repo.notifications.value.any { it.title == "You now teach CS210" })
    assertTrue("CS210" in repo.currentUser.value.teachesCourses)

    // Someone with no live class there can be removed again.
    signInAs(repo, SeedUsers.emanuel)
    assertNull(repo.setCourseLecturers("CS210", setOf(SeedUsers.mushi.id)).error)
    assertEquals(listOf(SeedUsers.mushi.id), repo.lecturersFor("CS210").map { it.id })
    signInAs(repo, SeedUsers.kweka)
    assertTrue("CS210" !in repo.currentUser.value.teachesCourses)
  }

  @Test
  fun `only the campus administrator can assign lecturers and only inside their campus`() {
    val repo = CampusRepository()
    val ask = setOf(SeedUsers.kweka.id)

    signInAs(repo, SeedUsers.mushi)
    assertEquals(AssignLecturersError.NOT_ALLOWED, repo.setCourseLecturers("CS210", ask).error)
    assertTrue(repo.campusLecturers().isEmpty())
    signInAs(repo, SeedUsers.asha)
    assertEquals(AssignLecturersError.NOT_ALLOWED, repo.setCourseLecturers("CS210", ask).error)

    // Another campus's registry cannot reach a Cuom course or a Cuom lecturer.
    signInAs(repo, SeedUsers.ifmRegistry)
    assertEquals(AssignLecturersError.UNKNOWN_COURSE, repo.setCourseLecturers("CS210", ask).error)
    assertTrue(repo.campusLecturers().isEmpty())

    // The app manager has no campus, so it cannot assign either.
    signInAs(repo, SeedUsers.platformManager)
    assertEquals(AssignLecturersError.NOT_ALLOWED, repo.setCourseLecturers("CS210", ask).error)

    // Inside Cuom: unknown course, a student, or a person from another campus are all refused, and nothing changes.
    signInAs(repo, SeedUsers.emanuel)
    assertEquals(AssignLecturersError.UNKNOWN_COURSE, repo.setCourseLecturers("NOPE999", ask).error)
    assertEquals(AssignLecturersError.NOT_A_LECTURER, repo.setCourseLecturers("CS210", setOf(SeedUsers.asha.id)).error)
    assertEquals(AssignLecturersError.NOT_A_LECTURER, repo.setCourseLecturers("CS210", setOf(SeedUsers.ifmRegistry.id)).error)
    assertEquals(AssignLecturersError.NOT_A_LECTURER, repo.setCourseLecturers("CS210", setOf(SeedUsers.mushi.id, "usr-ghost")).error)
    assertEquals(listOf(SeedUsers.mushi.id), repo.lecturersFor("CS210").map { it.id })
    assertTrue(repo.auditLogs.value.none { it.action == "COURSE_LECTURERS_SET" })
  }
}
