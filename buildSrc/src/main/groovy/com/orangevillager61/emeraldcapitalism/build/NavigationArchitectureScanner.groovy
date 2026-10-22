package com.orangevillager61.emeraldcapitalism.build

import java.util.regex.Pattern

/**
 * Pure source-scanning rules used by verifyNavigationArchitecture and its unit tests.
 * This deliberately scans source text rather than attempting to infer ownership from
 * variable names or receiver names.
 */
final class NavigationArchitectureScanner {

    static final List<String> NAVIGATION_OPERATIONS = ['moveTo', 'createPath', 'stop', 'getPath'].asImmutable()
    static final List<String> TRANSITIONAL_REFERENCE_NAMES = [
            'MovementRequestManager', 'VillagerMovementRequestBinding'
    ].asImmutable()

    private static final Pattern CHAINED_NAVIGATION_OPERATION = Pattern.compile(
            '(?s)(?:\\b[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*)*\\s*\\.\\s*)?' +
                    'getNavigation\\s*\\(\\s*\\)\\s*\\.\\s*(moveTo|createPath|stop|getPath)\\s*\\(')
    private static final Pattern TYPED_NAVIGATION_ALIAS = Pattern.compile(
            '\\b(?:PathNavigation|GroundPathNavigation)(?:\\s*<[^;=,>()]+>)?\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b')
    private static final Pattern VAR_NAVIGATION_ALIAS = Pattern.compile(
            '\\bvar\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*)?getNavigation\\s*\\(\\s*\\)')
    private static final Pattern NAVIGATION_ALIAS_OPERATION_TEMPLATE = Pattern.compile(
            '\\b%s\\s*\\.\\s*(moveTo|createPath|stop|getPath)\\s*\\(')
    private static final Pattern GOAL_SELECTOR_REGISTRATION = Pattern.compile(
            '\\b[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*addGoal\\s*\\(')
    private static final Pattern BRAIN_MEMORY_MUTATION = Pattern.compile(
            '(?s)(?:setMemory|eraseMemory)\\s*\\(\\s*(?:[A-Za-z0-9_$.]+\\s*,\\s*)?MemoryModuleType\\.[A-Z0-9_]+')
    private static final Pattern PATH_MEMORY_MUTATION = Pattern.compile(
            '(?s)(?:setMemory|eraseMemory)\\s*\\([^;]{0,240}\\bMemoryModuleType\\.PATH\\b')
    private static final Pattern PATH_MEMORY_ALIAS = Pattern.compile(
            '\\b(?:MemoryModuleType(?:\\s*<[^>]+>)?|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*MemoryModuleType\\.PATH\\b')

    private NavigationArchitectureScanner() {
    }

    static Map scanSource(String rawSource) {
        String source = stripJavaLiterals(rawSource ?: '')
        Set<String> aliases = navigationAliases(source)
        Map<String, Integer> operations = NAVIGATION_OPERATIONS.collectEntries { [(it): 0] }

        def record = { Pattern pattern ->
            def matcher = pattern.matcher(source)
            while (matcher.find()) {
                String operation = matcher.group(1)
                operations[operation] = (operations[operation] ?: 0) + 1
            }
        }
        record(CHAINED_NAVIGATION_OPERATION)
        aliases.each { String alias ->
            Pattern aliasPattern = Pattern.compile(String.format(
                    NAVIGATION_ALIAS_OPERATION_TEMPLATE.pattern(), Pattern.quote(alias)))
            record(aliasPattern)
        }

        Map<String, Integer> transitionalReferences = TRANSITIONAL_REFERENCE_NAMES.collectEntries { String name ->
            [(name): countMatches(Pattern.compile('\\b' + Pattern.quote(name) + '\\b'), source)]
        }
        Set<String> pathAliases = [] as Set<String>
        def pathAliasMatcher = PATH_MEMORY_ALIAS.matcher(source)
        while (pathAliasMatcher.find()) pathAliases.add(pathAliasMatcher.group(1))
        boolean pathMemoryMutation = PATH_MEMORY_MUTATION.matcher(source).find() || pathAliases.any { String alias ->
            Pattern aliasMutation = Pattern.compile('(?s)(?:setMemory|eraseMemory)\\s*\\([^;]{0,240}\\b' +
                    Pattern.quote(alias) + '\\b')
            aliasMutation.matcher(source).find()
        }

        [
                source                         : source,
                navigationAliases              : aliases,
                operations                     : operations,
                directNavigationCount          : operations.values().sum() as Integer,
                transitionalReferences         : transitionalReferences,
                goalSelectorRegistrations      : countMatches(GOAL_SELECTOR_REGISTRATION, source),
                brainMemoryMutation            : BRAIN_MEMORY_MUTATION.matcher(source).find(),
                pathMemoryMutation             : pathMemoryMutation,
                pathMemoryAliases              : pathAliases
        ]
    }

    /**
     * Validates one source against exact manifest entries. This is intentionally
     * suitable for in-memory fixtures so negative coverage cannot disappear with a
     * deleted temporary source file.
     */
    static List<String> validateSource(String sourcePath, String rawSource, Map options = [:]) {
        Map scan = scanSource(rawSource)
        List<String> errors = []
        String normalizedPath = normalize(sourcePath)

        Map<String, Map> transitionalEntries = ((options.transitionalReferences ?: []) as List<Map>)
                .findAll { normalize(it.sourcePath as String) == normalizedPath }
                .collectEntries { Map entry -> [((entry.referenceName ?: '') as String), entry] }
        TRANSITIONAL_REFERENCE_NAMES.each { String name ->
            int actual = (scan.transitionalReferences[name] ?: 0) as Integer
            Map entry = transitionalEntries[name]
            if (actual > 0 && entry == null) {
                errors << "unauthorized ${name} reference outside frozen transitional inventory: ${sourcePath}"
            } else if (entry != null) {
                int expected = entry.expectedOccurrences instanceof Number ? entry.expectedOccurrences as Integer : -1
                if (actual != expected) {
                    errors << "${entry.id ?: name} expected ${expected} ${name} references in ${sourcePath}, found ${actual}"
                }
            }
        }

        List<Map> directEntries = ((options.directNavigationReferences ?: []) as List<Map>)
                .findAll { normalize(it.sourcePath as String) == normalizedPath }
        if (scan.directNavigationCount > 0 && directEntries.isEmpty()) {
            errors << "direct navigation/path operation outside frozen alias-independent inventory: ${sourcePath}"
        } else if (!directEntries.isEmpty()) {
            if (directEntries.size() > 1) {
                errors << "duplicate direct navigation reference inventory entry: ${sourcePath}"
            }
            Map entry = directEntries[0]
            Map expectedOperations = (entry.expectedOperations ?: [:]) as Map
            NAVIGATION_OPERATIONS.each { String operation ->
                if (!expectedOperations.containsKey(operation)) {
                    errors << "${entry.id ?: sourcePath}.expectedOperations must document ${operation}"
                } else if ((expectedOperations[operation] as Integer) != (scan.operations[operation] as Integer)) {
                    errors << "${entry.id ?: sourcePath} expected ${expectedOperations[operation]} ${operation} references in ${sourcePath}, found ${scan.operations[operation]}"
                }
            }
        }

        List<Map> goalEntries = ((options.goalSelectorReferences ?: []) as List<Map>)
                .findAll { normalize(it.sourcePath as String) == normalizedPath }
        if (scan.goalSelectorRegistrations > 0 && goalEntries.isEmpty()) {
            errors << "GoalSelector registration outside frozen alias-independent inventory: ${sourcePath}"
        } else if (!goalEntries.isEmpty()) {
            if (goalEntries.size() > 1) errors << "duplicate GoalSelector reference inventory entry: ${sourcePath}"
            Map entry = goalEntries[0]
            int expected = entry.expectedOccurrences instanceof Number ? entry.expectedOccurrences as Integer : -1
            if (scan.goalSelectorRegistrations != expected) {
                errors << "${entry.id ?: sourcePath} expected ${expected} GoalSelector registrations in ${sourcePath}, found ${scan.goalSelectorRegistrations}"
            }
        }

        Set<String> memoryMutationSources = ((options.memoryMutationSources ?: []) as Collection)
                .collect { normalize(it as String) } as Set<String>
        if (scan.brainMemoryMutation && !memoryMutationSources.contains(normalizedPath)) {
            errors << "Brain memory mutation outside inventory/allowlist: ${sourcePath}"
        }
        Set<String> pathMemoryWriters = ((options.pathMemoryWriters ?: []) as Collection)
                .collect { normalize(it as String) } as Set<String>
        if (scan.pathMemoryMutation && !pathMemoryWriters.contains(normalizedPath)) {
            errors << "PATH memory mutation outside declared writer: ${sourcePath}"
        }
        errors
    }

    static List<String> validateOwnerReferences(Set<String> canonicalOwnerIds,
                                                 Map<String, String> ownerCategories,
                                                 List<Map> sourceOwnership,
                                                 List<Map> transitionalReferences,
                                                 List<Map> directReferences,
                                                 List<Map> goalReferences) {
        List<String> errors = []
        Set<String> ownedPaths = [] as Set<String>
        sourceOwnership.each { Map entry ->
            String path = normalize(entry.path as String)
            if (!ownedPaths.add(path)) errors << "duplicate source ownership assignment: ${path}"
            String ownerId = (entry.ownerId ?: '') as String
            if (!canonicalOwnerIds.contains(ownerId)) errors << "sourceOwnership ${path}.ownerId is not an inventoried owner: ${ownerId}"
        }
        def check = { String category, List<Map> entries, Set<String> allowedCategories ->
            entries.each { Map entry ->
                String id = (entry.id ?: '') as String
                String ownerId = (entry.ownerId ?: '') as String
                if (!canonicalOwnerIds.contains(ownerId)) {
                    errors << "${category} ${id}.ownerId is not an inventoried owner: ${ownerId}"
                } else if (ownerCategories[ownerId] !in allowedCategories) {
                    errors << "${category} ${id}.ownerId ${ownerId} has unrelated owner category ${ownerCategories[ownerId]}"
                }
            }
        }
        check('transitional reference', transitionalReferences,
                ['request-owner', 'transitional-caller', 'navigation'] as Set<String>)
        check('direct navigation reference', directReferences,
                ['navigation', 'registrar', 'exception'] as Set<String>)
        check('GoalSelector reference', goalReferences,
                ['registrar', 'exception'] as Set<String>)
        errors
    }

    static List<String> validateAllowlistedSources(Collection<String> allowlistedSources,
                                                    Collection<String> inventorySources,
                                                    Collection<String> exceptionSources) {
        Set<String> allowed = allowlistedSources.collect { normalize(it) } as Set<String>
        Set<String> inventory = inventorySources.collect { normalize(it) } as Set<String>
        Set<String> exceptions = exceptionSources.collect { normalize(it) } as Set<String>
        (allowed - inventory - exceptions).collect { "allowlisted source has no inventory explanation: ${it}" }
    }

    static List<String> validateWantedItemProducer(Map producer,
                                                   Collection<String> exceptionSources,
                                                   Collection<String> requiredSources) {
        List<String> errors = []
        Set<String> producerSources = ((producer.sourcePaths ?: []) as Collection)
                .collect { normalize(it as String) } as Set<String>
        Set<String> exceptions = exceptionSources.collect { normalize(it as String) } as Set<String>
        Set<String> required = requiredSources.collect { normalize(it as String) } as Set<String>
        Set<String> overlap = producerSources.intersect(exceptions)
        if (!overlap.isEmpty()) errors << 'wanted-item-pursuit cannot use a non-villager exception source'
        if (!producerSources.intersect(required).containsAll(required)) {
            (required - producerSources).each { errors << "wanted-item-pursuit must inventory ${it}" }
        }
        String executor = ((producer.current ?: [:]).executor ?: '') as String
        if (!executor.contains('1.21.1') || !executor.contains('1.21.4')) {
            errors << 'wanted-item-pursuit must name the 1.21.1 and 1.21.4 external vanilla executors'
        }
        errors
    }

    /**
     * Validates an explicit producer-to-Brain-behavior relationship. The
     * relationship is structural so a stale prose scheduler cannot silently
     * contradict the registered package/activity owner.
     */
    static List<String> validateRegisteredBehaviorConsistency(Collection<Map> producers,
                                                              Collection<Map> registeredBehaviors) {
        List<String> errors = []
        Map<String, List<Map>> behaviorsById = (registeredBehaviors ?: []).groupBy { Map behavior ->
            (behavior.id ?: '') as String
        }

        (producers ?: []).each { Map producer ->
            String producerId = (producer.id ?: '') as String
            String behaviorId = (producer.registeredBehaviorId ?: '') as String
            if (!behaviorId) return

            List<Map> matches = behaviorsById[behaviorId] ?: []
            if (matches.size() != 1) {
                errors << "${producerId}.registeredBehaviorId ${behaviorId} must resolve to exactly one registered Brain behavior (found ${matches.size()})"
                return
            }

            Map behavior = matches[0]
            String packageOwner = (behavior.owner ?: '') as String
            String registeredActivity = (behavior.targetBrainActivity ?: '') as String
            Map current = (producer.current ?: [:]) as Map
            String schedulerOwner = (current.schedulerOwner ?: '') as String
            String schedulerActivity = (current.schedulerActivity ?: '') as String
            String currentActivity = (current.activity ?: '') as String
            String movementAdapter = (current.movementAdapter ?: '') as String

            if (!packageOwner || !registeredActivity) {
                errors << "${producerId}.registeredBehaviorId ${behaviorId} must have exactly one package/activity owner"
            }
            if (!schedulerOwner.equalsIgnoreCase('Brain')) {
                errors << "${producerId} schedulerOwner must be Brain; registered behavior ${behaviorId} cannot be assigned to another task scheduler"
            }
            if (!schedulerActivity.equalsIgnoreCase(registeredActivity)) {
                errors << "${producerId} scheduler activity ${schedulerActivity ?: '<missing>'} does not match registered behavior ${behaviorId} activity ${registeredActivity ?: '<missing>'}"
            }
            if (!currentActivity.equalsIgnoreCase(registeredActivity)) {
                errors << "${producerId} current activity ${currentActivity ?: '<missing>'} does not match registered behavior ${behaviorId} activity ${registeredActivity ?: '<missing>'}"
            }
            if (movementAdapter && !movementAdapter.toLowerCase(Locale.ROOT).contains('movement adapter')) {
                errors << "${producerId} transitional MovementRequestManager must be classified as a movement adapter, not a second task scheduler"
            }
            if (movementAdapter.toLowerCase(Locale.ROOT).contains('movementrequestmanager') &&
                    schedulerOwner.toLowerCase(Locale.ROOT).contains('movementrequestmanager')) {
                errors << "${producerId} MovementRequestManager is classified as a second scheduler"
            }
        }
        errors
    }

    static List<String> validateFrozenText(String id, Map target, Collection<String> fields = ['activity', 'finality', 'claimScope']) {
        List<String> errors = []
        Pattern ambiguous = Pattern.compile('(?is).*\\b(?:or|when available|until decided|unresolved|provisional)\\b.*')
        fields.each { String field ->
            String value = (target[field] ?: '') as String
            if (ambiguous.matcher(value).matches()) {
                errors << "${id}.target.${field} contains an unfrozen alternative or provisional ownership decision"
            }
        }
        errors
    }

    static String normalize(String path) {
        (path ?: '').replace('\\', '/')
    }

    static int countMatches(Pattern pattern, String source) {
        def matcher = pattern.matcher(source ?: '')
        int count = 0
        while (matcher.find()) count++
        count
    }

    static String stripJavaLiterals(String source) {
        StringBuilder cleaned = new StringBuilder(source.length())
        boolean lineComment = false
        boolean blockComment = false
        boolean stringLiteral = false
        boolean charLiteral = false
        boolean escaped = false
        int index = 0
        while (index < source.length()) {
            char current = source.charAt(index)
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0'
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false
                    cleaned.append('\n')
                } else {
                    cleaned.append(' ')
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false
                    cleaned.append(' ').append(' ')
                    index++
                } else {
                    cleaned.append(current == '\n' ? '\n' : ' ')
                }
            } else if (stringLiteral || charLiteral) {
                cleaned.append(current == '\n' ? '\n' : ' ')
                if (escaped) {
                    escaped = false
                } else if (current == '\\') {
                    escaped = true
                } else if ((stringLiteral && current == '"') || (charLiteral && current == '\'')) {
                    stringLiteral = false
                    charLiteral = false
                }
            } else if (current == '/' && next == '/') {
                lineComment = true
                cleaned.append(' ').append(' ')
                index++
            } else if (current == '/' && next == '*') {
                blockComment = true
                cleaned.append(' ').append(' ')
                index++
            } else if (current == '"') {
                stringLiteral = true
                cleaned.append(' ')
            } else if (current == '\'') {
                charLiteral = true
                cleaned.append(' ')
            } else {
                cleaned.append(current)
            }
            index++
        }
        cleaned.toString()
    }

    private static Set<String> navigationAliases(String source) {
        Set<String> aliases = [] as Set<String>
        def typed = TYPED_NAVIGATION_ALIAS.matcher(source)
        while (typed.find()) aliases.add(typed.group(1))
        def variable = VAR_NAVIGATION_ALIAS.matcher(source)
        while (variable.find()) aliases.add(variable.group(1))
        aliases
    }
}
