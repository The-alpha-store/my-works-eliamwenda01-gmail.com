package com.willykez.campconnect.data.local

import android.content.Context
import com.willykez.campconnect.BuildConfig
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(entities = [ProcessedDocumentEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
  abstract fun processedDocumentDao(): ProcessedDocumentDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
      INSTANCE ?: Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "campusconnect_local.db"
      ).addCallback(object : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
          super.onCreate(db)
          // Sample summaries exist only for demo builds. A real library starts empty.
          if (BuildConfig.DEMO_MODE_ENABLED.toBoolean()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
              getInstance(context).processedDocumentDao().insertDocuments(initialAcademicSummaries())
            }
          }
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
          super.onOpen(db)
          // Phones that installed an earlier build still hold the sample entries: remove them.
          if (!BuildConfig.DEMO_MODE_ENABLED.toBoolean()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
              getInstance(context).processedDocumentDao().deleteSeedDocuments()
            }
          }
        }
      }).build().also { INSTANCE = it }
    }

    fun initialAcademicSummaries(): List<ProcessedDocumentEntity> = listOf(
      ProcessedDocumentEntity(
        id = "seed-algorithms", title = "Introduction to Algorithms", fileName = "algorithms-summary.pdf",
        fileSize = 184320L, mimeType = "application/pdf", courseCode = "CS210", courseTitle = "Data Structures and Algorithms",
        university = "Sample University", uploaderName = "ChuoPulse Academic Library", timestamp = 1_760_000_000_000L,
        extractedSummary = "Algorithms are finite, well-defined sequences of steps used to solve computational problems. This summary introduces correctness, time and space complexity, asymptotic notation, and common design strategies.",
        keyTopics = "Asymptotic Analysis, Big-O, Recursion, Memoization", learningObjectives = "Explain algorithm correctness\nCompare time and space complexity\nUse Big-O notation", studyTips = "Trace examples by hand\nCompare best, average and worst cases\nPractise recurrence relations", wordCount = 820, pageCount = 5, readingTimeMinutes = 4, extractionStatus = "COMPLETED", category = "Notes", tags = "algorithms,complexity"
      ),
      ProcessedDocumentEntity(
        id = "seed-normalization", title = "Database Normalization", fileName = "normalization-notes.pdf",
        fileSize = 245760L, mimeType = "application/pdf", courseCode = "IS201", courseTitle = "Database Systems",
        university = "Sample University", uploaderName = "ChuoPulse Academic Library", timestamp = 1_760_100_000_000L,
        extractedSummary = "Database normalization structures relational tables to reduce redundancy and update anomalies. The notes explain functional dependencies and the practical differences between first, second and third normal forms.",
        keyTopics = "Functional Dependencies, 1NF, 2NF, 3NF", learningObjectives = "Identify functional dependencies\nNormalize a relation to 3NF\nRecognize insertion and deletion anomalies", studyTips = "Practise decomposing sample tables\nCheck lossless joins\nDistinguish partial and transitive dependencies", wordCount = 1040, pageCount = 7, readingTimeMinutes = 5, extractionStatus = "COMPLETED", category = "Notes", tags = "database,normalization"
      ),
      ProcessedDocumentEntity(
        id = "seed-tcp", title = "TCP Congestion Control", fileName = "tcp-congestion.pdf",
        fileSize = 327680L, mimeType = "application/pdf", courseCode = "NW302", courseTitle = "Computer Networks",
        university = "Sample University", uploaderName = "ChuoPulse Academic Library", timestamp = 1_760_200_000_000L,
        extractedSummary = "TCP congestion control adjusts the sender's transmission rate to reduce network congestion and packet loss. This overview covers slow start, congestion avoidance, fast retransmit, and fast recovery.",
        keyTopics = "Slow Start, Congestion Window, Fast Retransmit, AIMD", learningObjectives = "Describe the congestion window\nCompare slow start and congestion avoidance\nExplain fast retransmit and recovery", studyTips = "Sketch cwnd over time\nReview packet-loss scenarios\nCompare Tahoe and Reno behaviour", wordCount = 930, pageCount = 6, readingTimeMinutes = 5, extractionStatus = "COMPLETED", category = "Exam Revision", tags = "networking,tcp,revision"
      )
    )
  }
}
