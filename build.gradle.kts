import org.gradle.kotlin.dsl.assign

plugins {
    application
    kotlin("jvm") version "1.8.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.google.cloud.tools.jib") version "3.3.1"
}

group = "me.theminecoder"
version = "1.0"

repositories {
    mavenCentral()
    maven { url = uri("https://nexus.outadoc.fr/repository/public") }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    implementation("io.ktor:ktor-client-core:2.1.0")
    implementation("io.ktor:ktor-client-cio:2.1.0")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("com.github.alexdlaird:java-ngrok:2.0.0")

    implementation("org.litote.kmongo:kmongo-coroutine-native:3.12.2")
    implementation("fr.outadoc.mastodonk:mastodonk-core:+")
    implementation("club.minnced:discord-webhooks:0.8.2")
}

kotlin {
    jvmToolchain(11)
}

application {
    applicationName = "Checker"
    mainClass = "me.theminecoder.blockedservers.CheckerKt"
}

jib {
    from {
        image = "openjdk:17"
    }
    to {
        image = "ghcr.io/theminecoder/blockedservers-checker:latest"
    }
    container {
        workingDirectory = "/apprun"
    }
}