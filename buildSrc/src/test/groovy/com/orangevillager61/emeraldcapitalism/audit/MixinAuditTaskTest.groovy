package com.orangevillager61.emeraldcapitalism.audit

import com.orangevillager61.emeraldcapitalism.build.MixinAuditTask
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.util.jar.JarOutputStream
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    void taskUsesConfiguredDurableArtifactAndWritesCurrentCommitReport() {
        MixinAuditTask task = configureFixtureTask(true)

        GradleException failure = assertThrows(GradleException) {
            task.audit()
        }
        assertTrue(failure.message.contains('requiring review'))

        Path reportPath = tempDir.resolve('build/reports/mixin-audit/report.json')
        assertTrue(Files.isRegularFile(reportPath))
        assertFalse(Files.exists(reportPath.resolveSibling('audit-failure.json')))
        Map report = new JsonSlurper().parse(reportPath.toFile()) as Map
        assertEquals('2', report.schemaVersion.toString())
        assertEquals(git('rev-parse', 'HEAD'), report.gitCommit)
        assertEquals(
                tempDir.resolve('build/moddev/artifacts/neoforge-21.1.219.jar').toAbsolutePath().normalize().toString(),
                report.artifacts.minecraftArtifact.path)
        assertEquals(
                MixinAuditTask.sha256(tempDir.resolve('build/moddev/artifacts/neoforge-21.1.219.jar')),
                report.artifacts.minecraftArtifact.sha256)
        Map baseline = report.results.find { it.unit == 'baseline' } as Map
        assertTrue(baseline.completed)
        assertTrue(baseline.selectorConfirmed)
        assertTrue(baseline.mixinEvidenceValid)
        assertEquals('BASELINE_PASSED', baseline.classification)
        String baselineLog = Files.readString(tempDir.resolve('build/mixin-audit/baseline/console.log'))
        assertTrue(baselineLog.contains('MIXIN_AUDIT_ARTIFACT ' +
                tempDir.resolve('build/moddev/artifacts/neoforge-21.1.219.jar').toAbsolutePath().normalize()))
    }

    @Test
    void taskFailurePreservesOriginalMissingArtifactAndWritesDiagnostic() {
        MixinAuditTask task = configureFixtureTask(false)

        GradleException failure = assertThrows(GradleException) {
            task.audit()
        }
        assertTrue(failure.message.contains('Cannot fingerprint missing audit input'))

        Path reportRoot = tempDir.resolve('build/reports/mixin-audit')
        assertFalse(Files.exists(reportRoot.resolve('report.json')))
        Path diagnosticPath = reportRoot.resolve('audit-failure.json')
        assertTrue(Files.isRegularFile(diagnosticPath))
        Map diagnostic = new JsonSlurper().parse(diagnosticPath.toFile()) as Map
        assertEquals('validate Minecraft artifact fingerprint input', diagnostic.stage)
        assertEquals(failure.message, diagnostic.exception.message)
        assertFalse(diagnostic.input.exists)
    }

    private MixinAuditTask configureFixtureTask(boolean createArtifact) {
        Path repository = tempDir
        Files.createDirectories(repository.resolve('gradle'))
        Files.createDirectories(repository.resolve('src/main/resources'))
        Files.createDirectories(repository.resolve('src/main/templates/META-INF'))
        Files.createDirectories(repository.resolve('src/main/java'))
        Files.createDirectories(repository.resolve('build/classes/java/main'))
        Files.createDirectories(repository.resolve('build/resources/main'))
        Files.createDirectories(repository.resolve('core/build/classes/java/main'))
        Files.createDirectories(repository.resolve('core/build/resources/main'))
        Files.createDirectories(repository.resolve('build/moddev/artifacts'))

        Files.writeString(repository.resolve('src/main/resources/emeraldcapitalism.mixins.json'),
                '{"mixins":["FixtureMixin"]}\n')
        Files.writeString(repository.resolve('src/main/templates/META-INF/neoforge.mods.toml'),
                'config="${mod_id}.mixins.json"\n')
        Files.writeString(repository.resolve('src/main/java/FixtureTests.java'), '''
            @GameTestHolder("fixture")
            public class FixtureTests {
                @GameTest(template = "fixture")
                public static void passes() {}
            }
            ''')

        Files.writeString(repository.resolve('gradle/mixin-audit.json'), '''
            {
              "schemaVersion": 2,
              "mixinConfig": "src/main/resources/emeraldcapitalism.mixins.json",
              "units": [{
                "id": "fixture-unit",
                "config": "src/main/resources/emeraldcapitalism.mixins.json",
                "side": "server",
                "mixins": ["FixtureMixin"],
                "tests": ["fixturetests.passes"],
                "invariant": "Fixture invariant",
                "target": "Fixture target",
                "injectionPoint": "Fixture injection",
                "supportedEventAlternative": "Fixture alternative"
              }]
            }
            ''')
        Files.writeString(repository.resolve('build/moddev/gameTestServerVmArgs.txt'), '')
        Files.writeString(repository.resolve('build/moddev/gameTestServerRunVmArgs.txt'), '')
        Files.writeString(repository.resolve('build/moddev/gameTestServerRunProgramArgs.txt'),
                'com.orangevillager61.emeraldcapitalism.audit.MixinAuditTaskTest$AuditFixtureMain\n')
        Files.writeString(repository.resolve('build/moddev/gameTestServerLegacyClasspath.txt'),
                System.getProperty('java.class.path') + '\n')

        Path artifact = repository.resolve('build/moddev/artifacts/neoforge-21.1.219.jar')
        if (createArtifact) {
            new JarOutputStream(Files.newOutputStream(artifact)).withCloseable { }
        }
        git('init')
        git('config', 'user.email', 'fixture@example.invalid')
        git('config', 'user.name', 'Fixture')
        git('add', '.')
        git('commit', '-m', 'fixture')

        def project = ProjectBuilder.builder().withProjectDir(repository.toFile()).build()
        MixinAuditTask task = project.tasks.create('audit', MixinAuditTask)
        task.repositoryDirectory.set(repository.toFile())
        task.buildOutputDirectory.set(repository.resolve('build').toFile())
        task.manifestFile.set(repository.resolve('gradle/mixin-audit.json').toFile())
        task.mixinConfigFile.set(repository.resolve('src/main/resources/emeraldcapitalism.mixins.json').toFile())
        task.vmArgsFile.set(repository.resolve('build/moddev/gameTestServerRunVmArgs.txt').toFile())
        task.programArgsFile.set(repository.resolve('build/moddev/gameTestServerRunProgramArgs.txt').toFile())
        task.legacyClasspathFile.set(repository.resolve('build/moddev/gameTestServerLegacyClasspath.txt').toFile())
        task.minecraftArtifact.set(artifact.toFile())
        task.targetVersion.set('1.21.1')
        task.javaExecutable.set(javaExecutable())
        task
    }

    private String javaExecutable() {
        Path executable = Path.of(System.getProperty('java.home'), 'bin',
                System.getProperty('os.name').toLowerCase().contains('win') ? 'java.exe' : 'java')
        executable.toString()
    }

    private String git(String... args) {
        List<String> command = ['git'] + args.toList()
        Process process = new ProcessBuilder(command).directory(tempDir.toFile()).redirectErrorStream(true).start()
        String output = process.inputStream.getText('UTF-8').trim()
        if (process.waitFor() != 0) {
            throw new AssertionError("git ${args.join(' ')} failed: ${output}")
        }
        output
    }

    static class AuditFixtureMain {
        static void main(String[] args) {
            String unit = System.getProperty('emeraldcapitalism.mixinAudit.unit', 'baseline')
            String disabled = System.getProperty('emeraldcapitalism.mixinAudit.disabledMixins', '')
            boolean applied = !disabled.split(',').contains('FixtureMixin')
            String expectedArtifact = System.getProperty('user.dir')
                    .replace('build\\mixin-audit\\baseline', 'build\\moddev\\artifacts\\neoforge-21.1.219.jar')
                    .replace('build/mixin-audit/baseline', 'build/moddev/artifacts/neoforge-21.1.219.jar')
            boolean durableArtifactOnClasspath = System.getProperty('java.class.path', '')
                    .split(java.io.File.pathSeparator)
                    .collect { Path.of(it).toAbsolutePath().normalize().toString() }
                    .contains(Path.of(expectedArtifact).toAbsolutePath().normalize().toString())
            if (!durableArtifactOnClasspath) {
                throw new IllegalStateException('durable Minecraft artifact is absent from launched classpath: ' + expectedArtifact)
            }
            System.out.println("MIXIN_AUDIT_ACTIVE unit=${unit}")
            System.out.println("MIXIN_AUDIT_MIXIN unit=${unit} mixin=FixtureMixin applied=${applied}")
            System.out.println("MIXIN_AUDIT_ARTIFACT ${expectedArtifact}")
            System.out.println('========== 1 GAME TESTS COMPLETE IN 1 sec ==========')
            System.out.println('All 1 required tests passed')
        }
    }
}
