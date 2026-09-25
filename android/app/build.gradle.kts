import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.linkassist.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linkassist.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 12
        versionName = "3.0.0"
    }

    val signingFile = providers.environmentVariable("LINKASSIST_KEYSTORE_FILE").orNull
    val signingPassword = providers.environmentVariable("LINKASSIST_KEYSTORE_PASSWORD").orNull
    val signingAlias = providers.environmentVariable("LINKASSIST_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("LINKASSIST_KEY_PASSWORD").orNull
    if (listOf(signingFile, signingPassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }) {
        val releaseKey = signingConfigs.create("releaseKey") {
            storeFile = file(requireNotNull(signingFile))
            storePassword = signingPassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
        buildTypes.getByName("release").signingConfig = releaseKey
    }
    // Without all four variables release remains unsigned; never fall back to a debug key.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Explicit opt-in only. assembleDebug must never overwrite a user's existing PC update package.
tasks.register("publishApkToPc") {
    group = "distribution"
    description = "Build and explicitly publish the debug APK and SHA-256 metadata to the PC app."
    dependsOn("assembleDebug")
    doLast {
        val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apkFile.isFile) { "Debug APK not found: ${apkFile.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val versionJson = """{"versionCode":${android.defaultConfig.versionCode},"versionName":"${android.defaultConfig.versionName}","file":"LinkAssist.apk","size":${apkFile.length()},"sha256":"$sha256"}"""
        val linkAssistDir = requireNotNull(rootDir.parentFile)
        for (sub in listOf("pc/apk", "pc/dist/apk")) {
            val outDir = linkAssistDir.resolve(sub)
            check(outDir.isDirectory || outDir.mkdirs()) { "Cannot create ${outDir.absolutePath}" }
            apkFile.copyTo(outDir.resolve("LinkAssist.apk"), overwrite = true)
            outDir.resolve("version.json").writeText(versionJson)
        }
        println("APK explicitly published to ${linkAssistDir.absolutePath}/pc/{apk,dist/apk}")
    }
}

// 项目路径含中文,测试工作进程需显式 UTF-8,否则类加载失败(ClassNotFoundException)
tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
