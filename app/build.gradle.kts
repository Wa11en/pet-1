import com.android.build.api.artifact.SingleArtifact
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

abstract class CheckNoInternet : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @TaskAction fun check() {
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        val permissions = listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
        for (tag in permissions) {
            val nodes = xml.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val permission = nodes.item(i) as org.w3c.dom.Element
                check(permission.getAttributeNS("http://schemas.android.com/apk/res/android", "name") != "android.permission.INTERNET") {
                    "Privacy violation: INTERNET permission found in merged manifest"
                }
            }
        }
    }
}

abstract class VerifyBundledModel : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modelDirectory: DirectoryProperty

    @TaskAction fun verify() {
        val expected = mapOf(
            "model_int8.onnx" to "4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c",
            "tokenizer.json" to "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
        )
        expected.forEach { (name, sha256) ->
            val file = modelDirectory.get().file(name).asFile
            check(file.isFile) { "Offline build requires $name. Run bash scripts/fetch_model.sh on the build computer." }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == sha256) {
                "Offline build rejected: SHA-256 mismatch for $name"
            }
        }
    }
}

val requireModel = providers.gradleProperty("requireModel").map { it.toBooleanStrict() }.orElse(false)
val verifyOfflineModel = providers.gradleProperty("verifyOfflineModel").map { it.toBooleanStrict() }.orElse(false)
val unsignedReleaseCandidate = providers.gradleProperty("unsignedReleaseCandidate").map { it.toBooleanStrict() }.orElse(false)
val releaseKeyFile = providers.environmentVariable("DIARY_RELEASE_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("DIARY_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("DIARY_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("DIARY_RELEASE_KEY_PASSWORD")
val releaseCredentialsPresent = listOf(releaseKeyFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it.orNull?.isNotBlank() == true }
val verifyBundledModel = tasks.register<VerifyBundledModel>("verifyBundledModel") {
    modelDirectory.set(layout.projectDirectory.dir("src/main/assets/model"))
}

android {
    namespace = "ru.privatediary"
    compileSdk = 37
    compileSdkMinor = 0
    defaultConfig {
        applicationId = "ru.privatediary"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.0.0-rc1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PUBLISHER_DISPLAY_NAME", "\"Private Diary\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"gr1val@yandex.ru\"")
    }
    // Both languages must be available offline, including installs from App Bundles.
    bundle { language { enableSplit = false } }
    buildFeatures { compose = true; buildConfig = true }
    if (releaseCredentialsPresent && !unsignedReleaseCandidate.get()) {
        signingConfigs.create("ownerRelease") {
            storeFile = rootProject.file(releaseKeyFile.get())
            storePassword = releaseStorePassword.get()
            keyAlias = releaseKeyAlias.get()
            keyPassword = releaseKeyPassword.get()
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseCredentialsPresent && !unsignedReleaseCandidate.get()) signingConfig = signingConfigs.getByName("ownerRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["androidTest"].assets.srcDir("schemas")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    androidResources { noCompress += "onnx" }
}

androidComponents.onVariants { variant ->
    val suffix = variant.name.replaceFirstChar { it.uppercase() }
    val verify = tasks.register<CheckNoInternet>("check${suffix}NoInternet") {
        manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
    if (variant.buildType == "release") {
        val signingCheck = tasks.register("check${suffix}Signing") {
            doLast {
                if (!unsignedReleaseCandidate.get()) {
                    check(releaseCredentialsPresent) {
                        "Owner release signing is not configured. Run scripts/create_release_key.sh locally and supply DIARY_RELEASE_* variables, or use -PunsignedReleaseCandidate=true to prepare an explicitly unsigned review artifact."
                    }
                    check(rootProject.file(releaseKeyFile.get()).isFile) { "Release keystore does not exist" }
                }
            }
        }
        tasks.matching { it.name == "package$suffix" || it.name == "package${suffix}Bundle" ||
            it.name == "sign${suffix}Bundle" || it.name == "assemble$suffix" || it.name == "bundle$suffix" }.configureEach {
            dependsOn(signingCheck, verifyBundledModel)
        }
    }
    tasks.matching { it.name == "assemble$suffix" || it.name == "bundle$suffix" || it.name == "test${suffix}UnitTest" || it.name == "check" }.configureEach {
        dependsOn(verify)
        if (requireModel.get()) dependsOn(verifyBundledModel)
    }
    tasks.withType<Test>().configureEach {
        providers.gradleProperty("tokenizerFixturePath").orNull?.let { systemProperty("tokenizerFixturePath", it) }
        val explicitModelDirectory = providers.gradleProperty("offlineModelDirectory").orNull
        if (explicitModelDirectory != null) {
            systemProperty("offlineModelDirectory", rootProject.file(explicitModelDirectory).absolutePath)
        } else if (verifyOfflineModel.get()) {
            systemProperty("offlineModelDirectory", layout.projectDirectory.dir("src/main/assets/model").asFile.absolutePath)
        }
        if (name == "test${suffix}UnitTest") {
            dependsOn(verify)
            systemProperty("mergedManifestPath", variant.artifacts.get(SingleArtifact.MERGED_MANIFEST).get().asFile.absolutePath)
        }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("net.zetetic:sqlcipher-android:4.10.0")
    implementation("androidx.sqlite:sqlite-ktx:2.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.github.rholder:snowball-stemmer:1.3.0.581.1")
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
    // CPU native library for explicitly requested JVM model tests only; never included in the Android APK.
    if (providers.gradleProperty("offlineModelDirectory").isPresent || verifyOfflineModel.get()) {
        testRuntimeOnly("com.microsoft.onnxruntime:onnxruntime:1.23.2")
    }
}
