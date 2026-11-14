import com.android.build.api.artifact.SingleArtifact

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

android {
    namespace = "ru.privatediary"
    compileSdk = 37
    compileSdkMinor = 0
    defaultConfig {
        applicationId = "ru.privatediary"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    androidResources { noCompress += "onnx" }
}

androidComponents.onVariants { variant ->
    val suffix = variant.name.replaceFirstChar { it.uppercase() }
    val verify = tasks.register<CheckNoInternet>("check${suffix}NoInternet") {
        manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
    tasks.matching { it.name == "assemble$suffix" || it.name == "test${suffix}UnitTest" || it.name == "check" }.configureEach { dependsOn(verify) }
    tasks.withType<Test>().configureEach {
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
    testImplementation("junit:junit:4.13.2")
}
