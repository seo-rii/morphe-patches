import org.jetbrains.kotlin.gradle.dsl.JvmTarget

group = "app.morphe"

patches {
    about {
        name = "YouTube + Music WireGuard preview (unofficial)"
        description = "Experimental local fork of Morphe Patches; includes opt-in WireGuard proxies for YouTube and YouTube Music"
        source = "Local source archive; see README-WIREGUARD.md for the upstream revision"
        author = "Local prototype; upstream contributions by MorpheApp"
        contact = "na"
        website = "https://morphe.software"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")
val jamApk = providers.gradleProperty("jamApk")
val jamOutput = providers.gradleProperty("jamOutput")

// Resolve local composite build projects (smali, patcher) to jars for compilation,
// same as the runtime classpath. Otherwise, both the classes directory and the jar
// are passed to D8 by buildAndroid, and it fails with "Classpath type already present".
configurations.compileClasspath {
    attributes {
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.morphe.patches.library)

    patchListGeneratorClasspath(libs.gson)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.protobuf.javalite)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
    val buildWireGuardNative = register<Exec>("buildWireGuardNative") {
        description = "Builds both pinned Android WireGuard payloads from source"
        group = "build"
        workingDir(rootProject.projectDir)
        commandLine("bash", rootProject.file("scripts/build-wireguard-android.sh"))
        // The standalone script can build to an isolated directory for comparison.
        // Gradle always builds exactly the files declared below and packaged into the bundle.
        environment("WIREGUARD_NATIVE_OUTPUT", rootProject.file("native/wireguard/build/android"))
        inputs.files(rootProject.fileTree("native/wireguard") {
            exclude("build/**", "vendor/**", ".build/**")
        })
        inputs.file(rootProject.file("scripts/build-wireguard-android.sh"))
        inputs.property("goBinary", providers.environmentVariable("WIREGUARD_GO_BIN").getOrElse("go"))
        inputs.property("ndkDirectory", providers.environmentVariable("ANDROID_NDK_HOME")
            .orElse(providers.environmentVariable("ANDROID_NDK_ROOT")).getOrElse("sdk-default"))
        outputs.files(listOf("arm64-v8a", "x86_64").map { abi ->
            rootProject.file("native/wireguard/build/android/$abi/libmorphewireguard.so")
        })
    }

    val compileWireGuardBundleCheck = register<JavaCompile>("compileWireGuardBundleCheck") {
        description = "Compiles the no-APK WireGuard bundle loader smoke test"
        source(rootProject.file("tests/wireguard-bundle/WireGuardBundleSmoke.java"))
        classpath = configurations.runtimeClasspath.get()
        destinationDirectory.set(layout.buildDirectory.dir("wireguard-bundle-check/classes"))
        options.release.set(17)
    }

    register<JavaExec>("verifyWireGuardBundleLoading") {
        description = "Loads the built bundle through Morphe without patching an APK"
        group = "verification"
        dependsOn("buildAndroid", compileWireGuardBundleCheck)
        // Do not include main.output: the WireGuard patch must load from the archive itself.
        classpath = files(compileWireGuardBundleCheck.flatMap { it.destinationDirectory },
                configurations.runtimeClasspath)
        mainClass.set("WireGuardBundleSmoke")
        doFirst {
            setArgs(listOf(project.tasks.named("jar", Jar::class.java)
                    .get().archiveFile.get().asFile.absolutePath))
        }
    }

    processResources {
        dependsOn(buildWireGuardNative)
        from(rootProject.layout.projectDirectory.dir("native/wireguard/build/android")) {
            into("wireguard")
            include("*/libmorphewireguard.so")
        }
        from(rootProject.layout.projectDirectory.file("NOTICE")) {
            into("wireguard/licenses")
            rename { "MORPHE-NOTICE.txt" }
        }
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            into("wireguard/licenses")
            rename { "MORPHE-LICENSE.txt" }
        }
        from(rootProject.layout.projectDirectory.dir("native/wireguard/licenses")) {
            into("wireguard/licenses/dependencies")
        }
        doLast {
            val licenses = destinationDir.resolve("wireguard/licenses")
            val paths = licenses.walkTopDown().filter { it.isFile && it.name != "INDEX" }
                .map { it.relativeTo(licenses).invariantSeparatorsPath }.sorted().toList()
            licenses.resolve("INDEX").writeText(paths.joinToString("\n", postfix = "\n"))
        }
    }

    test {
        useJUnitPlatform()
        dependsOn(":extensions:youtube:generateReleaseProto", ":extensions:youtube:compileReleaseJavaWithJavac")
        jamApk.orNull?.let { systemProperty("jamApk", it) }
        // The Android-boundary tests load release bytecode in isolated class loaders.
        // Declare those actual classes as inputs so runtime changes invalidate cached results.
        inputs.dir(rootProject.file(
            "extensions/youtube/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
        )).withPropertyName("queueRuntimeClasses").withNormalizer(ClasspathNormalizer::class)
        inputs.file(rootProject.file(
            "extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/OpenVideosFullscreenHookPatch.java"
        )).withPropertyName("queueFullscreenBoundarySource")
        inputs.files(
            rootProject.file("extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/queue/QueueSaveActions.java"),
            rootProject.file("extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/queue/QueueSaveFeedback.java"),
            rootProject.file("extensions/youtube/src/main/proto/app/morphe/extension/youtube/innertube/playlist_queue.proto"),
            rootProject.file("extensions/youtube/src/main/proto/app/morphe/extension/youtube/innertube/icon.proto")
        ).withPropertyName("queueSaveBoundarySources")
    }

    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        // Only the compiled patches and the raw string resources are needed. Using the full
        // main runtime classpath would run processResources, which builds and minifies all extensions.
        classpath = files(
            sourceSets["main"].output.classesDirs,
            configurations.runtimeClasspath,
            layout.projectDirectory.dir("src/main/resources"),
        )
        mainClass.set("app.morphe.patches.util.resource.CheckStringResourcesKt")
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }

    register<JavaExec>("validateJam") {
        description = "Patches a supplied YouTube Music APK for Jam validation and optional device testing"
        group = "verification"

        dependsOn("testClasses")
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.morphe.patches.music.interaction.jam.JamDeviceBuildKt")

        doFirst {
            val apk = jamApk.orNull
                ?: throw GradleException("validateJam requires -PjamApk=/absolute/path/to/ytm.apk")
            setArgs(listOfNotNull(apk, jamOutput.orNull))
        }
    }

    register<JavaExec>("validatePlaylistQueue") {
        description = "Applies Native playlist queue to a stock APK and verifies it with Android SDK tools"
        group = "verification"
        dependsOn("testClasses")
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.morphe.patches.youtube.interaction.nativeplaylistqueue.PlaylistQueueApkBuildKt")
        doFirst {
            val apk = providers.gradleProperty("playlistQueueApk").orNull
                ?: throw GradleException("validatePlaylistQueue requires -PplaylistQueueApk=/absolute/path/youtube.apk")
            val mode = providers.gradleProperty("playlistQueueMode").getOrElse("standalone")
            val output = providers.gradleProperty("playlistQueueOutput").orNull
            setArgs(listOfNotNull(apk, mode, output))
        }
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
