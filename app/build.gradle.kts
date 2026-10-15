import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val protocCompiler by configurations.creating
val grpcCompiler by configurations.creating
val controlPlaneProtoFile = layout.projectDirectory.file("src/main/proto/control_plane.proto")
val controlPlaneProtoOutput = layout.buildDirectory.dir("generated/source/controlPlaneProto/main/java")

val generateControlPlaneProto by tasks.registering(Exec::class) {
  inputs.file(controlPlaneProtoFile)
  outputs.dir(controlPlaneProtoOutput)
  notCompatibleWithConfigurationCache(
    "Exec generateControlPlaneProto resolves protoc/grpc configuration files at execution time"
  )
  doFirst {
    val protoc = protocCompiler.singleFile
    val grpcPlugin = grpcCompiler.singleFile
    check(protoc.setExecutable(true)) { "Unable to make protoc executable" }
    check(grpcPlugin.setExecutable(true)) { "Unable to make gRPC protoc plugin executable" }
    val output = controlPlaneProtoOutput.get().asFile
    output.mkdirs()
    commandLine(
      protoc.absolutePath,
      "--proto_path=${controlPlaneProtoFile.asFile.parentFile.absolutePath}",
      "--java_out=${output.absolutePath}",
      "--plugin=protoc-gen-grpc=${grpcPlugin.absolutePath}",
      "--grpc_out=${output.absolutePath}",
      controlPlaneProtoFile.asFile.absolutePath
    )
  }
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    val ociClientId = (System.getenv("OCI_CLIENT_ID") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
    val ociDomainUrl = (System.getenv("OCI_DOMAIN_URL") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
    buildConfigField("String", "OCI_CLIENT_ID", "\"$ociClientId\"")
    buildConfigField("String", "OCI_DOMAIN_URL", "\"$ociDomainUrl\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  sourceSets.getByName("main").java.srcDir(controlPlaneProtoOutput.get().asFile)
  buildFeatures {
    compose = true
    buildConfig = true
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }
tasks.named("preBuild").configure { dependsOn(generateControlPlaneProto) }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  add(protocCompiler.name, "com.google.protobuf:protoc:4.30.2:linux-x86_64@exe")
  add(grpcCompiler.name, "io.grpc:protoc-gen-grpc-java:1.70.0:linux-x86_64@exe")
  implementation(libs.androidx.security.crypto)
  implementation(libs.grpc.okhttp)
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.stub)
  implementation(libs.protobuf.java)
  implementation(libs.javax.annotation)
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
