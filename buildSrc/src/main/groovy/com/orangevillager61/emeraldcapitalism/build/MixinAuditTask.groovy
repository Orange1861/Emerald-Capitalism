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
import java.security.MessageDigest
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
    abstract RegularFileProperty getMinecraftArtifact()

    @Internal
    abstract Property<String> getTargetVersion()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void audit() {
        Path repository = null
        Path buildRoot = null
        Path reportRoot = null
        String stage = 'initialize audit'
        boolean reportWritten = false
        try {
            stage = 'resolve audit paths'
            repository = repositoryDirectory.get().asFile.toPath().toAbsolutePath().normalize()
            buildRoot = buildOutputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
            Path auditRoot = buildRoot.resolve('mixin-audit')
            reportRoot = buildRoot.resolve('reports').resolve('mixin-audit')
            assertBuildChild(buildRoot, auditRoot)
            assertBuildChild(buildRoot, reportRoot)
            stage = 'clean audit outputs'
            deleteTree(auditRoot)
            deleteTree(reportRoot)
            Files.createDirectories(auditRoot)
            Files.createDirectories(reportRoot)

            stage = 'load audit manifest'
            Map manifest = readJson(manifestFile.get().asFile.toPath())
            List<Map> mixinConfigs = readMixinConfigs(repository, manifest, mixinConfigFile.get().asFile.toPath())
            Set<String> configuredMixins = mixinConfigs.collectMany { it.names as List<String> } as Set<String>
            Set<String> serverMixins = mixinConfigs.findAll { it.side == 'server' }
                    .collectMany { it.names as List<String> } as Set<String>
            List<Map> units = (manifest.units ?: []) as List<Map>
            Set<String> discoveredTests = discoverGameTests(repository.resolve('src/main/java'))
            validateManifest(repository, manifest, mixinConfigs, configuredMixins, units, discoveredTests)

            Path minecraftArtifactPath = getMinecraftArtifact().get().asFile.toPath().toAbsolutePath().normalize()
            stage = 'validate Minecraft artifact fingerprint input'
            MixinAuditTask.sha256(minecraftArtifactPath)

            stage = 'build audit classpath'
            List<String> classpath = buildClasspath(repository, buildRoot)
            List<Map> results = []
            stage = 'run baseline variant'
            Map baseline = runVariant(repository, auditRoot, 'baseline', [], classpath, serverMixins)
            baseline.classification = baseline.completed && baseline.allRequiredPassed && baseline.exitCode == 0 &&
                    baseline.mixinEvidenceValid
                    ? 'BASELINE_PASSED' : 'BASELINE_FAILED'
            if (baseline.classification == 'BASELINE_FAILED') {
                baseline.reason = baseline.mixinEvidenceValid
                        ? 'The fresh baseline reached the GameTest completion marker but required tests failed.'
                        : 'Required server mixin application evidence was missing or disagreed with the requested audit variant.'
            }
            results.add(baseline)

            if (baseline.classification == 'BASELINE_PASSED') {
                stage = 'run subtractive audit variants'
                units.findAll { (it.auditMode ?: 'gametest') == 'gametest' }.each { Map unit ->
                    Map result = runVariant(repository, auditRoot, unit.id as String,
                            (unit.mixins as List).collect { it as String }, classpath, serverMixins)
                    Set<String> mappedTests = (unit.tests as List).collect { (it as String).toLowerCase(Locale.ROOT) } as Set
                    Set<String> failures = (result.failedTests as List).collect {
                        (it as String).toLowerCase(Locale.ROOT)
                    } as Set

                    if (!result.completed || !result.selectorConfirmed || !result.mixinEvidenceValid) {
                        result.classification = 'AUDIT_ERROR'
                        result.reason = !result.selectorConfirmed
                                ? 'The audit selector did not confirm the requested unit.'
                                : !result.mixinEvidenceValid
                                ? 'Required server mixin application evidence was missing or disagreed with the requested audit variant.'
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

            units.findAll { (it.auditMode ?: 'gametest') == 'manual-client' }.each { Map unit ->
                results.add([
                        unit           : unit.id,
                        disabledMixins : (unit.mixins as List).collect { it as String },
                        classification : 'MANUAL_CLIENT',
                        reason         : 'Run the manifest-linked client smoke checks before porting this client hook.',
                        manualChecks   : (unit.manualChecks as List).collect { it as String }
                ])
            }

            stage = 'fingerprint audit inputs'
            String minecraftArtifactSha256 = MixinAuditTask.sha256(minecraftArtifactPath)
            String gitCommit = readGitCommit(repository)
            String javaVersion = readJavaVersion()
            String generatedAt = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
            Map report = [
                    schemaVersion    : 2,
                    generatedAt      : generatedAt,
                    gitCommit        : gitCommit,
                    javaVersion      : javaVersion,
                    manifest         : [path: repository.relativize(manifestFile.get().asFile.toPath()).toString().replace('\\', '/'),
                                        sha256: MixinAuditTask.sha256(manifestFile.get().asFile.toPath())],
                    mixinConfigs     : mixinConfigs.collect {
                        [path: it.path, side: it.side, entryKey: it.entryKey,
                         sha256: MixinAuditTask.sha256(repository.resolve(it.path as String))]
                    },
                    configuredMixins : configuredMixins.toList().sort(),
                    serverMixins     : serverMixins.toList().sort(),
                    discoveredTests  : discoveredTests.toList().sort(),
                    artifacts        : [minecraftArtifact: [path: minecraftArtifactPath.toString(),
                                                            sha256: minecraftArtifactSha256]],
                    results          : results
            ]
            stage = 'write audit report'
            Path jsonReport = reportRoot.resolve('report.json')
            Files.writeString(jsonReport, JsonOutput.prettyPrint(JsonOutput.toJson(report)) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            writeSummary(reportRoot.resolve('summary.txt'), results)
            reportWritten = true

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
        } catch (Throwable failure) {
            if (!reportWritten) {
                writeFailureDiagnostic(reportRoot, repository, buildRoot, stage, failure)
            }
            throw failure
        }
    }

    private static Map readJson(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new GradleException("Required mixin audit input is missing: ${path}")
        }
        return new JsonSlurper().parse(path.toFile(), StandardCharsets.UTF_8.name()) as Map
    }

    private static List<Map> readMixinConfigs(Path repository, Map manifest, Path fallbackConfig) {
        List<Map> entries = (manifest.mixinConfigs ?: [[
                path: manifest.mixinConfig ?: repository.relativize(fallbackConfig).toString(),
                side: 'server',
                entryKey: 'mixins'
        ]]) as List<Map>
        if (entries.isEmpty()) {
            throw new GradleException('Mixin audit manifest must declare at least one mixin config')
        }

        List<Map> configs = []
        Set<String> configPaths = new HashSet<>()
        entries.each { Map entry ->
            String relativePath = (entry.path as String)?.replace('\\', '/')
            String side = entry.side as String
            String entryKey = (entry.entryKey as String) ?: (side == 'client' ? 'client' : 'mixins')
            String expectedEntryKey = side == 'client' ? 'client' : 'mixins'
            if (!relativePath || !(side in ['server', 'client']) || entryKey != expectedEntryKey) {
                throw new GradleException("Invalid mixin config declaration: ${entry}")
            }
            if (!configPaths.add(relativePath)) {
                throw new GradleException("Mixin config is declared more than once: ${relativePath}")
            }
            Path configPath = repository.resolve(relativePath).normalize()
            if (!configPath.startsWith(repository) || !Files.isRegularFile(configPath)) {
                throw new GradleException("Mixin config is missing or outside the repository: ${relativePath}")
            }
            Map config = readJson(configPath)
            List<String> names = (config[entryKey] ?: []) as List<String>
            if (names.isEmpty()) {
                throw new GradleException("Mixin config ${relativePath} has no ${entryKey} entries")
            }
            if (names.any { !(it instanceof String) } || names.size() != names.toSet().size()) {
                throw new GradleException("Mixin config ${relativePath} must contain unique string mixin names")
            }
            configs << [path: relativePath, side: side, entryKey: entryKey, names: names]
        }
        validateLoadedMixinConfigs(repository, configs)
        return configs
    }

    private static void validateLoadedMixinConfigs(Path repository, List<Map> configs) {
        Path metadataPath = repository.resolve('src/main/templates/META-INF/neoforge.mods.toml')
        if (!Files.isRegularFile(metadataPath)) {
            throw new GradleException("Mod metadata is missing; cannot verify loaded mixin configs: ${metadataPath}")
        }

        Set<String> manifestSuffixes = configs.collect {
            String name = new File(it.path as String).name
            int separator = name.indexOf('.')
            if (separator < 1 || separator == name.length() - 1) {
                throw new GradleException("Mixin config path has no usable file name: ${it.path}")
            }
            name.substring(separator + 1)
        } as Set<String>
        Set<String> loadedSuffixes = new LinkedHashSet<>()
        def matcher = Files.readString(metadataPath) =~ /config="\$\{mod_id\}\.([^"]+)"/
        matcher.each { match -> loadedSuffixes.add(match[1] as String) }
        if (loadedSuffixes != manifestSuffixes) {
            throw new GradleException(
                    "Mixin audit configs do not match the configs declared in neoforge.mods.toml: " +
                            "loaded=${loadedSuffixes}, manifest=${manifestSuffixes}")
        }
    }

    private static void validateManifest(Path repository, Map manifest, List<Map> mixinConfigs,
                                         Set<String> configuredMixins, List<Map> units,
                                         Set<String> discoveredTests) {
        if (manifest.schemaVersion != 2) {
            throw new GradleException('gradle/mixin-audit.json must use schemaVersion 2')
        }
        if (units.isEmpty()) {
            throw new GradleException('Mixin audit manifest must declare at least one unit')
        }

        Map<String, Map> configsByPath = mixinConfigs.collectEntries { [(it.path as String): it] }
        Set<String> clientSmokeIds = units.any { ((it.auditMode as String) ?: 'gametest') == 'manual-client' }
                ? readClientSmokeIds(repository) : new HashSet<>()
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
            String configPath = (unit.config as String)?.replace('\\', '/')
            Map config = configsByPath[configPath]
            if (config == null) {
                throw new GradleException("Mixin audit unit ${id} references unknown mixin config ${unit.config}")
            }
            if ((unit.side as String) != (config.side as String)) {
                throw new GradleException("Mixin audit unit ${id} side does not match ${configPath}")
            }
            String auditMode = (unit.auditMode as String) ?: 'gametest'
            if (!(auditMode in ['gametest', 'manual-client'])) {
                throw new GradleException("Mixin audit unit ${id} has unknown auditMode ${auditMode}")
            }
            if (auditMode == 'gametest' && config.side != 'server') {
                throw new GradleException("Client mixin unit ${id} must use auditMode=manual-client")
            }
            if (auditMode == 'manual-client') {
                if (config.side != 'client') {
                    throw new GradleException("Manual-client mixin unit ${id} must use a client mixin config")
                }
                List<String> manualChecks = (unit.manualChecks ?: []) as List<String>
                if (manualChecks.isEmpty()) {
                    throw new GradleException("Client mixin unit ${id} must declare manualChecks")
                }
                Set<String> unknownManualChecks = new LinkedHashSet<>(manualChecks)
                unknownManualChecks.removeAll(clientSmokeIds)
                if (!unknownManualChecks.isEmpty()) {
                    throw new GradleException("Client mixin unit ${id} references unknown smoke checks: ${unknownManualChecks}")
                }
            }
            ['invariant', 'target', 'injectionPoint', 'supportedEventAlternative'].each { String field ->
                if (!((unit[field] as String)?.trim())) {
                    throw new GradleException("Mixin audit unit ${id} must declare ${field}")
                }
            }
            Set<String> unknownUnitMixins = new LinkedHashSet<>(mixins)
            unknownUnitMixins.removeAll(config.names as Set<String>)
            if (!unknownUnitMixins.isEmpty()) {
                throw new GradleException("Mixin audit unit ${id} names mixins outside ${configPath}: ${unknownUnitMixins}")
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
        Set<String> configured = new LinkedHashSet<>(configuredMixins)
        Set<String> missing = new LinkedHashSet<>(configured)
        missing.removeAll(assignedSet)
        Set<String> unknown = new LinkedHashSet<>(assignedSet)
        unknown.removeAll(configured)
        List<String> duplicates = assigned.countBy { it }.findAll { key, count -> count != 1 }.keySet().toList()
        if (!missing.isEmpty() || !unknown.isEmpty() || !duplicates.isEmpty()) {
            throw new GradleException(
                    "Mixin audit assignments must match the live config exactly; missing=${missing}, unknown=${unknown}, duplicates=${duplicates}")
        }

        List<Path> mixinSources = [
                repository.resolve('src/main/java/com/orangevillager61/emeraldcapitalism/mixin'),
                repository.resolve('src/main/java/com/orangevillager61/emeraldcapitalism/client/mixin')
        ]
        List<String> optionalHooks = []
        mixinSources.findAll { Files.isDirectory(it) }.each { Path mixinSource ->
            Files.walk(mixinSource).withCloseable { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.java') }.forEach { Path file ->
                    String source = Files.readString(file)
                    if (source =~ /require\s*=\s*0/) {
                        optionalHooks.add(repository.relativize(file).toString())
                    }
                }
            }
        }
        if (!optionalHooks.isEmpty()) {
            Set<String> fallbackTests = units.collectMany {
                ((it.optionalFallback?.diagnosticTests ?: []) as List).collect { it as String }
            } as Set<String>
            if (fallbackTests.isEmpty() || !fallbackTests.every { discoveredTests.contains(it.toLowerCase(Locale.ROOT)) }) {
                throw new GradleException("Optional mixin hooks require a documented fallback and diagnostic GameTest: ${optionalHooks}")
            }
        }
    }

    private static Set<String> readClientSmokeIds(Path repository) {
        Map manifest = readJson(repository.resolve('gradle/client-smoke.json'))
        Set<String> ids = new HashSet<>()
        ((manifest.renderers ?: []) + (manifest.screens ?: []) + (manifest.checks ?: [])).each { Map entry ->
            String id = entry.id as String
            if (!id || !ids.add('client-smoke.' + id)) {
                throw new GradleException("Client smoke manifest contains a duplicate or empty id: ${id}")
            }
        }
        return ids
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

    private List<String> buildClasspath(Path repository, Path buildRoot) {
        List<String> classpath = [
                buildRoot.resolve('classes/java/main').toString(),
                repository.resolve('core/build/classes/java/main').toString(),
                buildRoot.resolve('resources/main').toString(),
                repository.resolve('core/build/resources/main').toString(),
                repository.resolve('src/main/resources').toString(),
                minecraftArtifact.get().asFile.toPath().toAbsolutePath().toString()
        ]
        classpath.addAll(Files.readAllLines(legacyClasspathFile.get().asFile.toPath()).findAll { !it.isBlank() })
        return classpath
    }

    private Map runVariant(Path repository, Path auditRoot, String unit, List<String> disabledMixins,
                           List<String> classpath, Set<String> serverMixins) {
        Path variantDirectory = auditRoot.resolve(unit)
        Files.createDirectories(variantDirectory)
        copyTree(repository.resolve('gameteststructures'), variantDirectory.resolve('gameteststructures'))
        Path consoleLog = variantDirectory.resolve('console.log')

        String modFolders = [
                buildOutputDirectory.get().asFile.toPath().resolve('classes/java/main'),
                buildOutputDirectory.get().asFile.toPath().resolve('resources/main'),
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
        Map<String, Boolean> mixinDecisions = parseMixinDecisions(text, unit)
        Set<String> expectedApplied = new LinkedHashSet<>(serverMixins)
        expectedApplied.removeAll(disabledMixins as Set<String>)
        Set<String> missingMixinDecisions = new TreeSet<>(serverMixins)
        missingMixinDecisions.removeAll(mixinDecisions.keySet())
        Set<String> unappliedRequiredMixins = new TreeSet<>(expectedApplied.findAll {
            mixinDecisions[it] != Boolean.TRUE
        })
        Set<String> disabledMixinsStillApplied = new TreeSet<>((disabledMixins as Set<String>).findAll {
            mixinDecisions[it] == Boolean.TRUE
        })
        boolean mixinEvidenceValid = missingMixinDecisions.isEmpty() && unappliedRequiredMixins.isEmpty() &&
                disabledMixinsStillApplied.isEmpty()
        return [
                unit              : unit,
                disabledMixins    : disabledMixins,
                exitCode          : exitCode,
                completed         : completed,
                allRequiredPassed : allRequiredPassed,
                selectorConfirmed : selectorConfirmed,
                mixinDecisions    : mixinDecisions,
                missingMixinDecisions: missingMixinDecisions.toList(),
                unappliedRequiredMixins: unappliedRequiredMixins.toList(),
                disabledMixinsStillApplied: disabledMixinsStillApplied.toList(),
                mixinEvidenceValid: mixinEvidenceValid,
                failedTests       : failedTests,
                directory         : variantDirectory.toString(),
                log               : latestLog.toString(),
                logSha256         : MixinAuditTask.sha256(Files.isRegularFile(latestLog) ? latestLog : consoleLog)
        ]
    }

    private static Map<String, Boolean> parseMixinDecisions(String text, String unit) {
        Map<String, Boolean> decisions = new LinkedHashMap<>()
        Pattern pattern = Pattern.compile('MIXIN_AUDIT_MIXIN unit=([A-Za-z0-9-]+) mixin=([A-Za-z0-9_$]+) applied=(true|false)')
        def matcher = pattern.matcher(text)
        while (matcher.find()) {
            if (matcher.group(1) == unit) {
                decisions[matcher.group(2)] = matcher.group(3) == 'true'
            }
        }
        decisions
    }

    static String sha256(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new GradleException("Cannot fingerprint missing audit input: ${path}")
        }
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path))
        digest.collect { String.format('%02x', it & 0xff) }.join()
    }

    static Map createFailureDiagnostic(String target, String stage, Throwable failure,
                                       String gitCommit, String javaVersion, Path artifact) {
        StringWriter trace = new StringWriter()
        failure.printStackTrace(new PrintWriter(trace))
        [
                schemaVersion: 1,
                type        : 'mixin-audit-failure',
                generatedAt : new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"),
                target      : target,
                stage       : stage,
                exception   : [
                        type      : failure.class.name,
                        message   : failure.message ?: failure.toString(),
                        stackTrace: trace.toString()
                ],
                gitCommit   : gitCommit,
                javaVersion : javaVersion,
                input       : [
                        type  : 'minecraft-artifact',
                        path  : artifact?.toString(),
                        exists: artifact != null && Files.isRegularFile(artifact)
                ]
        ]
    }

    static Path writeFailureDiagnosticArtifact(Path reportRoot, Map diagnostic) {
        Files.createDirectories(reportRoot)
        Path diagnosticPath = reportRoot.resolve('audit-failure.json')
        Files.writeString(diagnosticPath, JsonOutput.prettyPrint(JsonOutput.toJson(diagnostic)) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        diagnosticPath
    }

    private void writeFailureDiagnostic(Path reportRoot, Path repository, Path buildRoot,
                                        String stage, Throwable failure) {
        if (reportRoot == null) {
            logger.error("Mixin audit failed during ${stage}; no report directory was available", failure)
            return
        }
        try {
            Files.createDirectories(reportRoot)
            Path artifact = null
            try {
                artifact = minecraftArtifact.getOrNull()?.asFile?.toPath()?.toAbsolutePath()?.normalize()
            } catch (Throwable ignored) {
                // The property itself may be unavailable while reporting a configuration failure.
            }
            Map diagnostic = createFailureDiagnostic(targetValue(buildRoot), stage, failure,
                    safeGitCommit(repository), safeJavaVersion(), artifact)
            Path diagnosticPath = writeFailureDiagnosticArtifact(reportRoot, diagnostic)
            logger.error("Mixin audit failed before writing a valid report; diagnostic: ${diagnosticPath}", failure)
        } catch (Throwable diagnosticFailure) {
            logger.error("Mixin audit failed during ${stage}, and its diagnostic artifact could not be written", diagnosticFailure)
        }
    }

    private String targetValue(Path buildRoot) {
        try {
            String configured = targetVersion.getOrNull()
            if (configured?.trim()) {
                return configured.trim()
            }
        } catch (Throwable ignored) {
            // Fall back to the target directory when task configuration is incomplete.
        }
        buildRoot?.parent?.fileName?.toString() ?: 'unknown'
    }

    private String safeGitCommit(Path repository) {
        if (repository == null) {
            return null
        }
        try {
            return readGitCommit(repository)
        } catch (Throwable ignored) {
            return null
        }
    }

    private String safeJavaVersion() {
        try {
            return readJavaVersion()
        } catch (Throwable ignored) {
            return null
        }
    }

    private static String readGitCommit(Path repository) {
        Process process = new ProcessBuilder('git', 'rev-parse', 'HEAD')
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start()
        String output = process.inputStream.getText(StandardCharsets.UTF_8.name()).trim()
        if (process.waitFor() != 0 || output.isEmpty()) {
            throw new GradleException("Could not identify the audited Git commit: ${output}")
        }
        output
    }

    private String readJavaVersion() {
        Process process = new ProcessBuilder(javaExecutable.get(), '-version')
                .redirectErrorStream(true)
                .start()
        String output = process.inputStream.getText(StandardCharsets.UTF_8.name()).trim()
        if (process.waitFor() != 0 || output.isEmpty()) {
            throw new GradleException("Could not identify the audit Java runtime: ${output}")
        }
        output.readLines().find { it.contains('version') } ?: output.readLines().first()
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
