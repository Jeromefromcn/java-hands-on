package com.jerome.slledger.bootstrap.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tokens for the tests, signed with a key generated when the JVM starts. The key is never written
 * anywhere but memory; only its public half goes to a temporary file, which is where the
 * application's resource-server configuration points ({@code public-key-location}), the same
 * property a deployment points at a JWK set. Nothing in the application knows it is under test.
 */
public final class TestTokens {

  public static final String ISSUER = "https://issuer.test.invalid/realms/ledger";
  public static final String AUDIENCE = "sl-ledger";

  private static final RSAKey KEY = generate("test-key");
  private static final RSAKey OTHER_KEY = generate("other-key");
  private static final Path PUBLIC_KEY = writePublicKey();

  private TestTokens() {}

  /** The resource-server arguments every test application starts with. */
  public static List<String> applicationArguments() {
    return List.of(
        "--spring.security.oauth2.resourceserver.jwt.public-key-location=file:" + PUBLIC_KEY,
        "--ledger.security.issuer=" + ISSUER,
        "--spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE);
  }

  /** {@code Authorization} header value for a token that holds both staff roles. */
  public static String staffBearer() {
    return "Bearer " + staff();
  }

  /** A request builder that already carries the staff token. */
  public static HttpRequest.Builder request(URI uri) {
    return HttpRequest.newBuilder(uri).header("Authorization", staffBearer());
  }

  public static String staff() {
    return builder().roles("ledger-trader", "ledger-ops").build();
  }

  public static String trader() {
    return builder().subject("trader-1").roles("ledger-trader").build();
  }

  public static String ops() {
    return builder().subject("ops-1").roles("ledger-ops").build();
  }

  public static String auditor() {
    return builder().subject("auditor-1").roles("ledger-auditor").build();
  }

  public static String metrics() {
    return builder().subject("scraper-1").roles("ledger-metrics").build();
  }

  public static String counterparty(String counterpartyId) {
    return builder()
        .subject("borrower-" + counterpartyId)
        .roles("ledger-counterparty")
        .counterparty(counterpartyId)
        .build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String subject = "test-subject";
    private String issuer = ISSUER;
    private String audience = AUDIENCE;
    private Instant expiresAt = Instant.now().plusSeconds(300);
    private final List<String> roles = new ArrayList<>();
    private String counterparty;
    private RSAKey key = KEY;

    public Builder subject(String subject) {
      this.subject = subject;
      return this;
    }

    public Builder roles(String... roles) {
      this.roles.addAll(List.of(roles));
      return this;
    }

    public Builder counterparty(String counterparty) {
      this.counterparty = counterparty;
      return this;
    }

    public Builder issuer(String issuer) {
      this.issuer = issuer;
      return this;
    }

    public Builder audience(String audience) {
      this.audience = audience;
      return this;
    }

    public Builder expiresAt(Instant expiresAt) {
      this.expiresAt = expiresAt;
      return this;
    }

    /** Signed by a key the application does not trust. */
    public Builder signedByStranger() {
      this.key = OTHER_KEY;
      return this;
    }

    public String build() {
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .issuer(issuer)
              .audience(audience)
              .jwtID(UUID.randomUUID().toString())
              .issueTime(Date.from(Instant.now().minusSeconds(10)))
              .expirationTime(Date.from(expiresAt))
              .claim("realm_access", Map.of("roles", roles));
      if (counterparty != null) {
        claims.claim("counterparty_id", counterparty);
      }
      try {
        SignedJWT jwt =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
      } catch (JOSEException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static RSAKey generate(String id) {
    try {
      return new RSAKeyGenerator(2048).keyID(id).generate();
    } catch (JOSEException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Path writePublicKey() {
    try {
      byte[] der = KEY.toPublicKey().getEncoded();
      String pem =
          "-----BEGIN PUBLIC KEY-----\n"
              + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
              + "\n-----END PUBLIC KEY-----\n";
      Path file = Files.createTempFile("ledger-test-jwt-", ".pem");
      file.toFile().deleteOnExit();
      Files.writeString(file, pem);
      return file;
    } catch (JOSEException | IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
