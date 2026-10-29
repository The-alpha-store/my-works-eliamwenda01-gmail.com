package com.willykez.campconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.willykez.campconnect.data.supabase.SupabaseClient
import com.willykez.campconnect.ui.theme.SuccessGreen
import com.willykez.campconnect.ui.theme.WarmAmber

/**
 * Shows whether THIS build has real Supabase settings.
 * Green  = SUPABASE_URL and SUPABASE_ANON_KEY were found at build time (.env, Gradle property or GitHub secret).
 * Amber  = they were not found, so the app can only run on its local in-phone test data.
 */
@Composable
fun BackendBadge(modifier: Modifier = Modifier, connectedText: String = "Backend connected", localText: String = "Local test mode") {
  val live = remember { SupabaseClient().isConfigured }
  val color = if (live) SuccessGreen else WarmAmber
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .background(color.copy(alpha = 0.14f))
      .padding(horizontal = 14.dp, vertical = 8.dp)
      .testTag("backend_badge")
  ) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(8.dp))
    Text(
      if (live) connectedText else localText,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      color = color
    )
  }
}
