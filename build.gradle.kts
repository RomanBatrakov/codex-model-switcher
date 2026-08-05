plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "ru.roman"
version = "0.9.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/IntelliJ IDEA.app")
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
