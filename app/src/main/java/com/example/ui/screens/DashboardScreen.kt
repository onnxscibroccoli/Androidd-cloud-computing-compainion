package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.example.model.CommandType
import com.example.model.ComputeJob
import com.example.model.ConnectionState
import com.example.model.ExecutionTarget
import com.example.model.JobStatus
import com.example.model.MtlsConfig
import com.example.model.StreamEvent
import com.example.model.TelemetrySnapshot
import com.example.ui.components.DemoHonestyBanner
import com.example.ui.components.MetricMiniCard
import com.example.ui.components.MtlsSecurityBanner
import com.example.ui.components.StatusBadge
import com.example.ui.components.StreamTerminalCard
import com.example.ui.theme.OciRed
import com.example.ui.theme.QueueAmber
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.StreamCyan
import java.util.Locale

@Composable
fun DashboardScreen(
  connectionState: ConnectionState,
  telemetry: TelemetrySnapshot,
  mtlsConfig: MtlsConfig,
  events: List<StreamEvent>,
  jobs: List<ComputeJob>,
  onDispatchPreset: (CommandType, ExecutionTarget) -> Unit,
  onNavigateToTasks: () -> Unit,
  onTestHandshake: () -> Unit,
  ociAuthStatus: String,
  onOciSignIn: () -> Unit,
  onRequestShizukuPermission: () -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
    contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Top Header
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "OCI Cloud Controller",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
          )
          Text(
            text = "Edge compute coordinator & client adapter",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        IconButton(
          onClick = onTestHandshake,
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("dashboard_handshake_diagnostic_button")
        ) {
          Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Test Handshake Diagnostic",
            tint = MaterialTheme.colorScheme.primary
          )
        }
      }
    }

    // Explicit Demo Notice
    item {
      DemoHonestyBanner()
    }

    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text("OCI Identity Domain", fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Text(
            text = ociAuthStatus,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
          )
          Button(
            onClick = onOciSignIn,
            modifier = Modifier.fillMaxWidth().testTag("oci_pkce_sign_in")
          ) {
            Text("Sign in with OCI")
          }
        }
      }
    }

    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text("Shizuku elevated execution", fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Text(
            "Commands are rejected until Shizuku is running, permission is explicitly granted, and a verified Rish bundle is installed.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
          )
          Button(
            onClick = onRequestShizukuPermission,
            modifier = Modifier.fillMaxWidth().testTag("request_shizuku_permission")
          ) {
            Text("Request Shizuku permission")
          }
        }
      }
    }

    // Connection & mTLS Specification Banner
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
                  .background(OciRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Cloud,
                  contentDescription = "OCI",
                  tint = OciRed,
                  modifier = Modifier.size(20.dp)
                )
              }
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = "OCI Transport Gate",
                  fontWeight = FontWeight.SemiBold,
                  fontSize = 13.sp
                )
                Text(
                  text = "Unlinked from external canonical daemon",
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }

            StatusBadge(state = connectionState)
          }

          Spacer(modifier = Modifier.height(12.dp))
          MtlsSecurityBanner(config = mtlsConfig, connectionState = connectionState)
        }
      }
    }

    // Metrics 2x2 Grid with Honest Labels
    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          MetricMiniCard(
            title = "LOCAL CPU BENCHMARK",
            value = if (telemetry.localCpuPct > 0f) String.format(Locale.US, "%.1f%%", telemetry.localCpuPct) else "Idle",
            subtitle = "Actual On-Device Measure",
            icon = Icons.Default.Memory,
            accentColor = SecurityGreen,
            modifier = Modifier.weight(1f)
          )
          MetricMiniCard(
            title = "SIMULATED RTT LATENCY",
            value = String.format(Locale.US, "%.1f ms", telemetry.roundTripLatencyMs),
            subtitle = "Synthetic FastConnect Model",
            icon = Icons.Default.Speed,
            accentColor = StreamCyan,
            modifier = Modifier.weight(1f)
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          MetricMiniCard(
            title = "SIMULATED CLOUD SPEC",
            value = if (telemetry.ociCpuPct > 0f) String.format(Locale.US, "%.1f%%", telemetry.ociCpuPct) else "Standby",
            subtitle = "Demo Spec (16 OCPUs)",
            icon = Icons.Default.Bolt,
            accentColor = OciRed,
            modifier = Modifier.weight(1f)
          )
          MetricMiniCard(
            title = "DEMO PACKET LOG",
            value = "${telemetry.packetsDispatched} / ${telemetry.packetsIngested}",
            subtitle = "Local Ingestion Ring",
            icon = Icons.Default.SwapVert,
            accentColor = Color.Gray,
            modifier = Modifier.weight(1f)
          )
        }
      }
    }

    // Computational Triggers
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = "Benchmark & Demonstration Triggers",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Run actual local CPU benchmarks or explore modeled cloud offloads",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Spacer(modifier = Modifier.height(14.dp))

          LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
              PresetTriggerButton(
                title = "Matrix GEMM",
                subtitle = "192x192 FP32",
                target = "Local Device (Actual)",
                icon = Icons.Default.Storage,
                color = SecurityGreen,
                onClick = { onDispatchPreset(CommandType.MATRIX_MULTIPLY, ExecutionTarget.LOCAL_DEVICE) }
              )
            }
            item {
              PresetTriggerButton(
                title = "SHA-256 Chain",
                subtitle = "30k Hashes",
                target = "Local Device (Actual)",
                icon = Icons.Default.Bolt,
                color = SecurityGreen,
                onClick = { onDispatchPreset(CommandType.CRYPTO_HASH_CHAIN, ExecutionTarget.LOCAL_DEVICE) }
              )
            }
            item {
              PresetTriggerButton(
                title = "Monte Carlo Pi",
                subtitle = "300k samples",
                target = "Demo Cloud Simulation",
                icon = Icons.Default.Speed,
                color = OciRed,
                onClick = { onDispatchPreset(CommandType.MONTE_CARLO_PI, ExecutionTarget.OCI_CLOUD_STREAM) }
              )
            }
            item {
              PresetTriggerButton(
                title = "Edge Telemetry",
                subtitle = "8k sensor frames",
                target = "Hybrid (25% Local / 75% OCI mTLS)",
                icon = Icons.Default.Memory,
                color = StreamCyan,
                onClick = { onDispatchPreset(CommandType.SENSOR_TELEMETRY_BATCH, ExecutionTarget.HYBRID_SPLIT) }
              )
            }
          }
        }
      }
    }

    // Event & Stream Terminal
    item {
      StreamTerminalCard(events = events)
    }

    // Recent Tasks Preview
    item {
      val runningOrRecent = jobs.take(3)
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
            Text(
              text = "Recent Task Records",
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "View All (${jobs.size})",
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier
                .clickable { onNavigateToTasks() }
                .padding(4.dp)
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          if (runningOrRecent.isEmpty()) {
            Text(
              text = "No jobs dispatched yet. Tap a benchmark trigger above to measure device CPU.",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              runningOrRecent.forEach { job ->
                MiniJobRow(job = job)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PresetTriggerButton(
  title: String,
  subtitle: String,
  target: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  color: Color,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(12.dp)
      .testTag("preset_trigger_${title.replace(" ", "_")}")
  ) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(text = subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = target,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = color
      )
    }
  }
}

@Composable
private fun MiniJobRow(job: ComputeJob) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = job.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = if (job.target == ExecutionTarget.LOCAL_DEVICE) "Local (Actual)" else "Demo Cloud",
          fontSize = 10.sp,
          color = Color(job.target.badgeColor),
          fontWeight = FontWeight.Bold
        )
      }
      if (job.status == JobStatus.RUNNING) {
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
          progress = { job.progress },
          modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
      } else if (job.resultSummary.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = job.resultSummary,
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1
        )
      }
    }

    Spacer(modifier = Modifier.width(8.dp))

    val statusColor = when (job.status) {
      JobStatus.COMPLETED -> SecurityGreen
      JobStatus.RUNNING -> StreamCyan
      JobStatus.QUEUED -> MaterialTheme.colorScheme.primary
      JobStatus.FAILED -> MaterialTheme.colorScheme.error
      JobStatus.CANCELLED -> Color.Gray
    }

    Text(
      text = when (job.status) {
        JobStatus.COMPLETED -> "${job.durationMs}ms"
        JobStatus.RUNNING -> "${(job.progress * 100).toInt()}%"
        JobStatus.QUEUED -> "Queued"
        JobStatus.FAILED -> "Failed"
        JobStatus.CANCELLED -> "Cancelled"
      },
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      color = statusColor
    )
  }
}
