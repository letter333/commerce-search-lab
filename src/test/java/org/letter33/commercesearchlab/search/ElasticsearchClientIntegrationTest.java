package org.letter33.commercesearchlab.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("elasticsearch")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ElasticsearchClientIntegrationTest {
    @Autowired ElasticsearchClient client;
    @Autowired ElasticsearchProperties properties;
    @TempDir Path temp;

    @Test
    void readsRealEngineInformationWithConfiguredTlsAndAuthentication() throws Exception {
        var info = client.info();
        assertThat(info.clusterName()).isEqualTo("commerce-search-lab");
        assertThat(info.version().number()).isEqualTo("9.4.7");
        assertThat(info.clusterUuid()).matches("[A-Za-z0-9_-]{22}");
        System.out.printf("Verified Elasticsearch %s, cluster %s, UUID %s%n",
                info.version().number(), info.clusterName(), info.clusterUuid());
    }

    @Test
    void rejectsWrongPasswordAgainstRealEngine() throws Exception {
        Path wrongPassword = temp.resolve("wrong-password.txt");
        Files.writeString(wrongPassword, UUID.randomUUID().toString());
        configured().withPropertyValues("search.elasticsearch.password-file=" + wrongPassword).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(ElasticsearchClient.class).info())
                    .isInstanceOfSatisfying(ElasticsearchException.class,
                            failure -> assertThat(failure.status()).isEqualTo(401));
        });
    }

    @Test
    void rejectsUntrustedCaAgainstRealEngine() throws Exception {
        Path unrelatedCa = Path.of(getClass().getResource("/search/test-ca.crt").toURI());
        configured().withPropertyValues("search.elasticsearch.ca-certificate=" + unrelatedCa).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(ElasticsearchClient.class).info())
                    .isInstanceOf(IOException.class)
                    .satisfies(failure -> assertThat(hasCause(failure, SSLHandshakeException.class)).isTrue());
        });
    }

    @Test
    void acceptsConventionalNewlineWithoutChangingExistingCredentialFile() throws Exception {
        Path copy = temp.resolve("password-with-newline.txt");
        Files.writeString(copy, Files.readString(Path.of(properties.passwordFile())).replaceFirst("\\r?\\n\\z", "") + "\r\n");
        configured().withPropertyValues("search.elasticsearch.password-file=" + copy).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ElasticsearchClient.class).info().version().number()).isEqualTo("9.4.7");
        });
    }

    private ApplicationContextRunner configured() {
        return new ApplicationContextRunner().withUserConfiguration(ElasticsearchConfiguration.class)
                .withPropertyValues("search.elasticsearch.enabled=true",
                        "search.elasticsearch.url=" + properties.url(),
                        "search.elasticsearch.username=" + properties.username(),
                        "search.elasticsearch.password-file=" + properties.passwordFile(),
                        "search.elasticsearch.ca-certificate=" + properties.caCertificate(),
                        "search.elasticsearch.connect-timeout=" + properties.connectTimeout(),
                        "search.elasticsearch.request-timeout=" + properties.requestTimeout());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }
}
