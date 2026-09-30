package labs.caching;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The same price on the wire three ways, written with protobuf's own {@link CodedOutputStream} (no
 * {@code .proto} compile step: the encoding is the point). Field 1 is the amount, field 2 the
 * currency code.
 *
 * <ul>
 *   <li>JSON with a string amount, the main build's choice;
 *   <li>protobuf with a string amount;
 *   <li>protobuf with a {@code double} amount, which is the natural type and the wrong one;
 *   <li>protobuf with the amount as an {@code int64} count of minor units, which is exact.
 * </ul>
 */
public final class ProtobufVsJson {

  private ProtobufVsJson() {}

  public static void main(String[] args) throws Exception {
    StringBuilder out = new StringBuilder();
    out.append("Stage 9 lab: one price, JSON and protobuf\n");
    out.append("Environment: ").append(Env.describe()).append("\n");
    out.append(
        """
        JSON is what the ledger stores in Redis. Protobuf sizes are the encoded message alone
        (no framing). "back" is the amount as read after the round trip.

        """);
    for (String[] sample :
        new String[][] {
          {"41.26", "HKD", "2"},
          {"12345678901234567.89", "HKD", "2"},
          {"1234567890123456.789", "BHD", "3"},
        }) {
      String amount = sample[0];
      String currency = sample[1];
      int scale = Integer.parseInt(sample[2]);
      out.append("amount %s %s%n".formatted(amount, currency));

      String json =
          "{\"state\":\"present\",\"amount\":\"%s\",\"currency\":\"%s\"}".formatted(amount, currency);
      out.append(
          "  JSON, string amount           : %3d bytes, back %s%n"
              .formatted(json.getBytes(StandardCharsets.UTF_8).length, amount));

      byte[] asString = encodeString(amount, currency);
      out.append(
          "  protobuf, string amount       : %3d bytes, back %s%n"
              .formatted(asString.length, decodeString(asString)));

      byte[] asDouble = encodeDouble(Double.parseDouble(amount), currency);
      BigDecimal fromDouble = new BigDecimal(decodeDouble(asDouble));
      out.append(
          "  protobuf, double amount       : %3d bytes, back %s  %s%n"
              .formatted(
                  asDouble.length,
                  fromDouble.toPlainString(),
                  fromDouble.compareTo(new BigDecimal(amount)) == 0 ? "(exact)" : "(LOST DIGITS)"));

      long minor = new BigDecimal(amount).movePointRight(scale).longValueExact();
      byte[] asMinor = encodeMinor(minor, currency);
      BigDecimal fromMinor = BigDecimal.valueOf(decodeMinor(asMinor), scale);
      out.append(
          "  protobuf, int64 minor units   : %3d bytes, back %s%n%n"
              .formatted(asMinor.length, fromMinor.toPlainString()));
    }
    out.append(
        """
        Reading: protobuf is smaller than JSON either way, and the type chosen for the amount
        decides whether it is right. `double` is what a schema author reaches for and loses the
        digits; a string or a count of minor units keeps them. The minor-unit form needs the
        currency's scale on both ends and cannot say "more decimals than the currency allows".
        """);

    Path target = Path.of("target", "evidence");
    Files.createDirectories(target);
    Files.writeString(target.resolve("09-protobuf-vs-json.txt"), out.toString());
    System.out.println(out);
  }

  private static byte[] encodeString(String amount, String currency) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeString(1, amount);
    out.writeString(2, currency);
    out.flush();
    return bytes.toByteArray();
  }

  private static String decodeString(byte[] message) throws Exception {
    CodedInputStream in = CodedInputStream.newInstance(message);
    in.readTag();
    return in.readString();
  }

  private static byte[] encodeDouble(double amount, String currency) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeDouble(1, amount);
    out.writeString(2, currency);
    out.flush();
    return bytes.toByteArray();
  }

  private static double decodeDouble(byte[] message) throws Exception {
    CodedInputStream in = CodedInputStream.newInstance(message);
    in.readTag();
    return in.readDouble();
  }

  private static byte[] encodeMinor(long minor, String currency) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeInt64(1, minor);
    out.writeString(2, currency);
    out.flush();
    return bytes.toByteArray();
  }

  private static long decodeMinor(byte[] message) throws Exception {
    CodedInputStream in = CodedInputStream.newInstance(message);
    in.readTag();
    return in.readInt64();
  }
}
