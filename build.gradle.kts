plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "ru.roman"
version = "0.5.1"

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
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

intellijPlatform {
    buildSearchableOptions = false
}
