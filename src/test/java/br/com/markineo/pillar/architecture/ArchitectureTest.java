package br.com.markineo.pillar.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(packages = "br.com.markineo.pillar", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    // UNIVERSAL LAYERS: logger, error, concurrent (and JDK/slf4j) are allowed everywhere.
    // So we don't explicitly restrict who can depend on them.

    @ArchTest
    static final ArchRule coreIsPure = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..config..",
                    "..api..",
                    "..redis..",
                    "..paper..",
                    "..velocity.."
            ).allowEmptyShould(true);

    @ArchTest
    static final ArchRule configOnlyDependsOnCore = noClasses()
            .that().resideInAPackage("..config..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..",
                    "..redis..",
                    "..paper..",
                    "..velocity.."
            ).allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiOnlyDependsOnCore = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..config..",
                    "..redis..",
                    "..paper..",
                    "..velocity.."
            ).allowEmptyShould(true);

    @ArchTest
    static final ArchRule redisOnlyDependsOnCoreAndApi = noClasses()
            .that().resideInAPackage("..redis..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..config..",
                    "..paper..",
                    "..velocity.."
            ).allowEmptyShould(true);

    @ArchTest
    static final ArchRule paperDoesNotDependOnVelocity = noClasses()
            .that().resideInAPackage("..paper..")
            .should().dependOnClassesThat().resideInAPackage("..velocity..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule velocityDoesNotDependOnPaper = noClasses()
            .that().resideInAPackage("..velocity..")
            .should().dependOnClassesThat().resideInAPackage("..paper..")
            .allowEmptyShould(true);

    // PIL-51 explicit consumer rule: The API surface should not expose internal implementation details.
    // The classes in `api` should not expose (in their public/protected signatures) types from `redis` or `core.task`.
    @ArchTest
    static final ArchRule apiDoesNotExposeInternals = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..redis..",
                    "..core.task.."
            ).allowEmptyShould(true);
}
