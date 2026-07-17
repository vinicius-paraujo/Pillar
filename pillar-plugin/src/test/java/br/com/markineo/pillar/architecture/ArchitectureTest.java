package br.com.markineo.pillar.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(packages = "br.com.markineo.pillar", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule coreIsPure = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..config..",
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

}
