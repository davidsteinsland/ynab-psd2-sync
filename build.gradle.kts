import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    kotlin("jvm") version "2.3.21"
}

group = "com.github.davidsteinsland.ynab_psd2_sync"

val logbackClassicVersion = "1.5.32"
val logbackEncoderVersion = "9.0"
val jacksonVersion = "3.1.3"

dependencies {
    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")
    api("tools.jackson.module:jackson-module-kotlin:$jacksonVersion")
}

application {
    mainClass.set("com.github.davidsteinsland.ynab_psd2_sync.enablebanking.MainKt")
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
}
java {
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("skipped", "failed")
        }
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
