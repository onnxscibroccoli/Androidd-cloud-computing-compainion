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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.example.model.ConnectionState
import com.example.model.MtlsConfig
import com.example.ui.components.DemoHonestyBanner
import com.example.ui.components.StatusBadge
import com.example.ui.theme.OciRed
import com.example.ui.theme.SecurityGreen
import com.example.ui.theme.StreamCyan

@Composable
fun MtlsSettingsScreen(
  connectionState: ConnectionState,
  config: MtlsConfig,
  onUpdateConfig: (String, Int, Int, Int, Boolean) -> Unit,
  onGenerateNewCert: () -> Unit,
  onAttemptHandshake: () -> Unit,
  onDisconnect: () -> Unit,
  onClearAll: () -> Unit,
  modifier: Modifier = Modifier
) {
  var hostInput by remember(config.endpointHost) { mutableStateOf(config.endpointHost) }
  var portInput by remember(config.endpointPort) { mutableStateOf(config.endpointPort.toString()) }
  var latencySlider by remember(config.simulatedFastConnectLatencyMs) {
    mutableFloatStateOf(config.simulatedFastConnectLatencyMs.toFloat())
  }
  var keepAliveSlider by remember(config.keepAliveIntervalSec) {
    mutableFloatStateOf(config.keepAliveIntervalSec.toFloat())
  }
  var enforceMtls by remember(config.isMtlsEnforced) { mutableStateOf(config.isMtlsEnforced) }

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
            text = "mTLS & OCI Configuration",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Mutual authentication & streaming endpoints",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        StatusBadge(state = connectionState)
      }
    }

    // Honesty Notice
    item {
      DemoHonestyBanner()
    }

    // 1. Connection Controls
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(text = "Transport Handshake Diagnostic", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Runs a real server-certificate TLS probe only when mTLS is disabled. This reports TLS_SERVER_VERIFIED, never mutual authentication. mTLS fails closed until a real client identity and trusted CA are provisioned.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(12.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Button(
              onClick = onAttemptHandshake,
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1f)
                .testTag("settings_handshake_diagnostic_button")
            ) {
              Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Run Handshake Test", fontSize = 12.sp)
            }

            if (connectionState != ConnectionState.DISCONNECTED) {
              OutlinedButton(
                onClick = onDisconnect,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("settings_disconnect_button")
              ) {
                Icon(Icons.Default.WifiOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Disconnect", fontSize = 12.sp)
              }
            }
          }
        }
      }
    }

    // 2. Endpoint Configuration Card
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
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
              )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "Target OCI Endpoint Profile", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          }

          Spacer(modifier = Modifier.height(14.dp))

          OutlinedTextField(
            value = hostInput,
            onValueChange = { hostInput = it },
            label = { Text("Target Host (Unlinked)") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_oci_host")
          )

          Spacer(modifier = Modifier.height(10.dp))

          OutlinedTextField(
            value = portInput,
            onValueChange = { portInput = it },
            label = { Text("Target Port (Default: 443)") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_oci_port")
          )

          Spacer(modifier = Modifier.height(14.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Simulated Link RTT Latency", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
              "${latencySlider.toInt()} ms (Model)",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = StreamCyan
            )
          }
          Slider(
            value = latencySlider,
            onValueChange = { latencySlider = it },
            valueRange = 5f..80f
          )

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Keep-Alive Interval", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
              "${keepAliveSlider.toInt()} sec",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary
            )
          }
          Slider(
            value = keepAliveSlider,
            onValueChange = { keepAliveSlider = it },
            valueRange = 2f..15f
          )

          Spacer(modifier = Modifier.height(12.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Enforce Mutual TLS (mTLS)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
              Text("Require valid client cert verification for transport streams", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
              checked = enforceMtls,
              onCheckedChange = { enforceMtls = it },
              colors = SwitchDefaults.colors(checkedThumbColor = SecurityGreen)
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          Button(
            onClick = {
              val port = portInput.toIntOrNull() ?: 443
              onUpdateConfig(
                hostInput.trim(),
                port,
                latencySlider.toInt(),
                keepAliveSlider.toInt(),
                enforceMtls
              )
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("save_endpoint_button")
          ) {
            Text("Save Profile Specification")
          }
        }
      }
    }

    // 3. Client Certificate & Security Details
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
              Icon(imageVector = Icons.Default.VpnKey, contentDescription = null, tint = SecurityGreen, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "Client X.509 Identity (Provisioning Required)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          }

          Spacer(modifier = Modifier.height(14.dp))

          CertDetailRow(label = "Subject DN", value = config.clientCertSubject)
          CertDetailRow(label = "Issuer CA", value = config.clientCertIssuer)
          CertDetailRow(label = "Fingerprint", value = config.clientCertFingerprint)
          CertDetailRow(label = "Cipher Suite", value = config.cipherSuite)
          CertDetailRow(label = "TLS Protocol", value = config.tlsVersion)

          Spacer(modifier = Modifier.height(14.dp))

          OutlinedButton(
            onClick = onGenerateNewCert,
            enabled = false,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("generate_new_cert_button")
          ) {
            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Provisioning unavailable without enrollment service", fontSize = 12.sp)
          }
        }
      }
    }

    // 4. Reset & Diagnostics
    item {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(text = "Database & Persistence Maintenance", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Reset all cached jobs, local SQLite persistence records, and event logs.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(12.dp))

          OutlinedButton(
            onClick = onClearAll,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Reset Local Controller Database")
          }
        }
      }
    }
  }
}

@Composable
private fun CertDetailRow(label: String, value: String) {
  Column(modifier = Modifier.padding(vertical = 4.dp)) {
    Text(
      text = label,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = value,
      fontSize = 11.sp,
      color = MaterialTheme.colorScheme.onSurface,
      fontFamily = FontFamily.Monospace
    )
  }
}
