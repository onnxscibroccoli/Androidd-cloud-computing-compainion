package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.model.ExecutionTarget
import com.example.model.JobStatus
import com.example.model.Priority
import com.example.ui.components.DemoHonestyBanner
import com.example.ui.theme.OciRed
import com.example.ui.theme.QueueAmber
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.StreamCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputeQueueScreen(
  jobs: List<ComputeJob>,
  onSubmitJob: (String, CommandType, ExecutionTarget, Priority, Int) -> Unit,
  onCancelJob: (String) -> Unit,
  onDeleteJob: (String) -> Unit,
  onClearAll: () -> Unit,
  modifier: Modifier = Modifier
) {
  var showCreateDialog by remember { mutableStateOf(false) }
  var filterTarget by remember { mutableStateOf<ExecutionTarget?>(null) }
  var showClearConfirm by remember { mutableStateOf(false) }

  val filteredJobs = if (filterTarget == null) jobs else jobs.filter { it.target == filterTarget }

  Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp),
      contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Header & Controls
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "Compute Coordinator",
              fontSize = 22.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "${jobs.count { it.status == JobStatus.RUNNING }} running • ${jobs.size} total tasks",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          if (jobs.isNotEmpty()) {
            OutlinedButton(
              onClick = { showClearConfirm = true },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.testTag("clear_history_button")
            ) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Clear",
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("Clear", fontSize = 12.sp)
            }
          }
        }
      }

      item {
        DemoHonestyBanner()
      }

      // Filter Chips
      item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          item {
            FilterChip(
              label = "All Targets (${jobs.size})",
              isSelected = filterTarget == null,
              onClick = { filterTarget = null }
            )
          }
          item {
            FilterChip(
              label = "OCI Cloud (${jobs.count { it.target == ExecutionTarget.OCI_CLOUD_STREAM }})",
              isSelected = filterTarget == ExecutionTarget.OCI_CLOUD_STREAM,
              onClick = { filterTarget = ExecutionTarget.OCI_CLOUD_STREAM }
            )
          }
          item {
            FilterChip(
              label = "Hybrid Split (${jobs.count { it.target == ExecutionTarget.HYBRID_SPLIT }})",
              isSelected = filterTarget == ExecutionTarget.HYBRID_SPLIT,
              onClick = { filterTarget = ExecutionTarget.HYBRID_SPLIT }
            )
          }
          item {
            FilterChip(
              label = "Local CPU (${jobs.count { it.target == ExecutionTarget.LOCAL_DEVICE }})",
              isSelected = filterTarget == ExecutionTarget.LOCAL_DEVICE,
              onClick = { filterTarget = ExecutionTarget.LOCAL_DEVICE }
            )
          }
        }
      }

      // Jobs List
      if (filteredJobs.isEmpty()) {
        item {
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 24.dp)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Box(
                modifier = Modifier
                  .size(56.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Bolt,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(28.dp)
                )
              }
              Spacer(modifier = Modifier.height(14.dp))
              Text(
                text = "No Compute Tasks",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Dispatch a computational task to benchmark local vs OCI cloud execution.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
              Spacer(modifier = Modifier.height(16.dp))
              Button(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp)
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Dispatch New Task")
              }
            }
          }
        }
      } else {
        items(filteredJobs, key = { it.id }) { job ->
          JobDetailCard(
            job = job,
            onCancel = { onCancelJob(job.id) },
            onDelete = { onDeleteJob(job.id) },
            onRerun = {
              onSubmitJob(job.title, job.algorithm, job.target, job.priority, job.complexityUnits)
            }
          )
        }
      }
    }

    // Floating Action Button to Add New Job
    FloatingActionButton(
      onClick = { showCreateDialog = true },
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = Color.White,
      shape = CircleShape,
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 20.dp, bottom = 90.dp)
        .testTag("fab_dispatch_job")
    ) {
      Icon(imageVector = Icons.Default.Add, contentDescription = "Dispatch Job")
    }
  }

  // Dialog: Dispatch New Compute Task
  if (showCreateDialog) {
    CreateJobDialog(
      onDismiss = { showCreateDialog = false },
      onSubmit = { title, algorithm, target, priority, units ->
        onSubmitJob(title, algorithm, target, priority, units)
        showCreateDialog = false
      }
    )
  }

  // Confirm Clear All Dialog
  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = { showClearConfirm = false },
      title = { Text("Clear All History?") },
      text = { Text("This will permanently remove all past compute job records and telemetry logs.") },
      confirmButton = {
        Button(
          onClick = {
            onClearAll()
            showClearConfirm = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Clear All")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearConfirm = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun FilterChip(
  label: String,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(20.dp))
      .background(
        if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 7.dp)
  ) {
    Text(
      text = label,
      fontSize = 12.sp,
      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
      color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun JobDetailCard(
  job: ComputeJob,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
  onRerun: () -> Unit
) {
  val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
  val formattedTime = timeFormat.format(Date(job.timestamp))

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .border(
        1.dp,
        if (job.status == JobStatus.RUNNING) StreamCyan.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        RoundedCornerShape(16.dp)
      )
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // Row 1: Title, Target Pill, and Actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = job.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = "${job.algorithm.displayName} • $formattedTime",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .background(Color(job.target.badgeColor).copy(alpha = 0.15f))
              .border(1.dp, Color(job.target.badgeColor).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
              .padding(horizontal = 8.dp, vertical = 4.dp)
          ) {
            Text(
              text = job.target.displayName.substringBefore(" ("),
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(job.target.badgeColor)
            )
          }

          Spacer(modifier = Modifier.width(6.dp))

          if (job.status == JobStatus.RUNNING) {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel Job",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
              )
            }
          } else {
            IconButton(onClick = onRerun, modifier = Modifier.size(32.dp)) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Rerun",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
              )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }
      }

      // Progress bar if running
      if (job.status == JobStatus.RUNNING) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Executing on ${job.target.displayName}...",
            fontSize = 11.sp,
            color = StreamCyan,
            fontWeight = FontWeight.Medium
          )
          Text(
            text = "${(job.progress * 100).toInt()}%",
            fontSize = 11.sp,
            color = StreamCyan,
            fontWeight = FontWeight.Bold
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
          progress = { job.progress },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
          color = StreamCyan,
          trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
      }

      // Metrics badge row if completed
      if (job.status == JobStatus.COMPLETED) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          MetricBadge(
            label = "Duration",
            value = "${job.durationMs}ms",
            color = MaterialTheme.colorScheme.primary
          )
          if (job.target == ExecutionTarget.LOCAL_DEVICE) {
            MetricBadge(
              label = "Measurement",
              value = "Actual Device Measure",
              color = SecurityGreen
            )
          } else if (job.speedupRatio > 1.0f) {
            MetricBadge(
              label = "Model Speedup",
              value = String.format(Locale.US, "%.1fx (Simulated)", job.speedupRatio),
              color = QueueAmber
            )
          }
          MetricBadge(
            label = "Device CPU",
            value = String.format(Locale.US, "%.0f%%", job.localCpuPct),
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        if (job.resultSummary.isNotBlank()) {
          Spacer(modifier = Modifier.height(8.dp))
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
              .padding(8.dp)
          ) {
            Text(
              text = job.resultSummary,
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.onSurface,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }

      if (job.status == JobStatus.CANCELLED || job.status == JobStatus.FAILED) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = job.resultSummary,
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.error,
          fontFamily = FontFamily.Monospace
        )
      }
    }
  }
}

@Composable
private fun MetricBadge(label: String, value: String, color: Color) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.12f))
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = "$label: ", fontSize = 10.sp, color = color)
      Text(text = value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateJobDialog(
  onDismiss: () -> Unit,
  onSubmit: (String, CommandType, ExecutionTarget, Priority, Int) -> Unit
) {
  var selectedAlgorithm by remember { mutableStateOf(CommandType.MATRIX_MULTIPLY) }
  var selectedTarget by remember { mutableStateOf(ExecutionTarget.OCI_CLOUD_STREAM) }
  var selectedPriority by remember { mutableStateOf(Priority.NORMAL) }
  var customTitle by remember { mutableStateOf("") }
  var complexityUnits by remember { mutableFloatStateOf(192f) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(text = "Dispatch Compute Task", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        // Algorithm Picker
        Column {
          Text("Algorithm", fontSize = 12.sp, fontWeight = FontWeight.Medium)
          Spacer(modifier = Modifier.height(4.dp))
          CommandType.values().forEach { algo ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                  if (selectedAlgorithm == algo) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                  else Color.Transparent
                )
                .clickable {
                  selectedAlgorithm = algo
                  complexityUnits = when (algo) {
                    CommandType.MATRIX_MULTIPLY -> 192f
                    CommandType.CRYPTO_HASH_CHAIN -> 40_000f
                    CommandType.MONTE_CARLO_PI -> 400_000f
                    CommandType.PRIME_SIEVE -> 200_000f
                    CommandType.SENSOR_TELEMETRY_BATCH -> 10_000f
                  }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(16.dp)
                  .clip(CircleShape)
                  .border(
                    2.dp,
                    if (selectedAlgorithm == algo) MaterialTheme.colorScheme.primary else Color.Gray,
                    CircleShape
                  )
                  .padding(2.dp)
              ) {
                if (selectedAlgorithm == algo) {
                  Box(
                    modifier = Modifier
                      .fillMaxSize()
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.primary)
                  )
                }
              }
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(text = algo.displayName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(text = algo.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }

        // Target Picker
        Column {
          Text("Execution Target", fontSize = 12.sp, fontWeight = FontWeight.Medium)
          Spacer(modifier = Modifier.height(4.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            ExecutionTarget.values().forEach { target ->
              val isSel = selectedTarget == target
              Box(
                modifier = Modifier
                  .weight(1f)
                  .clip(RoundedCornerShape(8.dp))
                  .background(
                    if (isSel) Color(target.badgeColor).copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                  )
                  .border(
                    1.dp,
                    if (isSel) Color(target.badgeColor) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                  )
                  .clickable { selectedTarget = target }
                  .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = target.displayName.substringBefore(" "),
                  fontSize = 10.sp,
                  fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                  color = if (isSel) Color(target.badgeColor) else MaterialTheme.colorScheme.onSurface
                )
              }
            }
          }
        }

        // Complexity Slider
        Column {
          val (minVal, maxVal, label) = when (selectedAlgorithm) {
            CommandType.MATRIX_MULTIPLY -> Triple(64f, 384f, "${complexityUnits.toInt()}x${complexityUnits.toInt()} Matrix")
            CommandType.CRYPTO_HASH_CHAIN -> Triple(5_000f, 100_000f, "${complexityUnits.toInt()} Hash Rounds")
            CommandType.MONTE_CARLO_PI -> Triple(50_000f, 1_000_000f, "${complexityUnits.toInt()} Samples")
            CommandType.PRIME_SIEVE -> Triple(20_000f, 500_000f, "Primes up to ${complexityUnits.toInt()}")
            CommandType.SENSOR_TELEMETRY_BATCH -> Triple(2_000f, 40_000f, "${complexityUnits.toInt()} Telemetry Packets")
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Computational Scale", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
          Slider(
            value = complexityUnits,
            onValueChange = { complexityUnits = it },
            valueRange = minVal..maxVal
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSubmit(
            customTitle.ifBlank { "${selectedAlgorithm.displayName} (${selectedTarget.displayName.substringBefore(" ")})" },
            selectedAlgorithm,
            selectedTarget,
            selectedPriority,
            complexityUnits.toInt()
          )
        },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Dispatch")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
