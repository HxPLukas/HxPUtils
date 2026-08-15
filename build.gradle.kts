import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    `maven-publish`
}

group = property("maven_group") as String
version = property("mod_version") as String

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    maven("https://maven.terraformersmc.com/")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    property("commodore_version").let {
        implementation("com.github.stivais:Commodore:$it")
        include("com.github.stivais:Commodore:$it")
    }

    compileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")

    property("minecraft_lwjgl_version").let { lwjglVersion ->
        implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion")
        include("org.lwjgl:lwjgl-nanovg:$lwjglVersion")

        listOf("windows", "linux", "macos", "macos-arm64").forEach { os ->
            implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-$os")
            include("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-$os")
        }
    }

    compileOnly("maven.modrinth:iris:${property("iris")}")

    "localRuntime"("me.djtheredstoner:DevAuth-fabric:${property("devauth_version")}")
    "localRuntime"("org.apache.httpcomponents:httpclient:4.5.14")
}

loom {
    accessWidenerPath.set(file("src/main/resources/hxpaddons.accesswidener"))

    runConfigs.named("client") {
        isIdeConfigGenerated = true
        vmArg("-Dmixin.debug.export=true")
        vmArg("-XX:+AllowEnhancedClassRedefinition")
        vmArg("-XX:+IgnoreUnrecognizedVMOptions")
    }

    runConfigs.named("server") {
        isIdeConfigGenerated = false
    }
}

afterEvaluate {
    loom.runs.named("client") {
        vmArg("-javaagent:${configurations.compileClasspath.get().find { it.name.contains("sponge-mixin") }}")
    }
}

tasks {
    processResources {
        filesMatching("fabric.mod.json") {
            expand(project.properties)
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
            freeCompilerArgs.add("-Xlambdas=class") //Commodore
        }
    }

    compileJava {
        sourceCompatibility = "25"
        targetCompatibility = "25"
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "de.hxp"
            artifactId = "HxPAddons"
            version = version
            from(components["java"])
        }
    }
}
