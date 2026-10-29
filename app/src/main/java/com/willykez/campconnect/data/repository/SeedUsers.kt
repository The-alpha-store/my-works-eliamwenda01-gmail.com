package com.willykez.campconnect.data.repository

import com.willykez.campconnect.data.model.AccountStatus
import com.willykez.campconnect.data.model.Role
import com.willykez.campconnect.data.model.University
import com.willykez.campconnect.data.model.UserProfile

/**
 * Sample people and the university's reference lists.
 *
 * In a connected build these come from the backend (profiles table, student list, HR list).
 * They live here only because this prototype has no server yet.
 */
object SeedUsers {

  /** Cuom's courses, used to enrol the sample students below. The live catalogue is in CampusCatalog. */
  val courseCatalog: Map<String, Set<String>> = mapOf(
    "BSc Computer Science" to setOf("CS210", "MT101", "CS212", "IS202", "CS215", "GS101"),
    "BSc Information Systems" to setOf("IS202", "MT101", "GS101")
  )

  val programmes: List<String> = courseCatalog.keys.toList()

  /** The id of the pseudo-university that holds the app manager. It is never offered at sign-up. */
  const val PLATFORM_ID = "platform"

  private val csCourses = courseCatalog.getValue("BSc Computer Science")

  val asha = UserProfile(
    id = "usr-stu-01",
    fullName = "Asha Mwangi",
    email = "a.mwangi@campus.ac.tz",
    role = Role.STUDENT,
    status = AccountStatus.APPROVED,
    studentNumber = "T22-03-08492",
    programme = "BSc Computer Science",
    cohort = "Year 2 • Semester 1",
    enrolledCourses = csCourses
  )

  val kelvin = UserProfile(
    id = "usr-stu-02",
    fullName = "Kelvin Tarimo",
    email = "k.tarimo@campus.ac.tz",
    role = Role.STUDENT,
    status = AccountStatus.APPROVED,
    studentNumber = "T22-03-09120",
    programme = "BSc Computer Science",
    cohort = "Year 2 • Semester 1",
    enrolledCourses = csCourses
  )

  val mushi = UserProfile(
    id = "usr-lec-01",
    fullName = "Dr. Joseph Mushi",
    email = "j.mushi@campus.ac.tz",
    role = Role.LECTURER,
    status = AccountStatus.APPROVED,
    staffNumber = "STF-CS-041",
    programme = "Department of Computer Science",
    cohort = "Senior Lecturer",
    requestedRole = Role.LECTURER,
    teachesCourses = setOf("CS210", "CS215")
  )

  val grace = UserProfile(
    id = "usr-lec-02",
    fullName = "Prof. Grace Temu",
    email = "g.temu@campus.ac.tz",
    role = Role.LECTURER,
    status = AccountStatus.APPROVED,
    staffNumber = "STF-MT-019",
    programme = "Mathematics & Logic Department",
    cohort = "Associate Professor",
    requestedRole = Role.LECTURER,
    teachesCourses = setOf("MT101")
  )

  // Lecturers who appear on the timetable but are not demo personas.
  val bakari = UserProfile(
    id = "usr-lec-03",
    fullName = "Dr. Salim Bakari",
    email = "s.bakari@campus.ac.tz",
    role = Role.LECTURER,
    status = AccountStatus.APPROVED,
    staffNumber = "STF-CS-052",
    programme = "Department of Computer Science",
    cohort = "Lecturer",
    requestedRole = Role.LECTURER,
    teachesCourses = setOf("CS212")
  )

  val kweka = UserProfile(
    id = "usr-lec-04",
    fullName = "Eng. Daniel Kweka",
    email = "d.kweka@campus.ac.tz",
    role = Role.LECTURER,
    status = AccountStatus.APPROVED,
    staffNumber = "STF-IS-033",
    programme = "Department of Information Systems",
    cohort = "Lecturer",
    requestedRole = Role.LECTURER,
    teachesCourses = setOf("IS202")
  )

  val ndosi = UserProfile(
    id = "usr-lec-05",
    fullName = "Dr. Mariam Ndosi",
    email = "m.ndosi@campus.ac.tz",
    role = Role.LECTURER,
    status = AccountStatus.APPROVED,
    staffNumber = "STF-GS-012",
    programme = "General Studies Department",
    cohort = "Lecturer",
    requestedRole = Role.LECTURER,
    teachesCourses = setOf("GS101")
  )

  val emanuel = UserProfile(
    id = "usr-adm-01",
    fullName = "Emanuel Mwenda (Super Admin)",
    email = "admin.registry@campus.ac.tz",
    role = Role.ADMIN,
    status = AccountStatus.APPROVED,
    staffNumber = "STF-ADM-001",
    programme = "Academic Registry Office",
    cohort = "Chief Administrator",
    requestedRole = Role.ADMIN
  )

  val sarah = UserProfile(
    id = "usr-adm-02",
    fullName = "Dr. Sarah Kimaro (Faculty Admin)",
    email = "s.kimaro@campus.ac.tz",
    role = Role.ADMIN,
    status = AccountStatus.APPROVED,
    staffNumber = "STF-ADM-008",
    programme = "Dean of ICT & Engineering",
    cohort = "Faculty Administrator",
    requestedRole = Role.ADMIN
  )

  // Accounts waiting in the registry queue. They have confirmed their email but see nothing else.
  val baraka = UserProfile(
    id = "usr-pen-01",
    fullName = "Baraka Juma",
    email = "b.juma@campus.ac.tz",
    role = Role.STUDENT,
    status = AccountStatus.PENDING,
    studentNumber = "T24-03-01140",
    programme = "BSc Information Systems",
    cohort = "Year 1 • Semester 1"
  )

  val neema = UserProfile(
    id = "usr-pen-02",
    fullName = "Neema Mollel",
    email = "n.mollel@campus.ac.tz",
    role = Role.STUDENT,
    status = AccountStatus.PENDING,
    studentNumber = "T23-03-09412",
    programme = "BSc Computer Science",
    cohort = "Year 2 • Semester 1"
  )

  val minja = UserProfile(
    id = "usr-pen-03",
    fullName = "Eng. Peter Minja",
    email = "p.minja@campus.ac.tz",
    role = Role.STUDENT, // not a lecturer until an administrator grants it
    status = AccountStatus.PENDING,
    staffNumber = "STF-EE-078",
    programme = "Department of Electrical Engineering",
    cohort = "Assistant Lecturer",
    requestedRole = Role.LECTURER
  )

  // A second campus, to show that universities are kept apart. Their numbers and emails are free-form.
  val ifmRegistry = UserProfile(
    id = "usr-ifm-adm",
    fullName = "Rehema Salum (IFM Registry)",
    email = "ifm.registry@gmail.com",
    role = Role.ADMIN,
    status = AccountStatus.APPROVED,
    universityId = "ifm",
    staffNumber = "IFM/REG/01",
    programme = "Registry",
    cohort = "Registrar",
    requestedRole = Role.ADMIN
  )

  val ifmStudent = UserProfile(
    id = "usr-ifm-stu",
    fullName = "Joyce Mapunda",
    email = "joyce.mapunda@gmail.com",
    role = Role.STUDENT,
    status = AccountStatus.APPROVED,
    universityId = "ifm",
    studentNumber = "2023/BAC/00123",
    programme = "Bachelor of Accounting",
    cohort = "Year 2"
  )

  // The app manager. Belongs to no campus, so nothing a campus owns is visible to them.
  val platformManager = UserProfile(
    id = "usr-platform",
    fullName = "ChuoPulse Manager",
    email = "manager@campusconnect.app",
    role = Role.ADMIN,
    status = AccountStatus.APPROVED,
    universityId = PLATFORM_ID,
    staffNumber = "APP-001",
    programme = "App management",
    cohort = "App manager",
    requestedRole = Role.ADMIN,
    isPlatformAdmin = true
  )

  /** Everyone the prototype knows about. */
  val directory: List<UserProfile> =
    listOf(asha, kelvin, mushi, grace, bakari, kweka, ndosi, emanuel, sarah, baraka, neema, minja, ifmRegistry, ifmStudent, platformManager)

  /** The three people shown in demo mode. */
  val demoPersonas: List<UserProfile> = listOf(asha, mushi, emanuel)

  /** Cuom's student list: used to flag registration numbers that do not match. Other campuses add theirs later. */
  val studentList: Set<String> = setOf(
    "T22-03-08492", "T22-03-09120", "T24-03-01140", "T23-03-09412", "T23-03-00007", "T24-03-00311"
  )

  /** HR staff list. */
  val staffList: Set<String> = setOf(
    "STF-CS-041", "STF-MT-019", "STF-CS-052", "STF-IS-033", "STF-GS-012",
    "STF-ADM-001", "STF-ADM-008", "STF-EE-078"
  )

  /** Only Cuom has uploaded its student and staff lists so far. */
  fun hasRegistryList(universityId: String): Boolean = universityId == "cuom"

  /** Password given to the sample accounts so the sign-in flow can be tried end to end. */
  const val SAMPLE_PASSWORD = "Campus#2026"
}
