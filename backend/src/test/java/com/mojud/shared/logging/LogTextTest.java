package com.mojud.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogTextTest {

    private static String json(String text, int limit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return LogText.body("application/json", bytes, bytes.length, limit);
    }

    @Test
    void passwordsTokensAndTwoStepSecretsAreMasked() {
        assertThat(json("{\"login\":\"rahima\",\"password\":\"s3cr\\\"et!\",\"newPassword\":\"x\"}", 4096))
                .isEqualTo("{\"login\":\"rahima\",\"password\":\"***\",\"newPassword\":\"***\"}");
        assertThat(json("{\"secret\":\"JBSWY3DP\",\"otpauthUri\":\"otpauth://totp/x\",\"recoveryCodes\":[\"a-b\",\"c-d\"]}", 4096))
                .isEqualTo("{\"secret\":\"***\",\"otpauthUri\":\"***\",\"recoveryCodes\":\"***\"}");
        assertThat(json("{\"startingPassword\":\"Kx7-pq\",\"userId\":\"u1\"}", 4096))
                .isEqualTo("{\"startingPassword\":\"***\",\"userId\":\"u1\"}");
    }

    @Test
    void aOneTimeCodeIsMaskedButOtherCodesAreNot() {
        assertThat(json("{\"to\":\"x\",\"code\":\"482913\"}", 4096)).isEqualTo("{\"to\":\"x\",\"code\":\"***\"}");
        assertThat(json("{\"code\":482913}", 4096)).isEqualTo("{\"code\":\"***\"}");
        assertThat(json("{\"code\":\"field.invalid\",\"planCode\":\"PRO\"}", 4096))
                .isEqualTo("{\"code\":\"field.invalid\",\"planCode\":\"PRO\"}");
    }

    @Test
    void aLongBodyIsCutAndASecretCutInHalfIsStillMasked() {
        String body = "{\"name\":\"" + "ক".repeat(10) + "\",\"password\":\"abcdefghijklmnop\"}";
        int cut = body.getBytes(StandardCharsets.UTF_8).length - 8;
        String shown = json(body, cut);
        assertThat(shown).doesNotContain("abcdefgh").contains("\"password\":\"***\"").endsWith("…(+8 bytes)");
    }

    @Test
    void imagesAndFilesAreNamedNotWritten() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        assertThat(LogText.body("image/png", png, 23_456, 4096)).isEqualTo("<image/png 22.9 KB>");
        assertThat(LogText.body("text/html;charset=UTF-8", "<p>".getBytes(), 3, 4096)).isEqualTo("<text/html 3 B>");
        assertThat(LogText.body("application/json", new byte[0], 0, 4096)).isEmpty();
    }

    @Test
    void aBodyStaysOnOneLine() {
        assertThat(json("{\"note\":\"a\nb\"}", 4096)).isEqualTo("{\"note\":\"a\\nb\"}");
    }

    @Test
    void secretsInAddressesAndFormsAreMasked() {
        assertThat(LogText.maskParameters("https://gw/validator?val_id=v1&store_id=shop&store_passwd=hunter2&v=1"))
                .isEqualTo("https://gw/validator?val_id=v1&store_id=shop&store_passwd=***&v=1");
        byte[] form = "store_id=shop&store_passwd=hunter2&total_amount=499".getBytes(StandardCharsets.UTF_8);
        assertThat(LogText.body("application/x-www-form-urlencoded", form, form.length, 4096))
                .isEqualTo("store_id=shop&store_passwd=***&total_amount=499");
        Map<String, String[]> fields = new LinkedHashMap<>();
        fields.put("tran_id", new String[] {"MJ-1"});
        fields.put("verify_sign", new String[] {"abc"});
        assertThat(LogText.fields(fields, 4096)).isEqualTo("{\"tran_id\":\"MJ-1\",\"verify_sign\":\"***\"}");
    }
}
