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
 * Architecture rules, checked on the one classpath that has every module on it: {@code bootstrap}
 * depends (transitively) on all of {@code domain}, {@code application}, {@code
 * adapter-persistence}, {@code adapter-web} and {@code batch}.
 *
 * <p>Named {@code ArchitectureIT}, not {@code ArchitectureTest}: Failsafe's default inclusion
 * pattern is {@code **&#47;*IT.java}, and matching it is simpler than reconfiguring the pattern.
 * Bound to the {@code integration-test}/{@code verify} phases, not {@code surefire}'s {@code test}
 * phase — these are architecture checks, not unit tests, and stay out of the fast inner loop.
 *
 * <p>Since stage 6, rules 1 and 3 check real classes: {@code adapter-persistence} holds the JPA
 * adapter, and {@code application} holds the {@code @Transactional} use cases. Since stage 7, rule
 * 2 does too, and two more govern {@code adapter-web} itself: controllers reach the ledger only
 * through {@code application}, and the outbound market data client is kept apart from the inbound
 * side.
 *
 * <p>Test classes are not imported. The rules govern production code; test-only demos are exactly
 * where reproductions belong, and stage 5's {@code @Transactional} demos under {@code
 * bootstrap/src/test} would otherwise violate rule 3 by design. {@code DoNotIncludeTests} only
 * recognises {@code test-classes} directories, so the {@code adapter-persistence} test-jar that
 * this module consumes is excluded by name as well.
 */
class ArchitectureIT {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .withImportOption(location -> !location.contains("-tests.jar"))
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
  @DisplayName("adapter-web does not depend on adapter-persistence")
  void webDoesNotDependOnPersistence() {
    // Stage 7 gave adapter-web its classes, so the allowEmptyShould(true) that let this rule pass
    // on an empty package is gone: an empty selection would now mean a renamed package.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.adapter.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.jerome.slledger.adapter.persistence..");

    rule.check(CLASSES);
  }

  @Test
  @DisplayName("controllers reach the ledger only through application, never a port or domain port")
  void controllersCallUseCasesOnly() {
    ArchRule rule =
        noClasses()
            .that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.jerome.slledger.domain.loan.LoanPositionRepository")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Query")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.jerome.slledger.application.port.TradeLedger")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.jerome.slledger.application.port.MarketPriceSource");

    rule.check(CLASSES);
  }

  @Test
  @DisplayName("nothing inbound in adapter-web depends on the outbound market data client")
  void inboundDoesNotReachTheClient() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.adapter.web..")
            .and()
            .resideOutsideOfPackage("com.jerome.slledger.adapter.web.client..")
            .and()
            .doNotHaveFullyQualifiedName("com.jerome.slledger.adapter.web.WebConfiguration")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.jerome.slledger.adapter.web.client..");

    rule.check(CLASSES);
  }

  @Test
  @DisplayName("application depends on no cache, Redis or JSON library")
  void applicationHasNoCacheLibrary() {
    // Stage 9: the read-through, the ports and the decorators are plain Java. Caffeine, Lettuce,
    // Spring Data Redis and Jackson are adapter-persistence's.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.github.benmanes.caffeine..",
                "io.lettuce..",
                "org.springframework.data.redis..",
                "tools.jackson..",
                "com.fasterxml.jackson..");

    rule.check(CLASSES);
  }

  @Test
  @DisplayName("application depends on no Kafka, JSON or JDBC type")
  void applicationHasNoMessagingLibrary() {
    // Stage 10: events, the outbox, the relay and the saga are plain Java over ports. Kafka and
    // Jackson are adapter-messaging's; JDBC is adapter-persistence's.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.apache.kafka..",
                "org.springframework.kafka..",
                "org.springframework.jdbc..",
                "tools.jackson..",
                "com.fasterxml.jackson..");

    rule.check(CLASSES);
  }

  @Test
  @DisplayName("adapter-messaging and adapter-persistence do not depend on each other")
  void adaptersAreIndependent() {
    ArchRule messaging =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.adapter.messaging..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.jerome.slledger.adapter.persistence..");
    ArchRule persistence =
        noClasses()
            .that()
            .resideInAPackage("com.jerome.slledger.adapter.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.jerome.slledger.adapter.messaging..");

    messaging.check(CLASSES);
    persistence.check(CLASSES);
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
