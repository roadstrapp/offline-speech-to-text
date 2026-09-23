import java.io.FileInputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

// Normal APKs stay small. Speech models are downloaded once into app-private
// storage and survive ordinary APK updates. A maintainer can still opt into a
// bundled-model build for controlled offline testing.
val includeBundledModel = providers.gradleProperty("includeBundledModel")
    .orNull?.toBooleanStrictOrNull() ?: false

android {
    namespace = "dev.notune.transcribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.offlinespeechtotext"
        minSdk = 26
        targetSdk = 35
        versionCode = 39
        versionName = "0.10.0"
        buildConfigField("boolean", "HAS_BUNDLED_MODEL", includeBundledModel.toString())
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val configuredKeystore = System.getenv("OSTT_KEYSTORE_FILE")
            val ksFile = configuredKeystore?.let { file(it) }
                ?: rootProject.file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                // Keep credential validation lazy: a checkout that happens to
                // contain a release key must still configure debug/test tasks
                // without production-signing environment variables.
                storePassword = System.getenv("STORE_PASS")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    lint {
        // The maintained locale sets are intentionally partial. Android falls
        // back to the complete English resources for untranslated strings.
        disable += "MissingTranslation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Source sets — the Rust-built .so files land in jniLibs via cargo-ndk
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false          // extractNativeLibs=false (16KB safe)
            keepDebugSymbols += "**/*.so"
        }
    }

    // Play Asset Delivery: large model files go into a separate asset pack
    // so the base module stays under the 200 MB Play Store limit.
    if (includeBundledModel) {
        assetPacks += listOf(":model_assets")
    }
}

// For APK builds (assemble/install), asset packs are ignored by AGP so we
// must include the asset-pack assets as an extra source directory.  For
// bundle builds the asset pack module handles delivery and we must NOT add
// the directory here (would cause duplicate-resource errors).
val isBundle = gradle.startParameter.taskNames.any {
    it.contains("bundle", ignoreCase = true)
}
if (!isBundle && includeBundledModel) {
    android.sourceSets.getByName("main") {
        assets.srcDirs(
            "src/main/assets",
            rootProject.file("model_assets/src/main/assets")
        )
    }
}

dependencies {
    // Material Components (Material 3 / Material You). Pulls in AppCompat.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core:1.15.0")

    // Material/AppCompat transitively pull the legacy kotlin-stdlib-jdk7/jdk8:1.6.21
    // (via kotlinx-coroutines-android), whose classes were folded into
    // kotlin-stdlib in Kotlin 1.8 — causing duplicate-class build failures.
    // Align them with the resolved kotlin-stdlib (1.8.22), where they are empty
    // stubs. See https://kotlinlang.org/docs/whatsnew18.html#kotlin-stdlib
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}

// ---------------------------------------------------------------------------
// Rust / cargo-ndk build task
// ---------------------------------------------------------------------------

val ndkDir = project.findProperty("ndk.dir")?.toString()
    ?: System.getenv("ANDROID_NDK_HOME")
    ?: System.getenv("ANDROID_NDK")
    ?: android.ndkDirectory.absolutePath

fun nativeVariant(name: String, armArch: String) = tasks.register<Exec>("cargoNdkBuild$name") {
    description = "Build the $name Rust native backend via cargo-ndk"
    group = "build"
    workingDir = rootProject.projectDir
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("ANDROID_NDK_ROOT", ndkDir)
    environment("ANDROID_NDK", ndkDir)
    environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("rust-$name").get().asFile)
    environment("TRANSCRIBE_CMAKE_ARGS", "-DGGML_CPU_ARM_ARCH=$armArch")
    val packagedName = if (name == "Armv8") {
        "libandroid_transcribe_app_armv8.so"
    } else {
        "libandroid_transcribe_app_dotprod.so"
    }
    environment("RUSTFLAGS", "-C link-arg=-Wl,-soname,$packagedName")
    val output = layout.buildDirectory.dir("jni-$name").get().asFile
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-o", output.absolutePath,
        "build", "--release")
    outputs.dir(output)
    outputs.upToDateWhen { false }
}

val cargoNdkBuildArmv8 = nativeVariant("Armv8", "armv8-a")
val cargoNdkBuildDotprod = nativeVariant("Dotprod", "armv8.2-a+dotprod+fp16")

val compileCpuProbe by tasks.registering(Exec::class) {
    description = "Build the baseline CPU feature probe"
    group = "build"
    val source = project.file("src/main/cpp/cpu_features_probe.c")
    val outputDir = layout.buildDirectory.dir("jni-Probe/arm64-v8a").get().asFile
    val output = File(outputDir, "libostt_cpu_probe.so")
    doFirst { outputDir.mkdirs() }
    commandLine(
        "$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin/clang",
        "--target=aarch64-linux-android26",
        "-shared", "-fPIC", "-Oz",
        "-Wl,-soname,libostt_cpu_probe.so",
        source.absolutePath,
        "-o", output.absolutePath
    )
    inputs.file(source)
    outputs.file(output)
}

val cargoNdkBuild by tasks.registering {
    description = "Build and package baseline and optimized native backends"
    group = "build"
    dependsOn(cargoNdkBuildArmv8, cargoNdkBuildDotprod, compileCpuProbe)
    doLast {
        val destDir = project.file("src/main/jniLibs/arm64-v8a")
        destDir.mkdirs()
        File(destDir, "libandroid_transcribe_app.so").delete()
        val armv8Source = layout.buildDirectory.file(
            "jni-Armv8/arm64-v8a/libandroid_transcribe_app.so").get().asFile
        val dotprodSource = layout.buildDirectory.file(
            "jni-Dotprod/arm64-v8a/libandroid_transcribe_app.so").get().asFile
        if (!armv8Source.isFile) {
            throw GradleException("Baseline native backend missing: ${armv8Source.absolutePath}")
        }
        if (!dotprodSource.isFile) {
            throw GradleException("Optimized native backend missing: ${dotprodSource.absolutePath}")
        }
        copy {
            from(armv8Source)
            into(destDir)
            rename { "libandroid_transcribe_app_armv8.so" }
        }
        copy {
            from(dotprodSource)
            into(destDir)
            rename { "libandroid_transcribe_app_dotprod.so" }
        }
        val probeSource = layout.buildDirectory.file(
            "jni-Probe/arm64-v8a/libostt_cpu_probe.so").get().asFile
        if (!probeSource.isFile) {
            throw GradleException("CPU feature probe missing: ${probeSource.absolutePath}")
        }
        probeSource.copyTo(File(destDir, "libostt_cpu_probe.so"), overwrite = true)
        val libcpp = file("$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
        if (!libcpp.exists()) {
            throw GradleException("libc++_shared.so not found in NDK at: ${libcpp.absolutePath}")
        }
        libcpp.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
        println("Packaged baseline and optimized native backends")
    }
}

// Wire the cargo-ndk build into the Android build lifecycle
tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

// ---------------------------------------------------------------------------
// Model asset download task
// ---------------------------------------------------------------------------

data class ModelFile(val name: String, val size: Long, val sha256: String)

// The bundled GGUF goes into the model_assets asset pack so the base module
// stays under the Play Store 200 MB compressed-download limit.
val modelPackFiles = listOf(
    ModelFile("parakeet-tdt-0.6b-v3-Q4_K_M.gguf", 485425504L,
        "b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181"),
)

val huggingFaceRepo = "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/85ac09ea12fc4b1112fa76810059364bc6adc9de"

fun downloadToDir(assetsDir: File, files: List<ModelFile>) {
    assetsDir.mkdirs()
    val expectedNames = files.map { it.name }.toSet()
    assetsDir.listFiles { file -> file.extension.equals("gguf", ignoreCase = true) }
        ?.filterNot { it.name in expectedNames }
        ?.forEach {
            println("  - Removing superseded bundled model ${it.name}")
            if (!it.delete()) throw GradleException("Failed to remove superseded model ${it.name}")
        }
    files.forEach { model ->
        val destFile = File(assetsDir, model.name)
        if (destFile.exists() && destFile.length() != model.size) {
            println("  ✗ ${model.name} size mismatch, re-downloading...")
            destFile.delete()
        }
        if (destFile.exists()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(destFile).use { fis ->
                val buf = ByteArray(8192)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash == model.sha256) {
                println("  ✓ ${model.name} already downloaded and verified")
                return@forEach
            } else {
                println("  ✗ ${model.name} checksum mismatch, re-downloading...")
                destFile.delete()
            }
        }

        if (!destFile.exists()) {
            println("  ↓ Downloading ${model.name}...")
            val downloadUrl = "$huggingFaceRepo/${model.name}?download=true"
            val proc = ProcessBuilder("curl", "-L", "-f", "-o", destFile.absolutePath, downloadUrl)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("Failed to download ${model.name} (curl exit code $exitCode)")
            }

            if (destFile.length() != model.size) {
                destFile.delete()
                throw GradleException(
                    "Size verification failed for ${model.name}: expected ${model.size} bytes"
                )
            }
            if (model.sha256.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(destFile).use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        digest.update(buf, 0, read)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != model.sha256) {
                    throw GradleException(
                        "Checksum verification failed for ${model.name}:\n" +
                        "  Expected: ${model.sha256}\n" +
                        "  Got:      $hash"
                    )
                }
                println("  ✓ ${model.name} verified")
            }
        }
    }
}

val downloadModels by tasks.registering {
    description = "Download the built-in speech model (GGUF)"
    group = "build"

    // The GGUF -> asset pack (separate install-time delivery)
    val packAssetsDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    outputs.dir(packAssetsDir)

    doLast {
        downloadToDir(packAssetsDir, modelPackFiles)
    }
}

tasks.named("preBuild") {
    if (includeBundledModel) {
        dependsOn(downloadModels)
    }
}
