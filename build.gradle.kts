plugins {
    java
    kotlin("jvm") version "2.2.10"
    id("com.gradleup.shadow") version "9.0.2"
    id("xyz.wagyourtail.jvmdowngrader") version "1.3.5"
}

group = "pictologic"
version = "1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven("https://maven.xpdustry.com/mindustry")
    maven("https://www.jitpack.io")
}

val mindustryVersion = "v159.7"
val archivesName = "pictologic2"

dependencies {
    compileOnly("com.github.Anuken.Arc:arc-core:$mindustryVersion")
    compileOnly("com.github.anuken.mindustry:core:$mindustryVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}

tasks.shadowJar {
    from("mod.hjson") {
        filter { if (it.startsWith("version")) "version: $version" else it }
    }
    if (file("assets").exists()) {
        from("assets/") {
            include("**")
        }
    }
    minimize()
}

tasks.shadeDowngradedApi {
    archiveFileName.set("${archivesName}Desktop.jar")
}

tasks.register<Copy>("deploy") {
    dependsOn("shadeDowngradedApi")
    from(layout.buildDirectory.file("libs/${archivesName}Desktop.jar"))
    into(File(System.getenv("APPDATA") + "/Mindustry/mods"))
}
