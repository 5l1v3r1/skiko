import kotlin.text.capitalize

plugins {
    kotlin("multiplatform") version "1.4-M3"
    `cpp-library`
    `maven-publish`
}
group = "org.jetbrains.skiko"
version = "0.1-SNAPSHOT"


val skiaDir = System.getenv("SKIA_DIR") ?: "/Users/igotti/compose/skija/third_party/skia"
val hostOs = System.getProperty("os.name")
val target = when {
    hostOs == "Mac OS X" -> "macos"
    hostOs == "Linux" -> "linux"
    hostOs.startsWith("Win") -> "windows"
    else -> throw Error("Unknown os $hostOs")
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://dl.bintray.com/kotlin/kotlin-eap")
    }
}
kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        withJava()
    }

    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val jvmMain by getting {
            //kotlin.srcDirs.add(file("$projectDir/../skija/java_delombok"))
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                compileOnly("org.projectlombok:lombok:1.18.12")
                compileOnly("org.jetbrains:annotations:19.0.0")
            }

        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        val nativeMain by getting
        val nativeTest by getting
    }
}

// See https://docs.gradle.org/current/userguide/cpp_library_plugin.html.
tasks.withType(CppCompile::class.java).configureEach {
    source.from(
            fileTree("$projectDir/../skija/src/main/cc"),
            fileTree("$projectDir/src/jvmMain/cpp")
    )
    val jdkHome = System.getenv("JAVA_HOME")
    compilerArgs.addAll(listOf(
        "-I$jdkHome/include",
        "-I$skiaDir",
        "-I$skiaDir/include",
        "-I$skiaDir/include/core",
        "-I$skiaDir/include/gpu",
        "-I$skiaDir/include/effects",
        "-I$skiaDir/include/pathops",
        "-I$skiaDir/modules/skparagraph/include",
        "-I$skiaDir/modules/skshaper/include",
        "-I$skiaDir/third_party/externals/harfbuzz/src",
        "-DSK_ALLOW_STATIC_GLOBAL_INITIALIZERS=1",
        "-DSK_FORCE_DISTANCE_FIELD_TEXT=0",
        "-DSK_GAMMA_APPLY_TO_A8",
        "-DSK_GAMMA_SRGB",
        "-DSK_SCALAR_TO_FLOAT_EXCLUDED",
        "-DSK_SUPPORT_GPU=1",
        "-DSK_SUPPORT_OPENCL=0",
        "-Dskija_EXPORTS",
        "-DNDEBUG"
    ))
    when (target) {
        "macos" -> {
            compilerArgs.addAll(
                listOf(
                    "-std=c++14",
                    "-O3",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    "-I$jdkHome/include/darwin",
                    "-DSK_BUILD_FOR_MAC"
                )
            )
        }
        "linux" -> {
            compilerArgs.addAll(
                listOf(
                    "-std=c++14",
                    "-O3",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    "-I$jdkHome/include/linux",
                    "-DSK_BUILD_FOR_LINUX",
                    "-DSK_R32_SHIFT=16"
                )
            )
        }
        "windows" -> {
            compilerArgs.addAll(
                listOf(
                    "-I$jdkHome/include/win32",
                    "-DSK_BUILD_FOR_WIN",
                    "-D_CRT_SECURE_NO_WARNINGS",
                    "-D_HAS_EXCEPTIONS=0",
                    "-DWIN32_LEAN_AND_MEAN",
                    "-DNOMINMAX",
                    "-DSK_GAMMA_APPLY_TO_A8",
                    "/utf-8",
                    "/O2",
                    "/GR-" // no-RTTI.
            )
            )
        }
    }
}

tasks.withType(LinkSharedLibrary::class.java).configureEach {
    when (target) {
        "macos" -> {
            linkerArgs.addAll(
                listOf(
                    "-dead_strip",
                    // "-framework", "OpenGL",
                    // "-framework", "Cocoa",
                    // "-framework", "Foundation",
                    // "-framework", "QuartzCore"
                    "-framework", "CoreGraphics",
                    "-framework", "CoreFoundation",
                    "-framework", "CoreGraphics",
                    "-framework", "CoreServices",
                    "-framework", "CoreText"
                )
            )
        }
        "linux" -> {
            linkerArgs.addAll(
                listOf(
                    "-lfontconfig"
                )
            )
        }
        "windows" -> {
            linkerArgs.addAll(
                listOf(
                    "user32.lib",
                    "opengl32.lib"
                )
            )
        }
    }
}

library {
    linkage.addAll(listOf(Linkage.SHARED))
    targetMachines.addAll(listOf(machines.macOS.x86_64, machines.linux.x86_64, machines.windows.x86_64))
    baseName.set("skiko")

    dependencies {
        implementation(fileTree("$skiaDir/out/Release-x64").matching {
            if (target == "windows")
                include("**.lib")
            else
                include("**.a")
        })
    }
}
val skikoJarFile = "$buildDir/skiko-$target.jar"
project.tasks.register<Jar>("skikoJar") {
    archiveBaseName.set("skiko-$target")
    archiveFileName.set(skikoJarFile)
    from(kotlin.jvm().compilations["main"].output.allOutputs)
    from(project.tasks.named("linkRelease${target.capitalize()}").get().outputs.files.filter { it.isFile })
}

val skikoArtifact = artifacts.add("archives", file(skikoJarFile)) {
    type = "jar"
    group = "skiko"
    builtBy("skikoJar")
}

project.tasks.register<JavaExec>("run") {
    dependsOn("skikoJar")
    main = "org.jetbrains.skiko.MainKt"
    classpath = files(skikoJarFile)
}


publishing {
    publications {
        create<MavenPublication>("skiko") {
            groupId = "org.jetbrains.skiko"
            artifactId = "skiko-$target"
            artifact(skikoArtifact)
            pom {
                name.set("Skiko")
                description.set("Kotlin Skia bindings")
                url.set("http://www.github.com/JetBrains/skiko")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}