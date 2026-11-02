import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.jumpserverbridge.datagrip"
version = "0.2.6"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else datagrip("2026.2.6")
        bundledPlugin("com.intellij.database")
    }
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.5")
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}

tasks.processResources {
    from("LICENSE") { into("META-INF") }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.jumpserverbridge.auth"
        name = "JumpServer Database Auth"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262.10968.148"
            // Explicitly unset the Gradle plugin's default MAJOR.* upper bound.
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Experimental authentication APIs remain visible in reports. Actual binary
        // incompatibilities and unsupported API use must fail local checks and CI.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES
        )
        ides {
            // Verification targets are independent of the SDK used to compile.
            val verificationIde = providers.gradleProperty("verificationIdePath").orNull
            val verificationVersion = providers.gradleProperty("verificationIdeVersion").orNull
            when {
                verificationIde != null -> local(verificationIde)
                verificationVersion != null -> create("DB", verificationVersion)
                else -> {
                    create("DB", "2026.2.6")
                    create("DB", "2026.2.6.1")
                }
            }
            if (providers.gradleProperty("verifyLatestRelease").map(String::toBoolean).getOrElse(false)) {
                latest {
                    types = listOf(IntelliJPlatformType.DataGrip)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
        }
    }
}
