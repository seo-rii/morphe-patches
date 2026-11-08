import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.protobuf)
}

configure<LibraryExtension> {
    namespace = "app.morphe.extension.shared.youtube"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.morphe.extensions.library)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.gson)
    implementation(libs.protobuf.javalite)
    implementation(project(":extensions:shared:library"))
    compileOnly(project(":extensions:shared-youtube:stub"))
}

// Boundary tests compile the production sources against isolated host stubs.
// Reading those sources at test runtime must invalidate a cached test result.
tasks.withType<Test>().configureEach {
    inputs.files(listOf(
        "wireguard/ConfigText.java",
        "wireguard/WireGuardConfigStore.java",
        "wireguard/WireGuardProxy.java",
        "patches/NetworkProxyPatch.java",
    ).map { file("src/main/java/app/morphe/extension/shared/$it") })
        .withPropertyName("wireguardBoundarySources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
