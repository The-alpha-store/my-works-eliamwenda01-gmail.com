package com.willykez.campconnect

import com.willykez.campconnect.data.auth.AuthOutcome
import com.willykez.campconnect.data.model.AttemptStatus
import com.willykez.campconnect.data.model.QuestionType
import com.willykez.campconnect.data.model.QuizError
import com.willykez.campconnect.data.model.QuizQuestion
import com.willykez.campconnect.data.model.UserProfile
import com.willykez.campconnect.data.repository.CampusRepository
import com.willykez.campconnect.data.repository.SeedUsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of timed quizzes: who can set them, the countdown, auto-submit and marking. */
class QuizRulesTest {

  private fun signInAs(repo: CampusRepository, user: UserProfile) {
    if (repo.session.value != null) repo.signOut()
    var outcome = repo.auth.signIn(user.email, SeedUsers.SAMPLE_PASSWORD)
    if (outcome is AuthOutcome.NeedCode) {
      val code = repo.auth.visibleCode.value!!
      outcome = repo.auth.submitCode(outcome.purpose, outcome.email, code)
    }
    assertTrue("sign-in should succeed for ${user.email}", outcome is AuthOutcome.Success)
  }

  private val minute = 60_000L
  private val t0 = 1_800_000_000_000L // a fixed "now" so the tests never depend on the clock

  private val questions = listOf(
    QuizQuestion("", QuestionType.MULTIPLE_CHOICE, "Which structure is last in, first out?", listOf("Queue", "Stack", "Tree"), correctOption = 1, points = 2),
    QuizQuestion("", QuestionType.MULTIPLE_CHOICE, "Binary search needs a...", listOf("Sorted list", "Hash table"), correctOption = 0, points = 1),
    QuizQuestion("", QuestionType.SHORT_ANSWER, "Define Big-O notation.", modelAnswer = "An upper bound on growth.", points = 4)
  )

  /** Mushi sets a 20-minute quiz that is open for two hours starting at t0. */
  private fun repoWithQuiz(): Pair<CampusRepository, String> {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.mushi)
    assertNull(repo.createQuiz("CS210", "Week 5 quiz", "Answer everything", 20, t0, t0 + 120 * minute, questions, now = t0 - minute))
    val id = repo.quizzes.value.single().id
    return repo to id
  }

  @Test
  fun `a lecturer sets a quiz and students get the questions without the answers`() {
    val (repo, quizId) = repoWithQuiz()
    val setByLecturer = repo.quizzes.value.single()
    assertEquals(7, setByLecturer.totalPoints)
    assertEquals(1, setByLecturer.questions.first().correctOption)

    signInAs(repo, SeedUsers.asha)
    val seen = repo.quizzes.value.single { it.id == quizId }
    assertTrue(seen.questions.all { it.correctOption == null && it.modelAnswer.isEmpty() })
    assertEquals(3, seen.questions.size)
    assertTrue(repo.notifications.value.any { it.title == "New quiz: CS210" })
  }

  @Test
  fun `only the right people can set or change a quiz`() {
    val repo = CampusRepository()
    signInAs(repo, SeedUsers.asha)
    assertEquals(QuizError.NOT_ALLOWED, repo.createQuiz("CS210", "Sneaky", "", 10, t0, t0 + 60 * minute, questions, now = t0 - minute))

    signInAs(repo, SeedUsers.grace) // teaches MT101, not CS210
    assertEquals(QuizError.NOT_ALLOWED, repo.createQuiz("CS210", "Not mine", "", 10, t0, t0 + 60 * minute, questions, now = t0 - minute))

    signInAs(repo, SeedUsers.mushi)
    assertEquals(QuizError.UNKNOWN_COURSE, repo.createQuiz("NOPE1", "x", "", 10, t0, t0 + 60 * minute, questions, now = t0 - minute))
    assertEquals(QuizError.TITLE_REQUIRED, repo.createQuiz("CS210", " ", "", 10, t0, t0 + 60 * minute, questions, now = t0 - minute))
    assertEquals(QuizError.NO_QUESTIONS, repo.createQuiz("CS210", "x", "", 10, t0, t0 + 60 * minute, emptyList(), now = t0 - minute))
    assertEquals(QuizError.BAD_DURATION, repo.createQuiz("CS210", "x", "", 0, t0, t0 + 60 * minute, questions, now = t0 - minute))
    assertEquals(QuizError.BAD_DURATION, repo.createQuiz("CS210", "x", "", 181, t0, t0 + 600 * minute, questions, now = t0 - minute))
    assertEquals(QuizError.BAD_WINDOW, repo.createQuiz("CS210", "x", "", 30, t0, t0 + 10 * minute, questions, now = t0 - minute))
    assertEquals(QuizError.BAD_WINDOW, repo.createQuiz("CS210", "x", "", 10, t0, t0 + 60 * minute, questions, now = t0 + 90 * minute))
    val noRightAnswer = listOf(QuizQuestion("", QuestionType.MULTIPLE_CHOICE, "Pick one", listOf("a", "b"), correctOption = null))
    assertEquals(QuizError.BAD_QUESTION, repo.createQuiz("CS210", "x", "", 10, t0, t0 + 60 * minute, noRightAnswer, now = t0 - minute))
    assertTrue(repo.quizzes.value.isEmpty())
  }

  @Test
  fun `a quiz cannot be started before it opens or after it closes`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    assertEquals(QuizError.NOT_OPEN_YET, repo.startAttempt(quizId, now = t0 - minute).error)
    assertEquals(QuizError.CLOSED, repo.startAttempt(quizId, now = t0 + 121 * minute).error)
    assertNotNull(repo.startAttempt(quizId, now = t0 + minute).attempt)
  }

  @Test
  fun `the timer is the duration, but never runs past the closing time`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    val early = repo.startAttempt(quizId, now = t0 + 5 * minute).attempt!!
    assertEquals(t0 + 25 * minute, early.deadlineAt)

    signInAs(repo, SeedUsers.kelvin)
    val late = repo.startAttempt(quizId, now = t0 + 110 * minute).attempt!!
    assertEquals(t0 + 120 * minute, late.deadlineAt) // only 10 minutes were left in the window
  }

  @Test
  fun `pressing start again resumes the same attempt and never gives a second go`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    val first = repo.startAttempt(quizId, now = t0 + minute).attempt!!
    val again = repo.startAttempt(quizId, now = t0 + 3 * minute).attempt!!
    assertEquals(first.id, again.id)
    assertEquals(first.deadlineAt, again.deadlineAt)
    assertNull(repo.submitAttempt(first.id, now = t0 + 4 * minute))
    assertEquals(QuizError.ALREADY_SUBMITTED, repo.startAttempt(quizId, now = t0 + 5 * minute).error)
  }

  @Test
  fun `when the timer reaches zero the answers already given are handed in`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    val attempt = repo.startAttempt(quizId, now = t0).attempt!!
    assertNull(repo.saveAnswer(attempt.id, "q-1", "1", now = t0 + 2 * minute))
    assertNull(repo.saveAnswer(attempt.id, "q-3", "An upper bound on growth.", now = t0 + 5 * minute))

    // Nobody presses submit. The timer passes zero.
    repo.finalizeExpiredAttempts(now = t0 + 21 * minute)
    val done = repo.attempts.value.single()
    assertEquals(AttemptStatus.AUTO_SUBMITTED, done.status)
    assertEquals(t0 + 20 * minute, done.submittedAt)
    assertEquals("1", done.answers["q-1"])
    assertEquals(2, done.answers.size)

    // Answers sent after zero are refused and change nothing.
    assertEquals(QuizError.TIME_UP, repo.saveAnswer(attempt.id, "q-2", "0", now = t0 + 22 * minute))
    assertFalse(repo.attempts.value.single().answers.containsKey("q-2"))
  }

  @Test
  fun `answers still on the screen at zero are kept for a few seconds`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    val attempt = repo.startAttempt(quizId, now = t0).attempt!!
    val deadline = attempt.deadlineAt
    assertNull(repo.submitAttempt(attempt.id, mapOf("q-2" to "0", "q-3" to "Last second"), timedOut = true, now = deadline + 2_000))
    val done = repo.attempts.value.single()
    assertEquals(AttemptStatus.AUTO_SUBMITTED, done.status)
    assertEquals("0", done.answers["q-2"])
    assertEquals("Last second", done.answers["q-3"])

    // A late flush well after zero is ignored.
    signInAs(repo, SeedUsers.kelvin)
    val other = repo.startAttempt(quizId, now = t0).attempt!!
    assertNull(repo.submitAttempt(other.id, mapOf("q-2" to "0"), timedOut = true, now = other.deadlineAt + CampusRepository.SUBMIT_GRACE_MS + 5_000))
    assertTrue(repo.attempts.value.single().answers.isEmpty())
    assertEquals(AttemptStatus.AUTO_SUBMITTED, repo.attempts.value.single().status)
  }

  @Test
  fun `multiple choice is marked at once and written answers wait for the lecturer`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    val attempt = repo.startAttempt(quizId, now = t0).attempt!!
    assertNull(repo.scoreOf(attempt.id)) // no peeking while the quiz is running
    repo.saveAnswer(attempt.id, "q-1", "1", now = t0 + minute)   // right: 2 points
    repo.saveAnswer(attempt.id, "q-2", "1", now = t0 + minute)   // wrong
    repo.saveAnswer(attempt.id, "q-3", "It bounds growth from above.", now = t0 + minute)
    assertNull(repo.submitAttempt(attempt.id, now = t0 + 3 * minute))

    val first = repo.scoreOf(attempt.id)!!
    assertEquals(2, first.choicePoints)
    assertEquals(3, first.choiceTotal)
    assertEquals(0, first.writtenPoints)
    assertEquals(4, first.writtenTotal)
    assertFalse(first.complete)

    // A student cannot mark their own work.
    assertEquals(QuizError.NOT_ALLOWED, repo.gradeAttempt(attempt.id, mapOf("q-3" to 4)))

    signInAs(repo, SeedUsers.mushi)
    assertNull(repo.gradeAttempt(attempt.id, mapOf("q-3" to 3, "q-1" to 5))) // q-1 is not written: ignored
    signInAs(repo, SeedUsers.asha)
    val marked = repo.scoreOf(attempt.id)!!
    assertEquals(5, marked.points)
    assertEquals(7, marked.total)
    assertTrue(marked.complete)
    assertTrue(repo.notifications.value.any { it.title == "Quiz marked: CS210" })
  }

  @Test
  fun `points cannot exceed what a question is worth`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    val attempt = repo.startAttempt(quizId, now = t0).attempt!!
    repo.submitAttempt(attempt.id, now = t0 + minute)
    signInAs(repo, SeedUsers.mushi)
    assertNull(repo.gradeAttempt(attempt.id, mapOf("q-3" to 99)))
    assertEquals(4, repo.attempts.value.single().awarded["q-3"])
  }

  @Test
  fun `students only see their own attempts and only quizzes of their own campus and courses`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.asha)
    repo.startAttempt(quizId, now = t0)
    signInAs(repo, SeedUsers.kelvin)
    assertTrue(repo.attempts.value.isEmpty())
    assertNotNull(repo.startAttempt(quizId, now = t0).attempt)
    assertEquals(1, repo.attempts.value.size)

    signInAs(repo, SeedUsers.ifmStudent)
    assertTrue(repo.quizzes.value.isEmpty())
    assertEquals(QuizError.NOT_FOUND, repo.startAttempt(quizId, now = t0).error)

    signInAs(repo, SeedUsers.mushi)
    assertEquals(2, repo.attempts.value.size) // the lecturer sees everyone's attempt of their quiz
  }

  @Test
  fun `another lecturer cannot reschedule or delete a quiz that is not theirs`() {
    val (repo, quizId) = repoWithQuiz()
    signInAs(repo, SeedUsers.grace)
    assertEquals(QuizError.NOT_ALLOWED, repo.rescheduleQuiz(quizId, 30, t0, t0 + 200 * minute, now = t0))
    assertEquals(QuizError.NOT_ALLOWED, repo.deleteQuiz(quizId))

    signInAs(repo, SeedUsers.mushi)
    assertNull(repo.rescheduleQuiz(quizId, 30, t0, t0 + 200 * minute, now = t0))
    assertEquals(30, repo.quizzes.value.single().durationMinutes)
    assertNull(repo.deleteQuiz(quizId))
    assertTrue(repo.quizzes.value.isEmpty())
  }
}
