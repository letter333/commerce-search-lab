plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "org.letter33"
version = "0.0.1-SNAPSHOT"
description = "commerce-search-lab"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("tools.jackson.core:jackson-databind")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.test {
	useJUnitPlatform {
		excludeTags("seed")
	}
	inputs.files("docs/catalog-50.json", "docs/search-scenarios-30.json")
}

val verifySeeds = tasks.register<Test>("verifySeeds") {
	group = "verification"
	description = "Checks the development seed data contract without Spring or Elasticsearch."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("seed")
	}
	inputs.files("docs/catalog-50.json", "docs/search-scenarios-30.json")
	systemProperty("seed.directory", layout.projectDirectory.dir("docs").asFile.absolutePath)
}

tasks.check {
	dependsOn(verifySeeds)
}

tasks.register<JavaExec>("evaluateSearch") {
	group = "verification"
	description = "Runs development scenarios against a running search API and saves evaluation reports."
	dependsOn(tasks.testClasses, verifySeeds)
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass = "org.letter33.commercesearchlab.evaluation.SearchEvaluationCli"
	javaLauncher = javaToolchains.launcherFor(java.toolchain)
	workingDir = layout.projectDirectory.asFile
}
