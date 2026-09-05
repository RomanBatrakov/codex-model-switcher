plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.romanbatrakov"
version = "1.4.0"

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

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

intellijPlatform {
    buildSearchableOptions = false
}

// Self-signed certificate for plugin signing, kept outside version control
// (see .gitignore). Marketplace re-signs on top regardless, but a signed
// upload avoids the "unsigned plugin" install warning.
val signingCertificateChain = file("signing/chain.crt")
val signingPrivateKey = file("signing/private.pem")
if (signingCertificateChain.exists() && signingPrivateKey.exists()) {
    tasks.signPlugin {
        certificateChainFile.set(signingCertificateChain)
        privateKeyFile.set(signingPrivateKey)
    }
}

// Marketplace permanent token, also kept outside version control (see
// .gitignore). Lets a release ship via `./gradlew publishPlugin` instead of
// the manual web upload form.
val publishTokenFile = file("signing/publish_token.txt")
if (publishTokenFile.exists()) {
    tasks.publishPlugin {
        token.set(publishTokenFile.readText().trim())
    }
}
