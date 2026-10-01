package com.jerome.slledger.adapter.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Corrects the OpenAPI document where springdoc's inference and the wire disagree.
 *
 * <p>springdoc describes {@code Money} by introspecting the record with Jackson 2, not with the
 * {@code LedgerJacksonModule} that actually writes it: {@code amount} came out as a {@code number},
 * {@code currency} as {@code java.util.Currency}'s bean properties, and {@code isNegative()} as a
 * field. A client generated from that contract would parse amounts as doubles, the loss the module
 * exists to prevent. So the money invariant is restated here, for the contract. See {@code
 * docs/decisions/07-web.md}.
 *
 * <p>Every operation also gets a {@code default} response: an RFC 9457 problem, which is what every
 * error of this API is.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerOpenApi {

  static final String DECIMAL = "^-?[0-9]+(\\.[0-9]+)?$";
  static final String PROBLEM = "ProblemDetail";

  @Bean
  OpenApiCustomizer ledgerContract() {
    return openApi -> {
      Components components = openApi.getComponents();
      if (components == null) {
        components = new Components();
        openApi.setComponents(components);
      }
      components.addSecuritySchemes(
          "bearer",
          new SecurityScheme()
              .type(SecurityScheme.Type.HTTP)
              .scheme("bearer")
              .bearerFormat("JWT")
              .description(
                  "A token from the identity provider. The roles it carries decide which"
                      + " operations answer; a counterparty token sees only its own positions."));
      openApi.addSecurityItem(new SecurityRequirement().addList("bearer"));
      components.addSchemas("Money", money());
      components.addSchemas("Rate", rate());
      components.addSchemas(PROBLEM, problem());
      ApiResponse problem =
          new ApiResponse()
              .description("An RFC 9457 problem; branch on its type, never on its detail")
              .content(
                  new Content()
                      .addMediaType(
                          "application/problem+json",
                          new MediaType()
                              .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
      openApi
          .getPaths()
          .values()
          .forEach(
              path ->
                  path.readOperations()
                      .forEach(
                          operation ->
                              operation.getResponses().addApiResponse("default", problem)));
    };
  }

  private static Schema<Object> money() {
    Schema<Object> amount =
        string(
            DECIMAL,
            "A decimal string at the currency's scale, never a JSON number: a number may be"
                + " rounded to a double by the parser before it is read.");
    amount.setExamples(List.of("1234.50"));
    Schema<Object> money = typed("object");
    money.setDescription("An amount and its currency; one is never sent without the other.");
    money.addProperty("amount", amount);
    money.addProperty("currency", string("^[A-Z]{3}$", "ISO 4217 code"));
    money.setRequired(List.of("amount", "currency"));
    money.setAdditionalProperties(false);
    return money;
  }

  private static Schema<Object> rate() {
    return string(
        "^[0-9]+(\\.[0-9]+)?$",
        "An annual rate as a decimal fraction string: 0.0365 is 3.65 percent.");
  }

  private static Schema<Object> problem() {
    Schema<Object> error = typed("object");
    error.addProperty("field", typed("string"));
    error.addProperty("message", typed("string"));
    Schema<Object> errors = typed("array");
    errors.setItems(error);
    Schema<Object> type = typed("string");
    type.setFormat("uri");
    Schema<Object> status = typed("integer");
    status.setFormat("int32");
    Schema<Object> problem = typed("object");
    problem.addProperty("type", type);
    problem.addProperty("title", typed("string"));
    problem.addProperty("status", status);
    problem.addProperty("detail", typed("string"));
    problem.addProperty("instance", typed("string"));
    problem.addProperty("traceId", string("^[0-9a-f]{32}$", "This request's trace id"));
    problem.addProperty("path", typed("string"));
    problem.addProperty("errors", errors);
    problem.setRequired(List.of("type", "title", "status", "traceId"));
    return problem;
  }

  private static Schema<Object> string(String pattern, String description) {
    Schema<Object> schema = typed("string");
    schema.setPattern(pattern);
    schema.setDescription(description);
    return schema;
  }

  /** Sets both the 3.0 {@code type} and the 3.1 {@code types}; springdoc serves 3.1. */
  private static Schema<Object> typed(String type) {
    Schema<Object> schema = new Schema<>();
    schema.setType(type);
    schema.setTypes(Set.of(type));
    return schema;
  }
}
