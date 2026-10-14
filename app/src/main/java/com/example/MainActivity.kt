package com.example

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.auth.OciOAuthManager
import com.example.transport.ControlPlaneCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ui.screens.ComputeQueueScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.MtlsSettingsScreen
import com.example.ui.screens.TelemetryScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ControllerViewModel

class MainActivity : ComponentActivity() {

  private val viewModel: ControllerViewModel by viewModels()
  private val ociAuthStatus = mutableStateOf("OCI sign-in required")
  private var oauthManager: OciOAuthManager? = null
  private var pendingIdentityUri: Uri? = null
  private var pendingIdentityPassword: CharArray? = null

  private val identityPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) {
      viewModel.showMessage("mTLS identity import cancelled.")
    } else {
      pendingIdentityUri = uri
      promptIdentityPasswordAndChooseCa()
    }
  }

  private val rootCaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { caUri ->
    val identityUri = pendingIdentityUri
    val password = pendingIdentityPassword
    if (caUri == null || identityUri == null || password == null) {
      password?.fill('\\u0000')
      pendingIdentityUri = null
      pendingIdentityPassword = null
      viewModel.showMessage("mTLS identity and root CA must both be selected.")
      return@registerForActivityResult
    }
    lifecycleScope.launch {
      try {
        val summary = withContext(Dispatchers.IO) {
          val identityStream = contentResolver.openInputStream(identityUri)
            ?: throw IllegalStateException("Unable to read selected PKCS#12 identity")
          val caStream = contentResolver.openInputStream(caUri)
            ?: throw IllegalStateException("Unable to read selected root CA bundle")
          ControlPlaneCredentialStore(applicationContext).importCredentials(
            identityStream,
            password,
            caStream
          )
        }
        viewModel.updateMtlsIdentitySummary(
          subject = summary.subject,
          issuer = summary.issuer,
          fingerprint = summary.sha256Fingerprint,
          rootCertificateCount = summary.rootCertificateCount
        )
        viewModel.showMessage("Device-bound mTLS identity provisioned. Verify the endpoint with the authenticated health check.")
      } catch (error: Exception) {
        viewModel.showMessage("mTLS identity import failed: " + (error.message ?: error.javaClass.simpleName))
      } finally {
        password.fill('\\u0000')
        pendingIdentityUri = null
        pendingIdentityPassword = null
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        MainControllerApp(
          viewModel = viewModel,
          ociAuthStatus = ociAuthStatus.value,
          onOciSignIn = { beginOciSignIn() }
        )
      }
    }
    handleOAuthIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleOAuthIntent(intent)
  }

  private fun beginProvisioningIdentity() {
    viewModel.showMessage("Select the device-specific PKCS#12 client identity.")
    identityPicker.launch(arrayOf("application/x-pkcs12", "application/octet-stream"))
  }

  private fun promptIdentityPasswordAndChooseCa() {
    val input = EditText(this).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      hint = "PKCS#12 password"
    }
    AlertDialog.Builder(this)
      .setTitle("Unlock client identity")
      .setMessage("Enter the password for this device's PKCS#12 identity. It will be stored encrypted with Android Keystore.")
      .setView(input)
      .setNegativeButton("Cancel") { _, _ ->
        pendingIdentityUri = null
        viewModel.showMessage("mTLS identity import cancelled.")
      }
      .setPositiveButton("Continue") { _, _ ->
        val password = input.text?.toString()?.toCharArray() ?: charArrayOf()
        if (password.isEmpty()) {
          pendingIdentityUri = null
          viewModel.showMessage("PKCS#12 password is required.")
        } else {
          pendingIdentityPassword = password
          viewModel.showMessage("Select the trusted control-plane root CA PEM bundle.")
          rootCaPicker.launch(arrayOf("application/x-pem-file", "text/plain", "application/octet-stream"))
        }
      }
      .show()
  }

  private fun getOciOAuthManager(): OciOAuthManager =
    oauthManager ?: OciOAuthManager(applicationContext).also { oauthManager = it }

  private fun beginOciSignIn() {
    try {
      val request = getOciOAuthManager().createAuthorizationRequest()
      ociAuthStatus.value = "Waiting for OCI authorization in the browser"
      startActivity(Intent(Intent.ACTION_VIEW, request.url))
    } catch (error: Exception) {
      ociAuthStatus.value = "OCI sign-in unavailable: " + (error.message ?: error.javaClass.simpleName)
    }
  }

  private fun handleOAuthIntent(intent: Intent?) {
    val callback = intent?.data ?: return
    if (callback.scheme != "com.onnxscibroccoli.omnikali" ||
      callback.host != "oauth" || callback.path != "/callback") return
    lifecycleScope.launch {
      ociAuthStatus.value = "Validating OCI authorization response"
      try {
        val tokens = getOciOAuthManager().handleAuthorizationRedirect(callback)
        ociAuthStatus.value = "OCI authenticated. Token type=" + tokens.tokenType +
          "; expires in " + (tokens.expiresInSeconds?.toString() ?: "unknown") + " seconds."
      } catch (error: Exception) {
        ociAuthStatus.value = "OCI sign-in failed: " + (error.message ?: error.javaClass.simpleName)
      }
    }
  }
}

@Composable
fun MainControllerApp(
  viewModel: ControllerViewModel,
  ociAuthStatus: String,
  onOciSignIn: () -> Unit
) {
  val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
  val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
  val telemetry by viewModel.currentTelemetry.collectAsStateWithLifecycle()
  val mtlsConfig by viewModel.mtlsConfig.collectAsStateWithLifecycle()
  val jobs by viewModel.allJobs.collectAsStateWithLifecycle()
  val events by viewModel.streamEventsList.collectAsStateWithLifecycle()
  val isDemoActive by viewModel.isDemoTelemetryActive.collectAsStateWithLifecycle()
  val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(snackbarMessage) {
    snackbarMessage?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      viewModel.clearSnackbar()
    }
  }

  // Handle back navigation: if not on Dashboard, return to Dashboard
  BackHandler(enabled = activeTab != 0) {
    viewModel.setTab(0)
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    bottomBar = {
      NavigationBar(
        modifier = Modifier
          .windowInsetsPadding(WindowInsets.navigationBars)
          .testTag("main_navigation_bar"),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
      ) {
        NavigationBarItem(
          selected = activeTab == 0,
          onClick = { viewModel.setTab(0) },
          icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
          label = { Text(stringResource(R.string.tab_dashboard), fontSize = 11.sp, fontWeight = if (activeTab == 0) FontWeight.Bold else FontWeight.Normal) },
          colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary
          ),
          modifier = Modifier.testTag("nav_tab_dashboard")
        )
        NavigationBarItem(
          selected = activeTab == 1,
          onClick = { viewModel.setTab(1) },
          icon = { Icon(Icons.Default.Bolt, contentDescription = "Compute") },
          label = { Text(stringResource(R.string.tab_tasks), fontSize = 11.sp, fontWeight = if (activeTab == 1) FontWeight.Bold else FontWeight.Normal) },
          colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary
          ),
          modifier = Modifier.testTag("nav_tab_compute")
        )
        NavigationBarItem(
          selected = activeTab == 2,
          onClick = { viewModel.setTab(2) },
          icon = { Icon(Icons.Default.Speed, contentDescription = "Telemetry") },
          label = { Text(stringResource(R.string.tab_telemetry), fontSize = 11.sp, fontWeight = if (activeTab == 2) FontWeight.Bold else FontWeight.Normal) },
          colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary
          ),
          modifier = Modifier.testTag("nav_tab_telemetry")
        )
        NavigationBarItem(
          selected = activeTab == 3,
          onClick = { viewModel.setTab(3) },
          icon = { Icon(Icons.Default.Lock, contentDescription = "mTLS") },
          label = { Text(stringResource(R.string.tab_security), fontSize = 11.sp, fontWeight = if (activeTab == 3) FontWeight.Bold else FontWeight.Normal) },
          colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary
          ),
          modifier = Modifier.testTag("nav_tab_security")
        )
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      when (activeTab) {
        0 -> DashboardScreen(
          connectionState = connectionState,
          telemetry = telemetry,
          mtlsConfig = mtlsConfig,
          events = events,
          jobs = jobs,
          onDispatchPreset = { algo, target -> viewModel.dispatchPreset(algo, target) },
          onNavigateToTasks = { viewModel.setTab(1) },
          onTestHandshake = { viewModel.attemptTlsHandshake() },
          ociAuthStatus = ociAuthStatus,
          onOciSignIn = onOciSignIn
        )

        1 -> ComputeQueueScreen(
          jobs = jobs,
          onSubmitJob = { title, algo, target, prio, units ->
            viewModel.submitNewJob(title, algo, target, prio, units)
          },
          onCancelJob = { id -> viewModel.cancelJob(id) },
          onDeleteJob = { id -> viewModel.deleteJob(id) },
          onClearAll = { viewModel.clearAllHistory() }
        )

        2 -> TelemetryScreen(
          connectionState = connectionState,
          telemetry = telemetry,
          events = events,
          isDemoActive = isDemoActive,
          onToggleDemo = { viewModel.toggleDemoTelemetry(it) }
        )

        3 -> MtlsSettingsScreen(
          connectionState = connectionState,
          config = mtlsConfig,
          onUpdateConfig = { host, port, latency, keepalive, mtls ->
            viewModel.updateMtlsConfig(host, port, latency, keepalive, mtls)
          },
          onProvisionIdentity = { beginProvisioningIdentity() },
          onAttemptHandshake = { viewModel.attemptTlsHandshake() },
          onDisconnect = { viewModel.disconnectStream() },
          onClearAll = { viewModel.clearAllHistory() }
        )
      }
    }
  }
}
