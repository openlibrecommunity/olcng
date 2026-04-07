plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.subscription"
version = "1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.tencent:mmkv:1.3.9")
}

application {
    mainClass.set("AddSubscriptionKt")
}

kotlin {
    jvmToolchain(17)
}
