import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

val kotlinVersion = "2.4.10"

group = "dev.reactant"

plugins {
    `java-library`
    `maven-publish`
    signing
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
    id("com.palantir.git-version") version "5.0.0"
}

group = "dev.reactant"
val versionDetails = extra["versionDetails"] as groovy.lang.Closure<*>
val details = versionDetails() as com.palantir.gradle.gitversion.VersionDetails
version = details.lastTag + if (!details.isCleanTag && !details.lastTag.endsWith("-SNAPSHOT")) "-SNAPSHOT" else ""
val isRelease = details.isCleanTag && !details.lastTag.endsWith("-SNAPSHOT")

println("Preparing build ${project.name} $version")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-jvm-default=enable")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

repositories {
    mavenCentral()
    maven { url = URI.create("https://hub.spigotmc.org/nexus/content/repositories/snapshots") }
    maven { url = URI.create("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = URI.create("https://oss.sonatype.org/content/repositories/releases/") }
    maven { url = URI.create("https://repo.codemc.org/repository/maven-public") }
}

dependencies {
    listOf(
        "stdlib-jdk8",
        "reflect"
//            "script-util",
//            "script-runtime",
//            "compiler-embeddable",
//            "scripting-compiler"
    ).forEach { api(kotlin(it, kotlinVersion)) }

    implementation("org.bstats:bstats-bukkit:3.2.1")

    api("io.reactivex.rxjava3:rxjava:3.1.12")
    api("io.reactivex.rxjava3:rxkotlin:3.0.1")
    api("io.github.classgraph:classgraph:4.8.189")

    api("com.google.code.gson:gson:2.14.0")
    api("org.yaml:snakeyaml:2.6")
    api("com.moandjiezana.toml:toml4j:0.7.2")

    api("info.picocli:picocli:4.7.7")
    api("org.mariadb.jdbc:mariadb-java-client:3.5.10")

    api("org.apache.logging.log4j:log4j-core:2.26.1")

    api("com.squareup.retrofit2:retrofit:3.0.0")
    api("com.squareup.retrofit2:adapter-rxjava3:3.0.0")
    api("com.squareup.retrofit2:converter-gson:3.0.0")

    api("net.sourceforge.cssparser:cssparser:0.9.30")

    api("org.javassist:javassist:3.32.0-GA")

    compileOnly("org.spigotmc:spigot-api:26.2-R0.1-SNAPSHOT")
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it is Sign }) {
        allprojects {
            extra["signing.keyId"] = System.getenv("SIGNING_KEYID")
        }
    }
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    dependsOn(JavaPlugin.CLASSES_TASK_NAME)
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    relocate("org.bstats", "dev.reactant.reactant.core")
    relocate("okhttp3", "dev.reactant.reactant.okhttp3")
    relocate("okio", "dev.reactant.reactant.okio")
    relocate("cssparser", "dev.reactant.reactant.cssparser")
    relocate("javassist", "dev.reactant.reactant.javassist")
}

val dokkaJar = tasks.register<Jar>("dokkaJar") {
    archiveClassifier.set("dokka")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

val deployPlugin = tasks.register<Copy>("deployPlugin") {
    System.getenv("PLUGIN_DEPLOY_PATH")?.let {
        from(shadowJar)
        into(it)
    }
}

val build = (tasks["build"] as Task).apply {
    arrayOf(
        sourcesJar,
        shadowJar,
        deployPlugin
    ).forEach { dependsOn(it) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(sourcesJar.get())
            artifact(javadocJar.get())
            artifact(dokkaJar.get())
            artifact(shadowJar)

            groupId = group.toString()
            artifactId = project.name
            version = version

            pom {
                name.set("Reactant")
                description.set("An elegant plugin framework for spigot")
                url.set("https://reactant.dev")
                licenses {
                    license {
                        name.set("GPL-3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
                scm {
                    connection.set("scm:git:git@gitlab.com:reactant/reactant.git")
                    url.set("https://gitlab.com/reactant/reactant/")
                }

                developers {
                    developer {
                        id.set("setako")
                        name.set("Setako")
                        organization.set("Reactant Dev Team")
                        organizationUrl.set("https://gitlab.com/reactant")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
            val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            credentials {
                username = System.getenv("SONATYPE_USERNAME")
                password = System.getenv("SONATYPE_PASSWORD")
            }
        }
    }
}

if (isRelease) {
    signing {
        ext["signing.keyId"] = findProperty("signingKeyId") as String?
        val signingKey = findProperty("signingKey") as String?
        val signingPassword = findProperty("signingPassword") as String?
        useInMemoryPgpKeys(signingKey?.replace("\\n", "\n"), signingPassword)
        sign(publishing.publications["maven"])
    }
}
