package com.jerome.slledger.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Three architecture rules, checked on the one classpath that has every module on it: {@code
 * bootstrap} depends (transitively) on all of {@code domain}, {@code application}, {@code
 * adapter-persistence}, {@code adapter-web} and {@code batch}.
 *
 * <p>Named {@code ArchitectureIT}, not {@code ArchitectureTest}: Failsafe's default inclusion
 * pattern is {@code **&#47;*IT.java}, and matching it is simpler than reconfiguring the pattern.
 * Bound to the {@code integration-test}/{@code verify} phases, not {@code surefire}'s {@code test}
 * phase — these are architecture checks, not unit tests, and stay out of the fast inner loop.
 *
 * <p>Since stage 6, rules 1 and 3 check real classes: {@code adapter-persistence} holds the JPA
 * adapter, and {@code application} holds the {@code @Transactional} use cases. Rule 2 still passes
 * vacuously, because {@code adapter-web} is empty until stage 7; that is stated here rather than
 * hidden, per {@code docs/decisions/03-testing-foundation.md}.
 *
 * <p>Test classes are not imported. The rules govern production code; test-only demos are exactly
 * where reproductions belong, and stage 5's {@code @Transactional} demos under {@code
 * bootstrap/src/test} would otherwise violate rule 3 by design.
 */
class ArchitectureIT {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.jerome.slledger");

  @Test
  @DisplayName("domain does not depend on any adapter")
  void domainDoesNotDependOnAdapters() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.jerome.slledger.adapter..");

    rule.check(CLASSES);
  }

  @Test
  @DisplayName(
      "adapter-web does not depend on adapter-persistence (vacuous until stage 7: adapter-web"
          + " has no classes yet)")
  void webDoesNotDependOnPersistence() {
    // allowEmptyShould(true): ArchUnit fails a rule by default when the "that()" selection is
    // empty, on the theory that an empty selection usually means a typo'd package name. Here it
    // really is empty — adapter-web has no classes yet — so the default is overridden explicitly
    // rather than the rule silently never running.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.adapter.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.jerome.slledger.adapter.persistence..")
            .allowEmptyShould(true);

    rule.check(CLASSES);
  }

  @Test
  @DisplayName("@Transactional lives only in application")
  void transactionalOnlyInApplication() {
    transactionalOnlyInApplicationRule().check(CLASSES);
  }

  /** A class outside application whose only @Transactional is on a method, not on the class. */
  static class MethodLevelTransactional {
    @Transactional
    public void book() {}
  }

  @Test
  @DisplayName("the @Transactional rule catches an annotation on a method, not only on a class")
  void transactionalRuleCatchesMethodLevelAnnotation() {
    JavaClasses fixture = new ClassFileImporter().importClasses(MethodLevelTransactional.class);

    assertThatThrownBy(() -> transactionalOnlyInApplicationRule().check(fixture))
        .isInstanceOf(AssertionError.class);
  }

  private static ArchRule transactionalOnlyInApplicationRule() {
    // Matched by fully-qualified name, not by the annotation class itself. That dates from stage 5,
    // when Spring's annotation was on the test classpath only; since stage 6 it is on the main
    // classpath through application's spring-tx, and matching by name still works the same way:
    // ArchUnit checks annotations by bytecode inspection.
    //
    // Checked on classes and on methods alike: the annotation is equally valid on either, and a
    // class-only check passes a method-level @Transactional anywhere (found in stage 5, where
    // every demo annotates methods).
    String transactional = "org.springframework.transaction.annotation.Transactional";
    String application = "com.jerome.slledger.application..";
    return CompositeArchRule.of(
            classes()
                .that()
                .areAnnotatedWith(transactional)
                .should()
                .resideInAPackage(application)
                .allowEmptyShould(true))
        .and(
            methods()
                .that()
                .areAnnotatedWith(transactional)
                .should()
                .beDeclaredInClassesThat()
                .resideInAPackage(application)
                .allowEmptyShould(true));
  }
}
