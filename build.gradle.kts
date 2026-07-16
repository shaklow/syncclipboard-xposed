import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

extra["appPackageName"] = "io.github.erenche.syncclipboard"
extra["appVersionCode"] = 49
extra["appVersionName"] = "1.0.0-alpha49"
extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 37
extra["minSdkVersion"] = 29

// APK auto-export tasks
val cleanApks: TaskProvider<Task> = tasks.register("cleanAllApks") {
    group = "build"
    doFirst {
        val outputDir = project.layout.buildDirectory.dir("all-apks").get().asFile
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
            println("--- [Clean] Cleaned old APK export directory ---")
        }
    }
}

val copyApksAll: TaskProvider<Task> = tasks.register("copyApks") {
    group = "build"
    description = "Collect all APKs and package by BuildType into ZIP"
}

subprojects {
    plugins.withId("com.android.application") {
        val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()

        androidComponents.onVariants { variant ->
            val variantName = variant.name
            val moduleName = project.name
            val buildType = variant.buildType ?: "others"
            val versionName =
                variant.outputs.firstOrNull()?.versionName?.getOrElse(project.version.toString())
                    ?: "1.0"

            val zipTaskName = "zip${buildType.replaceFirstChar { it.uppercase() }}Apks"
            val typeZipTask = rootProject.tasks.maybeCreate(zipTaskName, Zip::class.java).apply {
                group = "build"
                archiveFileName.set("${rootProject.name}-all-$buildType.zip")
                destinationDirectory.set(rootProject.layout.buildDirectory.dir("distributions"))
                from(rootProject.layout.buildDirectory.dir("all-apks/$buildType"))
            }

            val copyTask =
                tasks.register<Copy>("copy${variantName.replaceFirstChar { it.uppercase() }}Apk") {
                    dependsOn(cleanApks)
                    from(variant.artifacts.get(SingleArtifact.APK))
                    into(rootProject.layout.buildDirectory.dir("all-apks/$buildType"))
                    include("*.apk")

                    eachFile {
                        relativePath = RelativePath(true, name)
                    }

                    rename { fileName ->
                        "${moduleName}-${versionName}-${buildType}.apk"
                    }
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                    finalizedBy(typeZipTask)
                }

            copyApksAll.configure {
                dependsOn(copyTask)
            }

            tasks.matching { it.name == "assemble${variantName.replaceFirstChar { c -> c.uppercase() }}" }
                .configureEach {
                    finalizedBy(copyTask)
                }
        }
    }
}
