package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionState
import com.example.model.MtlsConfig
import com.example.model.StreamEvent
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.OciRed
import com.example.ui.theme.QueueAmber
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.StreamCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusBadge(
  state: ConnectionState,
  modifier: Modifier = Modifier
) {
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.4f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "dotPulse"
  )

  val (color, text) = when (state) {
    ConnectionState.UNCONFIGURED -> Pair(Color.Gray, "UNCONFIGURED")
    ConnectionState.CONNECTING -> Pair(QueueAmber, "CONNECTING")
    ConnectionState.TLS_SERVER_VERIFIED -> Pair(QueueAmber, "TLS_SERVER_VERIFIED")
    ConnectionState.TLS_AUTHENTICATED -> Pair(StreamCyan, "MTLS_AUTHENTICATED")
    ConnectionState.WAITING_FOR_DEVICE_READINESS -> Pair(QueueAmber, "WAITING_FOR_DEVICE_READINESS")
    ConnectionState.READY -> Pair(SecurityGreen, "READY")
    ConnectionState.DISCONNECTED -> Pair(Color.Gray, "DISCONNECTED")
    ConnectionState.ERROR -> Pair(ErrorRed, "ERROR")
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .clip(RoundedCornerShape(16.dp))
      .background(color.copy(alpha = 0.15f))
      .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
      .padding(horizontal = 10.dp, vertical = 5.dp)
      .testTag("connection_status_badge")
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .clip(CircleShape)
        .background(color)
        .alpha(if (state == ConnectionState.CONNECTING || state == ConnectionState.WAITING_FOR_DEVICE_READINESS) pulseAlpha else 1.0f)
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = text,
      color = color,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace
    )
  }
}

@Composable
fun DemoHonestyBanner(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(Color(0xFF241C10))
      .border(1.dp, QueueAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
      .padding(12.dp)
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Icon(
        imageVector = Icons.Default.Info,
        contentDescription = "Notice",
        tint = QueueAmber,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.width(10.dp))
      Column {
        Text(
          text = "DEMONSTRATION & LOCAL MODE",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = QueueAmber,
          fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Demo telemetry is synthetic and labeled as such. Local CPU benchmarks are genuine on-device measurements. Live cloud jobs use authenticated mTLS RPCs and fail closed when credentials, server readiness, or execution receipts are invalid.",
          fontSize = 11.sp,
          color = Color(0xFFFDE68A),
          lineHeight = 15.sp
        )
      }
    }
  }
}

@Composable
fun MetricMiniCard(
  title: String,
  value: String,
  subtitle: String,
  icon: ImageVector,
  accentColor: Color,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = modifier
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = title,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontFamily = FontFamily.Monospace
        )
        Box(
          modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accentColor,
            modifier = Modifier.size(15.dp)
          )
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = value,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        fontSize = 10.sp,
        color = accentColor,
        fontWeight = FontWeight.Medium
      )
    }
  }
}

@Composable
fun MtlsSecurityBanner(
  config: MtlsConfig,
  connectionState: ConnectionState,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(38.dp)
          .clip(CircleShape)
          .background(SecurityGreen.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Lock,
          contentDescription = "mTLS Configuration",
          tint = SecurityGreen,
          modifier = Modifier.size(20.dp)
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "mTLS Profile Specification",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "TLS 1.3",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = SecurityGreen,
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(SecurityGreen.copy(alpha = 0.12f))
              .padding(horizontal = 4.dp, vertical = 2.dp)
          )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Client Cert: ${config.clientCertSubject.substringBefore(",")}",
          fontSize = 10.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontFamily = FontFamily.Monospace,
          maxLines = 1
        )
        Text(
          text = "Target Host: ${config.endpointHost}:${config.endpointPort} (Unlinked)",
          fontSize = 10.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontFamily = FontFamily.Monospace,
          maxLines = 1
        )
      }
    }
  }
}

@Composable
fun StreamTerminalCard(
  events: List<StreamEvent>,
  modifier: Modifier = Modifier
) {
  val listState = rememberLazyListState()
  val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  LaunchedEffect(events.size) {
    if (events.isNotEmpty()) {
      listState.animateScrollToItem(events.size - 1)
    }
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF070B12)),
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(StreamCyan)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "CONTROLLER EVENT & TELEMETRY STREAM",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = StreamCyan,
            fontFamily = FontFamily.Monospace
          )
        }
        Text(
          text = "${events.size} records",
          fontSize = 10.sp,
          color = Color(0xFF64748B),
          fontFamily = FontFamily.Monospace
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(180.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF04060A))
          .padding(8.dp)
      ) {
        if (events.isEmpty()) {
          Text(
            text = "No events logged. Tap 'Handshake Diagnostic' or run a local benchmark.",
            color = Color(0xFF475569),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
          )
        } else {
          LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(events, key = { it.id }) { event ->
              val timeStr = timeFormat.format(Date(event.timestamp))
              val (tag, tagColor, message) = when (event) {
                is StreamEvent.OutboundCommand ->
                  Triple("[CMD-DEMO]", OciRed, "${event.commandType.name} (${event.payloadSize} B) Priority: ${event.priority.name}")
                is StreamEvent.InboundTelemetry ->
                  Triple(if (event.isDemo) "[DEMO-TEL]" else "[TEL-IN]", StreamCyan, "${event.summary} (${String.format(Locale.US, "%.1f", event.rttMs)}ms)")
                is StreamEvent.Handshake ->
                  Triple("[TLS-SEC] ", if (event.success) SecurityGreen else QueueAmber, event.message)
                is StreamEvent.KeepAlive ->
                  Triple("[DEMO-PING]", Color(0xFF94A3B8), "Synthetic ping #${event.sequenceNumber} - ${String.format(Locale.US, "%.1f", event.rttMs)}ms")
                is StreamEvent.StreamError ->
                  Triple("[ERROR]   ", ErrorRed, "${event.code}: ${event.reason}")
              }

              Row(modifier = Modifier.padding(vertical = 1.5.dp)) {
                Text(
                  text = "$timeStr ",
                  color = Color(0xFF475569),
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace
                )
                Text(
                  text = "$tag ",
                  color = tagColor,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = FontFamily.Monospace
                )
                Text(
                  text = message,
                  color = Color(0xFFE2E8F0),
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
        }
      }
    }
  }
}
