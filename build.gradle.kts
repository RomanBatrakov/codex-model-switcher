plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "ru.roman"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    buildSearchableOptions = false
}
