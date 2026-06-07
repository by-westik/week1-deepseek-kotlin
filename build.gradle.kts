plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "example"
version = "1.0-SNAPSHOT"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("MainKt")
}
