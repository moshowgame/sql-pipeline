package com.sqlpipeline.datasource.crypto;

import com.sqlpipeline.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        CryptoProperties properties = new CryptoProperties();
        properties.setKey(Base64.getEncoder().encodeToString(new byte[32]));
        cryptoService = new CryptoService(properties);
    }

    @Test
    void roundTrip() {
        String plain = "P@ssw0rd-中文-123";
        String encrypted = cryptoService.encrypt(plain);
        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(cryptoService.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void ivIsRandomPerEncryption() {
        assertThat(cryptoService.encrypt("same")).isNotEqualTo(cryptoService.encrypt("same"));
    }

    @Test
    void rejectWrongKeyLength() {
        CryptoProperties properties = new CryptoProperties();
        properties.setKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> new CryptoService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void rejectBlankKey() {
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SQL_PIPELINE_AES_KEY");
    }

    @Test
    void tamperedCipherTextFails() {
        String encrypted = cryptoService.encrypt("secret");
        byte[] bytes = Base64.getDecoder().decode(encrypted);
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> cryptoService.decrypt(tampered)).isInstanceOf(BizException.class);
    }
}
