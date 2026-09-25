package org.letter33.commercesearchlab.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.StringReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ElasticsearchConfigurationTest {
    @TempDir Path temp;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ElasticsearchConfiguration.class);

    @Test
    void disabledClientNeedsNeitherEngineNorCredentialFiles() {
        runner.withPropertyValues("search.elasticsearch.password-file=missing",
                "search.elasticsearch.ca-certificate=missing").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ElasticsearchClient.class);
            assertThat(context).doesNotHaveBean(Rest5Client.class);
        });
    }

    @Test
    void enabledClientUsesConfiguredHttpsHostWithoutAnEagerRequest() throws Exception {
        configured().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ElasticsearchClient.class);
            assertThat(context).hasSingleBean(Rest5Client.class);
            var rest = context.getBean(Rest5Client.class);
            assertThat(rest.isRunning()).isTrue();
            assertThat(rest.getNodes().getFirst().getHost().toURI()).isEqualTo("https://localhost:1");
        });
    }

    private ApplicationContextRunner configured() throws Exception {
        Path password = temp.resolve("password.txt");
        Files.writeString(password, "unit-test-value");
        Path ca = Path.of(getClass().getResource("/search/test-ca.crt").toURI());
        return runner.withPropertyValues("search.elasticsearch.enabled=true",
                "search.elasticsearch.url=https://localhost:1", "search.elasticsearch.username=elastic",
                "search.elasticsearch.password-file=" + password,
                "search.elasticsearch.ca-certificate=" + ca);
    }

    @Test
    void enabledClientRequiresConnectionSettings() {
        runner.withPropertyValues("search.elasticsearch.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "search.elasticsearch.url must be an HTTPS origin without credentials, query or fragment");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "first\nsecond", "bad\u0000value", "bad\tvalue"})
    void rejectsInvalidPasswordFileContent(String content) throws Exception {
        var configured = configured();
        Files.writeString(temp.resolve("password.txt"), content);
        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Elasticsearch password file must contain one nonblank line without control characters (max 4096 bytes)");
        });
    }

    @Test
    void rejectsOversizedPasswordFile() throws Exception {
        var configured = configured();
        Files.writeString(temp.resolve("password.txt"), "x".repeat(4097));
        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Elasticsearch password file must contain one nonblank line without control characters (max 4096 bytes)");
        });
    }

    @Test
    void missingPasswordFileDoesNotDiscloseItsPath() throws Exception {
        configured().withPropertyValues("search.elasticsearch.password-file=" + temp.resolve("private-path-marker"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Elasticsearch password file could not be read");
                    assertThat(stackTrace(context.getStartupFailure())).doesNotContain("private-path-marker");
                });
    }

    @Test
    void missingCaFileDoesNotDiscloseItsPath() throws Exception {
        configured().withPropertyValues("search.elasticsearch.ca-certificate=" + temp.resolve("private-path-marker"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Elasticsearch CA file must contain valid X.509 CA certificates");
                    assertThat(stackTrace(context.getStartupFailure())).doesNotContain("private-path-marker");
                });
    }

    @Test
    void malformedCaDoesNotDiscloseItsContents() throws Exception {
        Path ca = temp.resolve("invalid-ca.crt");
        Files.writeString(ca, "private-content-marker");
        configured().withPropertyValues("search.elasticsearch.ca-certificate=" + ca).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Elasticsearch CA file must contain valid X.509 CA certificates");
            assertThat(stackTrace(context.getStartupFailure())).doesNotContain("private-content-marker");
        });
    }

    private static String stackTrace(Throwable failure) {
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    @Test
    void configuredMapperRoundTripsKoreanTextAndNumericFields() throws Exception {
        configured().run(context -> {
            var mapper = context.getBean(Jackson3JsonpMapper.class);
            var sample = new Sample("블랙 후드티", 39000, true, List.of("M", "L"));
            var output = new StringWriter();
            try (var generator = mapper.jsonProvider().createGenerator(output)) {
                mapper.serialize(sample, generator);
            }
            try (var parser = mapper.jsonProvider().createParser(new StringReader(output.toString()))) {
                assertThat(mapper.deserialize(parser, Sample.class)).isEqualTo(sample);
            }
            assertThat(output.toString()).contains("블랙 후드티", "39000");
        });
    }

    @Test
    void contextShutdownClosesOwnedHttpResources() throws Exception {
        var owned = new AtomicReference<Rest5Client>();
        configured().run(context -> {
            owned.set(context.getBean(Rest5Client.class));
            assertThat(owned.get().isRunning()).isTrue();
        });
        assertThat(owned.get().isRunning()).isFalse();
    }

    @Test
    void laterBeanFailureAlsoClosesAlreadyCreatedHttpResources() throws Exception {
        var owned = new AtomicReference<Rest5Client>();
        configured().withInitializer(context -> ((GenericApplicationContext) context).registerBean(
                "deliberatelyFailingBean", Object.class, () -> {
                    owned.set(context.getBean(Rest5Client.class));
                    throw new IllegalStateException("deliberate startup failure");
                })).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("deliberate startup failure");
                });
        assertThat(owned.get()).isNotNull();
        assertThat(owned.get().isRunning()).isFalse();
    }

    @Test
    void stalledTlsPeerCannotLeaveARequestWaitingIndefinitely() throws Exception {
        try (var peer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            configured().withPropertyValues("search.elasticsearch.url=https://127.0.0.1:" + peer.getLocalPort(),
                    "search.elasticsearch.connect-timeout=100ms", "search.elasticsearch.request-timeout=200ms")
                    .run(context -> assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                            assertThatThrownBy(() -> context.getBean(ElasticsearchClient.class).info())
                                    .isInstanceOf(IOException.class)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void acceptsASingleTextFileNewline(String newline) throws Exception {
        var configured = configured();
        Files.writeString(temp.resolve("password.txt"), "unit-test-value" + newline);
        configured.run(context -> assertThat(context).hasSingleBean(ElasticsearchClient.class));
    }

    @Test
    void rejectsMalformedUtf8Password() throws Exception {
        var configured = configured();
        Files.write(temp.resolve("password.txt"), new byte[] {(byte) 0xc3, (byte) 0x28});
        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Elasticsearch password file must contain one nonblank line without control characters (max 4096 bytes)");
        });
    }

    public record Sample(String title, long price, boolean active, List<String> sizes) {}
}
