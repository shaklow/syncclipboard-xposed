import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
}

configure<ApplicationExtension> {
    namespace = rootProject.extra["appPackageName"] as String

    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    packaging {
        resources {
            excludes.addAll(
                listOf(
                    "META-INF/**/LICENSE*",
                    "META-INF/**/NOTICE*",
                    "META-INF/*.version",
                    "DebugProbesKt.bin"
                )
            )
        }
        dex {
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = rootProject.extra["appPackageName"] as String
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // 构建前自动生成 debug keystore（keytool 由 JDK 提供，Linux/Windows 通用）
    val ensureDebugKeystore by tasks.registering {
        val keystoreFile = File(System.getProperty("user.home"), ".android/debug.keystore")
        outputs.file(keystoreFile)
        doFirst {
            if (!keystoreFile.exists()) {
                keystoreFile.parentFile.mkdirs()
                exec {
                    commandLine(
                        "keytool", "-genkeypair", "-v",
                        "-keystore", keystoreFile.absolutePath,
                        "-storepass", "android",
                        "-alias", "androiddebugkey",
                        "-keypass", "android",
                        "-dname", "CN=Android Debug,O=Android,C=US",
                        "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "10000"
                    )
                }
                println("--- [Keystore] debug keystore created at ${keystoreFile.absolutePath} ---")
            }
        }
    }

    tasks.matching { it.name.startsWith("package") && it.name.contains("Release") }
        .configureEach { dependsOn(ensureDebugKeystore) }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":app"))
    implementation(project(":xposed"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
