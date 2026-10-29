@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.willykez.campconnect.ui.screens

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.graphics.compositeOver

import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Visibility
import com.willykez.campconnect.ui.components.ActionButton
import com.willykez.campconnect.ui.components.SoftIconButton
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Send
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.willykez.campconnect.data.model.AttemptStatus
import com.willykez.campconnect.data.model.QuestionType
import com.willykez.campconnect.data.model.Quiz
import com.willykez.campconnect.data.model.QuizAttempt
import com.willykez.campconnect.data.model.QuizQuestion
import com.willykez.campconnect.data.model.Role
import com.willykez.campconnect.ui.components.AppBottomSheet
import com.willykez.campconnect.ui.components.ConfirmSheet
import com.willykez.campconnect.ui.components.DueDateTimeField
import com.willykez.campconnect.ui.components.PickerField
import com.willykez.campconnect.ui.components.PickerItem
import com.willykez.campconnect.ui.components.PickerSheet
import com.willykez.campconnect.ui.components.SheetButtons
import com.willykez.campconnect.ui.theme.DangerRed
import com.willykez.campconnect.ui.theme.DeepBlue
import com.willykez.campconnect.ui.theme.SuccessGreen
import com.willykez.campconnect.ui.theme.WarmAmber
import com.willykez.campconnect.util.DeadlineTime
import com.willykez.campconnect.viewmodel.CampusViewModel
import com.willykez.campconnect.viewmodel.ScreenTab
import kotlinx.coroutines.delay

// =====================================================================================
// The list of quizzes
// =====================================================================================

@Composable
fun QuizzesScreen(viewModel: CampusViewModel, modifier: Modifier = Modifier) {
  val user by viewModel.currentUser.collectAsState()
  val quizzes by viewModel.quizzes.collectAsState()
  val attempts by viewModel.quizAttempts.collectAsState()
  val isStudent = user.role == Role.STUDENT
  val t = { en: String, sw: String -> viewModel.t(en, sw) }

  // The clock moves on by itself so "opens in" and "closed" stay true, and timed-out attempts are handed in.
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      viewModel.finalizeExpiredQuizzes()
      delay(15_000)
    }
  }

  var creating by remember { mutableStateOf(false) }
  var taking by remember { mutableStateOf<Pair<Quiz, QuizAttempt>?>(null) }
  var resultAttemptId by remember { mutableStateOf<String?>(null) }
  var resultsQuizId by remember { mutableStateOf<String?>(null) }
  var reschedulingId by remember { mutableStateOf<String?>(null) }
  var deletingId by remember { mutableStateOf<String?>(null) }

  BackHandler { viewModel.setTab(ScreenTab.CALENDAR) }

  Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { viewModel.setTab(ScreenTab.CALENDAR) }, modifier = Modifier.testTag("quizzes_back_btn")) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back", "Rudi"))
        }
        Column {
          Text(t("Quizzes", "Majaribio"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
          Text(
            t("Timed online questions. Once you start, the clock does not stop.", "Maswali ya mtandaoni yenye muda. Ukishaanza, saa haisimami."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      Spacer(Modifier.height(10.dp))

      if (quizzes.isEmpty()) {
        Column(
          Modifier.fillMaxWidth().weight(1f).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
          Icon(Icons.Default.Quiz, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(8.dp))
          Text(if (isStudent) t("No quizzes yet", "Hakuna majaribio bado") else t("You have not set a quiz yet", "Hujaweka jaribio bado"), fontWeight = FontWeight.SemiBold)
          Text(
            if (isStudent) t("When a lecturer sets one for your course it appears here and you get a notification.", "Mhadhiri akiweka jaribio la kozi yako litaonekana hapa.")
            else t("Tap + to set multiple choice and written questions with a time limit.", "Gusa + kuweka maswali ya chaguo na ya kuandika yenye muda."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
          )
        }
      } else {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          items(quizzes.sortedByDescending { it.opensAt }, key = { it.id }) { quiz ->
            val mine = attempts.firstOrNull { it.quizId == quiz.id && it.studentId == user.id }
            val quizAttempts = attempts.filter { it.quizId == quiz.id }
            QuizCard(
              quiz = quiz, now = now, isStudent = isStudent, myAttempt = mine,
              myScore = mine?.let { viewModel.quizScore(it.id) },
              submitted = quizAttempts.count { it.status != AttemptStatus.IN_PROGRESS },
              running = quizAttempts.count { it.status == AttemptStatus.IN_PROGRESS },
              onStart = {
                val result = viewModel.startQuiz(quiz.id)
                result.attempt?.let { taking = quiz to it }
              },
              onViewResult = { mine?.let { resultAttemptId = it.id } },
              onResults = { resultsQuizId = quiz.id },
              onReschedule = { reschedulingId = quiz.id },
              onDelete = { deletingId = quiz.id },
              canManage = viewModel.canEditOrDelete(quiz.lecturerId),
              t = t
            )
          }
          item { Spacer(Modifier.height(80.dp)) }
        }
      }
    }

    if (!isStudent) {
      androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = { creating = true },
        containerColor = DeepBlue, contentColor = Color.White,
        icon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(26.dp)) },
        text = { Text(t("New quiz", "Jaribio jipya"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) },
        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).height(60.dp).testTag("fab_add_quiz")
      )
    }
  }

  if (creating) CreateQuizSheet(viewModel = viewModel, onDismiss = { creating = false })

  taking?.let { (quiz, attempt) ->
    TakeQuizDialog(viewModel = viewModel, quiz = quiz, attempt = attempt, onClose = { taking = null })
  }

  attempts.firstOrNull { it.id == resultAttemptId }?.let { attempt ->
    quizzes.firstOrNull { it.id == attempt.quizId }?.let { quiz ->
      QuizResultSheet(viewModel = viewModel, quiz = quiz, attempt = attempt, onDismiss = { resultAttemptId = null })
    }
  }

  quizzes.firstOrNull { it.id == resultsQuizId }?.let { quiz ->
    QuizResultsSheet(viewModel = viewModel, quiz = quiz, attempts = attempts.filter { it.quizId == quiz.id }, onDismiss = { resultsQuizId = null })
  }

  quizzes.firstOrNull { it.id == reschedulingId }?.let { quiz ->
    RescheduleQuizSheet(viewModel = viewModel, quiz = quiz, onDismiss = { reschedulingId = null })
  }

  quizzes.firstOrNull { it.id == deletingId }?.let { quiz ->
    ConfirmSheet(
      title = t("Delete this quiz?", "Futa jaribio hili?"),
      message = t(
        "\"${quiz.title}\" and every student's attempt and marks will be removed. This cannot be undone.",
        "\"${quiz.title}\" na majaribio na alama zote za wanafunzi zitaondolewa. Hili haliwezi kurudishwa."
      ),
      confirmLabel = t("Delete quiz", "Futa jaribio"),
      dismissLabel = t("Cancel", "Ghairi"),
      onDismiss = { deletingId = null },
      onConfirm = { viewModel.deleteQuiz(quiz.id); deletingId = null },
      confirmTag = "confirm_delete_quiz_btn"
    )
  }
}

@Composable
private fun QuizCard(
  quiz: Quiz, now: Long, isStudent: Boolean, myAttempt: QuizAttempt?, myScore: com.willykez.campconnect.data.model.QuizScore?,
  submitted: Int, running: Int,
  onStart: () -> Unit, onViewResult: () -> Unit, onResults: () -> Unit, onReschedule: () -> Unit, onDelete: () -> Unit,
  canManage: Boolean, t: (String, String) -> String
) {
  val open = now in quiz.opensAt until quiz.closesAt
  val state: Pair<String, Color> = when {
    isStudent && myAttempt?.status == AttemptStatus.IN_PROGRESS && myAttempt.deadlineAt > now -> t("In progress", "Inaendelea") to WarmAmber
    isStudent && myAttempt != null -> t("Submitted", "Imewasilishwa") to SuccessGreen
    now < quiz.opensAt -> t("Opens ${DeadlineTime.urgency(DeadlineTime.format(quiz.opensAt), "", false).replace("Due in ", "in ")}", "Inafunguliwa baadaye") to MaterialTheme.colorScheme.onSurfaceVariant
    now >= quiz.closesAt -> t("Closed", "Imefungwa") to DangerRed
    else -> t("Open now", "Wazi sasa") to SuccessGreen
  }
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).testTag("quiz_card_${quiz.id}")
  ) {
    Column {
    Box(Modifier.fillMaxWidth().height(8.dp).background(state.second))
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(quiz.courseCode, color = DeepBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
          state.first, color = state.second, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
          modifier = Modifier.clip(RoundedCornerShape(50)).background(state.second.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)
        )
      }
      Spacer(Modifier.height(6.dp))
      Text(quiz.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(quiz.courseTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
          t("${quiz.durationMinutes} min \u2022 ${quiz.questions.size} questions \u2022 ${quiz.totalPoints} points", "Dakika ${quiz.durationMinutes} \u2022 maswali ${quiz.questions.size} \u2022 alama ${quiz.totalPoints}"),
          style = MaterialTheme.typography.labelMedium
        )
      }
      Text(
        t("Open ${DeadlineTime.format(quiz.opensAt)}\nCloses ${DeadlineTime.format(quiz.closesAt)}", "Inafunguliwa ${DeadlineTime.format(quiz.opensAt)}\nInafungwa ${DeadlineTime.format(quiz.closesAt)}"),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
      )
      Spacer(Modifier.height(10.dp))

      if (isStudent) {
        when {
          myAttempt?.status == AttemptStatus.IN_PROGRESS && myAttempt.deadlineAt > now ->
            ActionButton(label = t("Resume", "Endelea"), icon = Icons.Default.PlayArrow, onClick = onStart, filled = true, color = WarmAmber, modifier = Modifier.fillMaxWidth(), tag = "resume_quiz_${quiz.id}")
          myAttempt != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                myScore?.let { s -> if (s.complete) t("Score ${s.points} / ${s.total}", "Alama ${s.points} / ${s.total}") else t("Multiple choice ${s.choicePoints} / ${s.choiceTotal} \u2022 written answers waiting for marks", "Chaguo ${s.choicePoints} / ${s.choiceTotal} \u2022 majibu yanasubiri alama") } ?: "",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)
              )
              ActionButton(label = t("View result", "Angalia matokeo"), icon = Icons.Default.Visibility, onClick = onViewResult, tag = "view_quiz_result_${quiz.id}")
            }
          }
          open -> ActionButton(
            label = t("Start (${quiz.durationMinutes} min)", "Anza (dakika ${quiz.durationMinutes})"),
            icon = Icons.Default.PlayArrow, onClick = onStart, filled = true,
            modifier = Modifier.fillMaxWidth(), tag = "start_quiz_${quiz.id}"
          )
          else -> Unit
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            t("$submitted submitted \u2022 $running writing now", "$submitted wamewasilisha \u2022 $running wanaandika sasa"),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)
          )
          if (canManage) {
            SoftIconButton(Icons.Default.Edit, t("Reschedule", "Panga upya"), onReschedule, tag = "reschedule_quiz_${quiz.id}")
            Spacer(Modifier.width(8.dp))
            SoftIconButton(Icons.Default.Delete, t("Delete", "Futa"), onDelete, color = DangerRed, tag = "delete_quiz_${quiz.id}")
            Spacer(Modifier.width(8.dp))
          }
          ActionButton(label = t("Results", "Matokeo"), icon = Icons.Default.BarChart, onClick = onResults, filled = true, tag = "quiz_results_${quiz.id}")
        }
      }
    }
    }
  }
}

// =====================================================================================
// Taking a quiz: the countdown, the questions, automatic hand-in
// =====================================================================================

private fun clock(ms: Long): String {
  val total = (ms.coerceAtLeast(0) + 999) / 1000
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun TakeQuizDialog(viewModel: CampusViewModel, quiz: Quiz, attempt: QuizAttempt, onClose: () -> Unit) {
  val t = { en: String, sw: String -> viewModel.t(en, sw) }
  val answers = remember(attempt.id) { mutableStateMapOf<String, String>().apply { putAll(attempt.answers) } }
  var index by remember(attempt.id) { mutableIntStateOf(0) }
  var remaining by remember(attempt.id) { mutableLongStateOf(attempt.deadlineAt - System.currentTimeMillis()) }
  // null while writing; false once handed in by the student; true once the timer ended it.
  var timedOut by remember(attempt.id) { mutableStateOf<Boolean?>(null) }
  var confirmSubmit by remember { mutableStateOf(false) }
  var confirmLeave by remember { mutableStateOf(false) }
  val total = (attempt.deadlineAt - attempt.startedAt).coerceAtLeast(1)

  // The countdown. It reads the wall clock, so leaving the app and coming back cannot buy extra time.
  LaunchedEffect(attempt.id, timedOut) {
    if (timedOut != null) return@LaunchedEffect
    while (true) {
      remaining = attempt.deadlineAt - System.currentTimeMillis()
      if (remaining <= 0) {
        // Hand in what has been answered so far, including text typed in the last moments.
        viewModel.submitQuiz(attempt.id, answers.toMap(), timedOut = true)
        timedOut = true
        break
      }
      delay(250)
    }
  }

  BackHandler(enabled = timedOut == null) { confirmLeave = true }

  Dialog(
    onDismissRequest = {},
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
  ) {
    Surface(modifier = Modifier.fillMaxSize().testTag("take_quiz_dialog"), color = MaterialTheme.colorScheme.background) {
      if (timedOut != null) {
        FinishedPanel(viewModel, quiz, attempt, timedOut == true, onClose)
      } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
          val urgent = remaining <= 60_000
          val warn = remaining <= 5 * 60_000
          val timerColor = when { urgent -> DangerRed; warn -> WarmAmber; else -> DeepBlue }
          Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { confirmLeave = true }) { Icon(Icons.Default.Close, contentDescription = t("Leave quiz", "Ondoka")) }
            Column(Modifier.weight(1f)) {
              Text(quiz.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(quiz.courseCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.clip(RoundedCornerShape(50)).background(timerColor.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp).testTag("quiz_timer")
            ) {
              Icon(Icons.Default.Timer, contentDescription = null, tint = timerColor, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(clock(remaining), color = timerColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
          }
          LinearProgressIndicator(
            progress = { (remaining.toFloat() / total).coerceIn(0f, 1f) },
            color = timerColor, trackColor = timerColor.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp)
          )

          // Google Forms style: the whole quiz is one scrolling form, one card per question.
          var focusedId by remember(attempt.id) { mutableStateOf<String?>(null) }
          var confirmClear by remember(attempt.id) { mutableStateOf(false) }
          val answeredCount = quiz.questions.count { !answers[it.id].isNullOrBlank() }

          Column(
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .background(DeepBlue.copy(alpha = 0.06f))
              .verticalScroll(rememberScrollState())
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            FormHeaderCard(quiz = quiz, t = t)
            quiz.questions.forEachIndexed { i, q ->
              FormQuestionCard(
                number = i + 1,
                question = q,
                answer = answers[q.id].orEmpty(),
                focused = focusedId == q.id,
                onFocus = { focusedId = q.id },
                onAnswer = { value ->
                  answers[q.id] = value
                  if (q.type == QuestionType.MULTIPLE_CHOICE) viewModel.saveQuizAnswer(attempt.id, q.id, value)
                },
                onPersist = { value -> viewModel.saveQuizAnswer(attempt.id, q.id, value) },
                t = t
              )
            }
            Spacer(Modifier.height(4.dp))
          }

          // Footer: progress, Clear form, Submit
          Surface(shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.navigationBarsPadding()) {
              LinearProgressIndicator(
                progress = { answeredCount.toFloat() / quiz.questions.size.coerceAtLeast(1) },
                color = SuccessGreen, trackColor = SuccessGreen.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(3.dp)
              )
              Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                  Text(
                    t("$answeredCount of ${quiz.questions.size} answered", "Umejibu $answeredCount kati ya ${quiz.questions.size}"),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold
                  )
                  TextButton(
                    onClick = { confirmClear = true },
                    enabled = answeredCount > 0,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.testTag("quiz_clear_form_btn")
                  ) { Text(t("Clear form", "Futa majibu"), color = if (answeredCount > 0) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Button(
                  onClick = { confirmSubmit = true },
                  colors = ButtonDefaults.buttonColors(containerColor = DeepBlue),
                  shape = RoundedCornerShape(14.dp),
                  modifier = Modifier.height(52.dp).widthIn(min = 150.dp).testTag("quiz_submit_btn")
                ) {
                  Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(Modifier.width(8.dp))
                  Text(t("Submit", "Wasilisha"), fontWeight = FontWeight.Bold)
                }
              }
            }
          }
          if (confirmClear) {
            ConfirmSheet(
              title = t("Clear your answers?", "Futa majibu yako?"),
              message = t("Every answer on this form will be removed. The timer keeps running.", "Kila jibu kwenye fomu hii litaondolewa. Saa inaendelea."),
              confirmLabel = t("Clear form", "Futa majibu"),
              dismissLabel = t("Cancel", "Ghairi"),
              destructive = true,
              onDismiss = { confirmClear = false },
              onConfirm = {
                confirmClear = false
                quiz.questions.forEach { q ->
                  if (!answers[q.id].isNullOrBlank()) {
                    answers[q.id] = ""
                    viewModel.saveQuizAnswer(attempt.id, q.id, "")
                  }
                }
              }
            )
          }
        }

        if (confirmSubmit) {
          val unanswered = quiz.questions.count { answers[it.id].isNullOrBlank() }
          ConfirmSheet(
            title = t("Submit the quiz?", "Wasilisha jaribio?"),
            message = if (unanswered == 0) t("You answered every question. You cannot change your answers after submitting.", "Umejibu maswali yote. Huwezi kubadilisha majibu baada ya kuwasilisha.")
            else t("$unanswered question(s) have no answer and will score zero. You cannot change your answers after submitting.", "Maswali $unanswered hayana jibu na yatapata sifuri. Huwezi kubadilisha majibu baada ya kuwasilisha."),
            confirmLabel = t("Submit now", "Wasilisha sasa"),
            dismissLabel = t("Keep going", "Endelea"),
            destructive = false,
            onDismiss = { confirmSubmit = false },
            onConfirm = {
              confirmSubmit = false
              viewModel.submitQuiz(attempt.id, answers.toMap(), timedOut = false)
              timedOut = false
            },
            confirmTag = "confirm_submit_quiz_btn"
          )
        }
        if (confirmLeave) {
          ConfirmSheet(
            title = t("Leave the quiz?", "Ondoka kwenye jaribio?"),
            message = t(
              "The timer keeps running. You can come back and continue until it reaches zero, then your answers are submitted automatically.",
              "Saa inaendelea. Unaweza kurudi kuendelea hadi ifike sifuri, kisha majibu yako yatawasilishwa yenyewe."
            ),
            confirmLabel = t("Leave", "Ondoka"),
            dismissLabel = t("Stay", "Baki"),
            destructive = false,
            onDismiss = { confirmLeave = false },
            onConfirm = { confirmLeave = false; onClose() }
          )
        }
      }
    }
  }
}

@Composable
private fun FinishedPanel(viewModel: CampusViewModel, quiz: Quiz, attempt: QuizAttempt, timedOut: Boolean, onClose: () -> Unit) {
  val t = { en: String, sw: String -> viewModel.t(en, sw) }
  val score = viewModel.quizScore(attempt.id)
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = if (timedOut) WarmAmber else SuccessGreen, modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(16.dp))
    Text(
      if (timedOut) t("Time is up", "Muda umeisha") else t("Quiz submitted", "Jaribio limewasilishwa"),
      style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("quiz_finished_title")
    )
    Spacer(Modifier.height(8.dp))
    Text(
      if (timedOut) t("Your answers so far were submitted automatically.", "Majibu yako hadi sasa yamewasilishwa yenyewe.") else t("Your answers have been sent to your lecturer.", "Majibu yako yametumwa kwa mhadhiri wako."),
      textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    score?.let {
      Spacer(Modifier.height(16.dp))
      Text(t("Multiple choice: ${it.choicePoints} / ${it.choiceTotal}", "Chaguo nyingi: ${it.choicePoints} / ${it.choiceTotal}"), fontWeight = FontWeight.SemiBold)
      if (it.writtenTotal > 0) Text(t("Written answers: your lecturer will mark them", "Majibu ya kuandika: mhadhiri atayapa alama"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onClose, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(48.dp).testTag("quiz_finished_close_btn")) { Text(t("Done", "Imekamilika")) }
  }
}

// =====================================================================================
// Results (student) and results + marking (lecturer)
// =====================================================================================

@Composable
private fun QuizResultSheet(viewModel: CampusViewModel, quiz: Quiz, attempt: QuizAttempt, onDismiss: () -> Unit) {
  val t = { en: String, sw: String -> viewModel.t(en, sw) }
  val score = viewModel.quizScore(attempt.id)
  AppBottomSheet(onDismissRequest = onDismiss, title = quiz.title, subtitle = "${quiz.courseCode} \u2022 ${quiz.courseTitle}", tag = "quiz_result_sheet") {
    Spacer(Modifier.height(4.dp))
    if (score != null) {
      Text(
        if (score.complete) "${score.points} / ${score.total}" else "${score.choicePoints} / ${score.choiceTotal}",
        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = DeepBlue
      )
      Text(
        if (score.complete) t("Final score", "Alama ya mwisho") else t("Multiple choice so far. Written answers are waiting for your lecturer.", "Chaguo nyingi kwa sasa. Majibu ya kuandika yanasubiri mhadhiri."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    if (attempt.status == AttemptStatus.AUTO_SUBMITTED) {
      Text(t("The timer ended this attempt and your answers were submitted automatically.", "Saa ilimaliza jaribio hili na majibu yako yaliwasilishwa yenyewe."), style = MaterialTheme.typography.labelMedium, color = WarmAmber, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    quiz.questions.forEachIndexed { i, q ->
      Text("${i + 1}. ${q.text}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
      val given = attempt.answers[q.id]
      val shown = when {
        given.isNullOrBlank() -> t("No answer", "Hakuna jibu")
        q.type == QuestionType.MULTIPLE_CHOICE -> q.options.getOrNull(given.toIntOrNull() ?: -1) ?: given
        else -> given
      }
      Text(shown, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (q.type == QuestionType.SHORT_ANSWER && attempt.marked) {
        Text(t("Marks: ${attempt.awarded[q.id] ?: 0} / ${q.points}", "Alama: ${attempt.awarded[q.id] ?: 0} / ${q.points}"), style = MaterialTheme.typography.labelMedium, color = DeepBlue)
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(t("Correct answers are not shown here.", "Majibu sahihi hayaonyeshwi hapa."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun QuizResultsSheet(viewModel: CampusViewModel, quiz: Quiz, attempts: List<QuizAttempt>, onDismiss: () -> Unit) {
  val t = { en: String, sw: String -> viewModel.t(en, sw) }
  var markingId by remember { mutableStateOf<String?>(null) }
  val hasWritten = quiz.questions.any { it.type == QuestionType.SHORT_ANSWER }
  AppBottomSheet(onDismissRequest = onDismiss, title = t("Results", "Matokeo"), subtitle = "${quiz.title} \u2022 ${quiz.courseCode}", tag = "quiz_results_sheet") {
    Spacer(Modifier.height(4.dp))
    if (attempts.isEmpty()) {
      Text(t("Nobody has started this quiz yet.", "Hakuna aliyeanza jaribio hili bado."), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    attempts.sortedBy { it.studentName }.forEach { attempt ->
      val score = viewModel.quizScore(attempt.id)
      Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(attempt.studentName, fontWeight = FontWeight.SemiBold)
          Text(
            when (attempt.status) {
              AttemptStatus.IN_PROGRESS -> t("Writing now", "Anaandika sasa")
              AttemptStatus.SUBMITTED -> t("Submitted", "Imewasilishwa")
              AttemptStatus.AUTO_SUBMITTED -> t("Submitted when time ran out", "Imewasilishwa muda ulipoisha")
            } + " \u2022 ${attempt.studentNumber}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          score?.let { Text(if (it.complete) "${it.points} / ${it.total}" else t("Choice ${it.choicePoints}/${it.choiceTotal} \u2022 needs marking", "Chaguo ${it.choicePoints}/${it.choiceTotal} \u2022 inahitaji alama"), style = MaterialTheme.typography.labelMedium, color = DeepBlue) }
        }
        if (hasWritten && attempt.status != AttemptStatus.IN_PROGRESS) {
          OutlinedButton(onClick = { markingId = attempt.id }, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("mark_attempt_${attempt.id}")) {
            Text(if (attempt.marked) t("Edit marks", "Hariri alama") else t("Mark", "Weka alama"))
          }
        }
      }
    }
    Spacer(Modifier.height(8.dp))
  }
  attempts.firstOrNull { it.id == markingId }?.let { attempt ->
    MarkAttemptSheet(viewModel = viewModel, quiz = quiz, attempt = attempt, onDismiss = { markingId = null })
  }
}

@Composable
private fun MarkAttemptSheet(viewModel: CampusViewModel, quiz: Quiz, attempt: QuizAttempt, onDismiss: () -> Unit) {
  val t = { en: String, sw: String -> viewModel.t(en, sw) }
  val written = quiz.questions.filter { it.type == QuestionType.SHORT_ANSWER }
  val points = remember(attempt.id) { mutableStateMapOf<String, Int>().apply { written.forEach { put(it.id, attempt.awarded[it.id] ?: 0) } } }
  var error by remember { mutableStateOf<String?>(null) }
  AppBottomSheet(
    onDismissRequest = onDismiss, title = attempt.studentName, subtitle = "${quiz.title} \u2022 ${attempt.studentNumber}", tag = "mark_attempt_sheet",
    footer = {
      SheetButtons(
        dismissLabel = t("Cancel", "Ghairi"), onDismiss = onDismiss,
        confirmLabel = t("Save marks", "Hifadhi alama"),
        onConfirm = {
          val e = viewModel.gradeQuiz(attempt.id, points.toMap())
          if (e == null) onDismiss() else error = e
        },
        confirmTag = "save_marks_btn"
      )
    }
  ) {
    Spacer(Modifier.height(4.dp))
    written.forEachIndexed { i, q ->
      Text("${i + 1}. ${q.text}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
      Text(
        attempt.answers[q.id]?.takeIf { it.isNotBlank() } ?: t("No answer", "Hakuna jibu"),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(12.dp)
      )
      if (q.modelAnswer.isNotBlank()) {
        Text(t("Guide: ${q.modelAnswer}", "Mwongozo: ${q.modelAnswer}"), style = MaterialTheme.typography.labelMedium, color = SuccessGreen, modifier = Modifier.padding(top = 6.dp))
      }
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(t("Marks", "Alama"), modifier = Modifier.weight(1f))
        IconButton(onClick = { points[q.id] = ((points[q.id] ?: 0) - 1).coerceAtLeast(0) }) { Icon(Icons.Default.Remove, contentDescription = t("Less", "Punguza")) }
        Text("${points[q.id] ?: 0} / ${q.points}", fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { points[q.id] = ((points[q.id] ?: 0) + 1).coerceAtMost(q.points) }) { Icon(Icons.Default.Add, contentDescription = t("More", "Ongeza")) }
      }
    }
    error?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    Spacer(Modifier.height(8.dp))
  }
}

// =====================================================================================
// Setting a quiz: a Google Forms style editor (lecturer / administrator)
// =====================================================================================

private class DraftQuestion(type: QuestionType) {
  var type by mutableStateOf(type)
  var text by mutableStateOf("")
  var points by mutableStateOf("1")
  val options = mutableStateListOf("", "")
  var correct by mutableIntStateOf(0)
  var model by mutableStateOf("")

  fun duplicate(): DraftQuestion = DraftQuestion(type).also {
    it.text = text
    it.points = points
    it.options.clear(); it.options.addAll(options)
    it.correct = correct
    it.model = model
  }
}

private fun nextQuarterHour(now: Long = System.currentTimeMillis()): Long = (now / 900_000L + 1) * 900_000L

@Composable
private fun formFieldColors() = androidx.compose.material3.TextFieldDefaults.colors(
  focusedContainerColor = Color.Transparent,
  unfocusedContainerColor = Color.Transparent,
  focusedIndicatorColor = DeepBlue,
  unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
)

@Composable
private fun CreateQuizSheet(viewModel: CampusViewModel, onDismiss: () -> Unit) {
  val t = { en: String, sw: String -> viewModel.t(en, sw) }
  val courses = remember { viewModel.schedulableCourses() }
  var course by remember { mutableStateOf(courses.firstOrNull()?.first) }
  var pickingCourse by remember { mutableStateOf(false) }
  var title by remember { mutableStateOf("") }
  var instructions by remember { mutableStateOf("") }
  var duration by remember { mutableStateOf("20") }
  var opens by remember { mutableStateOf(DeadlineTime.format(nextQuarterHour())) }
  var closes by remember { mutableStateOf(DeadlineTime.format(nextQuarterHour() + 2 * 3_600_000L)) }
  val drafts = remember { mutableStateListOf(DraftQuestion(QuestionType.MULTIPLE_CHOICE)) }
  var focused by remember { mutableIntStateOf(0) }
  var tab by remember { mutableIntStateOf(0) }
  var error by remember { mutableStateOf<String?>(null) }
  var confirmDiscard by remember { mutableStateOf(false) }
  val touched = title.isNotBlank() || instructions.isNotBlank() || drafts.any { it.text.isNotBlank() }
  val canPublish = course != null && title.isNotBlank()
  val totalPoints = drafts.sumOf { it.points.toIntOrNull() ?: 0 }

  fun publish() {
    val questions = drafts.map { d ->
      QuizQuestion(
        id = "", type = d.type, text = d.text,
        options = if (d.type == QuestionType.MULTIPLE_CHOICE) d.options.map { it.trim() } else emptyList(),
        correctOption = if (d.type == QuestionType.MULTIPLE_CHOICE) d.correct else null,
        modelAnswer = d.model.trim(), points = d.points.toIntOrNull() ?: 0
      )
    }
    val e = viewModel.createQuiz(
      course.orEmpty(), title, instructions, duration.toIntOrNull() ?: 0,
      DeadlineTime.parse(opens) ?: 0L, DeadlineTime.parse(closes) ?: 0L, questions
    )
    if (e == null) onDismiss() else { error = e; tab = 0 }
  }

  BackHandler { if (touched) confirmDiscard = true else onDismiss() }

  Dialog(
    onDismissRequest = {},
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
  ) {
    Surface(Modifier.fillMaxSize().testTag("create_quiz_sheet"), color = DeepBlue.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.background)) {
      Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // Top bar
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
          Column {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              IconButton(onClick = { if (touched) confirmDiscard = true else onDismiss() }, modifier = Modifier.size(52.dp).testTag("close_quiz_editor_btn")) {
                Icon(Icons.Default.Close, contentDescription = t("Close", "Funga"), modifier = Modifier.size(26.dp))
              }
              Column(Modifier.weight(1f)) {
                Text(
                  title.ifBlank { t("Untitled quiz", "Jaribio lisilo na jina") },
                  style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                  t("${drafts.size} questions \u2022 $totalPoints points", "Maswali ${drafts.size} \u2022 alama $totalPoints"),
                  style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              Button(
                onClick = { publish() }, enabled = canPublish,
                colors = ButtonDefaults.buttonColors(containerColor = DeepBlue),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(52.dp).testTag("publish_quiz_btn")
              ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("Publish", "Chapisha"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
              }
            }
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface, contentColor = DeepBlue) {
              Tab(selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.height(52.dp).testTag("quiz_tab_questions"),
                text = { Text(t("Questions", "Maswali"), fontWeight = FontWeight.Bold) })
              Tab(selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.height(52.dp).testTag("quiz_tab_settings"),
                text = { Text(t("Settings", "Mipangilio"), fontWeight = FontWeight.Bold) })
            }
          }
        }

        error?.let {
          Surface(color = DangerRed.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
            Text(it, color = DangerRed, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp).testTag("quiz_error"))
          }
        }

        if (tab == 0) {
          Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            // Title card
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
              modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            ) {
              Column {
                Box(Modifier.fillMaxWidth().height(12.dp).background(DeepBlue))
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                  androidx.compose.material3.TextField(
                    value = title, onValueChange = { title = it },
                    placeholder = { Text(t("Untitled quiz", "Jaribio lisilo na jina"), style = MaterialTheme.typography.headlineSmall) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    singleLine = true, colors = formFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("quiz_title_input")
                  )
                  androidx.compose.material3.TextField(
                    value = instructions, onValueChange = { instructions = it },
                    placeholder = { Text(t("Quiz description or instructions", "Maelezo au maelekezo ya jaribio")) },
                    colors = formFieldColors(), modifier = Modifier.fillMaxWidth()
                  )
                  Spacer(Modifier.height(10.dp))
                  PickerField(
                    label = t("Course", "Kozi"),
                    value = courses.firstOrNull { it.first == course }?.let { "${it.first}  ${it.second}" },
                    placeholder = t("Choose a course", "Chagua kozi"),
                    onClick = { pickingCourse = true }, tag = "quiz_course_field"
                  )
                }
              }
            }

            drafts.forEachIndexed { i, d ->
              QuestionEditorCard(
                number = i + 1, draft = d, focused = focused == i,
                canRemove = drafts.size > 1, canUp = i > 0, canDown = i < drafts.lastIndex,
                onFocus = { focused = i },
                onRemove = { drafts.removeAt(i); focused = (i - 1).coerceAtLeast(0) },
                onDuplicate = { drafts.add(i + 1, d.duplicate()); focused = i + 1 },
                onMoveUp = { drafts.add(i - 1, drafts.removeAt(i)); focused = i - 1 },
                onMoveDown = { drafts.add(i + 1, drafts.removeAt(i)); focused = i + 1 },
                t = t
              )
            }
            Spacer(Modifier.height(12.dp))
          }

          // Add-question bar, always in thumb reach
          Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
            Row(
              Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              ActionButton(
                label = t("Choice question", "Swali la chaguo"), icon = Icons.Default.RadioButtonChecked,
                onClick = { drafts.add(DraftQuestion(QuestionType.MULTIPLE_CHOICE)); focused = drafts.lastIndex },
                filled = true, modifier = Modifier.weight(1f), tag = "add_choice_question_btn"
              )
              ActionButton(
                label = t("Written answer", "Jibu la kuandika"), icon = Icons.Default.ShortText,
                onClick = { drafts.add(DraftQuestion(QuestionType.SHORT_ANSWER)); focused = drafts.lastIndex },
                modifier = Modifier.weight(1f), tag = "add_written_question_btn"
              )
            }
          }
        } else {
          Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            ) {
              Column(Modifier.padding(16.dp)) {
                Text(t("Time allowed", "Muda unaoruhusiwa"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                  t("The clock starts when a student presses Start and cannot be paused.", "Saa inaanza mwanafunzi anapobonyeza Anza na haisimami."),
                  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                  value = duration, onValueChange = { duration = it.filter(Char::isDigit).take(3) },
                  label = { Text(t("Minutes", "Dakika")) },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                  shape = RoundedCornerShape(14.dp),
                  modifier = Modifier.fillMaxWidth().testTag("quiz_duration_input")
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                  listOf("10", "20", "30", "45", "60", "90").forEach { m ->
                    FilterChip(selected = duration == m, onClick = { duration = m }, label = { Text("$m min", style = MaterialTheme.typography.labelLarge) }, modifier = Modifier.height(40.dp))
                  }
                }
              }
            }
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            ) {
              Column(Modifier.padding(16.dp)) {
                Text(t("Availability window", "Muda wa kupatikana"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                  t("Students can start any time between these two moments (East Africa Time).", "Wanafunzi wanaweza kuanza wakati wowote kati ya nyakati hizi (EAT)."),
                  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                DueDateTimeField(valueText = opens, onValueChange = { opens = it }, label = t("Opens (EAT)", "Inafunguliwa (EAT)"), tag = "quiz_opens_input")
                Spacer(Modifier.height(10.dp))
                DueDateTimeField(valueText = closes, onValueChange = { closes = it }, label = t("Closes (EAT)", "Inafungwa (EAT)"), tag = "quiz_closes_input")
              }
            }
            Spacer(Modifier.height(16.dp))
          }
        }
      }

      if (pickingCourse) {
        PickerSheet(
          title = t("Course", "Kozi"), options = courses.map { PickerItem(it.first, "${it.first}  ${it.second}") },
          selectedId = course, onSelect = { course = it }, onDismiss = { pickingCourse = false }
        )
      }
      if (confirmDiscard) {
        ConfirmSheet(
          title = t("Discard this quiz?", "Tupa jaribio hili?"),
          message = t("Your questions have not been published. They will be lost.", "Maswali yako hayajachapishwa. Yatapotea."),
          confirmLabel = t("Discard", "Tupa"), dismissLabel = t("Keep editing", "Endelea kuhariri"),
          destructive = true,
          onDismiss = { confirmDiscard = false },
          onConfirm = { confirmDiscard = false; onDismiss() }
        )
      }
    }
  }
}

/** One question while setting the quiz: collapsed it is a preview, tapped it opens like a Google Forms card. */
@Composable
private fun QuestionEditorCard(
  number: Int, draft: DraftQuestion, focused: Boolean,
  canRemove: Boolean, canUp: Boolean, canDown: Boolean,
  onFocus: () -> Unit, onRemove: () -> Unit, onDuplicate: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit,
  t: (String, String) -> String
) {
  var typeMenu by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(16.dp)
  Card(
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = if (focused) 4.dp else 1.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(if (focused) 1.5.dp else 1.dp, if (focused) DeepBlue.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
      .clip(shape)
      .clickable(enabled = !focused) { onFocus() }
      .testTag("question_editor_$number")
  ) {
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
      Box(Modifier.width(6.dp).fillMaxHeight().background(if (focused) DeepBlue else Color.Transparent))
      if (!focused) {
        Column(Modifier.weight(1f).padding(16.dp)) {
          Row(verticalAlignment = Alignment.Top) {
            Text(
              "$number. " + draft.text.ifBlank { t("Untitled question", "Swali lisilo na jina") },
              style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
              color = if (draft.text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f)
            )
            Text(
              t("${draft.points.ifBlank { "0" }} pts", "alama ${draft.points.ifBlank { "0" }}"),
              style = MaterialTheme.typography.labelLarge, color = DeepBlue, fontWeight = FontWeight.Bold
            )
          }
          Spacer(Modifier.height(8.dp))
          if (draft.type == QuestionType.MULTIPLE_CHOICE) {
            draft.options.forEachIndexed { i, o ->
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Icon(
                  if (draft.correct == i) Icons.Default.CheckCircle else Icons.Default.RadioButtonChecked,
                  contentDescription = null, modifier = Modifier.size(20.dp),
                  tint = if (draft.correct == i) SuccessGreen else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(10.dp))
                Text(o.ifBlank { t("Option ${i + 1}", "Chaguo ${i + 1}") }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          } else {
            Text(t("Written answer", "Jibu la kuandika"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      } else {
        Column(Modifier.weight(1f).padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextField(
              value = draft.text, onValueChange = { draft.text = it },
              placeholder = { Text(t("Question $number", "Swali $number")) },
              textStyle = MaterialTheme.typography.titleMedium,
              colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                focusedIndicatorColor = DeepBlue
              ),
              modifier = Modifier.weight(1f).testTag("question_text_$number")
            )
          }
          Spacer(Modifier.height(10.dp))
          Box {
            OutlinedButton(
              onClick = { typeMenu = true }, shape = RoundedCornerShape(14.dp),
              modifier = Modifier.fillMaxWidth().height(52.dp).testTag("question_type_$number")
            ) {
              Icon(if (draft.type == QuestionType.MULTIPLE_CHOICE) Icons.Default.RadioButtonChecked else Icons.Default.ShortText, contentDescription = null, modifier = Modifier.size(22.dp))
              Spacer(Modifier.width(10.dp))
              Text(
                if (draft.type == QuestionType.MULTIPLE_CHOICE) t("Multiple choice", "Chaguo nyingi") else t("Written answer", "Jibu la kuandika"),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
              )
              Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            androidx.compose.material3.DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
              androidx.compose.material3.DropdownMenuItem(
                text = { Text(t("Multiple choice", "Chaguo nyingi")) },
                leadingIcon = { Icon(Icons.Default.RadioButtonChecked, contentDescription = null) },
                onClick = { draft.type = QuestionType.MULTIPLE_CHOICE; typeMenu = false }
              )
              androidx.compose.material3.DropdownMenuItem(
                text = { Text(t("Written answer", "Jibu la kuandika")) },
                leadingIcon = { Icon(Icons.Default.ShortText, contentDescription = null) },
                onClick = { draft.type = QuestionType.SHORT_ANSWER; typeMenu = false }
              )
            }
          }
          Spacer(Modifier.height(8.dp))

          if (draft.type == QuestionType.MULTIPLE_CHOICE) {
            Text(
              t("Tap a circle to mark the right answer.", "Gusa duara kuweka jibu sahihi."),
              style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            draft.options.forEachIndexed { i, option ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { draft.correct = i }, modifier = Modifier.size(52.dp).testTag("question_${number}_correct_$i")) {
                  Icon(
                    if (draft.correct == i) Icons.Default.CheckCircle else Icons.Default.RadioButtonChecked,
                    contentDescription = t("Mark as correct", "Weka kama sahihi"),
                    tint = if (draft.correct == i) SuccessGreen else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(26.dp)
                  )
                }
                androidx.compose.material3.TextField(
                  value = option, onValueChange = { draft.options[i] = it }, singleLine = true,
                  placeholder = { Text(t("Option ${i + 1}", "Chaguo ${i + 1}")) },
                  colors = formFieldColors(),
                  modifier = Modifier.weight(1f).testTag("question_${number}_option_$i")
                )
                if (draft.options.size > 2) {
                  IconButton(onClick = {
                    draft.options.removeAt(i)
                    draft.correct = if (draft.correct == i) 0 else if (draft.correct > i) draft.correct - 1 else draft.correct
                  }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Close, contentDescription = t("Remove option", "Ondoa chaguo")) }
                }
              }
            }
            if (draft.options.size < 6) {
              ActionButton(
                label = t("Add option", "Ongeza chaguo"), icon = Icons.Default.Add, onClick = { draft.options.add("") },
                modifier = Modifier.padding(top = 8.dp).testTag("question_${number}_add_option")
              )
            }
          } else {
            OutlinedTextField(
              value = draft.model, onValueChange = { draft.model = it },
              label = { Text(t("What a good answer contains (only you see this)", "Jibu zuri lina nini (wewe tu unaona)")) },
              shape = RoundedCornerShape(14.dp), minLines = 2,
              modifier = Modifier.fillMaxWidth()
            )
          }

          androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
          Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
              value = draft.points, onValueChange = { draft.points = it.filter(Char::isDigit).take(3) },
              label = { Text(t("Points", "Alama")) }, singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              shape = RoundedCornerShape(14.dp), modifier = Modifier.width(112.dp)
            )
            Spacer(Modifier.weight(1f))
            if (canUp) SoftIconButton(Icons.Default.KeyboardArrowUp, t("Move up", "Hamisha juu"), onMoveUp)
            if (canUp && canDown) Spacer(Modifier.width(6.dp))
            if (canDown) SoftIconButton(Icons.Default.KeyboardArrowDown, t("Move down", "Hamisha chini"), onMoveDown)
          }
          Spacer(Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(label = t("Duplicate", "Nakili"), icon = Icons.Default.ContentCopy, onClick = onDuplicate, modifier = Modifier.weight(1f), tag = "question_${number}_duplicate")
            if (canRemove) ActionButton(label = t("Delete", "Futa"), icon = Icons.Default.Delete, onClick = onRemove, color = DangerRed, modifier = Modifier.weight(1f), tag = "question_${number}_delete")
          }
        }
      }
    }
  }
}

@Composable
private fun RescheduleQuizSheet(viewModel: CampusViewModel, quiz: Quiz, onDismiss: () -> Unit) {
  val t = { en: String, sw: String -> viewModel.t(en, sw) }
  var duration by remember(quiz.id) { mutableStateOf(quiz.durationMinutes.toString()) }
  var opens by remember(quiz.id) { mutableStateOf(DeadlineTime.format(quiz.opensAt)) }
  var closes by remember(quiz.id) { mutableStateOf(DeadlineTime.format(quiz.closesAt)) }
  var error by remember { mutableStateOf<String?>(null) }
  AppBottomSheet(
    onDismissRequest = onDismiss, title = t("Reschedule quiz", "Panga upya jaribio"), subtitle = "${quiz.title} \u2022 ${quiz.courseCode}", tag = "reschedule_quiz_sheet",
    footer = {
      SheetButtons(
        dismissLabel = t("Cancel", "Ghairi"), onDismiss = onDismiss,
        confirmLabel = t("Save & notify students", "Hifadhi na uwaarifu wanafunzi"),
        onConfirm = {
          val e = viewModel.rescheduleQuiz(quiz.id, duration.toIntOrNull() ?: 0, DeadlineTime.parse(opens) ?: 0L, DeadlineTime.parse(closes) ?: 0L)
          if (e == null) onDismiss() else error = e
        },
        confirmTag = "save_quiz_schedule_btn"
      )
    }
  ) {
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
      value = duration, onValueChange = { duration = it.filter(Char::isDigit).take(3) },
      label = { Text(t("Time allowed once started (minutes)", "Muda baada ya kuanza (dakika)")) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
      shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    DueDateTimeField(valueText = opens, onValueChange = { opens = it }, label = t("Opens (EAT)", "Inafunguliwa (EAT)"), tag = "reschedule_opens_input")
    Spacer(Modifier.height(10.dp))
    DueDateTimeField(valueText = closes, onValueChange = { closes = it }, label = t("Closes (EAT)", "Inafungwa (EAT)"), tag = "reschedule_closes_input")
    Text(
      t("Students who already started keep the deadline they were given.", "Wanafunzi walioanza wanabaki na muda waliopewa."),
      style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
    )
    error?.let { Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    Spacer(Modifier.height(8.dp))
  }
}

// =====================================================================================
// Google Forms style building blocks for the student's form
// =====================================================================================

/** The form's title card: a coloured top edge, the title, what it is about, and the key facts. */
@Composable
private fun FormHeaderCard(quiz: Quiz, t: (String, String) -> String) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
  ) {
    Column {
      Box(Modifier.fillMaxWidth().height(12.dp).background(DeepBlue))
      Column(Modifier.padding(20.dp)) {
        Text(quiz.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
          "${quiz.courseCode} \u2022 ${quiz.courseTitle}",
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          FormFactChip(t("${quiz.questions.size} questions", "Maswali ${quiz.questions.size}"))
          FormFactChip(t("${quiz.totalPoints} points", "Alama ${quiz.totalPoints}"))
          FormFactChip(t("${quiz.durationMinutes} min", "Dakika ${quiz.durationMinutes}"))
        }
        if (quiz.instructions.isNotBlank()) {
          Spacer(Modifier.height(12.dp))
          Text(quiz.instructions, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Spacer(Modifier.height(10.dp))
        Text(
          t("Answers are saved as you go. Unanswered questions score zero.", "Majibu huhifadhiwa unapoendelea. Maswali yasiyojibiwa hupata sifuri."),
          style = MaterialTheme.typography.labelMedium, color = DangerRed
        )
      }
    }
  }
}

@Composable
private fun FormFactChip(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.SemiBold,
    color = DeepBlue,
    modifier = Modifier.clip(RoundedCornerShape(50)).background(DeepBlue.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 6.dp)
  )
}

/** One question as its own card; a blue bar on the left shows the one you are working on. */
@Composable
private fun FormQuestionCard(
  number: Int,
  question: QuizQuestion,
  answer: String,
  focused: Boolean,
  onFocus: () -> Unit,
  onAnswer: (String) -> Unit,
  onPersist: (String) -> Unit,
  t: (String, String) -> String
) {
  val shape = RoundedCornerShape(16.dp)
  Card(
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = if (focused) 4.dp else 1.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(if (focused) 1.5.dp else 1.dp, if (focused) DeepBlue.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
      .testTag("quiz_question_card_$number")
  ) {
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
      Box(Modifier.width(6.dp).fillMaxHeight().background(if (focused) DeepBlue else Color.Transparent))
      Column(Modifier.weight(1f).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
          Text(
            "$number. ${question.text}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
          )
          Spacer(Modifier.width(8.dp))
          Text(
            t("${question.points} ${if (question.points == 1) "pt" else "pts"}", "alama ${question.points}"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(Modifier.height(12.dp))
        if (question.type == QuestionType.MULTIPLE_CHOICE) {
          question.options.forEachIndexed { i, option ->
            val chosen = answer == i.toString()
            Row(
              Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (chosen) DeepBlue.copy(alpha = 0.10f) else Color.Transparent)
                .border(1.dp, if (chosen) DeepBlue.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                .clickable { onFocus(); onAnswer(i.toString()) }
                .defaultMinSizeCompat()
                .testTag("quiz_option_${number - 1}_$i"),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(selected = chosen, onClick = null, modifier = Modifier.padding(14.dp))
              Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp, top = 6.dp, bottom = 6.dp))
            }
          }
          if (answer.isNotBlank()) {
            TextButton(onClick = { onFocus(); onAnswer("") }, modifier = Modifier.height(48.dp).testTag("quiz_clear_choice_$number")) {
              Text(t("Clear selection", "Futa chaguo"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
          }
        } else {
          androidx.compose.material3.TextField(
            value = answer,
            onValueChange = { onFocus(); onAnswer(it) },
            placeholder = { Text(t("Your answer", "Jibu lako")) },
            minLines = 4,
            colors = formFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("quiz_written_answer")
          )
          // Written answers are saved a moment after typing stops.
          LaunchedEffect(question.id, answer) {
            delay(500)
            onPersist(answer)
          }
        }
      }
    }
  }
}

private fun Modifier.defaultMinSizeCompat(): Modifier = this.defaultMinSize(minHeight = 56.dp)
