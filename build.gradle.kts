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
	implementation("co.elastic.clients:elasticsearch-java:9.4.5")
	implementation("tools.jackson.core:jackson-databind")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.test {
	useJUnitPlatform {
		excludeTags("seed", "elasticsearch")
	}
	inputs.files("docs/catalog-50.json", "docs/search-scenarios-30.json")
}

val integrationTest = tasks.register<Test>("integrationTest") {
	group = "verification"
	description = "Checks the configured client against a real Elasticsearch engine."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("elasticsearch")
	}
	// Engine state is external to Gradle's input snapshot.
	outputs.upToDateWhen { false }
	outputs.cacheIf { false }
	failOnNoDiscoveredTests = true
}

val verifyIntegrationTestExecution = tasks.register("verifyIntegrationTestExecution") {
	description = "Rejects integration verification without an actual, non-skipped test run."
	doLast {
		val test = integrationTest.get()
		check(test.state.executed && !test.state.noSource && !test.state.skipped) {
			"integrationTest did not execute; NO-SOURCE/skipped tasks are not verification."
		}
		val reports = fileTree(test.reports.junitXml.outputLocation) { include("TEST-*.xml") }
		val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
		val executed = reports.files.sumOf {
			val suite = factory.newDocumentBuilder().parse(it).documentElement
			suite.getAttribute("tests").toInt() - suite.getAttribute("skipped").toInt()
		}
		check(executed > 0) { "integrationTest must execute real engine tests; empty/skipped suites are not verification." }
	}
}

integrationTest.configure { finalizedBy(verifyIntegrationTestExecution) }

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
