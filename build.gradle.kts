plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.subscription"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
}

application {
    mainClass.set("AddSubscriptionKt")
}

kotlin {
    jvmToolchain(17)
}
