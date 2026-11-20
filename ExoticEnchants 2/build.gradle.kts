plugins {
    java
}

group = "com.exoticenchants"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API only. Vault is used through reflection, SQLite is downloaded by Paper at startup
    // (see "libraries" in plugin.yml), so nothing else is needed to compile.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") {
        expand("version" to v)
    }
}

tasks.jar {
    archiveBaseName.set("ExoticEnchants")
}

// Dependency-free tests for the registry and roll engine:  gradle coreTest
tasks.register<JavaExec>("coreTest") {
    group = "verification"
    description = "Runs the registry/roll engine self tests"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.exoticenchants.core.CoreSelfTest")
}

// Shop purchase flow, effect rules and effect formulas:  gradle logicTest
tasks.register<JavaExec>("logicTest") {
    group = "verification"
    description = "Runs the purchase/effect logic self tests"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.exoticenchants.logic.LogicSelfTest")
}

tasks.named("check") {
    dependsOn("coreTest", "logicTest")
}
