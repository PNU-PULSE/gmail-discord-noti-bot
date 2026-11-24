plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.8"
    id("io.spring.dependency-management") version "1.1.7"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    inputs.property("postgresIntegrationEnabled", providers.environmentVariable("TEST_DATABASE_URL").isPresent)
}

if (providers.gradleProperty("adminUi").isPresent) {
    tasks.processResources {
        from("admin-ui/dist") {
            into("static")
        }
        doFirst {
            check(file("admin-ui/dist/index.html").isFile) {
                "Build admin-ui with npm ci and npm run build first"
            }
        }
    }
}
