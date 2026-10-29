package com.willykez.campconnect.data.repository

import com.willykez.campconnect.data.auth.AuthRepository
import com.willykez.campconnect.data.auth.DeviceSession
import com.willykez.campconnect.data.auth.Session
import com.willykez.campconnect.data.model.AccountStatus
import com.willykez.campconnect.util.DeadlineTime
import com.willykez.campconnect.data.model.StartResult
import com.willykez.campconnect.data.model.QuizScore
import com.willykez.campconnect.data.model.QuizQuestion
import com.willykez.campconnect.data.model.QuizError
import com.willykez.campconnect.data.model.QuizAttempt
import com.willykez.campconnect.data.model.Quiz
import com.willykez.campconnect.data.model.QuestionType
import com.willykez.campconnect.data.model.AttemptStatus
import com.willykez.campconnect.data.model.Announcement
import com.willykez.campconnect.data.model.AnnouncementPriority
import com.willykez.campconnect.data.model.AppLanguage
import com.willykez.campconnect.data.model.AttachmentFile
import com.willykez.campconnect.data.model.AudienceType
import com.willykez.campconnect.data.model.AuditLog
import com.willykez.campconnect.data.model.ClassSession
import com.willykez.campconnect.data.model.ContentApprovalStatus
import com.willykez.campconnect.data.model.DeadlineItem
import com.willykez.campconnect.data.model.NotificationItem
import com.willykez.campconnect.data.model.NotificationType
import com.willykez.campconnect.data.model.Role
import com.willykez.campconnect.data.model.SessionStatus
import com.willykez.campconnect.data.model.StudentSubmission
import com.willykez.campconnect.data.model.StudyMaterial
import com.willykez.campconnect.data.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** One place a message can be sent to, offered in the publish dialog. */
data class AudienceOption(
  val label: String,
  val type: AudienceType,
  val name: String,
  val code: String
)

/** Why a post was refused. The screen turns this into English or Kiswahili text. */
enum class PostError {
  NOT_ALLOWED,
  CAMPUS_REGISTRY_ONLY,
  AUDIENCE_NOT_YOURS,
  URGENT_NOT_ALLOWED,
  URGENT_LIMIT_REACHED,
  RESPONSIBLE_NOT_FOR_COURSE
}

/** Why a class could not be scheduled. The screen turns this into words. */
/** Why the registry could not change who teaches a course. */
enum class AssignLecturersError { NOT_ALLOWED, UNKNOWN_COURSE, NOT_A_LECTURER, HAS_CLASSES }

/** [error] is null when the change was saved; [names] lists the lecturers blocking it (HAS_CLASSES). */
data class AssignLecturersResult(val error: AssignLecturersError? = null, val names: String = "")

enum class SessionError { NOT_ALLOWED, UNKNOWN_COURSE, LECTURER_NOT_FOR_COURSE, TIME_INVALID, ROOM_REQUIRED, ROOM_BUSY, LECTURER_BUSY }

/** A room and, if it is taken at the chosen time, by which course. */
data class RoomOption(val name: String, val busyWith: String?)

enum class PlatformError { NOT_ALLOWED, CAMPUS_EXISTS, DETAILS_MISSING }
enum class WipeScope { ANNOUNCEMENTS, TIMETABLE, MATERIALS, WORKS, NOTIFICATIONS, CATALOG, PEOPLE, EVERYTHING }

data class PlatformResult(
  val ok: Boolean,
  val platformError: PlatformError? = null,
  val authError: com.willykez.campconnect.data.auth.AuthError? = null
)

/** What the app manager sees about a campus: totals only, never the content itself. */
data class CampusSummary(
  val university: com.willykez.campconnect.data.model.University,
  val people: Int,
  val pending: Int,
  val admins: Int,
  val programmes: Int,
  val courses: Int,
  val rooms: Int,
  val sessions: Int,
  val announcements: Int,
  val materials: Int,
  val works: Int
)

class CampusRepository {

  companion object {
    /** Answers still on the screen when the timer ends are accepted for this long. */
    const val SUBMIT_GRACE_MS = 10_000L
    /** Most Urgent notices one lecturer may send in a day. Keeps "Urgent" meaningful. */
    const val DAILY_URGENT_LIMIT = 2
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  // ---------- Who is using the app ----------

  /** Programmes, courses, rooms and campuses. Each campus administrator fills in their own. */
  val catalog = CampusCatalog()

  val auth = AuthRepository(catalog)
  val session: StateFlow<Session?> = auth.session
  val devices: StateFlow<Map<String, List<DeviceSession>>> = auth.devices

  private val signedOutPlaceholder = UserProfile(
    id = "none",
    fullName = "",
    email = "",
    role = Role.STUDENT,
    status = AccountStatus.PENDING
  )

  /** Demo mode only: the person chosen with the persona switcher. */
  private val _demoUser = MutableStateFlow(SeedUsers.asha)

  /**
   * The active person. In a real session this always comes from the account record, so nothing on
   * the phone can change a role. Only demo mode may pick a persona by hand.
   */
  val currentUser: StateFlow<UserProfile> = combine(auth.session, auth.accounts, _demoUser) { s, accounts, demo ->
    when {
      s == null -> signedOutPlaceholder
      s.isDemo -> demo
      else -> accounts[s.userId]?.profile ?: signedOutPlaceholder
    }
  }.stateIn(scope, SharingStarted.Eagerly, signedOutPlaceholder)

  val isDemoMode: StateFlow<Boolean> = auth.session
    .map { it?.isDemo == true }
    .stateIn(scope, SharingStarted.Eagerly, false)

  val demoPersonas: List<UserProfile> = SeedUsers.demoPersonas

  // ---------- Preferences and sync ----------

  private val _appLanguage = MutableStateFlow(AppLanguage.ENGLISH)
  val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

  private val _isOffline = MutableStateFlow(false)
  val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

  /** Epoch millis of the last successful sync, or 0 when this phone holds no synced data. */
  private val _lastSyncedAt = MutableStateFlow(System.currentTimeMillis())
  val lastSyncedAt: StateFlow<Long> = _lastSyncedAt.asStateFlow()

  private val _is24HourFormat = MutableStateFlow(true)
  val is24HourFormat: StateFlow<Boolean> = _is24HourFormat.asStateFlow()

  // Initial Class Sessions
  // Initial Class Sessions
  private val _sessions = MutableStateFlow<List<ClassSession>>(
    listOf(
      ClassSession(
        id = "sess-01",
        courseCode = "CS210",
        courseTitle = "Data Structures & Algorithms",
        lecturerId = "usr-lec-01",
        lecturerName = "Dr. Joseph Mushi",
        venue = "LTB Room 4",
        dayOfWeek = 1,
        startTime = "08:00",
        endTime = "10:00",
        status = SessionStatus.SCHEDULED,
        programme = "BSc Computer Science",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      ClassSession(
        id = "sess-02",
        courseCode = "MT101",
        courseTitle = "Discrete Mathematics & Logic",
        lecturerId = "usr-lec-02",
        lecturerName = "Prof. Grace Temu",
        venue = "Science Complex Hall B",
        dayOfWeek = 1,
        startTime = "11:00",
        endTime = "13:00",
        status = SessionStatus.SCHEDULED,
        programme = "BSc Computer Science",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      ClassSession(
        id = "sess-03",
        courseCode = "CS212",
        courseTitle = "Computer Architecture & Org.",
        lecturerId = "usr-lec-03",
        lecturerName = "Dr. Salim Bakari",
        venue = "Computer Lab 3 (Moved from LTB 2)",
        originalVenue = "LTB Room 2",
        dayOfWeek = 2,
        startTime = "09:00",
        endTime = "11:00",
        status = SessionStatus.CHANGED,
        changeReason = "Projector lamp failure in LTB 2. Relocated to Computer Lab 3.",
        programme = "BSc Computer Science",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      ClassSession(
        id = "sess-04",
        courseCode = "IS202",
        courseTitle = "Relational Database Management Systems",
        lecturerId = "usr-lec-04",
        lecturerName = "Eng. Daniel Kweka",
        venue = "LTB Room 1",
        dayOfWeek = 2,
        startTime = "14:00",
        endTime = "16:00",
        status = SessionStatus.SCHEDULED,
        programme = "BSc Computer Science",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      ClassSession(
        id = "sess-05",
        courseCode = "CS215",
        courseTitle = "Object-Oriented Software Engineering",
        lecturerId = "usr-lec-01",
        lecturerName = "Dr. Joseph Mushi",
        venue = "Main Hall South",
        dayOfWeek = 3,
        startTime = "08:00",
        endTime = "10:00",
        status = SessionStatus.SCHEDULED,
        programme = "BSc Computer Science",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      ClassSession(
        id = "sess-06",
        courseCode = "CS210-L",
        courseTitle = "CS210 Lab: Tree Traversal & Heaps",
        lecturerId = "usr-lec-01",
        lecturerName = "Dr. Joseph Mushi",
        venue = "Advanced Software Lab",
        dayOfWeek = 4,
        startTime = "10:00",
        endTime = "12:00",
        status = SessionStatus.SCHEDULED,
        programme = "BSc Computer Science",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      ClassSession(
        id = "sess-07",
        courseCode = "GS101",
        courseTitle = "Development Perspectives & Ethics",
        lecturerId = "usr-lec-05",
        lecturerName = "Dr. Mariam Ndosi",
        venue = "LTB Room 3",
        dayOfWeek = 4,
        startTime = "14:00",
        endTime = "16:00",
        status = SessionStatus.CANCELLED,
        changeReason = "Lecturer attending Faculty Academic Board Meeting.",
        programme = "BSc Computer Science",
        approvalStatus = ContentApprovalStatus.APPROVED
      )
    )
  )

  // Announcements
  // Announcements
  private val _announcements = MutableStateFlow<List<Announcement>>(
    listOf(
      Announcement(
        id = "ann-01",
        authorId = "usr-lec-01",
        authorName = "Dr. Joseph Mushi",
        authorRole = "Senior Lecturer • Head of CS",
        title = "CS210 Midterm Continuous Assessment Test (CAT) Rescheduled",
        body = "Please note that the Midterm CAT originally slated for this Thursday has been moved to Monday 26 Oct 2026 at 08:00 in Main Examination Hall. The test will cover Binary Search Trees, AVL Trees, and Graph Traversal algorithms. Bring your official student IDs.",
        audienceType = AudienceType.COURSE,
        audienceName = "CS210 - Algorithms",
        audienceCode = "CS210",
        publishedAt = "Today, 10:45 EAT",
        priority = AnnouncementPriority.URGENT,
        readCount = 48,
        totalStudents = 54,
        isRead = false,
        attachment = AttachmentFile(
          fileName = "CAT_Revision_Topics_2026.pdf",
          fileSize = "1.2 MB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "TOPICS INCLUDED FOR CS210 MIDTERM:\n1. Binary Trees: Height balance, recursive depth, Morris traversal\n2. AVL Trees: Single & double rotations, balance factors\n3. Graph Algorithms: Adjacency list representation, Breadth-First Search, Depth-First Search, topological sorting\n4. Space and Time Complexity calculations (Big-O analysis)."
        ),
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      Announcement(
        id = "ann-02",
        authorId = "usr-adm-01",
        authorName = "Academic Registry Office",
        authorRole = "Central Administration",
        title = "Deadline for Semester 1 Course Registration and Verification",
        body = "All undergraduate students are reminded that online course confirmation closes this Friday at 23:59 EAT. Ensure your fees clearance is updated with the bursar before submitting your registration slip to the department coordinator.",
        audienceType = AudienceType.CAMPUS,
        audienceName = "All Students • Campus-wide",
        publishedAt = "Yesterday, 14:20 EAT",
        priority = AnnouncementPriority.ACADEMIC,
        readCount = 311,
        totalStudents = 450,
        isRead = true,
        attachment = AttachmentFile(
          fileName = "Registration_Notice_Schedule.pdf",
          fileSize = "850 KB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "UNIVERSITY OF CAMPUSCONNECT TANZANIA\nOFFICE OF THE ACADEMIC REGISTRAR\n\nCIRCULAR: 2026/S1-04\nSubject: Semester 1 Student Clearance Verification\n1. Payment validation deadline: 24 Oct 2026.\n2. In-person biometric check: Student Center Wing B."
        ),
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      Announcement(
        id = "ann-03",
        authorId = "usr-lec-04",
        authorName = "Eng. Daniel Kweka",
        authorRole = "Lecturer • Database Systems",
        title = "PostgreSQL Lab Server Maintenance This Wednesday Evening",
        body = "The departmental database server (db.campus.ac.tz) will undergo storage expansion and PostgreSQL 16 upgrade on Wednesday between 18:00 and 22:00 EAT. Please commit and export your sql dumps before maintenance begins.",
        audienceType = AudienceType.PROGRAMME,
        audienceName = "BSc Computer Science",
        audienceCode = "BSc Computer Science",
        publishedAt = "18 Oct 2026, 09:15 EAT",
        priority = AnnouncementPriority.NORMAL,
        readCount = 75,
        totalStudents = 95,
        isRead = true,
        attachment = AttachmentFile(
          fileName = "db_backup_instructions.pdf",
          fileSize = "480 KB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "POSTGRESQL BACKUP GUIDELINES:\npg_dump -U student -h db.campus.ac.tz -d my_db > backup_oct2026.sql\nVerify file size and store on local machine."
        ),
        approvalStatus = ContentApprovalStatus.APPROVED
      )
    )
  )

  // Study Materials
  // Study Materials
  private val _materials = MutableStateFlow<List<StudyMaterial>>(
    listOf(
      StudyMaterial(
        id = "mat-01",
        courseCode = "CS210",
        courseTitle = "Data Structures & Algorithms",
        title = "Module 4: Balanced Search Trees & Red-Black Trees",
        uploaderId = "usr-lec-01",
        uploadedBy = "Dr. Joseph Mushi",
        uploadedAt = "18 Oct 2026",
        attachment = AttachmentFile(
          fileName = "Module_4_RedBlackTrees.pdf",
          fileSize = "2.8 MB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "MODULE 4: RED-BLACK BALANCED TREES\nLecturer: Dr. Joseph Mushi\n\nProperties:\n1. Every node is either red or black.\n2. Root is always black.\n3. Every leaf (NIL) is black.\n4. If a node is red, both children are black.\n5. For each node, all simple paths from the node to descendant leaves contain the same number of black nodes."
        ),
        isDownloaded = true,
        description = "Comprehensive lecture slides and pseudocode implementations for Red-Black Tree rotations and node deletion.",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      StudyMaterial(
        id = "mat-02",
        courseCode = "CS210",
        courseTitle = "Data Structures & Algorithms",
        title = "Tutorial Sheet 3: Graph Algorithms & Dijkstra",
        uploaderId = "usr-lec-01",
        uploadedBy = "Dr. Joseph Mushi",
        uploadedAt = "16 Oct 2026",
        attachment = AttachmentFile(
          fileName = "Tutorial_Sheet_03_Graphs.pdf",
          fileSize = "1.1 MB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "TUTORIAL SHEET 3\nQ1: Trace Dijkstra's algorithm on the given graph starting from node A.\nQ2: Prove whether Prim's algorithm works correctly with negative edge weights."
        ),
        isDownloaded = false,
        description = "Practice problems on shortest path algorithms and minimum spanning trees.",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      StudyMaterial(
        id = "mat-03",
        courseCode = "IS202",
        courseTitle = "Database Systems",
        title = "Relational Algebra & Advanced SQL Queries Handout",
        uploaderId = "usr-lec-04",
        uploadedBy = "Eng. Daniel Kweka",
        uploadedAt = "14 Oct 2026",
        attachment = AttachmentFile(
          fileName = "Relational_Algebra_Handout.pdf",
          fileSize = "3.4 MB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "RELATIONAL ALGEBRA HANDOUT\nCovers: Selection, Projection, Cross-Product, Theta Join, Outer Joins, and Aggregation."
        ),
        isDownloaded = true,
        description = "Covers joins, aggregation grouping, window functions, and indexing strategies in PostgreSQL.",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      StudyMaterial(
        id = "mat-04",
        courseCode = "MT101",
        courseTitle = "Discrete Mathematics",
        title = "Set Theory, Relations & Combinatorics Summary Notes",
        uploaderId = "usr-lec-02",
        uploadedBy = "Prof. Grace Temu",
        uploadedAt = "12 Oct 2026",
        attachment = AttachmentFile(
          fileName = "MT101_SetTheory_Summary.pdf",
          fileSize = "4.2 MB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "DISCRETE MATHEMATICS & LOGIC\nProf. Grace Temu\nSection 1: Propositional Logic and Truth Tables.\nSection 2: Pigeonhole Principle and Permutations."
        ),
        isDownloaded = false,
        description = "Lecture summaries and past question walkthroughs for the midterm revision.",
        approvalStatus = ContentApprovalStatus.APPROVED
      )
    )
  )

  // Deadlines & Academic Calendar
  // Deadlines & Academic Calendar
  private val _deadlines = MutableStateFlow<List<DeadlineItem>>(
    listOf(
      DeadlineItem(
        id = "dl-01",
        courseCode = "CS210",
        courseTitle = "Data Structures & Algorithms",
        lecturerId = "usr-lec-01",
        lecturerName = "Dr. Joseph Mushi",
        title = "Programming Assignment 2: Graph Cycle Detection",
        instructions = "Implement Dijkstra and Tarjan's algorithm in Kotlin, Java, or Python. Submit your clean source code PDF report or screenshot test run.",
        dueDateTime = "Tomorrow, 22 Oct 2026 • 23:59 EAT",
        urgencyText = "Due in 24 hours",
        acceptedFormats = "PDF or Images (Max 15 MB)",
        isCompleted = false,
        submittedCount = 38,
        totalStudents = 54,
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      DeadlineItem(
        id = "dl-02",
        courseCode = "IS202",
        courseTitle = "Relational Databases",
        lecturerId = "usr-lec-04",
        lecturerName = "Eng. Daniel Kweka",
        title = "Database Schema Design Proposal",
        instructions = "Submit ER diagrams (image or PDF), 3NF normalization justification, and PostgreSQL DDL scripts for your university hostel allocation system.",
        dueDateTime = "Friday, 24 Oct 2026 • 17:00 EAT",
        urgencyText = "Due in 3 days",
        acceptedFormats = "PDF, PNG or JPG (Max 20 MB)",
        isCompleted = true,
        submittedCount = 51,
        totalStudents = 54,
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      DeadlineItem(
        id = "dl-03",
        courseCode = "ACADEMIC",
        courseTitle = "University Registry",
        lecturerId = "usr-adm-01",
        lecturerName = "Emanuel Mwenda",
        title = "Semester 1 Course Registration Slip Verification",
        instructions = "Upload a clear scanned photo or PDF of your fee clearance slip with official registry stamps.",
        dueDateTime = "Friday, 24 Oct 2026 • 16:00 EAT",
        urgencyText = "Due in 3 days",
        acceptedFormats = "PDF or Image photo (Max 10 MB)",
        isCompleted = false,
        submittedCount = 320,
        totalStudents = 450,
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      DeadlineItem(
        id = "dl-04",
        courseCode = "CS215",
        courseTitle = "Software Engineering",
        lecturerId = "usr-lec-01",
        lecturerName = "Dr. Joseph Mushi",
        title = "Sprint 1 User Stories & Architecture Specification",
        instructions = "Submit software architecture document following IEEE 830 format and Figma diagram exports.",
        dueDateTime = "Monday, 27 Oct 2026 • 12:00 EAT",
        urgencyText = "Next week",
        acceptedFormats = "PDF only (Max 25 MB)",
        isCompleted = false,
        submittedCount = 14,
        totalStudents = 54,
        approvalStatus = ContentApprovalStatus.APPROVED
      )
    )
  )

  // Student Submissions for assignments
  // Student Submissions for assignments
  private val _submissions = MutableStateFlow<List<StudentSubmission>>(
    listOf(
      StudentSubmission(
        id = "sub-01",
        deadlineId = "dl-01",
        courseCode = "CS210",
        assignmentTitle = "Programming Assignment 2: Graph Cycle Detection",
        studentId = "usr-stu-01",
        studentName = "Asha Mwangi",
        studentNumber = "T22-03-08492",
        notes = "Tarjan's algorithm implemented with recursive DFS. Included test cases with cyclic and acyclic graphs.",
        attachment = AttachmentFile(
          fileName = "CS210_AshaMwangi_Tarjan_Report.pdf",
          fileSize = "1.8 MB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "CS210 ASSIGNMENT 2: TARJAN'S SCC & CYCLE DETECTION\nStudent: Asha Mwangi (T22-03-08492)\n\nImplementation:\nClass GraphCycleDetector(val vertices: Int) {\n  private val adj = Array(vertices) { mutableListOf<Int>() }\n  fun hasCycle(): Boolean { ... }\n}\nComplexity: O(V + E) verified with JUnit tests."
        ),
        submittedAt = "20 Oct 2026, 16:45 EAT",
        grade = "92/100",
        feedback = "Excellent work Asha! The Tarjan SCC stack implementation is mathematically sound and unit tests covered edge cases.",
        approvalStatus = ContentApprovalStatus.APPROVED
      ),
      StudentSubmission(
        id = "sub-02",
        deadlineId = "dl-01",
        courseCode = "CS210",
        assignmentTitle = "Programming Assignment 2: Graph Cycle Detection",
        studentId = "usr-stu-02",
        studentName = "Kelvin Tarimo",
        studentNumber = "T22-03-09120",
        notes = "Submission contains Dijkstra implementation and cycle check.",
        attachment = AttachmentFile(
          fileName = "Tarimo_CS210_Solution.pdf",
          fileSize = "2.1 MB",
          mimeType = "application/pdf",
          isImage = false,
          previewText = "CS210 ASSIGNMENT SUBMISSION\nStudent: Kelvin Tarimo\nAlgorithms: Dijkstra and Cycle Detection."
        ),
        submittedAt = "20 Oct 2026, 18:20 EAT",
        grade = null,
        feedback = null,
        approvalStatus = ContentApprovalStatus.PENDING_REVIEW
      )
    )
  )

  // Notifications (personal: each item belongs to one person)
  // Notifications
  private val _notifications = MutableStateFlow<List<NotificationItem>>(
    listOf(
      NotificationItem(
        id = "notif-01",
        userId = "usr-stu-01",
        title = "Venue Changed for CS212",
        message = "Computer Architecture on Tuesday moved from LTB 2 to Computer Lab 3.",
        type = NotificationType.TIMETABLE_CHANGE,
        timestamp = "Today, 11:30 EAT",
        isRead = false
      ),
      NotificationItem(
        id = "notif-02",
        userId = "usr-stu-01",
        title = "Urgent: CS210 Midterm CAT Moved",
        message = "Dr. Mushi posted an update regarding the rescheduled examination hall date.",
        type = NotificationType.ANNOUNCEMENT,
        timestamp = "Today, 10:45 EAT",
        isRead = false
      ),
      NotificationItem(
        id = "notif-03",
        userId = "usr-stu-01",
        title = "Assignment Graded: CS210",
        message = "Dr. Joseph Mushi graded your Assignment 2: 92/100.",
        type = NotificationType.GRADE_POSTED,
        timestamp = "Today, 17:00 EAT",
        isRead = false
      ),
      NotificationItem(
        id = "notif-04",
        userId = "usr-stu-01",
        title = "Deadline Tomorrow",
        message = "CS210 Programming Assignment 2 is due tomorrow at 23:59 EAT.",
        type = NotificationType.DEADLINE_ALERT,
        timestamp = "Today, 08:00 EAT",
        isRead = true
      )
    )
  )

  // Audit Logs
  // Audit Logs
  private val _auditLogs = MutableStateFlow<List<AuditLog>>(
    listOf(
      AuditLog(
        id = "log-01",
        actor = "Emanuel Mwenda (Super Admin)",
        action = "ADMIN_POLICY_CONFIG",
        details = "Switched new accounts to approval-first access: nothing is visible until the registry approves.",
        timestamp = "21 Oct 2026, 08:00 EAT"
      ),
      AuditLog(
        id = "log-02",
        actor = "Dr. Joseph Mushi (Lecturer)",
        action = "ASSIGNMENT_GRADED",
        details = "Graded submission for Asha Mwangi: 92/100",
        timestamp = "20 Oct 2026, 17:00 EAT"
      ),
      AuditLog(
        id = "log-03",
        actor = "Dr. Joseph Mushi (Lecturer)",
        action = "VENUE_CHANGE",
        details = "Changed CS212 venue from LTB 2 to Computer Lab 3.",
        timestamp = "19 Oct 2026, 11:30 EAT"
      )
    )
  )

  // ---------- Per-person state (private to each person) ----------

  // Who opened which announcement: announcementId -> students. A student only ever sees their own.
  // The seeded counts below exclude Asha, who is recorded here.
  private val _readReceipts = MutableStateFlow<Map<String, Set<String>>>(
    mapOf("ann-02" to setOf(SeedUsers.asha.id), "ann-03" to setOf(SeedUsers.asha.id))
  )

  private val _remindedAnnouncements = MutableStateFlow<Set<String>>(emptySet())
  val remindedAnnouncements: StateFlow<Set<String>> = _remindedAnnouncements.asStateFlow()

  // "Done" ticks are private: userId -> deadline ids. No lecturer or administrator can read them.
  private val _doneTicks = MutableStateFlow<Map<String, Set<String>>>(
    mapOf(SeedUsers.asha.id to setOf("dl-02"))
  )

  // Offline copies kept on this phone, per person: userId -> material ids.
  private val _downloads = MutableStateFlow<Map<String, Set<String>>>(
    mapOf(SeedUsers.asha.id to setOf("mat-01", "mat-03"))
  )

  // ---------- What each person is allowed to see ----------
  // Every list the screens read is filtered for the signed-in person right here.

  val sessions: StateFlow<List<ClassSession>> = combine(_sessions, currentUser) { list, user ->
    list.filter { canSeeSession(it, user) }
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  val announcements: StateFlow<List<Announcement>> =
    combine(_announcements, currentUser, _readReceipts) { list, user, receipts ->
      list.filter { canSeeAnnouncement(it, user) }.map { a ->
        val readers = receipts[a.id].orEmpty()
        a.copy(
          isRead = user.id in readers || a.authorId == user.id,
          readCount = a.readCount + readers.size
        )
      }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  val materials: StateFlow<List<StudyMaterial>> =
    combine(_materials, currentUser, _downloads) { list, user, downloads ->
      val mine = downloads[user.id].orEmpty()
      list.filter { canSeeMaterial(it, user) }.map { it.copy(isDownloaded = it.id in mine) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  val deadlines: StateFlow<List<DeadlineItem>> =
    combine(_deadlines, currentUser, _doneTicks) { list, user, ticks ->
      val mine = if (user.role == Role.STUDENT) ticks[user.id].orEmpty() else emptySet()
      list.filter { canSeeDeadline(it, user) }.map { it.copy(isCompleted = it.id in mine) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  val submissions: StateFlow<List<StudentSubmission>> = combine(_submissions, currentUser) { list, user ->
    list.filter { canSeeSubmission(it, user) }
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  val notifications: StateFlow<List<NotificationItem>> = combine(_notifications, currentUser) { list, user ->
    list.filter { it.userId == user.id }
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  // Only administrators can read the audit log. Nothing in the app edits or erases an entry.
  val auditLogs: StateFlow<List<AuditLog>> = combine(_auditLogs, currentUser) { list, user ->
    if (user.role == Role.ADMIN) list.filter { it.universityId == user.universityId } else emptyList()
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  // Accounts waiting for the Academic Registry. Only people who proved their email appear here.
  // Each university's administrator sees only the people waiting to join that university.
  val pendingUsers: StateFlow<List<UserProfile>> = combine(auth.accounts, currentUser) { accounts, user ->
      accounts.values
        .filter { it.profile.status == AccountStatus.PENDING && it.emailVerified && it.profile.universityId == user.universityId }
        .map { it.profile }
    }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  // ---------- Who may see what ----------

  private fun baseCourse(code: String): String = code.substringBefore('-')

  private fun isActive(user: UserProfile) = user.status == AccountStatus.APPROVED

  /** The university a person belongs to. Content belongs to the university of whoever made it. */
  private fun universityOf(userId: String): String =
    auth.accounts.value[userId]?.profile?.universityId ?: ""

  /** Everything stays inside one campus: nobody sees another university's data. */
  private fun sameCampus(ownerId: String, user: UserProfile): Boolean = universityOf(ownerId) == user.universityId

  private fun canSeeSession(s: ClassSession, user: UserProfile): Boolean {
    if (!isActive(user)) return false
    if (!sameCampus(s.lecturerId, user)) return false
    if (user.role == Role.ADMIN) return true
    if (s.lecturerId == user.id) return true
    if (s.approvalStatus != ContentApprovalStatus.APPROVED) return false
    return when (user.role) {
      Role.STUDENT -> baseCourse(s.courseCode) in user.enrolledCourses
      Role.LECTURER -> baseCourse(s.courseCode) in user.teachesCourses
      Role.ADMIN -> true
    }
  }

  private fun canSeeAnnouncement(a: Announcement, user: UserProfile): Boolean {
    if (!isActive(user)) return false
    if (!sameCampus(a.authorId, user)) return false
    if (user.role == Role.ADMIN) return true
    if (a.authorId == user.id) return true // authors keep sight of their own, drafts and retracted included
    if (a.approvalStatus != ContentApprovalStatus.APPROVED || a.isRetracted) return false
    return when (a.audienceType) {
      AudienceType.CAMPUS -> true
      AudienceType.PROGRAMME -> when (user.role) {
        Role.STUDENT -> user.programme == a.audienceCode
        Role.LECTURER -> user.teachesCourses.any { it in catalog.catalogFor(user.universityId)[a.audienceCode].orEmpty() }
        Role.ADMIN -> true
      }
      AudienceType.COURSE -> when (user.role) {
        Role.STUDENT -> a.audienceCode in user.enrolledCourses
        Role.LECTURER -> a.audienceCode in user.teachesCourses
        Role.ADMIN -> true
      }
    }
  }

  private fun canSeeMaterial(m: StudyMaterial, user: UserProfile): Boolean {
    if (!isActive(user)) return false
    if (!sameCampus(m.uploaderId, user)) return false
    if (user.role == Role.ADMIN) return true
    if (m.uploaderId == user.id) return true
    if (m.approvalStatus != ContentApprovalStatus.APPROVED) return false
    val code = baseCourse(m.courseCode)
    return when (user.role) {
      Role.STUDENT -> code in user.enrolledCourses
      Role.LECTURER -> code in user.teachesCourses
      Role.ADMIN -> true
    }
  }

  private fun canSeeDeadline(d: DeadlineItem, user: UserProfile): Boolean {
    if (!isActive(user)) return false
    if (!sameCampus(d.lecturerId, user)) return false
    if (user.role == Role.ADMIN) return true
    if (d.lecturerId == user.id) return true
    if (d.approvalStatus != ContentApprovalStatus.APPROVED) return false
    return when (user.role) {
      Role.STUDENT -> d.courseCode == "ACADEMIC" || baseCourse(d.courseCode) in user.enrolledCourses
      Role.LECTURER -> baseCourse(d.courseCode) in user.teachesCourses
      Role.ADMIN -> true
    }
  }

  private fun canSeeSubmission(s: StudentSubmission, user: UserProfile): Boolean {
    if (!isActive(user)) return false
    if (!sameCampus(s.studentId, user)) return false
    return when (user.role) {
      Role.STUDENT -> s.studentId == user.id // never another student's work
      Role.LECTURER -> baseCourse(s.courseCode) in user.teachesCourses
      Role.ADMIN -> true
    }
  }

  // ---------- Recipients ----------

  private fun activeProfiles(): List<UserProfile> =
    auth.accounts.value.values.map { it.profile }
      .filter { it.status == AccountStatus.APPROVED && it.universityId == currentUser.value.universityId }

  private fun courseStudentIds(courseCode: String): List<String> =
    activeProfiles().filter { it.role == Role.STUDENT && baseCourse(courseCode) in it.enrolledCourses }.map { it.id }

  private fun audienceRecipientIds(type: AudienceType, code: String): List<String> = when (type) {
    AudienceType.CAMPUS -> activeProfiles().map { it.id }
    AudienceType.PROGRAMME -> activeProfiles().filter { it.role == Role.STUDENT && it.programme == code }.map { it.id }
    AudienceType.COURSE -> courseStudentIds(code)
  }

  private fun notifyUsers(userIds: Collection<String>, title: String, message: String, type: NotificationType) {
    val ids = userIds.distinct()
    if (ids.isEmpty()) return
    val items = ids.map {
      NotificationItem(
        id = "notif-" + UUID.randomUUID().toString().take(8),
        userId = it,
        title = title,
        message = message,
        type = type,
        timestamp = "Just now",
        isRead = false
      )
    }
    _notifications.value = items + _notifications.value
  }

  init {
    // New-device notices from the sign-in layer land in the person's own notifications.
    auth.onNotify = { userId, title, message ->
      notifyUsers(listOf(userId), title, message, NotificationType.APPROVAL)
    }
    // Signing in counts as a fresh sync.
    scope.launch {
      auth.session.collect { s -> if (s != null) _lastSyncedAt.value = System.currentTimeMillis() }
    }
  }

  // ---------- Permission checks ----------

  // Admin can edit/delete anything. Everyone else only what they authored or uploaded.
  fun canEditOrDelete(uploaderOrAuthorId: String): Boolean {
    val user = currentUser.value
    if (!isActive(user)) return false
    return user.role == Role.ADMIN || user.id == uploaderOrAuthorId
  }

  fun canApproveOrDisapprove(): Boolean {
    val user = currentUser.value
    return isActive(user) && user.role == Role.ADMIN
  }

  /** Where the current person may post, offered as choices in the publish dialog. */
  fun audienceOptions(): List<AudienceOption> {
    val user = currentUser.value
    if (!isActive(user)) return emptyList()
    fun courseOption(code: String): AudienceOption {
      val title = catalog.coursesFor(user.universityId)[code]
        ?: _sessions.value.firstOrNull { baseCourse(it.courseCode) == code }?.courseTitle
      return AudienceOption(code, AudienceType.COURSE, if (title != null) "$code - $title" else code, code)
    }
    return when (user.role) {
      Role.ADMIN -> {
        val courses = catalog.coursesFor(user.universityId).keys.sorted().map { courseOption(it) }
        val programmes = catalog.programmesFor(user.universityId).map { AudienceOption(it, AudienceType.PROGRAMME, it, it) }
        listOf(AudienceOption("Whole campus", AudienceType.CAMPUS, "Whole campus", "")) + programmes + courses
      }
      Role.LECTURER -> {
        val courses = user.teachesCourses.sorted().map { courseOption(it) }
        val programmes = catalog.catalogFor(user.universityId)
          .filter { (_, codes) -> codes.any { it in user.teachesCourses } }
          .keys.map { AudienceOption(it, AudienceType.PROGRAMME, it, it) }
        courses + programmes
      }
      Role.STUDENT -> emptyList()
    }
  }

  fun urgentLeftToday(): Int {
    val user = currentUser.value
    return when (user.role) {
      Role.ADMIN -> Int.MAX_VALUE
      Role.LECTURER -> (DAILY_URGENT_LIMIT - urgentSentToday(user.id)).coerceAtLeast(0)
      Role.STUDENT -> 0
    }
  }

  fun canUseUrgentFor(type: AudienceType): Boolean {
    val user = currentUser.value
    return user.role == Role.ADMIN || (user.role == Role.LECTURER && type == AudienceType.COURSE)
  }

  private val urgentCounts = mutableMapOf<String, Int>()

  private fun todayKey(userId: String): String =
    userId + "|" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

  private fun urgentSentToday(userId: String): Int = urgentCounts[todayKey(userId)] ?: 0

  // ---------- Preferences ----------

  fun setLanguage(language: AppLanguage) {
    _appLanguage.value = language
  }

  fun toggle24HourFormat() {
    _is24HourFormat.value = !_is24HourFormat.value
  }

  fun toggleOfflineSimulation() {
    _isOffline.value = !_isOffline.value
    if (!_isOffline.value) _lastSyncedAt.value = System.currentTimeMillis()
  }

  fun triggerSync() {
    if (_isOffline.value) return // nothing to sync with
    _lastSyncedAt.value = System.currentTimeMillis()
  }

  // ---------- Demo mode ----------

  fun startDemo() {
    _demoUser.value = SeedUsers.asha
    auth.startDemoSession(SeedUsers.asha.id)
  }

  /** The persona switcher exists only in demo mode. */
  fun switchDemoPersona(user: UserProfile) {
    if (!isDemoMode.value) return
    if (user.id !in demoPersonas.map { it.id }) return
    _demoUser.value = user
  }

  // ---------- Sessions ----------

  fun signOut() {
    wipeLocalCopies(currentUser.value.id)
    auth.signOut()
  }

  fun signOutEverywhere() {
    val id = currentUser.value.id
    wipeLocalCopies(id)
    auth.signOutEverywhere(id)
  }

  fun signOutDevice(deviceId: String) {
    val id = currentUser.value.id
    if (deviceId == auth.thisDeviceId) wipeLocalCopies(id)
    auth.signOutDevice(id, deviceId)
  }

  /** Removes what this phone keeps for the person: offline copies and the sync stamp. */
  private fun wipeLocalCopies(userId: String) {
    _downloads.value = _downloads.value - userId
    _lastSyncedAt.value = 0L
  }

  // ---------- Reading and ticking (always personal) ----------

  fun markAnnouncementRead(id: String) {
    val user = currentUser.value
    if (user.role != Role.STUDENT || !isActive(user)) return // only students' reads are counted
    val readers = _readReceipts.value[id].orEmpty()
    if (user.id in readers) return
    _readReceipts.value = _readReceipts.value + (id to (readers + user.id))
  }

  fun toggleDeadlineCompleted(id: String) {
    val user = currentUser.value
    if (user.role != Role.STUDENT || !isActive(user)) return
    val mine = _doneTicks.value[user.id].orEmpty()
    val next = if (id in mine) mine - id else mine + id
    _doneTicks.value = _doneTicks.value + (user.id to next)
  }

  fun toggleMaterialDownloaded(id: String) {
    val user = currentUser.value
    if (!isActive(user)) return
    val mine = _downloads.value[user.id].orEmpty()
    val next = if (id in mine) mine - id else mine + id
    _downloads.value = _downloads.value + (user.id to next)
  }

  fun markAllNotificationsRead() {
    val uid = currentUser.value.id
    _notifications.value = _notifications.value.map { if (it.userId == uid) it.copy(isRead = true) else it }
  }

  // ---------- Student work ----------

  fun submitStudentWork(
    deadlineId: String,
    courseCode: String,
    assignmentTitle: String,
    notes: String,
    attachment: AttachmentFile
  ) {
    val student = currentUser.value
    if (student.role != Role.STUDENT || !isActive(student)) return
    val deadline = _deadlines.value.find { it.id == deadlineId } ?: return
    if (!canSeeDeadline(deadline, student)) return
    val now = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date()) + " EAT"
    val newSub = StudentSubmission(
      id = "sub-" + UUID.randomUUID().toString().take(6),
      deadlineId = deadlineId,
      courseCode = courseCode,
      assignmentTitle = assignmentTitle,
      studentId = student.id,
      studentName = student.fullName,
      studentNumber = student.studentNumber ?: "STUDENT-${student.id.takeLast(4)}",
      notes = notes,
      attachment = attachment,
      submittedAt = now,
      approvalStatus = ContentApprovalStatus.PENDING_REVIEW
    )
    _submissions.value = listOf(newSub) + _submissions.value

    val alreadySubmitted = _doneTicks.value[student.id].orEmpty().contains(deadlineId)
    _deadlines.value = _deadlines.value.map { dl ->
      if (dl.id == deadlineId && !alreadySubmitted) dl.copy(submittedCount = dl.submittedCount + 1) else dl
    }
    _doneTicks.value = _doneTicks.value + (student.id to (_doneTicks.value[student.id].orEmpty() + deadlineId))

    notifyUsers(
      listOf(deadline.lecturerId),
      "Work submitted: $courseCode",
      "${student.fullName} submitted $assignmentTitle.",
      NotificationType.SUBMISSION_RECEIVED
    )
    recordAudit(
      action = "WORK_SUBMITTED",
      details = "${student.fullName} submitted work for $courseCode ($assignmentTitle) [${attachment.fileName}]"
    )
  }

  fun gradeStudentWork(submissionId: String, grade: String, feedback: String) {
    val actor = currentUser.value
    val existing = _submissions.value.find { it.id == submissionId } ?: return
    if (!canSeeSubmission(existing, actor) || actor.role == Role.STUDENT) return

    _submissions.value = _submissions.value.map { sub ->
      if (sub.id == submissionId) sub.copy(grade = grade, feedback = feedback, approvalStatus = ContentApprovalStatus.APPROVED) else sub
    }
    notifyUsers(
      listOf(existing.studentId),
      "Assignment Graded: ${existing.courseCode}",
      "${actor.fullName} posted your grade ($grade) for ${existing.assignmentTitle}",
      NotificationType.GRADE_POSTED
    )
    recordAudit(action = "WORK_GRADED", details = "Graded submission $submissionId with grade: $grade")
  }

  fun deleteStudentSubmission(submissionId: String) {
    val sub = _submissions.value.find { it.id == submissionId } ?: return
    if (!canEditOrDelete(sub.studentId)) return
    _submissions.value = _submissions.value.filter { it.id != submissionId }
    recordAudit(action = "SUBMISSION_DELETED", details = "Deleted submission ${sub.assignmentTitle} by ${sub.studentName}")
  }

  // ---------- Announcements ----------

  /** Returns null when posted, or the reason it was refused. */
  fun publishAnnouncement(
    title: String,
    body: String,
    audienceType: AudienceType,
    audienceName: String,
    audienceCode: String,
    priority: AnnouncementPriority,
    attachment: AttachmentFile? = null,
    responsibleLecturerId: String? = null
  ): PostError? {
    val author = currentUser.value
    if (!isActive(author) || author.role == Role.STUDENT) return PostError.NOT_ALLOWED

    // 1. Audience rules: only the registry may speak to the whole campus.
    when (audienceType) {
      AudienceType.CAMPUS -> if (author.role != Role.ADMIN) {
        recordAudit("POST_BLOCKED", "${author.fullName} tried to post campus-wide: '$title'")
        return PostError.CAMPUS_REGISTRY_ONLY
      }
      AudienceType.COURSE -> if (author.role == Role.LECTURER && audienceCode !in author.teachesCourses) {
        recordAudit("POST_BLOCKED", "${author.fullName} tried to post to course $audienceCode they do not teach: '$title'")
        return PostError.AUDIENCE_NOT_YOURS
      }
      AudienceType.PROGRAMME -> if (author.role == Role.LECTURER &&
        author.teachesCourses.none { it in catalog.catalogFor(author.universityId)[audienceCode].orEmpty() }
      ) {
        recordAudit("POST_BLOCKED", "${author.fullName} tried to post to programme $audienceCode: '$title'")
        return PostError.AUDIENCE_NOT_YOURS
      }
    }

    // The notice can be on behalf of a lecturer who teaches that course.
    var responsible: UserProfile? = null
    if (responsibleLecturerId != null && responsibleLecturerId != author.id) {
      responsible = auth.accounts.value[responsibleLecturerId]?.profile
      val valid = responsible != null && responsible.role == Role.LECTURER && responsible.status == AccountStatus.APPROVED &&
        responsible.universityId == author.universityId &&
        (audienceType != AudienceType.COURSE || audienceCode in responsible.teachesCourses)
      if (!valid) return PostError.RESPONSIBLE_NOT_FOR_COURSE
    }

    // 2. Urgent is rationed: registry freely, lecturers for their own course with a daily limit.
    if (priority == AnnouncementPriority.URGENT && author.role == Role.LECTURER) {
      if (audienceType != AudienceType.COURSE) {
        recordAudit("URGENT_BLOCKED", "${author.fullName} tried to mark a non-course notice Urgent: '$title'")
        return PostError.URGENT_NOT_ALLOWED
      }
      if (urgentSentToday(author.id) >= DAILY_URGENT_LIMIT) {
        recordAudit("URGENT_BLOCKED", "${author.fullName} reached the daily Urgent limit: '$title'")
        return PostError.URGENT_LIMIT_REACHED
      }
    }
    if (priority == AnnouncementPriority.URGENT && author.role == Role.LECTURER) {
      urgentCounts[todayKey(author.id)] = urgentSentToday(author.id) + 1
    }

    val recipients = audienceRecipientIds(audienceType, audienceCode).filter { it != author.id }
    val newAnn = Announcement(
      id = "ann-" + UUID.randomUUID().toString().take(6),
      authorId = author.id,
      authorName = author.fullName,
      authorRole = author.cohort ?: "Faculty Member",
      title = title,
      body = body,
      audienceType = audienceType,
      audienceName = audienceName,
      audienceCode = audienceCode,
      responsibleLecturerId = responsible?.id,
      responsibleLecturerName = responsible?.fullName,
      publishedAt = "Just now",
      priority = priority,
      readCount = 0,
      totalStudents = audienceRecipientIds(audienceType, audienceCode).count { id ->
        activeProfiles().any { it.id == id && it.role == Role.STUDENT }
      },
      isRead = true,
      attachment = attachment,
      approvalStatus = ContentApprovalStatus.APPROVED
    )
    _announcements.value = listOf(newAnn) + _announcements.value

    val prefix = when (priority) {
      AnnouncementPriority.URGENT -> "Urgent: "
      AnnouncementPriority.ACADEMIC -> ""
      AnnouncementPriority.NORMAL -> ""
    }
    notifyUsers(recipients, "$prefix$title", "${author.fullName} published a notice for $audienceName.", NotificationType.ANNOUNCEMENT)
    recordAudit("ANNOUNCEMENT_POSTED", "Published announcement '$title' to $audienceName (${priority.name})")
    return null
  }

  fun updateAnnouncement(id: String, newTitle: String, newBody: String, newPriority: AnnouncementPriority) {
    val ann = _announcements.value.find { it.id == id } ?: return
    if (!canEditOrDelete(ann.authorId)) return
    val author = currentUser.value
    // Editing may not be used to slip past the Urgent rules.
    if (newPriority == AnnouncementPriority.URGENT && ann.priority != AnnouncementPriority.URGENT && author.role != Role.ADMIN) return

    _announcements.value = _announcements.value.map {
      if (it.id == id) it.copy(title = newTitle, body = newBody, priority = newPriority) else it
    }
    recordAudit("ANNOUNCEMENT_EDITED", "Edited announcement '$newTitle'")
  }

  fun deleteAnnouncement(id: String) {
    val ann = _announcements.value.find { it.id == id } ?: return
    if (!canEditOrDelete(ann.authorId)) return
    _announcements.value = _announcements.value.filter { it.id != id }
    _readReceipts.value = _readReceipts.value - id
    recordAudit("ANNOUNCEMENT_DELETED", "Deleted announcement '${ann.title}'")
  }

  /** Posted to the wrong class? It disappears for everyone and a short Correction goes out. */
  fun retractAnnouncement(id: String): Boolean {
    val ann = _announcements.value.find { it.id == id } ?: return false
    if (!canEditOrDelete(ann.authorId) || ann.isRetracted) return false
    val actor = currentUser.value

    _announcements.value = _announcements.value.map { if (it.id == id) it.copy(isRetracted = true) else it }
    val recipients = audienceRecipientIds(ann.audienceType, ann.audienceCode).filter { it != ann.authorId }
    notifyUsers(
      recipients,
      "Correction: notice withdrawn",
      "\"${ann.title}\" was withdrawn by ${actor.fullName}. Please disregard it.",
      NotificationType.ANNOUNCEMENT
    )
    recordAudit("ANNOUNCEMENT_RETRACTED", "Retracted announcement '${ann.title}'")
    return true
  }

  /** One gentle nudge to the people in the audience who have not opened the notice. Returns how many. */
  fun remindUnread(id: String): Int {
    val ann = _announcements.value.find { it.id == id } ?: return 0
    if (!canEditOrDelete(ann.authorId) || ann.isRetracted || id in _remindedAnnouncements.value) return 0
    val readers = _readReceipts.value[id].orEmpty()
    val targets = audienceRecipientIds(ann.audienceType, ann.audienceCode)
      .filter { it !in readers && it != ann.authorId }
    notifyUsers(targets, "Reminder: ${ann.title}", "You have not opened this notice yet.", NotificationType.ANNOUNCEMENT)
    _remindedAnnouncements.value = _remindedAnnouncements.value + id
    recordAudit("ANNOUNCEMENT_REMINDER", "Reminded ${targets.size} people about '${ann.title}'")
    return targets.size
  }

  fun setAnnouncementApproval(id: String, status: ContentApprovalStatus) {
    if (!canApproveOrDisapprove()) return
    _announcements.value = _announcements.value.map { if (it.id == id) it.copy(approvalStatus = status) else it }
    recordAudit("CONTENT_APPROVAL_CHANGED", "Admin set announcement $id status to ${status.name}")
  }

  // ---------- Study materials ----------

  /** Returns false when the person does not teach that course. */
  fun uploadMaterial(
    title: String,
    courseCode: String,
    courseTitle: String,
    description: String,
    attachment: AttachmentFile
  ): Boolean {
    val author = currentUser.value
    if (!isActive(author) || author.role == Role.STUDENT) return false
    if (author.role == Role.LECTURER && baseCourse(courseCode) !in author.teachesCourses) return false

    val newMat = StudyMaterial(
      id = "mat-" + UUID.randomUUID().toString().take(6),
      courseCode = courseCode,
      courseTitle = courseTitle,
      title = title,
      uploaderId = author.id,
      uploadedBy = author.fullName,
      uploadedAt = "Today",
      attachment = attachment,
      isDownloaded = false,
      description = description,
      approvalStatus = ContentApprovalStatus.APPROVED
    )
    _materials.value = listOf(newMat) + _materials.value
    _downloads.value = _downloads.value + (author.id to (_downloads.value[author.id].orEmpty() + newMat.id))

    notifyUsers(courseStudentIds(courseCode), "New material: $courseCode", "${author.fullName} shared '$title' (${attachment.fileSize}).", NotificationType.ANNOUNCEMENT)
    recordAudit("MATERIAL_UPLOAD", "Uploaded '$title' for $courseCode (${attachment.fileName})")
    return true
  }

  fun updateMaterial(id: String, newTitle: String, newDescription: String) {
    val mat = _materials.value.find { it.id == id } ?: return
    if (!canEditOrDelete(mat.uploaderId)) return
    _materials.value = _materials.value.map { if (it.id == id) it.copy(title = newTitle, description = newDescription) else it }
    recordAudit("MATERIAL_EDITED", "Edited study material '$newTitle'")
  }

  fun deleteMaterial(id: String) {
    val mat = _materials.value.find { it.id == id } ?: return
    if (!canEditOrDelete(mat.uploaderId)) return
    _materials.value = _materials.value.filter { it.id != id }
    recordAudit("MATERIAL_DELETED", "Deleted study material '${mat.title}'")
  }

  fun setMaterialApproval(id: String, status: ContentApprovalStatus) {
    if (!canApproveOrDisapprove()) return
    _materials.value = _materials.value.map { if (it.id == id) it.copy(approvalStatus = status) else it }
    recordAudit("MATERIAL_APPROVAL_CHANGED", "Admin set material $id status to ${status.name}")
  }

  // ---------- Timetable ----------

  fun updateSessionVenue(sessionId: String, newVenue: String, reason: String) {
    val sess = _sessions.value.find { it.id == sessionId } ?: return
    if (!canEditOrDelete(sess.lecturerId)) return

    _sessions.value = _sessions.value.map { s ->
      if (s.id == sessionId) {
        s.copy(venue = newVenue, originalVenue = s.originalVenue ?: s.venue, status = SessionStatus.CHANGED, changeReason = reason)
      } else s
    }
    // Only the people enrolled in this course hear about it.
    notifyUsers(
      courseStudentIds(sess.courseCode),
      "Room changed: ${sess.courseCode}",
      "$newVenue, ${sess.startTime}. Reason: $reason",
      NotificationType.TIMETABLE_CHANGE
    )
    recordAudit("VENUE_CHANGE", "Updated ${sess.courseCode} venue to $newVenue ($reason)")
  }

  fun cancelSession(sessionId: String, reason: String) {
    val sess = _sessions.value.find { it.id == sessionId } ?: return
    if (!canEditOrDelete(sess.lecturerId)) return

    _sessions.value = _sessions.value.map { s ->
      if (s.id == sessionId) s.copy(status = SessionStatus.CANCELLED, changeReason = reason) else s
    }
    notifyUsers(
      courseStudentIds(sess.courseCode),
      "Cancelled: ${sess.courseCode}",
      "${sess.startTime} class was cancelled. Reason: $reason",
      NotificationType.TIMETABLE_CHANGE
    )
    recordAudit("SESSION_CANCELLED", "Cancelled ${sess.courseCode} session ($reason)")
  }

  fun restoreSession(sessionId: String) {
    val sess = _sessions.value.find { it.id == sessionId } ?: return
    if (!canEditOrDelete(sess.lecturerId)) return
    _sessions.value = _sessions.value.map { s ->
      if (s.id == sessionId) s.copy(status = SessionStatus.SCHEDULED, changeReason = null, venue = s.originalVenue ?: s.venue) else s
    }
    recordAudit("SESSION_RESTORED", "Restored ${sess.courseCode} session")
  }

  fun deleteSession(sessionId: String) {
    val sess = _sessions.value.find { it.id == sessionId } ?: return
    if (!canEditOrDelete(sess.lecturerId)) return
    _sessions.value = _sessions.value.filter { it.id != sessionId }
    recordAudit("SESSION_DELETED", "Deleted timetable session ${sess.courseCode} (${sess.venue})")
  }

  fun setSessionApproval(sessionId: String, status: ContentApprovalStatus) {
    if (!canApproveOrDisapprove()) return
    _sessions.value = _sessions.value.map { if (it.id == sessionId) it.copy(approvalStatus = status) else it }
    recordAudit("SESSION_APPROVAL_CHANGED", "Admin set session $sessionId status to ${status.name}")
  }

  // ---------- Deadlines ----------

  /** Returns false when the person does not teach that course. */
  fun addDeadline(
    courseCode: String,
    courseTitle: String,
    title: String,
    instructions: String,
    dueDateTime: String,
    acceptedFormats: String,
    attachment: AttachmentFile? = null
  ): Boolean {
    val author = currentUser.value
    if (!isActive(author) || author.role == Role.STUDENT) return false
    if (author.role == Role.LECTURER && baseCourse(courseCode) !in author.teachesCourses) return false

    val newDl = DeadlineItem(
      id = "dl-" + UUID.randomUUID().toString().take(6),
      courseCode = courseCode,
      courseTitle = courseTitle,
      lecturerId = author.id,
      lecturerName = author.fullName,
      title = title,
      instructions = instructions,
      dueDateTime = dueDateTime,
      urgencyText = "Upcoming",
      acceptedFormats = acceptedFormats.ifBlank { "PDF or Images (Max 15 MB)" },
      isCompleted = false,
      submittedCount = 0,
      totalStudents = courseStudentIds(courseCode).size,
      approvalStatus = ContentApprovalStatus.APPROVED,
      attachment = attachment
    )
    _deadlines.value = listOf(newDl) + _deadlines.value

    notifyUsers(courseStudentIds(courseCode), "New Deadline: $courseCode", "$title due on $dueDateTime", NotificationType.DEADLINE_ALERT)
    recordAudit("DEADLINE_CREATED", "Created deadline '$title' for $courseCode due $dueDateTime")
    return true
  }

  /** Edits or reschedules a deadline. Only the lecturer who set it, or an administrator, may do this. */
  fun updateDeadline(
    id: String,
    newTitle: String,
    newInstructions: String,
    newDueDateTime: String,
    acceptedFormats: String? = null,
    attachment: AttachmentFile? = null,
    clearAttachment: Boolean = false
  ) {
    val dl = _deadlines.value.find { it.id == id } ?: return
    if (!canEditOrDelete(dl.lecturerId)) return
    _deadlines.value = _deadlines.value.map {
      if (it.id != id) it else it.copy(
        title = newTitle,
        instructions = newInstructions,
        dueDateTime = newDueDateTime,
        acceptedFormats = acceptedFormats?.ifBlank { null } ?: it.acceptedFormats,
        attachment = when {
          clearAttachment -> null
          attachment != null -> attachment
          else -> it.attachment
        }
      )
    }
    if (newDueDateTime != dl.dueDateTime) {
      notifyUsers(courseStudentIds(dl.courseCode), "Deadline moved: ${dl.courseCode}", "$newTitle is now due $newDueDateTime", NotificationType.DEADLINE_ALERT)
    } else if (newTitle != dl.title || newInstructions != dl.instructions) {
      notifyUsers(courseStudentIds(dl.courseCode), "Assignment updated: ${dl.courseCode}", "$newTitle was changed. Check the instructions.", NotificationType.DEADLINE_ALERT)
    }
    recordAudit("DEADLINE_EDITED", "Edited deadline '$newTitle' (due ${newDueDateTime})")
  }

  fun deleteDeadline(id: String) {
    val dl = _deadlines.value.find { it.id == id } ?: return
    if (!canEditOrDelete(dl.lecturerId)) return
    _deadlines.value = _deadlines.value.filter { it.id != id }
    recordAudit("DEADLINE_DELETED", "Deleted deadline '${dl.title}'")
  }

  fun setDeadlineApproval(id: String, status: ContentApprovalStatus) {
    if (!canApproveOrDisapprove()) return
    _deadlines.value = _deadlines.value.map { if (it.id == id) it.copy(approvalStatus = status) else it }
    recordAudit("DEADLINE_APPROVAL_CHANGED", "Admin set deadline $id status to ${status.name}")
  }

  // ---------- E-learning: timed quizzes ----------

  private val _quizzes = MutableStateFlow<List<Quiz>>(emptyList())
  private val _attempts = MutableStateFlow<List<QuizAttempt>>(emptyList())

  /** Quizzes this person may see. Students get the questions only, never the answers. */
  val quizzes: StateFlow<List<Quiz>> = combine(_quizzes, currentUser) { list, user ->
    list.filter { canSeeQuiz(it, user) }.map { if (user.role == Role.STUDENT) it.withoutAnswers() else it }
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Attempts this person may see: a student's own; a lecturer's for their quizzes; the campus for an administrator. */
  val attempts: StateFlow<List<QuizAttempt>> = combine(_attempts, _quizzes, currentUser) { list, quizList, user ->
    list.filter { a -> quizList.firstOrNull { it.id == a.quizId }?.let { canSeeAttempt(a, it, user) } ?: false }
  }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  private fun canSeeQuiz(q: Quiz, user: UserProfile): Boolean {
    if (!isActive(user) || !sameCampus(q.lecturerId, user)) return false
    return when (user.role) {
      Role.ADMIN -> true
      Role.LECTURER -> q.lecturerId == user.id || baseCourse(q.courseCode) in user.teachesCourses
      Role.STUDENT -> baseCourse(q.courseCode) in user.enrolledCourses
    }
  }

  private fun canSeeAttempt(a: QuizAttempt, q: Quiz, user: UserProfile): Boolean {
    if (!isActive(user) || !sameCampus(q.lecturerId, user)) return false
    return when (user.role) {
      Role.STUDENT -> a.studentId == user.id
      Role.LECTURER -> q.lecturerId == user.id
      Role.ADMIN -> true
    }
  }

  private fun validateWindow(durationMinutes: Int, opensAt: Long, closesAt: Long, now: Long): QuizError? = when {
    durationMinutes !in 1..180 -> QuizError.BAD_DURATION
    closesAt <= opensAt || closesAt <= now -> QuizError.BAD_WINDOW
    // If the window is shorter than the time allowed, nobody could ever finish.
    closesAt - opensAt < durationMinutes * 60_000L -> QuizError.BAD_WINDOW
    else -> null
  }

  private fun validateQuestions(questions: List<QuizQuestion>): QuizError? {
    if (questions.isEmpty() || questions.size > 50) return QuizError.NO_QUESTIONS
    for (q in questions) {
      if (q.text.isBlank() || q.points !in 1..100) return QuizError.BAD_QUESTION
      if (q.type == QuestionType.MULTIPLE_CHOICE) {
        if (q.options.size !in 2..6 || q.options.any { it.isBlank() }) return QuizError.BAD_QUESTION
        if (q.correctOption == null || q.correctOption !in q.options.indices) return QuizError.BAD_QUESTION
      }
    }
    return null
  }

  /** A lecturer sets a quiz for a course they teach (an administrator may set one for any course of the campus). */
  fun createQuiz(
    courseCode: String,
    title: String,
    instructions: String,
    durationMinutes: Int,
    opensAt: Long,
    closesAt: Long,
    questions: List<QuizQuestion>,
    now: Long = System.currentTimeMillis()
  ): QuizError? {
    val author = currentUser.value
    if (!isActive(author) || author.role == Role.STUDENT) return QuizError.NOT_ALLOWED
    val code = courseCode.trim().uppercase()
    val courseTitle = catalog.coursesFor(author.universityId)[code] ?: return QuizError.UNKNOWN_COURSE
    if (author.role == Role.LECTURER && baseCourse(code) !in author.teachesCourses) return QuizError.NOT_ALLOWED
    if (title.isBlank()) return QuizError.TITLE_REQUIRED
    validateQuestions(questions)?.let { return it }
    validateWindow(durationMinutes, opensAt, closesAt, now)?.let { return it }

    val quiz = Quiz(
      id = "quiz-" + UUID.randomUUID().toString().take(6),
      courseCode = code, courseTitle = courseTitle,
      lecturerId = author.id, lecturerName = author.fullName,
      title = title.trim(), instructions = instructions.trim(),
      durationMinutes = durationMinutes, opensAt = opensAt, closesAt = closesAt,
      questions = questions.mapIndexed { i, q -> q.copy(id = "q-${i + 1}", text = q.text.trim()) }
    )
    _quizzes.value = listOf(quiz) + _quizzes.value
    notifyUsers(
      courseStudentIds(code), "New quiz: $code",
      "${quiz.title} opens ${DeadlineTime.format(opensAt)}. You get ${durationMinutes} minutes once you start.",
      NotificationType.DEADLINE_ALERT
    )
    recordAudit("QUIZ_CREATED", "Created quiz '${quiz.title}' for $code (${questions.size} questions, ${durationMinutes} min)")
    return null
  }

  /** Moves a quiz's window or changes the time allowed. Attempts already running keep their own deadline. */
  fun rescheduleQuiz(quizId: String, durationMinutes: Int, opensAt: Long, closesAt: Long, now: Long = System.currentTimeMillis()): QuizError? {
    val quiz = _quizzes.value.find { it.id == quizId } ?: return QuizError.NOT_FOUND
    if (!canEditOrDelete(quiz.lecturerId)) return QuizError.NOT_ALLOWED
    validateWindow(durationMinutes, opensAt, closesAt, now)?.let { return it }
    _quizzes.value = _quizzes.value.map { if (it.id == quizId) it.copy(durationMinutes = durationMinutes, opensAt = opensAt, closesAt = closesAt) else it }
    notifyUsers(
      courseStudentIds(quiz.courseCode), "Quiz rescheduled: ${quiz.courseCode}",
      "${quiz.title} now opens ${DeadlineTime.format(opensAt)} and closes ${DeadlineTime.format(closesAt)}.",
      NotificationType.DEADLINE_ALERT
    )
    recordAudit("QUIZ_RESCHEDULED", "Rescheduled quiz '${quiz.title}'")
    return null
  }

  fun deleteQuiz(quizId: String): QuizError? {
    val quiz = _quizzes.value.find { it.id == quizId } ?: return QuizError.NOT_FOUND
    if (!canEditOrDelete(quiz.lecturerId)) return QuizError.NOT_ALLOWED
    _quizzes.value = _quizzes.value.filterNot { it.id == quizId }
    _attempts.value = _attempts.value.filterNot { it.quizId == quizId }
    recordAudit("QUIZ_DELETED", "Deleted quiz '${quiz.title}' and its attempts")
    return null
  }

  /** Any attempt whose timer has run out is submitted with the answers it already has. */
  fun finalizeExpiredAttempts(now: Long = System.currentTimeMillis()) {
    // The hand-in grace keeps the very last answers from being lost: close an attempt only after it.
    if (_attempts.value.none { it.status == AttemptStatus.IN_PROGRESS && it.deadlineAt + SUBMIT_GRACE_MS <= now }) return
    _attempts.value = _attempts.value.map {
      if (it.status == AttemptStatus.IN_PROGRESS && it.deadlineAt + SUBMIT_GRACE_MS <= now)
        it.copy(status = AttemptStatus.AUTO_SUBMITTED, submittedAt = it.deadlineAt) else it
    }
  }

  /** A student presses Start. Starting again while an attempt is running simply resumes it. */
  fun startAttempt(quizId: String, now: Long = System.currentTimeMillis()): StartResult {
    val student = currentUser.value
    if (!isActive(student) || student.role != Role.STUDENT) return StartResult(error = QuizError.NOT_ALLOWED)
    val quiz = _quizzes.value.find { it.id == quizId }?.takeIf { canSeeQuiz(it, student) }
      ?: return StartResult(error = QuizError.NOT_FOUND)
    finalizeExpiredAttempts(now)
    _attempts.value.find { it.quizId == quizId && it.studentId == student.id }?.let {
      return if (it.status == AttemptStatus.IN_PROGRESS && it.deadlineAt > now) StartResult(attempt = it) else StartResult(error = QuizError.ALREADY_SUBMITTED)
    }
    if (now < quiz.opensAt) return StartResult(error = QuizError.NOT_OPEN_YET)
    if (now >= quiz.closesAt) return StartResult(error = QuizError.CLOSED)
    val attempt = QuizAttempt(
      id = "att-" + UUID.randomUUID().toString().take(6),
      quizId = quizId, studentId = student.id, studentName = student.fullName,
      studentNumber = student.studentNumber ?: "STUDENT-${student.id.takeLast(4)}",
      startedAt = now,
      deadlineAt = minOf(now + quiz.durationMinutes * 60_000L, quiz.closesAt)
    )
    _attempts.value = listOf(attempt) + _attempts.value
    return StartResult(attempt = attempt)
  }

  private fun ownAttempt(attemptId: String): QuizAttempt? =
    _attempts.value.find { it.id == attemptId && it.studentId == currentUser.value.id && currentUser.value.role == Role.STUDENT }

  private fun cleanAnswer(question: QuizQuestion, raw: String): String? = when (question.type) {
    QuestionType.MULTIPLE_CHOICE -> raw.trim().toIntOrNull()?.takeIf { it in question.options.indices }?.toString()
    QuestionType.SHORT_ANSWER -> raw.take(2000)
  }

  private fun applyAnswers(attempt: QuizAttempt, answers: Map<String, String>): QuizAttempt {
    val quiz = _quizzes.value.find { it.id == attempt.quizId } ?: return attempt
    val merged = attempt.answers.toMutableMap()
    answers.forEach { (qid, raw) ->
      val q = quiz.questions.find { it.id == qid } ?: return@forEach
      val cleaned = cleanAnswer(q, raw)
      if (cleaned == null || cleaned.isBlank()) merged.remove(qid) else merged[qid] = cleaned
    }
    return attempt.copy(answers = merged)
  }

  /** Saves one answer while the timer is still running. Answers sent after it hits zero are refused. */
  fun saveAnswer(attemptId: String, questionId: String, answer: String, now: Long = System.currentTimeMillis()): QuizError? {
    finalizeExpiredAttempts(now)
    val attempt = ownAttempt(attemptId) ?: return QuizError.NOT_FOUND
    if (attempt.status != AttemptStatus.IN_PROGRESS) {
      return if (attempt.status == AttemptStatus.AUTO_SUBMITTED) QuizError.TIME_UP else QuizError.ALREADY_SUBMITTED
    }
    // Inside the hand-in grace the attempt is still open for the final submit, but not for ordinary saves.
    if (now >= attempt.deadlineAt) return QuizError.TIME_UP
    val updated = applyAnswers(attempt, mapOf(questionId to answer))
    _attempts.value = _attempts.value.map { if (it.id == attemptId) updated else it }
    return null
  }

  /**
   * Hands the quiz in. [finalAnswers] are the last answers still on the screen; they are accepted for a few
   * seconds after the timer ends so that nothing typed at the last moment is lost.
   */
  fun submitAttempt(
    attemptId: String,
    finalAnswers: Map<String, String> = emptyMap(),
    timedOut: Boolean = false,
    now: Long = System.currentTimeMillis()
  ): QuizError? {
    val attempt = ownAttempt(attemptId) ?: return QuizError.NOT_FOUND
    if (attempt.status != AttemptStatus.IN_PROGRESS) return null // already handed in: nothing more to do
    val inTime = now <= attempt.deadlineAt + SUBMIT_GRACE_MS
    var working = if (inTime) applyAnswers(attempt, finalAnswers) else attempt
    val expired = timedOut || now >= attempt.deadlineAt
    working = working.copy(
      status = if (expired) AttemptStatus.AUTO_SUBMITTED else AttemptStatus.SUBMITTED,
      submittedAt = minOf(now, attempt.deadlineAt)
    )
    _attempts.value = _attempts.value.map { if (it.id == attemptId) working else it }
    val quiz = _quizzes.value.find { it.id == attempt.quizId }
    if (quiz != null) {
      notifyUsers(listOf(quiz.lecturerId), "Quiz submitted: ${quiz.courseCode}", "${attempt.studentName} finished ${quiz.title}.", NotificationType.SUBMISSION_RECEIVED)
    }
    return null
  }

  /** Written answers are marked by the lecturer who set the quiz (or an administrator). */
  fun gradeAttempt(attemptId: String, awarded: Map<String, Int>): QuizError? {
    val attempt = _attempts.value.find { it.id == attemptId } ?: return QuizError.NOT_FOUND
    val quiz = _quizzes.value.find { it.id == attempt.quizId } ?: return QuizError.NOT_FOUND
    if (!canEditOrDelete(quiz.lecturerId)) return QuizError.NOT_ALLOWED
    if (attempt.status == AttemptStatus.IN_PROGRESS) return QuizError.NOT_ALLOWED
    val written = quiz.questions.filter { it.type == QuestionType.SHORT_ANSWER }.associateBy { it.id }
    val clean = awarded.filterKeys { it in written }.mapValues { (id, pts) -> pts.coerceIn(0, written.getValue(id).points) }
    _attempts.value = _attempts.value.map { if (it.id == attemptId) it.copy(awarded = clean, marked = true) else it }
    notifyUsers(listOf(attempt.studentId), "Quiz marked: ${quiz.courseCode}", "${quiz.title} has been marked. Open it to see your score.", NotificationType.ANNOUNCEMENT)
    recordAudit("QUIZ_MARKED", "Marked ${attempt.studentName}'s attempt of '${quiz.title}'")
    return null
  }

  /** The score of a finished attempt. A student cannot ask for it while the quiz is still running. */
  fun scoreOf(attemptId: String): QuizScore? {
    val attempt = _attempts.value.find { it.id == attemptId } ?: return null
    val quiz = _quizzes.value.find { it.id == attempt.quizId } ?: return null
    val user = currentUser.value
    if (!canSeeAttempt(attempt, quiz, user) || attempt.status == AttemptStatus.IN_PROGRESS) return null
    val choice = quiz.questions.filter { it.type == QuestionType.MULTIPLE_CHOICE }
    val written = quiz.questions.filter { it.type == QuestionType.SHORT_ANSWER }
    return QuizScore(
      choicePoints = choice.filter { attempt.answers[it.id]?.toIntOrNull() == it.correctOption }.sumOf { it.points },
      choiceTotal = choice.sumOf { it.points },
      writtenPoints = written.sumOf { attempt.awarded[it.id] ?: 0 },
      writtenTotal = written.sumOf { it.points },
      complete = written.isEmpty() || attempt.marked
    )
  }

  // ---------- Registry: accounts ----------

  fun registryMatches(profile: UserProfile): Boolean? = auth.registryMatches(profile)

  fun approveUser(userId: String, role: Role, programme: String?, teaches: Set<String>) {
    if (!canApproveOrDisapprove()) return
    val before = pendingUsers.value.find { it.id == userId } ?: return // pendingUsers is already this campus only
    val updated = auth.approve(userId, role, programme, teaches) ?: return

    val roleLabel = role.name.lowercase().replaceFirstChar { it.uppercase() }
    val placeText = if (role == Role.STUDENT) updated.programme ?: "" else "teaching ${teaches.sorted().joinToString(", ").ifBlank { "no courses yet" }}"
    recordAudit("ACCOUNT_APPROVED", "Approved ${before.fullName} as $roleLabel ($placeText)")
    if (role != before.role) {
      recordAudit("ROLE_GRANTED", "Granted role $roleLabel to ${before.fullName}")
    }
    val first = before.fullName.substringBefore(' ')
    notifyUsers(listOf(userId), "Karibu, $first. You are in.", "The Academic Registry approved your account.", NotificationType.APPROVAL)
  }

  fun rejectUser(userId: String) {
    if (!canApproveOrDisapprove()) return
    val user = pendingUsers.value.find { it.id == userId } ?: return
    auth.reject(userId) ?: return
    recordAudit("ACCOUNT_REJECTED", "Rejected account registration for ${user.fullName}")
  }

  // ---------- Campus setup (the campus administrator) ----------

  private fun isCampusAdmin(): Boolean {
    val u = currentUser.value
    return isActive(u) && u.role == Role.ADMIN && !u.isPlatformAdmin
  }

  fun addProgramme(name: String): Boolean {
    if (!isCampusAdmin()) return false
    val ok = catalog.addProgramme(currentUser.value.universityId, name)
    if (ok) recordAudit("PROGRAMME_ADDED", name.trim())
    return ok
  }

  fun addCourse(code: String, title: String, programme: String?): Boolean {
    if (!isCampusAdmin()) return false
    val uni = currentUser.value.universityId
    val ok = catalog.addCourse(uni, code, title, programme)
    if (ok) {
      val clean = code.trim().uppercase().replace(" ", "")
      if (!programme.isNullOrBlank()) auth.enrollProgramme(uni, programme.trim(), clean)
      recordAudit("COURSE_ADDED", "$clean ${title.trim()}")
    }
    return ok
  }

  fun importCourses(text: String): ImportResult {
    if (!isCampusAdmin()) return ImportResult(0, 0)
    val uni = currentUser.value.universityId
    val before = catalog.coursesFor(uni).keys
    val result = catalog.importCourses(uni, text)
    // Students already in a programme get the courses just added to it.
    catalog.catalogFor(uni).forEach { (programme, codes) ->
      codes.filter { it !in before }.forEach { auth.enrollProgramme(uni, programme, it) }
    }
    if (result.added > 0) recordAudit("COURSES_IMPORTED", "${result.added} added, ${result.skipped} skipped")
    return result
  }

  fun addRoom(name: String): Boolean {
    if (!isCampusAdmin()) return false
    val ok = catalog.addRoom(currentUser.value.universityId, name)
    if (ok) recordAudit("ROOM_ADDED", name.trim())
    return ok
  }

  // ---------- Campus setup: who teaches which course ----------

  /** Approved lecturers of this campus. Only the campus administrator may list them for assigning. */
  fun campusLecturers(): List<UserProfile> =
    if (!isCampusAdmin()) emptyList()
    else activeProfiles().filter { it.role == Role.LECTURER }.sortedBy { it.fullName }

  /**
   * Makes [lecturerIds] exactly the lecturers of [courseCode]. All or nothing:
   * - only a campus administrator, and only for a course of their own campus;
   * - every chosen person must be an approved lecturer of the same campus;
   * - a lecturer who still has a live class in the course cannot be removed from it.
   * Mirrors the database function `set_course_lecturers`, which is the real authority.
   */
  fun setCourseLecturers(courseCode: String, lecturerIds: Set<String>): AssignLecturersResult {
    if (!isCampusAdmin()) return AssignLecturersResult(AssignLecturersError.NOT_ALLOWED)
    val uni = currentUser.value.universityId
    val code = courseCode.trim().uppercase()
    if (code !in catalog.coursesFor(uni)) return AssignLecturersResult(AssignLecturersError.UNKNOWN_COURSE)

    val lecturers = activeProfiles().filter { it.role == Role.LECTURER }.associateBy { it.id }
    if (lecturerIds.any { it !in lecturers }) return AssignLecturersResult(AssignLecturersError.NOT_A_LECTURER)

    val before = lecturers.values.filter { code in it.teachesCourses }.map { it.id }.toSet()
    val added = lecturerIds - before
    val removed = before - lecturerIds
    if (added.isEmpty() && removed.isEmpty()) return AssignLecturersResult()

    val blocking = removed.filter { id ->
      _sessions.value.any { it.lecturerId == id && baseCourse(it.courseCode) == code && it.status != SessionStatus.CANCELLED }
    }
    if (blocking.isNotEmpty()) {
      return AssignLecturersResult(AssignLecturersError.HAS_CLASSES, blocking.mapNotNull { lecturers[it]?.fullName }.sorted().joinToString(", "))
    }

    (added).forEach { id -> auth.setTeaching(id, lecturers.getValue(id).teachesCourses + code) }
    (removed).forEach { id -> auth.setTeaching(id, lecturers.getValue(id).teachesCourses - code) }

    recordAudit("COURSE_LECTURERS_SET", "$code: +${added.size} -${removed.size}")
    if (added.isNotEmpty()) {
      notifyUsers(added, "You now teach $code", "The Academic Registry assigned you to $code. You can post notices and upload materials for it.", NotificationType.ANNOUNCEMENT)
    }
    if (removed.isNotEmpty()) {
      notifyUsers(removed, "You no longer teach $code", "The Academic Registry changed the lecturers of $code.", NotificationType.ANNOUNCEMENT)
    }
    return AssignLecturersResult()
  }

  // ---------- Scheduling: pick the subject, the lecturer, the room and the time ----------

  /** Subjects the current person may schedule: every campus course for the registry, their own for a lecturer. */
  fun schedulableCourses(): List<Pair<String, String>> {
    val user = currentUser.value
    if (!isActive(user) || user.isPlatformAdmin) return emptyList()
    val all = catalog.coursesFor(user.universityId)
    return when (user.role) {
      Role.ADMIN -> all.toList().sortedBy { it.first }
      Role.LECTURER -> all.filterKeys { it in user.teachesCourses }.toList().sortedBy { it.first }
      Role.STUDENT -> emptyList()
    }
  }

  /** Lecturers of this campus who teach the subject. */
  fun lecturersFor(courseCode: String): List<UserProfile> =
    activeProfiles().filter { it.role == Role.LECTURER && courseCode in it.teachesCourses }.sortedBy { it.fullName }

  /** Every room of the campus, with the course that already uses it at that time (if any). */
  fun roomsWithAvailability(day: Int, start: String, end: String): List<RoomOption> {
    val user = currentUser.value
    val busy = _sessions.value.filter {
      universityOf(it.lecturerId) == user.universityId && it.status != SessionStatus.CANCELLED &&
        it.dayOfWeek == day && it.startTime < end && start < it.endTime
    }
    return catalog.roomsFor(user.universityId).map { room ->
      RoomOption(room, busy.firstOrNull { it.venue.equals(room, ignoreCase = true) }?.courseCode)
    }
  }

  fun addSession(courseCode: String, lecturerId: String, day: Int, start: String, end: String, venue: String): SessionError? {
    val user = currentUser.value
    if (!isActive(user) || user.isPlatformAdmin || user.role == Role.STUDENT) return SessionError.NOT_ALLOWED
    val title = catalog.coursesFor(user.universityId)[courseCode] ?: return SessionError.UNKNOWN_COURSE
    if (user.role == Role.LECTURER && courseCode !in user.teachesCourses) return SessionError.NOT_ALLOWED

    val lecturer = auth.accounts.value[lecturerId]?.profile
    if (lecturer == null || lecturer.role != Role.LECTURER || lecturer.status != AccountStatus.APPROVED ||
      lecturer.universityId != user.universityId || courseCode !in lecturer.teachesCourses
    ) return SessionError.LECTURER_NOT_FOR_COURSE

    val timeOk = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    if (day !in 1..6 || !timeOk.matches(start) || !timeOk.matches(end) || start >= end) return SessionError.TIME_INVALID
    if (venue.isBlank()) return SessionError.ROOM_REQUIRED

    val sameTime = _sessions.value.filter {
      universityOf(it.lecturerId) == user.universityId && it.status != SessionStatus.CANCELLED &&
        it.dayOfWeek == day && it.startTime < end && start < it.endTime
    }
    if (sameTime.any { it.venue.equals(venue.trim(), ignoreCase = true) }) return SessionError.ROOM_BUSY
    if (sameTime.any { it.lecturerId == lecturerId }) return SessionError.LECTURER_BUSY

    val programme = catalog.catalogFor(user.universityId).entries.firstOrNull { courseCode in it.value }?.key ?: ""
    val session = ClassSession(
      id = "sess-" + UUID.randomUUID().toString().take(6),
      courseCode = courseCode,
      courseTitle = title,
      lecturerId = lecturer.id,
      lecturerName = lecturer.fullName,
      venue = venue.trim(),
      dayOfWeek = day,
      startTime = start,
      endTime = end,
      programme = programme,
      cohort = ""
    )
    _sessions.value = _sessions.value + session
    notifyUsers(courseStudentIds(courseCode), "New class: $courseCode", "${venue.trim()}, $start-$end", NotificationType.TIMETABLE_CHANGE)
    recordAudit("SESSION_CREATED", "$courseCode day $day $start-$end in ${venue.trim()} with ${lecturer.fullName}")
    return null
  }

  // ---------- App manager (runs every campus, sees none of their content) ----------

  private val _dataVersion = MutableStateFlow(0)

  private fun isAppManager(): Boolean = currentUser.value.let { it.isPlatformAdmin && isActive(it) }

  /** Totals per campus. Counts only: the manager never reads what a campus wrote. */
  val campuses: StateFlow<List<CampusSummary>> =
    combine(catalog.universities, auth.accounts, _dataVersion) { universities, accounts, _ ->
      universities.map { u ->
        val people = accounts.values.map { it.profile }.filter { it.universityId == u.id }
        CampusSummary(
          university = u,
          people = people.size,
          pending = people.count { it.status == AccountStatus.PENDING },
          admins = people.count { it.role == Role.ADMIN },
          programmes = catalog.programmesFor(u.id).size,
          courses = catalog.coursesFor(u.id).size,
          rooms = catalog.roomsFor(u.id).size,
          sessions = _sessions.value.count { universityOf(it.lecturerId) == u.id },
          announcements = _announcements.value.count { universityOf(it.authorId) == u.id },
          materials = _materials.value.count { universityOf(it.uploaderId) == u.id },
          works = _deadlines.value.count { universityOf(it.lecturerId) == u.id } +
            _submissions.value.count { universityOf(it.studentId) == u.id }
        )
      }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Creates a campus and its first administrator in one step. */
  fun createCampus(
    code: String,
    name: String,
    adminName: String,
    adminEmail: String,
    adminNumber: String,
    adminPassword: String
  ): PlatformResult {
    if (!isAppManager()) return PlatformResult(false, PlatformError.NOT_ALLOWED)
    val shortName = code.trim().uppercase()
    val id = shortName.lowercase().replace(Regex("[^a-z0-9]"), "")
    if (id.length < 2 || name.trim().length < 2) return PlatformResult(false, PlatformError.DETAILS_MISSING)
    if (id == SeedUsers.PLATFORM_ID) return PlatformResult(false, PlatformError.CAMPUS_EXISTS)
    if (!catalog.addUniversity(com.willykez.campconnect.data.model.University(id, name.trim(), shortName))) {
      return PlatformResult(false, PlatformError.CAMPUS_EXISTS)
    }
    val error = auth.createApprovedAdmin(id, adminName, adminEmail, adminNumber, adminPassword)
    if (error != null) {
      catalog.removeUniversity(id) // nothing half-made is left behind
      return PlatformResult(false, authError = error)
    }
    _dataVersion.value++
    recordAudit("CAMPUS_CREATED", "$shortName ${name.trim()} with administrator ${adminName.trim()}", SeedUsers.PLATFORM_ID)
    return PlatformResult(true)
  }

  /** Deletes one kind of data (or all of it) from a campus. Irreversible. */
  fun wipeCampus(universityId: String, scope: WipeScope): Boolean {
    if (!isAppManager() || universityId == SeedUsers.PLATFORM_ID || !catalog.hasUniversity(universityId)) return false
    fun mine(ownerId: String) = universityOf(ownerId) == universityId
    val everything = scope == WipeScope.EVERYTHING
    val peopleIds = auth.accounts.value.values.map { it.profile }.filter { it.universityId == universityId }.map { it.id }.toSet()

    if (everything || scope == WipeScope.ANNOUNCEMENTS) {
      val ids = _announcements.value.filter { mine(it.authorId) }.map { it.id }.toSet()
      _announcements.value = _announcements.value.filterNot { it.id in ids }
      _readReceipts.value = _readReceipts.value - ids
      _remindedAnnouncements.value = _remindedAnnouncements.value - ids
    }
    if (everything || scope == WipeScope.TIMETABLE) {
      _sessions.value = _sessions.value.filterNot { mine(it.lecturerId) }
    }
    if (everything || scope == WipeScope.MATERIALS) {
      val ids = _materials.value.filter { mine(it.uploaderId) }.map { it.id }.toSet()
      _materials.value = _materials.value.filterNot { it.id in ids }
      _downloads.value = _downloads.value.mapValues { (_, v) -> v - ids }
    }
    if (everything || scope == WipeScope.WORKS) {
      val ids = _deadlines.value.filter { mine(it.lecturerId) }.map { it.id }.toSet()
      _deadlines.value = _deadlines.value.filterNot { it.id in ids }
      _submissions.value = _submissions.value.filterNot { mine(it.studentId) || it.deadlineId in ids }
      _doneTicks.value = _doneTicks.value.mapValues { (_, v) -> v - ids }
      val quizIds = _quizzes.value.filter { mine(it.lecturerId) }.map { it.id }.toSet()
      _quizzes.value = _quizzes.value.filterNot { it.id in quizIds }
      _attempts.value = _attempts.value.filterNot { it.quizId in quizIds || mine(it.studentId) }
    }
    if (everything || scope == WipeScope.NOTIFICATIONS || scope == WipeScope.PEOPLE) {
      _notifications.value = _notifications.value.filterNot { it.userId in peopleIds }
    }
    if (everything || scope == WipeScope.CATALOG) catalog.clearCampus(universityId)
    if (everything || scope == WipeScope.PEOPLE) auth.removeAccountsOf(universityId, includeAdmins = everything)

    _dataVersion.value++
    recordAudit("CAMPUS_DATA_DELETED", "${catalog.universityName(universityId)}: ${scope.name}", SeedUsers.PLATFORM_ID)
    return true
  }

  // ---------- Campus details, offline mode and adding people ----------

  /** The campus the signed-in person belongs to, or null for the app manager and when signed out. */
  val myCampus: StateFlow<com.willykez.campconnect.data.model.University?> =
    combine(currentUser, catalog.universities) { user, universities ->
      if (user.isPlatformAdmin) null else universities.firstOrNull { it.id == user.universityId }
    }.stateIn(scope, SharingStarted.Eagerly, null)

  /**
   * True while the person's campus is in offline (maintenance) mode. Everyone at that campus is paused,
   * including its administrators. Only the app manager can switch it off again.
   */
  val campusPaused: StateFlow<Boolean> = myCampus
    .map { it?.underMaintenance == true }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /** The app manager puts a campus offline for maintenance, or brings it back. */
  fun setCampusMaintenance(universityId: String, on: Boolean, note: String): Boolean {
    if (!isAppManager() || universityId == SeedUsers.PLATFORM_ID) return false
    val ok = catalog.updateUniversity(universityId) { it.copy(underMaintenance = on, maintenanceNote = if (on) note.trim() else "") }
    if (ok) {
      _dataVersion.value++
      recordAudit(if (on) "CAMPUS_OFFLINE" else "CAMPUS_ONLINE", catalog.universityName(universityId), SeedUsers.PLATFORM_ID)
    }
    return ok
  }

  /**
   * Edits a campus's details. The app manager may edit any campus (including its name); a campus
   * administrator may edit only their own campus's contact details.
   */
  fun updateCampusDetails(
    universityId: String,
    name: String?,
    location: String,
    contactEmail: String,
    contactPhone: String
  ): Boolean {
    val me = currentUser.value
    val manager = isAppManager()
    val ownAdmin = isActive(me) && me.role == Role.ADMIN && !me.isPlatformAdmin && me.universityId == universityId
    if (!manager && !ownAdmin) return false
    if (name != null && manager && name.trim().length < 2) return false
    val ok = catalog.updateUniversity(universityId) {
      it.copy(
        name = if (manager && name != null) name.trim() else it.name,
        location = location.trim(),
        contactEmail = contactEmail.trim(),
        contactPhone = contactPhone.trim()
      )
    }
    if (ok) {
      _dataVersion.value++
      recordAudit("CAMPUS_DETAILS_EDITED", catalog.universityName(universityId), me.id)
    }
    return ok
  }

  /**
   * Adds an approved person to a campus without the sign-up queue. The app manager can add to any
   * campus; a campus administrator only to their own.
   */
  fun addCampusUser(
    universityId: String,
    role: Role,
    fullName: String,
    email: String,
    idNumber: String,
    programme: String?,
    password: String
  ): PlatformResult {
    val me = currentUser.value
    val manager = isAppManager()
    val ownAdmin = isActive(me) && me.role == Role.ADMIN && !me.isPlatformAdmin && me.universityId == universityId
    if (!manager && !ownAdmin) return PlatformResult(false, PlatformError.NOT_ALLOWED)
    if (!catalog.hasUniversity(universityId)) return PlatformResult(false, PlatformError.DETAILS_MISSING)
    val error = auth.createApprovedUser(universityId, role, fullName, email, idNumber, programme, password)
    if (error != null) return PlatformResult(false, authError = error)
    _dataVersion.value++
    recordAudit("USER_ADDED", "${fullName.trim()} (${role.name.lowercase()}) at ${catalog.universityName(universityId)}", me.id)
    return PlatformResult(true)
  }

  /** Removes a campus entirely: its data, its people and its place in the sign-up list. */
  fun deleteCampus(universityId: String): Boolean {
    if (!isAppManager() || universityId == SeedUsers.PLATFORM_ID || !catalog.hasUniversity(universityId)) return false
    val name = catalog.universityName(universityId)
    wipeCampus(universityId, WipeScope.EVERYTHING)
    catalog.removeUniversity(universityId)
    _dataVersion.value++
    recordAudit("CAMPUS_DELETED", name, SeedUsers.PLATFORM_ID)
    return true
  }

  // ---------- My data ----------

  /** Plain-text copy of everything stored about the signed-in person. */
  fun exportMyData(): String {
    val user = currentUser.value
    val notes = _notifications.value.filter { it.userId == user.id }
    val ticks = _doneTicks.value[user.id].orEmpty()
    val reads = _readReceipts.value.count { (_, readers) -> user.id in readers }
    val myDevices = auth.devices.value[user.id].orEmpty()
    val mySubmissions = _submissions.value.filter { it.studentId == user.id }
    return buildString {
      appendLine("ChuoPulse: data stored about you")
      appendLine()
      appendLine("Name: ${user.fullName}")
      appendLine("Email: ${user.email}")
      appendLine("Role: ${user.role.name}")
      appendLine("Registration/Staff number: ${user.studentNumber ?: user.staffNumber ?: "none"}")
      appendLine("Programme/Department: ${user.programme ?: "none"}")
      appendLine("Enrolled courses: ${user.enrolledCourses.sorted().joinToString(", ").ifBlank { "none" }}")
      appendLine("Courses taught: ${user.teachesCourses.sorted().joinToString(", ").ifBlank { "none" }}")
      appendLine()
      appendLine("Notifications: ${notes.size}")
      appendLine("Deadlines ticked done: ${ticks.size}")
      appendLine("Announcements opened: $reads")
      appendLine("Submissions: ${mySubmissions.size}")
      appendLine("Signed-in phones: ${myDevices.size}")
    }
  }

  private val _deletionRequests = MutableStateFlow<Set<String>>(emptySet())
  val deletionRequests: StateFlow<Set<String>> = _deletionRequests.asStateFlow()

  fun requestDataDeletion() {
    val user = currentUser.value
    if (user.id in _deletionRequests.value || !isActive(user)) return
    _deletionRequests.value = _deletionRequests.value + user.id
    recordAudit("DATA_DELETION_REQUESTED", "${user.fullName} asked for their data to be deleted")
    val admins = activeProfiles().filter { it.role == Role.ADMIN }.map { it.id }
    notifyUsers(admins, "Data deletion request", "${user.fullName} asked for their personal data to be deleted.", NotificationType.APPROVAL)
  }

  // ---------- Audit ----------

  private fun recordAudit(action: String, details: String, universityId: String? = null) {
    val now = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date()) + " EAT"
    val user = currentUser.value
    val log = AuditLog(
      id = "log-" + UUID.randomUUID().toString().take(6),
      universityId = universityId ?: user.universityId,
      actor = user.fullName + " (${user.role.name})",
      action = action,
      details = details,
      timestamp = now
    )
    _auditLogs.value = listOf(log) + _auditLogs.value
  }
}
