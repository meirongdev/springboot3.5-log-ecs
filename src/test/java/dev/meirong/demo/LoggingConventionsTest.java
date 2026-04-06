package dev.meirong.demo;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Static architecture rules enforcing the project logging conventions.
 *
 * Tool split:
 *   ArchUnit  — structural rules (field modifiers, forbidden framework imports)
 *   SpotBugs  — bytecode rules (SLF4J string concat, System.out, missing exception arg)
 *               configured via spotbugs-include.xml + slf4j-bug-pattern plugin
 *
 * Rules here:
 *   1. Logger field must be private static final
 *   2. No java.util.logging direct usage
 *   3. No Log4j direct usage
 */
class LoggingConventionsTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importClasses(DemoApplication.class, DemoController.class, LoggingFilter.class);
    }

    // ── Rule 1: logger must be private static final ───────────────────────────

    @Test
    void loggerFieldShouldBePrivateStaticFinal() {
        fields().that().haveRawType("org.slf4j.Logger")
                .should().bePrivate()
                .andShould().beStatic()
                .andShould().beFinal()
                .allowEmptyShould(true)
                .because("规范：Logger 必须是 private static final，防止每实例分配")
                .check(classes);
    }

    // ── Rule 2 & 3: only SLF4J — no JUL or Log4j direct usage ───────────────
    // Note: System.out/err and SLF4J string-concat violations are caught by
    // SpotBugs (spotbugs-include.xml + slf4j-bug-pattern) during mvn verify.

    @Test
    void noJavaUtilLogging() {
        noClasses()
                .should().accessClassesThat().resideInAPackage("java.util.logging..")
                .allowEmptyShould(true)
                .because("规范：统一使用 SLF4J，禁止直接使用 java.util.logging")
                .check(classes);
    }

    @Test
    void noLog4jDirect() {
        noClasses()
                .should().accessClassesThat().resideInAPackage("org.apache.log4j..")
                .allowEmptyShould(true)
                .because("规范：统一使用 SLF4J，禁止直接使用 Log4j")
                .check(classes);
    }
}
