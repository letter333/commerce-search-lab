package org.letter33.commercesearchlab.search;

import java.net.URI;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchPropertiesTest {

	@Test
	void acceptsHttpsEndpointWithoutReadingLocalFiles() {
		var properties = properties("https://localhost:9200");

		assertThat(properties.validatedEndpoint()).isEqualTo(URI.create("https://localhost:9200"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://localhost/", "HTTPS://localhost:9200", "https://[::1]:9200" })
	void acceptsHttpsEndpointVariants(String url) {
		assertThat(properties(url).validatedEndpoint()).isEqualTo(URI.create(url));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "http://localhost:9200", "localhost:9200", "https:///",
			"https://username:secret@localhost:9200", "https://localhost:9200?password=secret",
			"https://localhost:9200#secret", "https://localhost:9200/base", "https://localhost:65536",
			"https://localhost:0", "https://localhost:-1", "https://localhost:secret",
			"https://local host/secret", "https://localhost:9200/%2f", "https://localhost:9200/\nsecret" })
	void rejectsInvalidEndpointWithoutExposingInput(String url) {
		assertThatThrownBy(() -> properties(url).validatedEndpoint())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("search.elasticsearch.url must be an HTTPS origin without credentials, query or fragment")
				.hasNoCause();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "elastic\nsecret", "elastic\rsecret", "elastic\tsecret", "elastic\u007fsecret" })
	void rejectsMissingOrControlCharactersInUsername(String username) {
		var properties = new ElasticsearchProperties("https://localhost:9200", username, "password-file", "ca-file",
				Duration.ofSeconds(5), Duration.ofSeconds(40));

		assertThatThrownBy(properties::validatedEndpoint)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("search.elasticsearch.username must be nonblank and contain no control characters")
				.hasNoCause();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void rejectsMissingPasswordFilePath(String path) {
		var properties = new ElasticsearchProperties("https://localhost:9200", "elastic", path, "ca-file",
				Duration.ofSeconds(5), Duration.ofSeconds(40));

		assertThatThrownBy(properties::validatedEndpoint)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("search.elasticsearch.password-file must be nonblank")
				.hasNoCause();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void rejectsMissingCaCertificatePath(String path) {
		var properties = new ElasticsearchProperties("https://localhost:9200", "elastic", "password-file", path,
				Duration.ofSeconds(5), Duration.ofSeconds(40));

		assertThatThrownBy(properties::validatedEndpoint)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("search.elasticsearch.ca-certificate must be nonblank")
				.hasNoCause();
	}

	@ParameterizedTest
	@MethodSource("invalidTimeouts")
	void rejectsInvalidConnectTimeout(Duration timeout) {
		var properties = new ElasticsearchProperties("https://localhost:9200", "elastic", "password-file", "ca-file",
				timeout, Duration.ofSeconds(40));

		assertThatThrownBy(properties::validatedEndpoint)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("search.elasticsearch.connect-timeout must be between 1ms and 5m")
				.hasNoCause();
	}

	@ParameterizedTest
	@MethodSource("invalidTimeouts")
	void rejectsInvalidRequestTimeout(Duration timeout) {
		var properties = new ElasticsearchProperties("https://localhost:9200", "elastic", "password-file", "ca-file",
				Duration.ofSeconds(5), timeout);

		assertThatThrownBy(properties::validatedEndpoint)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("search.elasticsearch.request-timeout must be between 1ms and 5m")
				.hasNoCause();
	}

	@ParameterizedTest
	@ValueSource(longs = { 1, 300_000 })
	void acceptsTimeoutBoundaries(long milliseconds) {
		var timeout = Duration.ofMillis(milliseconds);
		var properties = new ElasticsearchProperties("https://localhost:9200", "elastic", "password-file", "ca-file",
				timeout, timeout);

		assertThat(properties.validatedEndpoint()).isEqualTo(URI.create("https://localhost:9200"));
	}

	@Test
	void stringRepresentationDoesNotExposeConfigurationInputs() {
		var properties = new ElasticsearchProperties("https://user:password@localhost", "private-user",
				"private-password-file", "private-ca-file", Duration.ofSeconds(5), Duration.ofSeconds(40));

		assertThat(properties.toString()).isEqualTo("ElasticsearchProperties[redacted]");
	}

	private static Stream<Duration> invalidTimeouts() {
		return Stream.of(null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(999_999),
				Duration.ofMinutes(5).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE));
	}

	private static ElasticsearchProperties properties(String url) {
		return new ElasticsearchProperties(url, "elastic", "missing-password-file", "missing-ca-file",
				Duration.ofSeconds(5), Duration.ofSeconds(40));
	}
}
