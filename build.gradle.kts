plugins {
    java
}

group = "com.afterlife"
version = "0.1.0"

java {
    toolchain {
        // Paper 26.2 requires Java 25 (the Pterodactyl container runs yolks:java_25).
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://jitpack.io")
}

// Runtime libraries are resolved by the server through plugin.yml `libraries:`;
// keep the versions there in sync with these.
val hikariVersion = "6.3.0"
val flywayVersion = "11.8.2"
val mariadbVersion = "3.5.3"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.84-stable")
    compileOnly("net.luckperms:api:5.5")
    // Non-transitive: WorldGuard's strict Gson constraints clash with paper-api's
    // transitive graph; we only need these APIs at compile time.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.17") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.18") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.18") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }

    compileOnly("com.zaxxer:HikariCP:$hikariVersion")
    compileOnly("org.flywaydb:flyway-core:$flywayVersion")
    compileOnly("org.flywaydb:flyway-mysql:$flywayVersion")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")

    testImplementation("io.papermc.paper:paper-api:26.2.build.84-stable")
    testImplementation("com.zaxxer:HikariCP:$hikariVersion")
    testImplementation("org.flywaydb:flyway-core:$flywayVersion")
    testImplementation("org.flywaydb:flyway-mysql:$flywayVersion")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:mariadb:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "hikariVersion" to hikariVersion,
        "flywayVersion" to flywayVersion,
        "mariadbVersion" to mariadbVersion,
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform {
        if (System.getenv("AFTERLIFE_IT") == null) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<Copy>("deployToServer") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into("../plugins")
    rename { "AfterLifeRP.jar" }
}
