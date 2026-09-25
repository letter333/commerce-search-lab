package org.letter33.commercesearchlab.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "search.elasticsearch", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfiguration {
    @Bean(destroyMethod = "close")
    Rest5Client elasticsearchRestClient(ElasticsearchProperties properties) {
        var host = HttpHost.create(properties.validatedEndpoint());
        var ssl = readTrust(properties.caCertificate());
        char[] password = readPassword(properties.passwordFile());
        var credentials = new BasicCredentialsProvider();
        credentials.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(properties.username(),
                password));
        var connect = Timeout.ofMilliseconds(properties.connectTimeout().toMillis());
        var request = Timeout.ofMilliseconds(properties.requestTimeout().toMillis());
        try {
            return Rest5Client.builder(host)
                .setSSLContext(ssl)
                .setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(credentials))
                .setConnectionConfigCallback(connection -> connection.setConnectTimeout(connect).setSocketTimeout(request))
                .setRequestConfigCallback(config -> config.setConnectionRequestTimeout(connect).setResponseTimeout(request))
                .setConnectionManagerCallback(manager -> manager.setDefaultTlsConfig(
                        TlsConfig.custom().setHandshakeTimeout(connect).build()))
                .build();
        }
        catch (RuntimeException | Error failure) {
            Arrays.fill(password, '\0');
            throw failure;
        }
    }

    @Bean
    Jackson3JsonpMapper elasticsearchJsonMapper() {
        return new Jackson3JsonpMapper(JsonMapper.builder().build());
    }

    // Rest5Client is the single owner of the underlying HTTP resources.
    @Bean(destroyMethod = "")
    Rest5ClientTransport elasticsearchTransport(Rest5Client rest, Jackson3JsonpMapper mapper) {
        return new Rest5ClientTransport(rest, mapper);
    }

    @Bean(destroyMethod = "")
    ElasticsearchClient elasticsearchClient(Rest5ClientTransport transport) {
        return new ElasticsearchClient(transport);
    }

    private static SSLContext readTrust(String file) {
        try (var input = Files.newInputStream(Path.of(file))) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.isEmpty()) {
                throw new GeneralSecurityException();
            }
            var trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(null, null);
            int index = 0;
            for (var certificate : certificates) {
                var ca = (X509Certificate) certificate;
                ca.checkValidity();
                if (ca.getBasicConstraints() < 0) {
                    throw new GeneralSecurityException();
                }
                trustStore.setCertificateEntry("ca-" + index++, ca);
            }
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trustStore);
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(null, managers.getTrustManagers(), null);
            return ssl;
        }
        catch (IOException | GeneralSecurityException | InvalidPathException | SecurityException failure) {
            // File/parser exceptions can embed private paths or input; do not retain their causes.
            throw new IllegalArgumentException("Elasticsearch CA file must contain valid X.509 CA certificates");
        }
    }

    private static char[] readPassword(String file) {
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of(file))) {
            bytes = input.readNBytes(4097);
        }
        catch (IOException | InvalidPathException | SecurityException failure) {
            throw new IllegalArgumentException("Elasticsearch password file could not be read");
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            // Accept one conventional text-file newline without trimming meaningful password spaces.
            value = value.replaceFirst("\\r?\\n\\z", "");
            if (bytes.length > 4096 || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
                throw invalidPassword();
            }
            return value.toCharArray();
        }
        catch (java.nio.charset.CharacterCodingException failure) {
            throw invalidPassword();
        }
        finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static IllegalArgumentException invalidPassword() {
        return new IllegalArgumentException(
                "Elasticsearch password file must contain one nonblank line without control characters (max 4096 bytes)");
    }
}
