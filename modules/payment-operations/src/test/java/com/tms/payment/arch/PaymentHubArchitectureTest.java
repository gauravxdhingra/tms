package com.tms.payment.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

class PaymentHubArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().importPackages("com.tms.payment");
    }

    @Test
    void domainMustNotDependOnSpring() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .because("Domain is pure Java. No framework dependencies.");
        rule.check(classes);
    }

    @Test
    void enforceLayeredArchitecture() {
        // Import only com.tms.payment.* classes so that consideringAllDependencies()
        // doesn't flag java.*, jakarta.*, org.springframework.*, or com.tms.common.*.
        // Layer rules apply only to inter-layer relationships within this service.
        JavaClasses serviceClasses = new ClassFileImporter()
            .importPackages("com.tms.payment");

        // consideringOnlyDependenciesInLayers() restricts checks to dependencies
        // between defined layers only — ignores java.*, jakarta.*, org.springframework.*
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain")         .definedBy("com.tms.payment.domain..")
            .layer("Application")    .definedBy("com.tms.payment.application..")
            .layer("Infrastructure") .definedBy("com.tms.payment.infrastructure..")
            .layer("Config")         .definedBy("com.tms.payment.config..")

            .whereLayer("Domain")         .mayNotAccessAnyLayer()
            .whereLayer("Application")    .mayOnlyAccessLayers("Domain")
            .whereLayer("Infrastructure") .mayOnlyAccessLayers("Domain", "Application")
            .whereLayer("Config")         .mayOnlyAccessLayers("Domain", "Application", "Infrastructure")

            .check(serviceClasses);
    }

    @Test
    void noDoubleOrFloatForMoney() {
        ArchRule rule = noFields()
            .that().areDeclaredInClassesThat().resideInAPackage("com.tms.payment.domain..")
            .should().haveRawType(double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(Float.class)
            .because("Use MonetaryAmount (JSR-354). double/float lose precision on financial amounts.");
        rule.check(classes);
    }

    @Test
    void noDirectLocalDateNow() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.tms.payment..")
            .should().callMethod(java.time.LocalDate.class, "now")
            .orShould().callMethod(java.time.Instant.class, "now")
            .because("Inject java.time.Clock and use LocalDate.now(clock). Enables deterministic tests.");
        rule.check(classes);
    }

    @Test
    void repositoriesMustBeInInfrastructureLayer() {
        ArchRule rule = classes()
            .that().haveSimpleNameEndingWith("Repository")
            .should().resideInAPackage("..infrastructure.persistence..")
            .because("Repositories are infrastructure concerns. Domain uses them via interface.");
        rule.check(classes);
    }
}
