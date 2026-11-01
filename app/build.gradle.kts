import java.security.MessageDigest
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

base {
    archivesName.set("PegDown")
}

@org.gradle.work.DisableCachingByDefault(because = "Small local source fingerprint task")
abstract class GenerateBuildIdentity : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val variantName: Property<String>

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(variantName.get().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        val root = projectRoot.get().asFile
        sourceFiles.files.filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { source ->
                val path = source.relativeTo(root).invariantSeparatorsPath
                val content = source.readBytes()
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(content.size.toString().toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(content)
            }
        val buildId = digest.digest().take(6).joinToString("") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
        }
        val output = outputDirectory.file("values/build_identity.xml").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """<resources>
                <string name="app_build_id" translatable="false">$buildId</string>
            </resources>"""
        )
        logger.lifecycle("PegDown Build: $buildId (source fingerprint)")
    }
}

androidComponents.onVariants { variant ->
    val identity = tasks.register<GenerateBuildIdentity>(
        "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}BuildIdentity"
    ) {
        projectRoot.set(rootProject.layout.projectDirectory)
        variantName.set(variant.name)
        sourceFiles.from(fileTree(projectDir) {
            include("build.gradle.kts", "proguard-rules.pro", "gradle.lockfile", "src/**")
            exclude("src/test/**", "src/androidTest/**", "src/testFixtures/**", "src/journeysTest/**")
        })
        sourceFiles.from(rootProject.fileTree(rootProject.projectDir) {
            include("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle.lockfile",
                "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties")
        })
        // Reprint the current identity on every build; unchanged inputs produce unchanged resources.
        outputs.upToDateWhen { false }
    }
    variant.sources.res?.addGeneratedSourceDirectory(identity, GenerateBuildIdentity::outputDirectory)
}

android {
    namespace = "com.dpm.pegdown"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dpm.pegdown"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.all {
            it.jvmArgs("-XX:+EnableDynamicAgentLoading", "-Dnet.bytebuddy.experimental=true")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    dependenciesInfo {
        includeInBundle = false
        includeInApk = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}