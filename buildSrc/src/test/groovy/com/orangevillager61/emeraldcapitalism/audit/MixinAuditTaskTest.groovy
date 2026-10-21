package com.orangevillager61.emeraldcapitalism.audit

import com.orangevillager61.emeraldcapitalism.build.MixinAuditTask
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows

/** Permanent coverage for structured pre-report audit failures. */
class MixinAuditTaskTest {

    @TempDir
    Path tempDir

    @Test
    void missingFingerprintInputBecomesStructuredFailure() {
        def missing = tempDir.resolve('neoforge-missing.jar')
        GradleException original = assertThrows(GradleException) {
            MixinAuditTask.sha256(missing)
        }

        Map diagnostic = MixinAuditTask.createFailureDiagnostic(
                '1.21.1', 'validate Minecraft artifact fingerprint input', original,
                'fixture-commit', 'openjdk version "21.0.9"', missing)

        assertEquals('mixin-audit-failure', diagnostic.type)
        assertEquals('1.21.1', diagnostic.target)
        assertEquals('validate Minecraft artifact fingerprint input', diagnostic.stage)
        assertEquals('org.gradle.api.GradleException', diagnostic.exception.type)
        assertEquals('Cannot fingerprint missing audit input: ' + missing, diagnostic.exception.message)
        assertEquals('fixture-commit', diagnostic.gitCommit)
        assertEquals('openjdk version "21.0.9"', diagnostic.javaVersion)
        assertEquals(missing.toString(), diagnostic.input.path)
        assertFalse(diagnostic.input.exists)

        Path written = MixinAuditTask.writeFailureDiagnosticArtifact(tempDir.resolve('reports'), diagnostic)
        Map persisted = new JsonSlurper().parse(written.toFile()) as Map
        assertEquals('1.21.1', persisted.target)
        assertEquals('validate Minecraft artifact fingerprint input', persisted.stage)
        assertEquals(original.message, persisted.exception.message)
        assertFalse(persisted.input.exists)
    }
}
