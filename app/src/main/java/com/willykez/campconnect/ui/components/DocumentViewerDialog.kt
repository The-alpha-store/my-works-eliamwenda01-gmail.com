package com.willykez.campconnect.ui.components

import android.content.Context
import java.io.IOException
import java.io.File
import com.willykez.campconnect.data.docs.ExtractionException
import com.willykez.campconnect.data.docs.ExtractedDocument
import com.willykez.campconnect.data.docs.DocumentTextExtractor
import com.willykez.campconnect.data.docs.DocumentKind
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import kotlinx.coroutines.launch
import com.willykez.campconnect.data.model.AttachmentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val ViewerBackground = Color(0xFF111318)
private val ViewerBar = Color(0xFF1B1E26)

/** Whether an analysis of the open file exists, is running, or failed. */
enum class AnalysisState { NONE, RUNNING, DONE, FAILED }

/** Connects the viewer's "Analyse with AI" button. Pass null to hide the button. */
data class ViewerAnalysis(
  val state: AnalysisState,
  val onAnalyse: () -> Unit,
  val onViewResult: (() -> Unit)? = null
)

/**
 * Full-screen viewer for every kind of document the app accepts.
 *  - PDFs are drawn page by page; photos are shown with zoom.
 *  - Word, PowerPoint, Excel, OpenDocument and text files are shown as readable pages
 *    (slides as cards), and the text can be selected and copied.
 *  - A floating "Analyse with AI" button sits at the bottom for any file that can be analysed.
 *  - Pinch to zoom, drag when zoomed, double-tap to zoom in and out. Back or ✕ closes.
 */
@Composable
fun DocumentViewerDialog(
  attachment: AttachmentFile,
  authorOrUploaderName: String? = null,
  courseCode: String? = null,
  onDismiss: () -> Unit,
  analysis: ViewerAnalysis? = null
) {
  val context = LocalContext.current
  val kind = remember(attachment) {
    if (attachment.isImage) DocumentKind.IMAGE else DocumentKind.of(attachment.fileName, attachment.mimeType)
  }
  val subtitle = listOfNotNull(
    kind.label,
    attachment.fileSize.takeIf { it.isNotBlank() },
    courseCode,
    authorOrUploaderName
  ).joinToString("  \u2022  ")

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
  ) {
    Surface(
      color = ViewerBackground,
      modifier = Modifier.fillMaxSize().testTag("built_in_document_viewer_modal")
    ) {
      var pageLabel by remember { mutableStateOf<String?>(null) }

      Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
          // Top bar: close, what the file is, where you are in it, open elsewhere
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(ViewerBar)
              .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_document_viewer_btn")) {
              Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            FileKindBadge(kind = kind, size = 36.dp)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
              Text(
                text = attachment.fileName,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = subtitle,
                color = Color(0xFFB6BDCB),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            pageLabel?.let { label ->
              Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                  .clip(RoundedCornerShape(50))
                  .background(Color(0xFF2B3040))
                  .padding(horizontal = 10.dp, vertical = 4.dp)
                  .testTag("viewer_page_label")
              )
            }
            if (attachment.uriString != null) {
              IconButton(onClick = { openInAnotherApp(context, attachment) }) {
                Icon(Icons.Default.OpenInNew, contentDescription = "Open in another app", tint = Color.White)
              }
            }
          }

          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val uri = attachment.uriString
            when {
              uri == null && !attachment.previewText.isNullOrBlank() -> TextPages(
                sections = listOf(attachment.previewText),
                kind = kind,
                sampleNotice = true,
                onPageLabel = { pageLabel = it }
              )
              uri == null -> ViewerMessage(
                title = "Nothing to open",
                body = "This entry has no file attached."
              )
              kind == DocumentKind.IMAGE -> ImagePage(uri = uri, name = attachment.fileName)
              isPdf(attachment) -> PdfPages(uri = uri, onPageLabel = { pageLabel = it })
              kind == DocumentKind.WORD || kind == DocumentKind.SLIDES ||
                kind == DocumentKind.SHEET || kind == DocumentKind.TEXT -> ExtractedPages(
                attachment = attachment,
                kind = kind,
                onPageLabel = { pageLabel = it },
                onOpen = { openInAnotherApp(context, attachment) }
              )
              else -> OtherFile(attachment = attachment, onOpen = { openInAnotherApp(context, attachment) })
            }
          }
        }

        if (analysis != null && kind != DocumentKind.OTHER && attachment.uriString != null) {
          AnalysisPill(
            analysis = analysis,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding()
              .padding(bottom = 20.dp)
          )
        }
      }
    }
  }
}

// ---------- Pictures ----------

@Composable
private fun ImagePage(uri: String, name: String) {
  ZoomBox(modifier = Modifier.fillMaxSize()) {
    SubcomposeAsyncImage(
      model = uri,
      contentDescription = name,
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize(),
      loading = { LoadingIndicator() },
      error = { ViewerMessage("Could not open this picture", "The file may have been moved or deleted.") }
    )
  }
}

// ---------- PDF ----------

private class PdfDoc(private val renderer: PdfRenderer, private val descriptor: ParcelFileDescriptor) {
  // PdfRenderer allows one open page at a time, so renders take turns.
  private val lock = Mutex()
  val pageCount: Int = renderer.pageCount

  suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
    withContext(Dispatchers.IO) {
      val page = renderer.openPage(index)
      try {
        val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
      } finally {
        page.close()
      }
    }
  }

  fun close() {
    runCatching { renderer.close() }
    runCatching { descriptor.close() }
  }
}

private sealed interface PdfLoad {
  object Loading : PdfLoad
  class Ready(val doc: PdfDoc) : PdfLoad
  object Failed : PdfLoad
}

/**
 * Opens a PDF from a phone file (content://, file://) or from the cloud (http/https). Cloud files are
 * saved once into the app's cache first, because PdfRenderer can only read a real file. Timeouts make a
 * slow or missing file fail with a clear message instead of loading forever.
 */
private fun openPdfDescriptor(context: android.content.Context, uri: String): ParcelFileDescriptor? {
  if (uri.startsWith("http://") || uri.startsWith("https://")) {
    val dir = java.io.File(context.cacheDir, "viewer").apply { mkdirs() }
    val file = java.io.File(dir, "pdf_${uri.hashCode()}.pdf")
    if (!file.exists() || file.length() == 0L) {
      val tmp = java.io.File(dir, file.name + ".part")
      val conn = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
      try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
        if (!tmp.renameTo(file)) return null
      } finally {
        conn.disconnect()
        if (tmp.exists()) tmp.delete()
      }
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }
  return context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
}

@Composable
private fun PdfPages(uri: String, onPageLabel: (String?) -> Unit) {
  val context = LocalContext.current

  val load by produceState<PdfLoad>(PdfLoad.Loading, uri) {
    value = withTimeoutOrNull(20_000L) {
      withContext(Dispatchers.IO) {
      try {
        val fd = openPdfDescriptor(context, uri)
          ?: return@withContext PdfLoad.Failed
        try {
          PdfLoad.Ready(PdfDoc(PdfRenderer(fd), fd))
        } catch (e: Exception) {
          fd.close() // damaged, empty or password-protected
          PdfLoad.Failed
        }
      } catch (e: Exception) {
        PdfLoad.Failed
      }
      }
    } ?: PdfLoad.Failed
  }

  // Capture the document now. Reading `load` inside onDispose would see the NEW value (Ready) when
  // the state changes from Loading, and close the file the instant it finished opening.
  val opened = (load as? PdfLoad.Ready)?.doc
  DisposableEffect(opened) {
    onDispose { opened?.close() }
  }

  when (val state = load) {
    is PdfLoad.Loading -> LoadingIndicator()
    is PdfLoad.Failed -> ViewerMessage(
      "Could not open this PDF",
      "It may be damaged, protected by a password, or no longer on this phone."
    )
    is PdfLoad.Ready -> {
      val doc = state.doc
      val listState = rememberLazyListState()
      val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
      androidx.compose.runtime.LaunchedEffect(current, doc) { onPageLabel("Page $current of ${doc.pageCount}") }

      val zoom = remember(doc) { ZoomState() }
      val scope = androidx.compose.runtime.rememberCoroutineScope()

      Column(modifier = Modifier.fillMaxSize()) {
      // Reading toolbar: previous / next page, where you are, and zoom.
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF232733))
          .padding(horizontal = 6.dp, vertical = 2.dp)
          .testTag("pdf_toolbar"),
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = { scope.launch { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) } },
          enabled = current > 1,
          modifier = Modifier.testTag("pdf_prev_page_btn")
        ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page", tint = if (current > 1) Color.White else Color(0xFF5B6272)) }
        Text(
          "$current / ${doc.pageCount}",
          color = Color.White,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(
          onClick = { scope.launch { listState.animateScrollToItem(current.coerceAtMost(doc.pageCount - 1)) } },
          enabled = current < doc.pageCount,
          modifier = Modifier.testTag("pdf_next_page_btn")
        ) { Icon(Icons.Default.ChevronRight, contentDescription = "Next page", tint = if (current < doc.pageCount) Color.White else Color(0xFF5B6272)) }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = { zoom.zoomBy(-0.5f) }, modifier = Modifier.testTag("pdf_zoom_out_btn")) {
          Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = Color.White)
        }
        Text(
          "${(zoom.scaleState.floatValue * 100).toInt()}%",
          color = Color(0xFFB6BDCB),
          style = MaterialTheme.typography.labelMedium
        )
        IconButton(onClick = { zoom.zoomBy(0.5f) }, modifier = Modifier.testTag("pdf_zoom_in_btn")) {
          Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = Color.White)
        }
      }

      ZoomBox(modifier = Modifier.weight(1f).fillMaxWidth(), state = zoom) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
          val density = LocalDensity.current
          // Draw at about 1.6x the screen width so zooming stays sharp.
          val widthPx = with(density) { (maxWidth * 1.6f).roundToPx() }.coerceIn(480, 2400)

          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
          ) {
            itemsIndexed(List(doc.pageCount) { it }, key = { _, page -> page }) { _, index ->
              PdfPageImage(doc = doc, index = index, widthPx = widthPx)
            }
          }
        }
      }
      }
    }
  }
}

@Composable
private fun PdfPageImage(doc: PdfDoc, index: Int, widthPx: Int) {
  var failed by remember(doc, index) { mutableStateOf(false) }
  val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) {
    value = try {
      doc.render(index, widthPx)
    } catch (e: Exception) {
      failed = true
      null
    }
  }
  val image = bitmap
  if (failed) {
    Box(
      modifier = Modifier.fillMaxWidth().aspectRatio(0.707f).background(ViewerBar),
      contentAlignment = Alignment.Center
    ) { Text("Could not draw page ${index + 1}", color = Color(0xFFB6BDCB), style = MaterialTheme.typography.bodySmall) }
  } else if (image != null) {
    Image(
      bitmap = image.asImageBitmap(),
      contentDescription = "Page ${index + 1}",
      contentScale = ContentScale.FillWidth,
      modifier = Modifier.fillMaxWidth()
    )
  } else {
    // Keeps the scroll position steady while the page is drawn.
    Box(
      modifier = Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color(0xFF1B1E26)),
      contentAlignment = Alignment.Center
    ) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp), color = Color(0xFF8FA8FF)) }
  }
}

// ---------- Zoom and pan ----------

/** Zoom level and pan position, kept outside [ZoomBox] so toolbar buttons can change them too. */
private class ZoomState {
  val scaleState = mutableFloatStateOf(1f)
  val offsetState = mutableStateOf(Offset.Zero)

  fun zoomBy(step: Float) {
    val next = (scaleState.floatValue + step).coerceIn(1f, 5f)
    scaleState.floatValue = next
    if (next <= 1f) offsetState.value = Offset.Zero
  }
}

/**
 * Pinch to zoom (1x to 5x), drag to pan while zoomed, double-tap to jump in and out.
 * With one finger at 1x the gesture is left alone, so lists inside still scroll normally.
 */
@Composable
private fun ZoomBox(modifier: Modifier = Modifier, state: ZoomState = remember { ZoomState() }, content: @Composable () -> Unit) {
  var scale by state.scaleState
  var offset by state.offsetState

  Box(
    modifier = modifier
      .clipToBounds()
      .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = {
          if (scale > 1f) {
            scale = 1f
            offset = Offset.Zero
          } else {
            scale = 2.5f
          }
        })
      }
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (event.changes.size > 1 || scale > 1f) {
              val newScale = (scale * zoom).coerceIn(1f, 5f)
              scale = newScale
              offset = if (newScale > 1f) {
                val maxX = size.width * (newScale - 1f) / 2f
                val maxY = size.height * (newScale - 1f) / 2f
                Offset(
                  (offset.x + pan.x).coerceIn(-maxX, maxX),
                  (offset.y + pan.y).coerceIn(-maxY, maxY)
                )
              } else {
                Offset.Zero
              }
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
          } while (event.changes.any { it.pressed })
        }
      }
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
      }
  ) { content() }
}

// ---------- Small pieces ----------

@Composable
private fun LoadingIndicator() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = Color(0xFF8FA8FF))
  }
}

@Composable
private fun ViewerMessage(title: String, body: String) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFB6BDCB), modifier = Modifier.size(40.dp))
    Spacer(modifier = Modifier.height(12.dp))
    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(6.dp))
    Text(body, color = Color(0xFFB6BDCB), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
  }
}

private fun isPdf(a: AttachmentFile): Boolean {
  val ext = a.fileName.substringAfterLast('.', "")
  // The extension wins; the stored type is only trusted when the name has no extension at all.
  return ext.equals("pdf", ignoreCase = true) ||
    (ext.isEmpty() && a.mimeType.equals("application/pdf", ignoreCase = true))
}

/** Word, PowerPoint and other files cannot be drawn inside the app. They open in whichever app can show them. */
@Composable
private fun OtherFile(attachment: AttachmentFile, onOpen: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color(0xFFB6BDCB), modifier = Modifier.size(40.dp))
    Spacer(modifier = Modifier.height(12.dp))
    Text("Open this file in another app", color = Color.White, style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      "${attachment.fileName} can't be shown inside the app. It opens in whichever app can show it.",
      color = Color(0xFFB6BDCB), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(onClick = onOpen) { Text("Open", color = Color.White) }
  }
}

private fun openInAnotherApp(context: Context, attachment: AttachmentFile) {
  val raw = attachment.uriString?.let { Uri.parse(it) } ?: return
  runCatching {
    // Files kept in the app's own library are shared through a FileProvider, never as file://.
    val uri = if (raw.scheme == "file") {
      FileProvider.getUriForFile(context, "${context.packageName}.files", File(raw.path.orEmpty()))
    } else raw
    val mime = attachment.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
      ?: android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(DocumentKind.extensionOf(attachment.fileName))
      ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, mime)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(view, null))
  }
}

// ---------- Word, slides, spreadsheets and text ----------

private sealed interface TextLoad {
  data object Loading : TextLoad
  data class Ready(val document: ExtractedDocument) : TextLoad
  data class Failed(val reason: String) : TextLoad
}

@Composable
private fun ExtractedPages(
  attachment: AttachmentFile,
  kind: DocumentKind,
  onPageLabel: (String?) -> Unit,
  onOpen: () -> Unit
) {
  val context = LocalContext.current
  val state by produceState<TextLoad>(TextLoad.Loading, attachment.uriString) {
    value = withContext(Dispatchers.IO) {
      var temp: File? = null
      try {
        val ext = DocumentKind.extensionOf(attachment.fileName).filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        val file = File.createTempFile("view", ".$ext", context.cacheDir)
        temp = file
        val input = context.contentResolver.openInputStream(Uri.parse(attachment.uriString))
          ?: throw IOException("cannot open")
        input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
        TextLoad.Ready(DocumentTextExtractor.extract(file, attachment.fileName, attachment.mimeType))
      } catch (e: ExtractionException) {
        TextLoad.Failed(e.message ?: "This file could not be shown here.")
      } catch (e: Exception) {
        TextLoad.Failed("This file could not be shown here.")
      } finally {
        temp?.delete()
      }
    }
  }
  when (val s = state) {
    TextLoad.Loading -> LoadingIndicator()
    is TextLoad.Failed -> Column(
      modifier = Modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFB6BDCB), modifier = Modifier.size(40.dp))
      Spacer(modifier = Modifier.height(12.dp))
      Text(s.reason, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
      Spacer(modifier = Modifier.height(16.dp))
      OutlinedButton(onClick = onOpen) { Text("Open in another app", color = Color.White) }
    }
    is TextLoad.Ready -> TextPages(s.document.sections, kind, sampleNotice = false, onPageLabel = onPageLabel)
  }
}

@Composable
private fun TextPages(
  sections: List<String>,
  kind: DocumentKind,
  sampleNotice: Boolean,
  onPageLabel: (String?) -> Unit
) {
  val listState = rememberLazyListState()
  val offset = if (sampleNotice) 1 else 0
  val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
  val noun = if (kind == DocumentKind.SLIDES) "Slide" else "Page"
  LaunchedEffect(first, sections.size) {
    onPageLabel(
      if (sections.size > 1) "$noun ${(first - offset).coerceIn(0, sections.lastIndex) + 1} / ${sections.size}" else null
    )
  }
  LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize().testTag("extracted_pages"),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    if (sampleNotice) {
      item {
        Text(
          "Sample content. No original file is attached to this entry.",
          color = Color(0xFFFBBF24),
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF3A2F12))
            .padding(12.dp)
        )
      }
    }
    itemsIndexed(sections) { index, text ->
      if (kind == DocumentKind.SLIDES) SlideCard(number = index + 1, total = sections.size, text = text)
      else ReadingCard(text = text, label = if (sections.size > 1) "$noun ${index + 1}" else null)
    }
  }
}

@Composable
private fun SlideCard(number: Int, total: Int, text: String) {
  val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
  Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1F2430), modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(18.dp)) {
      Text(
        "SLIDE $number OF $total",
        color = Color(0xFFFB923C),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(10.dp))
      SelectionContainer {
        Column {
          lines.firstOrNull()?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          }
          lines.drop(1).forEach {
            Spacer(modifier = Modifier.height(6.dp))
            Text("\u2022  $it", color = Color(0xFFD7DBE6), style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
  }
}

@Composable
private fun ReadingCard(text: String, label: String?) {
  Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1B1F29), modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
      if (label != null) {
        Text(label.uppercase(), color = Color(0xFF8FA8FF), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
      }
      SelectionContainer {
        Text(
          text,
          color = Color(0xFFE6E9F0),
          style = MaterialTheme.typography.bodyLarge,
          lineHeight = 26.sp
        )
      }
    }
  }
}

// ---------- Analyse button ----------

@Composable
private fun AnalysisPill(analysis: ViewerAnalysis, modifier: Modifier = Modifier) {
  val running = analysis.state == AnalysisState.RUNNING
  val label = when (analysis.state) {
    AnalysisState.NONE -> "Analyse with AI"
    AnalysisState.RUNNING -> "Analysing\u2026"
    AnalysisState.DONE -> "View analysis"
    AnalysisState.FAILED -> "Retry analysis"
  }
  Surface(
    shape = RoundedCornerShape(50),
    color = Color(0xFF3B5BDB),
    shadowElevation = 10.dp,
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .clickable(enabled = !running) {
        if (analysis.state == AnalysisState.DONE) (analysis.onViewResult ?: analysis.onAnalyse)() else analysis.onAnalyse()
      }
      .testTag("viewer_analyse_btn")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (running) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
      } else {
        Icon(
          if (analysis.state == AnalysisState.FAILED) Icons.Default.Refresh else Icons.Default.AutoAwesome,
          contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)
        )
      }
      Spacer(modifier = Modifier.width(10.dp))
      Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
  }
}
