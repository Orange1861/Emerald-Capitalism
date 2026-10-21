package com.orangevillager61.emeraldcapitalism.architecture

import com.orangevillager61.emeraldcapitalism.build.NavigationArchitectureScanner
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** Permanent adversarial coverage for the Phase 2 source scanner. */
class NavigationArchitectureScannerTest {

    @Test
    void testRejectsNewManagerCaller() {
        assertUnauthorized('MovementRequestManager manager;') { it.transitionalReferences = [] }
    }

    @Test
    void testRejectsNewBindingCaller() {
        assertUnauthorized('VillagerMovementRequestBinding binding;') { it.transitionalReferences = [] }
    }

    @Test
    void testRejectsChainedNavigationMutation() {
        assertUnauthorized('villager.getNavigation().moveTo(target, 1.0D);') { it.directNavigationReferences = [] }
    }

    @Test
    void testRejectsTypedNavigationAliasMutation() {
        assertUnauthorized('PathNavigation nav = villager.getNavigation(); nav.moveTo(target, 1.0D);') {
            it.directNavigationReferences = []
        }
    }

    @Test
    void testRejectsVarNavigationAliasCreation() {
        assertUnauthorized('var nav = villager.getNavigation(); nav.createPath(target, 0);') {
            it.directNavigationReferences = []
        }
    }

    @Test
    void testRejectsFieldAndParameterNavigationAliases() {
        String source = '''
            class Fixture {
                private PathNavigation field;
                void use(PathNavigation parameter) {
                    field.moveTo(target, 1.0D);
                    parameter.stop();
                }
            }
        '''
        List<String> errors = NavigationArchitectureScanner.validateSource('fixture.java', source,
                [directNavigationReferences: []])
        assertTrue(errors.any { it.contains('direct navigation/path operation') })
    }

    @Test
    void testRejectsUnauthorizedDirectPathWrite() {
        String source = 'villager.getBrain().setMemory(MemoryModuleType.PATH, path);'
        List<String> errors = NavigationArchitectureScanner.validateSource('fixture.java', source,
                [memoryMutationSources: [], pathMemoryWriters: []])
        assertTrue(errors.any { it.contains('PATH memory mutation') })
    }

    @Test
    void testRejectsPathAliasWrite() {
        String source = 'var pathMemory = MemoryModuleType.PATH; villager.getBrain().eraseMemory(pathMemory);'
        List<String> errors = NavigationArchitectureScanner.validateSource('fixture.java', source,
                [memoryMutationSources: [], pathMemoryWriters: []])
        assertTrue(errors.any { it.contains('PATH memory mutation') })
    }

    @Test
    void testRejectsUnauthorizedGoalSelectorRegistration() {
        assertUnauthorized('goalSelector.addGoal(1, goal);') { it.goalSelectorReferences = [] }
    }

    @Test
    void testRejectsAdditionalRegistrationInsideInventoriedRegistrar() {
        String source = 'goalSelector.addGoal(1, first); goalSelector.addGoal(2, second);'
        List<String> errors = NavigationArchitectureScanner.validateSource('registrar.java', source,
                [goalSelectorReferences: [[id: 'registrar', sourcePath: 'registrar.java', expectedOccurrences: 1]]])
        assertTrue(errors.any { it.contains('expected 1 GoalSelector registrations') })
    }

    @Test
    void testRejectsNonVillagerExceptionSubstitutedForWantedItemProducer() {
        Map producer = [sourcePaths: ['EmeraldSkrimisherPickupGoal.java'], current: [executor: 'external vanilla 1.21.1 and 1.21.4']]
        List<String> errors = NavigationArchitectureScanner.validateWantedItemProducer(producer,
                ['EmeraldSkrimisherPickupGoal.java'], ['VillagerPickupCompat.java'])
        assertTrue(errors.any { it.contains('non-villager exception') })
        assertTrue(errors.any { it.contains('VillagerPickupCompat.java') })
    }

    @Test
    void testRejectsAmbiguousFrozenActivity() {
        List<String> errors = NavigationArchitectureScanner.validateFrozenText('mayor',
                [activity: 'WORK or IDLE', finality: 'frozen', claimScope: 'mayor UUID'])
        assertTrue(errors.any { it.contains('unfrozen alternative') })
    }

    @Test
    void testAcceptsDocumentedExistingTransitionalCaller() {
        List<String> errors = NavigationArchitectureScanner.validateSource('caller.java',
                'MovementRequestManager manager;',
                [transitionalReferences: [[id: 'caller-ref', sourcePath: 'caller.java', referenceName: 'MovementRequestManager', expectedOccurrences: 1]]])
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    void testAcceptsDocumentedNonVillagerException() {
        List<String> errors = NavigationArchitectureScanner.validateSource('exception.java',
                'skrimisher.getNavigation().stop();',
                [directNavigationReferences: [[id: 'exception-ref', sourcePath: 'exception.java',
                                               expectedOperations: [moveTo: 0, createPath: 0, stop: 1, getPath: 0]]]])
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    void testAcceptsReadOnlyNavigationObservation() {
        List<String> errors = NavigationArchitectureScanner.validateSource('observer.java',
                'Path path = villager.getNavigation().getPath();',
                [directNavigationReferences: [[id: 'observer-ref', sourcePath: 'observer.java',
                                               expectedOperations: [moveTo: 0, createPath: 0, stop: 0, getPath: 1]]]])
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    void testRejectsReplacingObservationWithMutationAtTheSameTotalCount() {
        List<String> errors = NavigationArchitectureScanner.validateSource('observer.java',
                'villager.getNavigation().moveTo(target, 1.0D);',
                [directNavigationReferences: [[id: 'observer-ref', sourcePath: 'observer.java',
                                               expectedOperations: [moveTo: 0, createPath: 0, stop: 0, getPath: 1]]]])
        assertTrue(errors.any { it.contains('expected 0 moveTo') })
        assertTrue(errors.any { it.contains('expected 1 getPath') })
    }

    @Test
    void testRejectsUnknownOwnerId() {
        List<String> errors = ownerErrors([sourceOwnership: [[path: 'a.java', ownerId: 'invented']]])
        assertTrue(errors.any { it.contains('not an inventoried owner') })
    }

    @Test
    void testRejectsDuplicateSourceOwnershipAssignment() {
        List<String> errors = ownerErrors([sourceOwnership: [[path: 'a.java', ownerId: 'navigation'],
                                                              [path: 'a.java', ownerId: 'navigation']]])
        assertTrue(errors.any { it.contains('duplicate source ownership assignment') })
    }

    @Test
    void testRejectsAllowlistedSourceWithoutOwnership() {
        List<String> errors = NavigationArchitectureScanner.validateAllowlistedSources(
                ['allowlisted.java'], [], [])
        assertTrue(errors.any { it.contains('allowlisted source has no inventory explanation') })
    }

    @Test
    void testRejectsUnrelatedReferenceOwnerCategory() {
        List<String> errors = ownerErrors([
                transitionalReferences: [[id: 'ref', ownerId: 'exception-owner']],
                ownerCategories: [navigation: 'navigation', 'exception-owner': 'exception']
        ])
        assertTrue(errors.any { it.contains('unrelated owner category') })
    }

    private void assertUnauthorized(String source, Closure configure) {
        Map options = [:]
        configure.call(options)
        List<String> errors = NavigationArchitectureScanner.validateSource('fixture.java', source, options)
        assertFalse(errors.isEmpty())
    }

    private List<String> ownerErrors(Map overrides = [:]) {
        Map options = [
                canonicalOwnerIds: ['navigation', 'exception-owner'],
                ownerCategories: [navigation: 'navigation', 'exception-owner': 'exception'],
                sourceOwnership: [], transitionalReferences: [], directReferences: [], goalReferences: []
        ] + overrides
        NavigationArchitectureScanner.validateOwnerReferences(
                options.canonicalOwnerIds as Set<String>, options.ownerCategories as Map<String, String>,
                options.sourceOwnership as List<Map>, options.transitionalReferences as List<Map>,
                options.directReferences as List<Map>, options.goalReferences as List<Map>)
    }
}
