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
    @get:Input abstract val networkFeatures: Property<Boolean>
    @TaskAction fun check() {
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        val ns = "http://schemas.android.com/apk/res/android"
        val declaredPermissions = mutableSetOf<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) {
            val nodes = xml.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val permission = (nodes.item(i) as org.w3c.dom.Element).getAttributeNS(ns, "name")
                declaredPermissions += permission
                check(networkFeatures.get() || permission != "android.permission.INTERNET") {
                    "Privacy violation: INTERNET permission found in merged manifest"
                }
            }
        }
        if (!networkFeatures.get()) return
        check("android.permission.INTERNET" in declaredPermissions) { "Store monetization requires INTERNET" }
        val forbiddenPermissions = setOf(
            "com.google.android.gms.permission.AD_ID",
            "android.permission.ACCESS_ADSERVICES_AD_ID",
            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            "android.permission.ACCESS_ADSERVICES_TOPICS",
        )
        check(declaredPermissions.intersect(forbiddenPermissions).isEmpty()) {
            "Store advertising identifiers/AdServices permissions must be removed"
        }
        val application = xml.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        check(application.getAttributeNS(ns, "usesCleartextTraffic") == "false") { "Store traffic must use HTTPS" }
        // The only reviewed SDK entry points. Any dependency change must be reviewed explicitly.
        val allowed = mapOf(
            "ru.privatediary.monetization.ads.StoreAdActivity" to ("activity" to true),
            "com.yandex.mobile.ads.common.AdActivity" to ("activity" to true),
            "com.yandex.mobile.ads.core.initializer.YandexAdsInitializeProvider" to ("provider" to false),
            "io.appmetrica.analytics.internal.PreloadInfoContentProvider" to ("provider" to false),
            "com.yandex.mobile.ads.features.debugpanel.data.local.DebugPanelFileProvider" to ("provider" to false),
            "com.yandex.mobile.ads.features.debugpanel.ui.IntegrationInspectorActivity" to ("activity" to false),
            "io.appmetrica.analytics.internal.AppMetricaService" to ("service" to true),
            "com.google.android.gms.common.api.GoogleApiActivity" to ("activity" to false),
        )
        val sdkPrefixes = listOf("com.yandex.", "io.appmetrica.", "com.google.", "com.monetization.", "yads.",
            "com.my.target.", "com.applovin.", "com.facebook.ads.", "com.unity3d.ads.")
        val seen = mutableSetOf<String>()
        val packageName = xml.documentElement.getAttribute("package")
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            val nodes = xml.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val component = nodes.item(i) as org.w3c.dom.Element
                val rawName = component.getAttributeNS(ns, "name")
                val name = if (rawName.startsWith(".")) packageName + rawName else rawName
                if (name !in allowed && sdkPrefixes.none { name.startsWith(it) }) continue
                val expected = allowed[name] ?: error("Unreviewed advertising SDK component: $name")
                check(tag == expected.first) { "Unexpected component type for $name" }
                check(seen.add(name)) { "Duplicate advertising component: $name" }
                check(component.getAttributeNS(ns, "process") == ":ads") { "SDK component outside :ads: $name" }
                check(component.getAttributeNS(ns, "exported") == "false") { "Exported advertising component: $name" }
                val enabled = component.getAttributeNS(ns, "enabled")
                check(if (expected.second) enabled.isEmpty() || enabled == "true" else enabled == "false") {
                    "Unexpected enabled state for advertising component: $name"
                }
            }
        }
        check(seen == allowed.keys) { "Missing reviewed advertising components: ${allowed.keys - seen}" }
        val metadata = (0 until application.childNodes.length).mapNotNull {
            application.childNodes.item(it) as? org.w3c.dom.Element
        }.filter {
            it.tagName == "meta-data" &&
                it.getAttributeNS(ns, "name") == "com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION"
        }
        check(metadata.size == 1 && metadata.single().getAttributeNS(ns, "value") == "false") {
            "Advertising SDK automatic initialization must be disabled"
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
        versionCode = 10
        versionName = "1.0.0-rc2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PUBLISHER_DISPLAY_NAME", "\"Private Diary\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"gr1val@yandex.ru\"")
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("offline") {
            dimension = "distribution"
            buildConfigField("boolean", "NETWORK_FEATURES", "false")
            buildConfigField("String", "DISTRIBUTION", "\"offline\"")
            buildConfigField("String", "YAN_AD_UNIT_ID", "\"\"")
            buildConfigField("String", "PAYMENT_BACKEND_URL", "\"\"")
            buildConfigField("String", "PRO_LICENSE_PUBLIC_KEY", "\"\"")
            listOf("SELLER_LEGAL_NAME", "PRO_TERMS_URL", "PRO_REFUND_URL").forEach { key ->
                buildConfigField("String", key, "\"\"")
            }
        }
        create("store") {
            dimension = "distribution"
            buildConfigField("boolean", "NETWORK_FEATURES", "true")
            buildConfigField("String", "DISTRIBUTION", "\"store\"")
            listOf("YAN_AD_UNIT_ID", "PAYMENT_BACKEND_URL", "PRO_LICENSE_PUBLIC_KEY",
                "SELLER_LEGAL_NAME", "PRO_TERMS_URL", "PRO_REFUND_URL").forEach { key ->
                val value = providers.gradleProperty(key).orElse("").get()
                require(value.none { it.isISOControl() || it == '"' || it == '\\' }) { "Invalid public build setting: $key" }
                buildConfigField("String", key, "\"$value\"")
            }
        }
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
        networkFeatures.set(variant.productFlavors.any { it.second == "store" })
    }
    tasks.register("check${suffix}Privacy") { dependsOn(verify) }
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
            systemProperty("distribution", if (variant.productFlavors.any { it.second == "store" }) "store" else "offline")
        }
    }
}

tasks.register("testDebugUnitTest") { dependsOn("testOfflineDebugUnitTest", "testStoreDebugUnitTest") }

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
    "storeImplementation"("com.yandex.android:mobileads:8.6.0")
    constraints {
        "storeImplementation"("io.appmetrica.analytics:analytics:8.6.0") { version { strictly("8.6.0") } }
    }
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
    // CPU native library for explicitly requested JVM model tests only; never included in the Android APK.
    if (providers.gradleProperty("offlineModelDirectory").isPresent || verifyOfflineModel.get()) {
        testRuntimeOnly("com.microsoft.onnxruntime:onnxruntime:1.23.2")
    }
}
