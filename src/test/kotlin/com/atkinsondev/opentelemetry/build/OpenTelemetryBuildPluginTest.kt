package com.atkinsondev.opentelemetry.build

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import org.awaitility.Awaitility.await
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThanOrEqualTo
import strikt.assertions.isNotNull
import java.io.File
import java.nio.file.Path

@WireMockTest
class OpenTelemetryBuildPluginTest {

    @Test
    fun `should send data to OpenTelemetry with HTTP`(wmRuntimeInfo: WireMockRuntimeInfo, @TempDir projectRootDirPath: Path) {
        val wiremockBaseUrl = wmRuntimeInfo.httpBaseUrl

        val buildFileContents = """
            buildscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            
            plugins {
                id "com.atkinsondev.opentelemetry-build"
                id "org.jetbrains.kotlin.jvm" version "1.7.10"
            }
            
            repositories {
                mavenCentral()
            }
            
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.9.0"))
                testImplementation("org.junit.jupiter:junit-jupiter")
            }
            
            test {
                useJUnitPlatform()
            }
            
            openTelemetryBuild {
                endpoint = '$wiremockBaseUrl/otel'
                exporterMode = com.atkinsondev.opentelemetry.build.OpenTelemetryExporterMode.HTTP
            }
        """.trimIndent()

        File(projectRootDirPath.toFile(), "build.gradle").writeText(buildFileContents)

        createSrcDirectoryAndClassFile(projectRootDirPath)
        createTestDirectoryAndClassFile(projectRootDirPath)

        stubFor(post("/otel").willReturn(ok()))

        val buildResult = GradleRunner.create()
            .withProjectDir(projectRootDirPath.toFile())
            .withArguments("test", "--info", "--stacktrace")
            .withPluginClasspath()
            .build()

        expectThat(buildResult.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        await().untilAsserted {
            val otelRequests = findAll(postRequestedFor(urlEqualTo("/otel")))

            val otelRequestBodies = otelRequests.map { it.bodyAsString }

            val rootSpanName = "gradle-build"
            expectThat(otelRequestBodies.find { it.contains(rootSpanName) }).isNotNull()
        }

        await().untilAsserted {
            val otelRequests = findAll(postRequestedFor(urlEqualTo("/otel")))

            val otelRequestBodies = otelRequests.map { it.bodyAsString }

            val testSpanName = ":test"
            expectThat(otelRequestBodies.find { it.contains(testSpanName) }).isNotNull()
        }
    }

    @Test
    fun `should send data to OpenTelemetry with GRPC`(wmRuntimeInfo: WireMockRuntimeInfo, @TempDir projectRootDirPath: Path) {
        val wiremockBaseUrl = wmRuntimeInfo.httpBaseUrl

        val buildFileContents = """
            buildscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            
            plugins {
                id "com.atkinsondev.opentelemetry-build"
                id "org.jetbrains.kotlin.jvm" version "1.7.10"
            }
            
            repositories {
                mavenCentral()
            }
            
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.9.0"))
                testImplementation("org.junit.jupiter:junit-jupiter")
            }
            
            test {
                useJUnitPlatform()
            }
            
            openTelemetryBuild {
                endpoint = '$wiremockBaseUrl/otel'
                exporterMode = com.atkinsondev.opentelemetry.build.OpenTelemetryExporterMode.GRPC
            }
        """.trimIndent()

        File(projectRootDirPath.toFile(), "build.gradle").writeText(buildFileContents)

        createSrcDirectoryAndClassFile(projectRootDirPath)
        createTestDirectoryAndClassFile(projectRootDirPath)

        stubFor(post("/opentelemetry.proto.collector.trace.v1.TraceService/Export").willReturn(ok()))

        val buildResult = GradleRunner.create()
            .withProjectDir(projectRootDirPath.toFile())
            .withArguments("test", "--info", "--stacktrace")
            .withPluginClasspath()
            .build()

        expectThat(buildResult.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        await().untilAsserted {
            val otelRequests = findAll(postRequestedFor(urlEqualTo("/opentelemetry.proto.collector.trace.v1.TraceService/Export")))

            expectThat(otelRequests.size).isGreaterThanOrEqualTo(1)
        }
    }
}
