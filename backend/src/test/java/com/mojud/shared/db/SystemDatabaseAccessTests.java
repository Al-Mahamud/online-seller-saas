package com.mojud.shared.db;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The system role bypasses row-level security, so its use is limited to "system" packages. */
class SystemDatabaseAccessTests {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mojud");

    @Test
    void onlySystemPackagesUseTheSystemDatabase() {
        noClasses()
                .that().resideOutsideOfPackages("..system..", "com.mojud.shared.db")
                .should().dependOnClassesThat().resideInAPackage("com.mojud.shared.db.system..")
                .because("mojud_system bypasses row-level security (backend-plan.md §4)")
                .check(classes);
    }
}
