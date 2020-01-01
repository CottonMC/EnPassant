plugins {
    `java-library`
    `java-gradle-plugin`
    kotlin("jvm") version "1.3.61"
    `maven-publish`
    id("com.jfrog.artifactory") version "4.9.0"
}

group = "io.github.cottonmc"
base.archivesBaseName = "en-passant"
version = "0.0.36"

val privateConfig = rootProject.file("private.gradle")
if (privateConfig.exists()) {
    apply(from = privateConfig)
}

apply(from = "artifactory.gradle")

repositories {
    mavenCentral()
    maven {
        name = "CottonMC"
        url = uri("http://server.bbkr.space:8081/artifactory/libs-release")
    }
    maven {
        name = "FabricMC"
        url = uri("https://maven.fabricmc.net")
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    api("net.sf.proguard:proguard-gradle:6.2.2")
    implementation("io.github.cottonmc:proguard-mappings-parser:1.3.0")
    implementation("blue.endless:jankson:1.2.0")

    implementation("net.fabricmc:fabric-loom:0.2.6-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.8.6")
}

gradlePlugin {
    plugins.create("en-passant") {
        id = "${project.group}.en-passant"
        implementationClass = "io.github.cottonmc.enpassant.EnPassant"
    }
}

val sourcesJar = tasks.create<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

afterEvaluate {
    publishing.publications.getByName<MavenPublication>("pluginMaven") {
        artifactId = "en-passant"
        artifact(sourcesJar)
    }
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}
