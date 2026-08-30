package com.orangevillager61.emeraldcapitalism.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.regex.Pattern

/** Runs the full suite in fresh JVMs while subtracting one declared mixin unit at a time. */
abstract class MixinAuditTask extends DefaultTask {

    private static final String AUDIT_MARKER = 'emeraldcapitalism.mixinAudit'
    private static final String AUDIT_UNIT = 'emeraldcapitalism.mixinAudit.unit'
    private static final String DISABLED_MIXINS = 'emeraldcapitalism.mixinAudit.disabledMixins'

    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @Internal
    abstract DirectoryProperty getBuildOutputDirectory()

    @Internal
    abstract RegularFileProperty getManifestFile()

    @Internal
    abstract RegularFileProperty getMixinConfigFile()

    @Internal
    abstract Property<String> getJavaExecutable()

    @Internal
    abstract RegularFileProperty getVmArgsFile()

    @Internal
    abstract RegularFileProperty getProgramArgsFile()

    @Internal
    abstract RegularFileProperty getLegacyClasspathFile()

    @Internal
    abstract RegularFileProperty getMergedMinecraftJar()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void audit() {
        Path repository = repositoryDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        Path buildRoot = buildOutputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        Path auditRoot = buildRoot.resolve('mixin-audit')
        Path reportRoot = buildRoot.resolve('reports').resolve('mixin-audit')
        assertBuildChild(buildRoot, auditRoot)
        assertBuildChild(buildRoot, reportRoot)
        deleteTree(auditRoot)
        deleteTree(reportRoot)
        Files.createDirectories(auditRoot)
        Files.createDirectories(reportRoot)

        Map manifest = readJson(manifestFile.get().asFile.toPath())
        Map mixinConfig = readJson(mixinConfigFile.get().asFile.toPath())
        List<Map> units = (manifest.units ?: []) as List<Map>
        Set<String> discoveredTests = discoverGameTests(repository.resolve('src/main/java'))
        validateManifest(repository, manifest, mixinConfig, units, discoveredTests)

        List<String> classpath = buildClasspath(repository)
        List<Map> results = []
        Map baseline = runVariant(repository, auditRoot, 'baseline', [], classpath)
        baseline.classification = baseline.completed && baseline.allRequiredPassed && baseline.exitCode == 0
                ? 'BASELINE_PASSED' : 'BASELINE_FAILED'
        results.add(baseline)

        if (baseline.classification == 'BASELINE_PASSED') {
            units.each { Map unit ->
                Map result = runVariant(repository, auditRoot, unit.id as String,
                        (unit.mixins as List).collect { it as String }, classpath)
                Set<String> mappedTests = (unit.tests as List).collect { (it as String).toLowerCase(Locale.ROOT) } as Set
                Set<String> failures = (result.failedTests as List).collect {
                    (it as String).toLowerCase(Locale.ROOT)
                } as Set

                if (!result.completed || !result.selectorConfirmed) {
                    result.classification = 'AUDIT_ERROR'
                    result.reason = !result.selectorConfirmed
                            ? 'The audit selector did not confirm the requested unit.'
                            : 'The fresh GameTest JVM did not reach suite completion.'
                } else if (result.allRequiredPassed && result.exitCode == 0) {
                    result.classification = 'REMOVAL_CANDIDATE'
                    result.reason = 'All required GameTests passed with this unit disabled.'
                } else if (!failures.isEmpty() && mappedTests.containsAll(failures)) {
                    result.classification = 'REQUIRED'
                    result.reason = 'Only manifest-mapped invariant tests failed.'
                } else {
                    result.classification = 'AUDIT_ERROR'
                    Set<String> unexpected = new TreeSet<>(failures)
                    unexpected.removeAll(mappedTests)
                    result.unexpectedFailures = unexpected as List
                    result.reason = failures.isEmpty()
                            ? 'The process failed without reporting required GameTest failures.'
                            : 'Disabling the unit caused failures not mapped by the manifest.'
                }
                results.add(result)
            }
        }

        Map report = [
                schemaVersion   : 1,
                generatedAt     : new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"),
                mixinConfig      : manifest.mixinConfig,
                configuredMixins : mixinConfig.mixins,
                discoveredTests  : discoveredTests.toList().sort(),
                results          : results
        ]
        Path jsonReport = reportRoot.resolve('report.json')
        Files.writeString(jsonReport, JsonOutput.prettyPrint(JsonOutput.toJson(report)) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        writeSummary(reportRoot.resolve('summary.txt'), results)

        logger.lifecycle("Mixin audit report: ${jsonReport}")
        if (baseline.classification == 'BASELINE_FAILED') {
            throw new GradleException("Mixin audit baseline failed; see ${jsonReport}")
        }

        List<Map> review = results.findAll {
            it.classification == 'REMOVAL_CANDIDATE' || it.classification == 'AUDIT_ERROR'
        }
        if (!review.isEmpty()) {
            throw new GradleException("Mixin audit found ${review.size()} unit(s) requiring review; see ${jsonReport}")
        }
    }

    private static Map readJson(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new GradleException("Required mixin audit input is missing: ${path}")
        }
        return new JsonSlurper().parse(path.toFile(), StandardCharsets.UTF_8.name()) as Map
    }

    private static void validateManifest(Path repository, Map manifest, Map mixinConfig, List<Map> units,
                                         Set<String> discoveredTests) {
        if (manifest.schemaVersion != 1) {
            throw new GradleException('gradle/mixin-audit.json must use schemaVersion 1')
        }
        if (units.isEmpty()) {
            throw new GradleException('Mixin audit manifest must declare at least one unit')
        }

        Set<String> configured = new LinkedHashSet<>((mixinConfig.mixins ?: []) as List<String>)
        List<String> assigned = []
        Set<String> unitIds = new HashSet<>()
        units.each { Map unit ->
            String id = unit.id as String
            if (id == null || !(id ==~ /[a-z0-9]+(?:-[a-z0-9]+)*/)) {
                throw new GradleException("Invalid mixin audit unit id: ${id}")
            }
            if (!unitIds.add(id)) {
                throw new GradleException("Duplicate mixin audit unit id: ${id}")
            }
            List<String> mixins = (unit.mixins ?: []) as List<String>
            List<String> tests = (unit.tests ?: []) as List<String>
            if (mixins.isEmpty() || tests.isEmpty()) {
                throw new GradleException("Mixin audit unit ${id} must declare mixins and invariant tests")
            }
            assigned.addAll(mixins)
            tests.each { String test ->
                String normalized = test.toLowerCase(Locale.ROOT)
                if (!discoveredTests.contains(normalized)) {
                    throw new GradleException("Mixin audit unit ${id} references unknown GameTest ${test}")
                }
            }
        }

        Set<String> assignedSet = new LinkedHashSet<>(assigned)
        Set<String> missing = new LinkedHashSet<>(configured)
        missing.removeAll(assignedSet)
        Set<String> unknown = new LinkedHashSet<>(assignedSet)
        unknown.removeAll(configured)
        List<String> duplicates = assigned.countBy { it }.findAll { key, count -> count != 1 }.keySet().toList()
        if (!missing.isEmpty() || !unknown.isEmpty() || !duplicates.isEmpty()) {
            throw new GradleException(
                    "Mixin audit assignments must match the live config exactly; missing=${missing}, unknown=${unknown}, duplicates=${duplicates}")
        }

        Path mixinSource = repository.resolve('src/main/java/com/orangevillager61/emeraldcapitalism/mixin')
        List<String> optionalHooks = []
        Files.walk(mixinSource).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.java') }.forEach { Path file ->
                String source = Files.readString(file)
                if (source =~ /require\s*=\s*0/) {
                    optionalHooks.add(repository.relativize(file).toString())
                }
            }
        }
        if (!optionalHooks.isEmpty()) {
            throw new GradleException("Optional mixin hooks require an explicit fallback audit: ${optionalHooks}")
        }
    }

    private static Set<String> discoverGameTests(Path sourceRoot) {
        Set<String> tests = new TreeSet<>()
        Pattern testPattern = Pattern.compile(
                '@GameTest\\s*\\([^)]*\\)\\s*public\\s+static\\s+void\\s+([A-Za-z0-9_]+)\\s*\\(',
                Pattern.DOTALL)
        Files.walk(sourceRoot).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.java') }.forEach { Path file ->
                String source = Files.readString(file)
                if (source =~ /@GameTestHolder\s*\(/) {
                    String className = file.fileName.toString()
                            .substring(0, file.fileName.toString().length() - '.java'.length())
                            .toLowerCase(Locale.ROOT)
                    def testMatcher = testPattern.matcher(source)
                    while (testMatcher.find()) {
                        tests.add(className + '.' + testMatcher.group(1).toLowerCase(Locale.ROOT))
                    }
                }
            }
        }
        return tests
    }

    private List<String> buildClasspath(Path repository) {
        List<String> classpath = [
                repository.resolve('build/classes/java/main').toString(),
                repository.resolve('core/build/classes/java/main').toString(),
                repository.resolve('build/resources/main').toString(),
                repository.resolve('core/build/resources/main').toString(),
                repository.resolve('src/main/resources').toString(),
                mergedMinecraftJar.get().asFile.toPath().toAbsolutePath().toString()
        ]
        classpath.addAll(Files.readAllLines(legacyClasspathFile.get().asFile.toPath()).findAll { !it.isBlank() })
        return classpath
    }

    private Map runVariant(Path repository, Path auditRoot, String unit, List<String> disabledMixins,
                           List<String> classpath) {
        Path variantDirectory = auditRoot.resolve(unit)
        Files.createDirectories(variantDirectory)
        copyTree(repository.resolve('gameteststructures'), variantDirectory.resolve('gameteststructures'))
        Path consoleLog = variantDirectory.resolve('console.log')

        String modFolders = [
                repository.resolve('build/classes/java/main'),
                repository.resolve('build/resources/main'),
                repository.resolve('core/build/classes/java/main'),
                repository.resolve('core/build/resources/main')
        ].collect { "emeraldcapitalism%%${it}" }.join(File.pathSeparator)

        List<String> command = [
                javaExecutable.get(),
                '-cp', classpath.join(File.pathSeparator),
                "-Dfml.modFolders=${modFolders}",
                "-D${AUDIT_MARKER}=true",
                "-D${AUDIT_UNIT}=${unit}",
                "-D${DISABLED_MIXINS}=${disabledMixins.join(',')}",
                '@' + vmArgsFile.get().asFile.absolutePath,
                '@' + programArgsFile.get().asFile.absolutePath
        ]

        int exitCode
        Files.newOutputStream(consoleLog, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                .withCloseable { output ->
                    exitCode = execOperations.exec { spec ->
                        spec.commandLine(command)
                        spec.workingDir(variantDirectory.toFile())
                        spec.standardOutput = output
                        spec.errorOutput = output
                        spec.ignoreExitValue = true
                    }.exitValue
                }

        Path latestLog = variantDirectory.resolve('logs/latest.log')
        String text = Files.isRegularFile(latestLog)
                ? Files.readString(latestLog, StandardCharsets.UTF_8)
                : Files.readString(consoleLog, StandardCharsets.UTF_8)
        boolean completed = text.contains('GAME TESTS COMPLETE IN')
        boolean allRequiredPassed = text =~ /All \d+ required tests passed/
        boolean selectorConfirmed = text.contains("MIXIN_AUDIT_ACTIVE unit=${unit}")
        List<String> failedTests = parseRequiredFailures(text)
        return [
                unit              : unit,
                disabledMixins    : disabledMixins,
                exitCode          : exitCode,
                completed         : completed,
                allRequiredPassed : allRequiredPassed,
                selectorConfirmed : selectorConfirmed,
                failedTests       : failedTests,
                directory         : variantDirectory.toString(),
                log               : latestLog.toString()
        ]
    }

    private static List<String> parseRequiredFailures(String text) {
        List<String> failures = []
        boolean inRequiredFailures = false
        text.eachLine { String line ->
            if (line =~ /\d+ required tests failed/) {
                inRequiredFailures = true
            } else if (inRequiredFailures && line.contains('====')) {
                inRequiredFailures = false
            } else if (inRequiredFailures) {
                def matcher = (line =~ /\s-\s+([A-Za-z0-9_.:-]+)/)
                if (matcher.find()) {
                    failures.add(matcher.group(1).toLowerCase(Locale.ROOT))
                }
            }
        }
        return failures
    }

    private static void writeSummary(Path path, List<Map> results) {
        StringBuilder summary = new StringBuilder('Mixin audit results' + System.lineSeparator())
        results.each { Map result ->
            summary.append(String.format('%-36s %s', result.unit, result.classification))
                    .append(System.lineSeparator())
            if (result.reason) {
                summary.append('  ').append(result.reason).append(System.lineSeparator())
            }
            if (result.failedTests) {
                summary.append('  failed: ').append(result.failedTests.join(', ')).append(System.lineSeparator())
            }
        }
        Files.writeString(path, summary.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private static void copyTree(Path source, Path destination) {
        if (!Files.exists(source)) {
            return
        }
        Files.walk(source).withCloseable { stream ->
            stream.forEach { Path entry ->
                Path target = destination.resolve(source.relativize(entry).toString())
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private static void deleteTree(Path target) {
        if (!Files.exists(target)) {
            return
        }
        Files.walk(target).withCloseable { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private static void assertBuildChild(Path buildRoot, Path target) {
        if (target == buildRoot || !target.startsWith(buildRoot)) {
            throw new GradleException("Refusing to modify audit path outside the build directory: ${target}")
        }
    }
}
