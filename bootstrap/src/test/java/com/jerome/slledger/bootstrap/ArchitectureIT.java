package com.jerome.slledger.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * <p>All three rules pass vacuously today: {@code application}, {@code adapter-persistence} and
 * {@code adapter-web} hold no classes yet. That is stated here rather than hidden, per {@code
 * docs/decisions/03-testing-foundation.md}. The point of writing them now, rather than when the
 * first violating class would appear, is that the first class that would violate rule 1 or rule 3
 * fails the build the moment it is written, not after a review misses it.
 */
class ArchitectureIT {

  private static final JavaClasses CLASSES =
      new ClassFileImporter().importPackages("com.jerome.slledger");

  @Test
  @DisplayName(
      "domain does not depend on any adapter (vacuous today: no adapter classes exist yet)")
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
      "adapter-web does not depend on adapter-persistence (vacuous today: neither has classes"
          + " yet)")
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
  @DisplayName(
      "@Transactional lives only in application (vacuous today: no @Transactional class exists"
          + " yet)")
  void transactionalOnlyInApplication() {
    // Matched by fully-qualified name, not by the annotation class itself: neither
    // org.springframework.transaction.annotation.Transactional nor
    // jakarta.transaction.Transactional is on this module's classpath yet (stage 4 adds Spring).
    // ArchUnit checks annotations by bytecode inspection, so the rule compiles and runs correctly
    // before either dependency exists.
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .should()
            .resideInAPackage("com.jerome.slledger.application..")
            .allowEmptyShould(true);

    rule.check(CLASSES);
  }
}
