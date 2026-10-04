package work.archaic.tend.integration;

import com.google.gson.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.*;

/** Test-client verification for this fixture's RS256 ID tokens, not a general JOSE implementation. */
final class OidcToken {
    static JsonObject verify(String token, JsonObject jwks, String issuer, String audience, String nonce, String accessToken, Instant now) throws IOException {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || token.length() > 16384) throw new GeneralSecurityException();
            var decoder = Base64.getUrlDecoder();
            var header = JsonParser.parseString(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!header.get("alg").getAsString().equals("RS256") || header.has("crit")) throw new GeneralSecurityException();
            JsonObject key = null;
            for (var entry : jwks.getAsJsonArray("keys")) {
                var candidate = entry.getAsJsonObject();
                if (candidate.get("kid").getAsString().equals(header.get("kid").getAsString())) {
                    if (key != null) throw new GeneralSecurityException();
                    key = candidate;
                }
            }
            if (key == null || !key.get("kty").getAsString().equals("RSA") || !key.get("alg").getAsString().equals("RS256") || !key.get("use").getAsString().equals("sig")) throw new GeneralSecurityException();
            var modulus = new BigInteger(1, decoder.decode(key.get("n").getAsString()));
            var exponent = new BigInteger(1, decoder.decode(key.get("e").getAsString()));
            if (modulus.bitLength() != 3072 || !exponent.equals(BigInteger.valueOf(65537))) throw new GeneralSecurityException();
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(publicKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(parts[2]))) throw new GeneralSecurityException();
            var claims = JsonParser.parseString(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            var aud = claims.get("aud");
            boolean matches = aud.isJsonArray() ? aud.getAsJsonArray().size() == 1 && aud.getAsJsonArray().get(0).getAsString().equals(audience) : aud.getAsString().equals(audience);
            if (!claims.get("iss").getAsString().equals(issuer) || !matches || !claims.get("nonce").getAsString().equals(nonce)
                    || claims.get("sub").getAsString().isBlank() || claims.get("exp").getAsLong() <= now.getEpochSecond()
                    || claims.get("iat").getAsLong() > now.getEpochSecond() + 5 || claims.get("iat").getAsLong() >= claims.get("exp").getAsLong()
                    || (claims.has("azp") && !claims.get("azp").getAsString().equals(audience))) throw new GeneralSecurityException();
            if (claims.has("at_hash")) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
                String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, digest.length / 2));
                if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), claims.get("at_hash").getAsString().getBytes(StandardCharsets.US_ASCII))) throw new GeneralSecurityException();
            }
            return claims;
        } catch (GeneralSecurityException | RuntimeException e) { throw new IOException("OIDC ID token validation failed; private token withheld"); }
    }
}
