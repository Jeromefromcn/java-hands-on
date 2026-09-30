package com.jerome.slledger.adapter.web.json;

import com.jerome.slledger.domain.error.NegativeRateException;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * A rate is a JSON string holding a plain decimal fraction, {@code "0.0400"}, unscaled: {@link
 * Rate} keeps the scale it was given, and so does the wire.
 */
final class RateJson {

  private RateJson() {}

  static final class Writer extends ValueSerializer<Rate> {

    @Override
    public void serialize(Rate value, JsonGenerator gen, SerializationContext ctxt) {
      gen.writeString(value.annual().toPlainString());
    }
  }

  static final class Reader extends ValueDeserializer<Rate> {

    @Override
    public Rate deserialize(JsonParser p, DeserializationContext ctxt) {
      if (p.currentToken() != JsonToken.VALUE_STRING) {
        return ctxt.reportInputMismatch(
            Rate.class, "rate must be a JSON string, not %s", p.currentToken());
      }
      String text = p.getString();
      if (!MoneyJson.PLAIN_DECIMAL.matcher(text).matches()) {
        return ctxt.reportInputMismatch(Rate.class, "rate '%s' is not a plain decimal", text);
      }
      try {
        return new Rate(new BigDecimal(text));
      } catch (NegativeRateException e) {
        return ctxt.reportInputMismatch(Rate.class, "rate %s is negative", text);
      }
    }
  }
}
