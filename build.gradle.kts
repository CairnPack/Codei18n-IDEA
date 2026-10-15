plugins { java }

group = "dev.codei18n"
version = "0.1.0"

// Compile against an installed IDEA SDK; no IDE download or third-party runtime dependencies.
val ideaHome = providers.gradleProperty("ideaHome").orElse(providers.environmentVariable("IDEA_HOME"))
dependencies {
    compileOnly(fileTree("${ideaHome.orNull}/lib") { include("*.jar") })
    testImplementation(fileTree("${ideaHome.orNull}/lib") { include("*.jar") })
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.codei18n.idea.MappingIndexTest"
}
tasks.check { dependsOn("coreTest") }
// This project uses the executable regression suite above instead of a JUnit engine.
tasks.test { enabled = false }
tasks.register<Zip>("buildPlugin") {
    dependsOn(tasks.jar, tasks.check)
    archiveFileName = "codei18n-idea-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into("codei18n-idea/lib") { from(tasks.jar) }
}
