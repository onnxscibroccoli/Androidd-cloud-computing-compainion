package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionState
import com.example.model.StreamEvent
import com.example.model.TelemetrySnapshot
import com.example.ui.components.DemoHonestyBanner
import com.example.ui.components.StatusBadge
import com.example.ui.components.StreamTerminalCard
import com.example.ui.theme.OciRed
import com.example.ui.theme.QueueAmber
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.StreamCyan
import java.util.Locale

@Composable
fun TelemetryScreen(
  connectionState: ConnectionState,
  telemetry: TelemetrySnapshot,
  events: List<StreamEvent>,
  isDemoActive: Boolean,
  onToggleDemo: (Boolean) -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
    contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Header
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Telemetry & Observability",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "High-frequency bi-directional metrics pipeline",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        StatusBadge(state = connectionState)
      }
    }

    // Honesty Banner
    item {
      DemoHonestyBanner()
    }

    // Demo Telemetry Stream Generator Controls
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Demo Telemetry Generator",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (isDemoActive) "Generating synthetic metric frames (25 FPS)" else "Demo telemetry generator paused",
              fontSize = 11.sp,
              color = if (isDemoActive) StreamCyan else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          if (isDemoActive) {
            OutlinedButton(
              onClick = { onToggleDemo(false) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.testTag("btn_pause_demo_telemetry")
            ) {
              Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Pause", fontSize = 12.sp)
            }
          } else {
            Button(
              onClick = { onToggleDemo(true) },
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.testTag("btn_start_demo_telemetry")
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Start Demo", fontSize = 12.sp)
            }
          }
        }
      }
    }

    // Latency Model Card
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(StreamCyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Speed,
                  contentDescription = null,
                  tint = StreamCyan,
                  modifier = Modifier.size(20.dp)
                )
              }
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = "Simulated Link Latency (Model)",
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "Modeled FastConnect RTT (No live socket)",
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }

            Text(
              text = String.format(Locale.US, "%.1f ms", telemetry.roundTripLatencyMs),
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
              color = StreamCyan
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          val latencyRatio = (telemetry.roundTripLatencyMs / 60f).coerceIn(0f, 1f)
          LinearProgressIndicator(
            progress = { latencyRatio },
            modifier = Modifier
              .fillMaxWidth()
              .height(8.dp)
              .clip(RoundedCornerShape(4.dp)),
            color = if (telemetry.roundTripLatencyMs < 30f) SecurityGreen else StreamCyan,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
          )

          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("0ms (Optimal)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Model Target: <20ms", fontSize = 10.sp, color = SecurityGreen, fontWeight = FontWeight.SemiBold)
            Text("60ms+", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    // OCI Cloud Instance (Simulated Spec)
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(OciRed.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(imageVector = Icons.Default.Cloud, contentDescription = null, tint = OciRed, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(text = "OCI Compute Node (Simulated Profile)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
              Text(
                text = "Preset hardware template (Unlinked)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          TelemetryDetailRow(
            label = "Instance Profile",
            value = telemetry.profileLabel
          )
          TelemetryDetailRow(
            label = "Target Region",
            value = telemetry.regionLabel
          )
          TelemetryDetailRow(
            label = "Simulated Server Load",
            value = String.format(Locale.US, "%.1f%% (Model Spec: 16 OCPUs)", telemetry.ociCpuPct)
          )
          TelemetryDetailRow(
            label = "Simulated Memory Spec",
            value = "${telemetry.ociMemoryMb} MB (Modeled)"
          )
          TelemetryDetailRow(
            label = "Demo Stream FPS",
            value = if (isDemoActive) "${telemetry.streamFps} FPS synthetic" else "0 FPS (Paused)"
          )
        }
      }
    }

    // Local Android Device (Actual Measurements)
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SecurityGreen.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = SecurityGreen,
                modifier = Modifier.size(18.dp)
              )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(text = "Edge Android Controller (Actual Device)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
              Text(text = "Measured on-device subsystem", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          TelemetryDetailRow(
            label = "Local CPU Utilization",
            value = when {
              telemetry.isSimulated -> String.format(Locale.US, "DEMO %.1f%%", telemetry.localCpuPct)
              telemetry.localCpuMeasured -> String.format(Locale.US, "%.1f%% (Measured)", telemetry.localCpuPct)
              else -> "N/A"
            }
          )
          TelemetryDetailRow(
            label = "Runtime",
            value = "Android ART / JVM Standard Sandbox"
          )
          TelemetryDetailRow(
            label = "Offloading Efficiency",
            value = "Projected 80%+ CPU offload ratio"
          )
        }
      }
    }

    // Packet Flow Counters
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(text = "Synthetic Packet Flow Counters", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          Spacer(modifier = Modifier.height(12.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column {
              Text("Commands Dispatched", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = "${telemetry.packetsDispatched}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = OciRed
              )
            }
            Column {
              Text("Inbound Frames (Demo)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = "${telemetry.packetsIngested}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = StreamCyan
              )
            }
            Column {
              Text("Loss Rate", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = "0.00%",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SecurityGreen
              )
            }
          }
        }
      }
    }

    // Stream Terminal
    item {
      StreamTerminalCard(events = events)
    }
  }
}

@Composable
private fun TelemetryDetailRow(
  label: String,
  value: String,
  isMonospace: Boolean = false
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = value,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurface,
      fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
    )
  }
}
