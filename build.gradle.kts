plugins {
    `java-library`
    `java-gradle-plugin`
    kotlin("jvm") version "1.3.61"
    `maven-publish`
    // TODO: Set up Artifactory
}

group = "io.github.cottonmc"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        name = "CottonMC"
        setUrl("http://server.bbkr.space:8081/artifactory/libs-release")
    }
    maven {
        name = "FabricMC"
        url = uri("https://maven.fabricmc.net")
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    api("net.sf.proguard:proguard-gradle:6.2.2")
    implementation("io.github.cottonmc:proguard-mappings-parser:1.2.0")
    implementation("blue.endless:jankson:1.2.0")

    implementation("net.fabricmc:fabric-loom:0.2.6-SNAPSHOT")
}

gradlePlugin {
    plugins.create("en-passant") {
        id = "${project.group}.en-passant"
        implementationClass = "io.github.cottonmc.enpassant.EnPassant"
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
