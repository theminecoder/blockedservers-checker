plugins {
    kotlin("jvm") version "1.8.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://nexus.outadoc.fr/repository/public") }
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    implementation("io.ktor:ktor-client-core:2.1.0")
    implementation("io.ktor:ktor-client-cio:2.1.0")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("com.github.alexdlaird:java-ngrok:2.0.0")

    implementation("org.litote.kmongo:kmongo-coroutine-native:3.12.2")
    implementation("fr.outadoc.mastodonk:mastodonk-core:+")
    implementation("club.minnced:discord-webhooks:0.8.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}