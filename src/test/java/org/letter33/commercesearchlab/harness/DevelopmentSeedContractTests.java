package org.letter33.commercesearchlab.harness;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Validates the authored development fixtures, not search relevance or retrieval quality.
 * Matching below only checks the explicit information_need and filter annotations.
 */
@Tag("seed")
class DevelopmentSeedContractTests {

    private static final Set<String> CATEGORIES = Set.of("맨투맨", "후드티", "셔츠", "니트", "티셔츠");
    private static final Set<String> CORRECTION_TYPES = Set.of("typo", "keyboard_layout");
    private static final Set<String> CASE_TYPES = Set.of("category", "attribute", "brand_category",
            "filter", "protected_sku", "protected_brand", "normalization", "typo", "keyboard_layout",
            "alias", "no_answer");
    private static final Set<String> NEED_FIELDS = Set.of("id", "sku", "title", "brand", "brand_en",
            "category", "color", "material", "material_contains", "fit", "lining", "sleeve",
            "price_min", "price_max");
    private static final Set<String> FILTER_FIELDS = Set.of("category", "color", "price_min", "price_max");
    private static JsonNode catalog;
    private static JsonNode scenarios;
    private static List<JsonNode> products;
    private static List<JsonNode> cases;

    @BeforeAll
    static void loadSeedSnapshots() {
        Path directory = Path.of(System.getProperty("seed.directory", "docs"));
        JsonMapper mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        catalog = mapper.readTree(directory.resolve("catalog-50.json").toFile());
        scenarios = mapper.readTree(directory.resolve("search-scenarios-30.json").toFile());
        products = array(catalog, "products", "catalog");
        cases = array(scenarios, "cases", "scenarios");
    }

    @Test
    void metadataIdentifiesTheSameVersionedDevelopmentSnapshot() {
        assertEquals("0.1", text(catalog, "schema_version", "catalog"));
        assertEquals("0.1", text(scenarios, "schema_version", "scenarios"));
        assertEquals("catalog-v0.1", text(catalog, "dataset_id", "catalog"));
        assertEquals("queries-dev-v0.1", text(scenarios, "dataset_id", "scenarios"));
        assertEquals(catalog.path("dataset_id"), scenarios.path("catalog_id"),
                "Scenario expectations must reference the loaded catalog snapshot");
        text(catalog, "purpose", "catalog");
        text(catalog, "provenance", "catalog");
        text(scenarios, "purpose", "scenarios");
        text(scenarios, "judgment_provenance", "scenarios");
    }

    @Test
    void currentSnapshotKeepsTheDocumentedCountsAndDevelopmentOnlySplit() {
        assertEquals(50, products.size(), "Update the reviewed seed contract when expanding the catalog");
        assertEquals(30, cases.size(), "Update the reviewed seed contract when expanding scenarios");
        Map<String, Long> counts = new HashMap<>();
        products.forEach(product -> counts.merge(text(product, "category", "product"), 1L, Long::sum));
        assertEquals(CATEGORIES, counts.keySet());
        counts.forEach((category, count) -> assertEquals(10L, count, category));
        assertEquals(6, cases.stream().filter(DevelopmentSeedContractTests::requiresCorrection).count());
        assertEquals(24, cases.stream().filter(scenario -> !requiresCorrection(scenario)).count());
        assertEquals(3, cases.stream().filter(scenario -> "no_answer".equals(scenario.path("type").stringValue())).count());
        cases.forEach(scenario -> assertEquals("development", text(scenario, "split", "case"),
                "These seeds must not be presented as a held-out final evaluation set"));
    }

    @Test
    void productAndScenarioIdentifiersAreUnique() {
        uniqueField(products, "id", false, "product");
        uniqueField(products, "sku", true, "product");
        uniqueField(cases, "id", false, "case");
    }

    @Test
    void oneIntentGroupCannotCrossDatasetSplits() {
        Map<String, String> splits = new HashMap<>();
        for (JsonNode scenario : cases) {
            String id = text(scenario, "id", "case");
            String group = text(scenario, "intent_group", id);
            String split = text(scenario, "split", id);
            String previous = splits.putIfAbsent(group, split);
            assertTrue(previous == null || previous.equals(split),
                    id + ": intent_group " + group + " crosses splits " + previous + " and " + split);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyProductHasValidIndexableFields() {
        return products.stream().map(product -> dynamicTest(product.path("id").stringValue(), () -> {
            String id = text(product, "id", "product");
            for (String field : List.of("sku", "title", "brand", "brand_en", "category", "color",
                    "material", "fit", "lining", "sleeve")) {
                text(product, field, id);
            }
            assertTrue(CATEGORIES.contains(product.path("category").stringValue()), id + ": unsupported category");
            stringSet(product, "sizes", id, false);
            integer(product, "price_krw", id, 0);
            integer(product, "version", id, 1);
            bool(product, "active", id);
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyScenarioHasConsistentExpectationsAndCorrectionPolicy() {
        return cases.stream().map(scenario -> dynamicTest(scenario.path("id").stringValue(),
                () -> validateScenario(scenario)));
    }

    private static void validateScenario(JsonNode scenario) {
        String id = text(scenario, "id", "case");
        String type = text(scenario, "type", id);
        assertTrue(CASE_TYPES.contains(type), id + ": unsupported case type " + type);
        text(scenario, "intent_group", id);
        text(scenario, "split", id);
        JsonNode request = object(scenario, "request", id);
        text(request, "q", id + ".request");
        assertEquals(1, integer(request, "page", id + ".request", 1),
                id + ": Hit@5 and MRR@10 require rankings from the first page");
        assertTrue(integer(request, "size", id + ".request", 10) <= 100,
                id + ": evaluation request size must be between 10 and 100");
        JsonNode filters = object(request, "filters", id + ".request");
        JsonNode need = object(scenario, "information_need", id);
        assertFalse(need.isEmpty(), id + ": information_need must describe the relevance judgment");
        validateConditions(filters, FILTER_FIELDS, id + ".request.filters");
        validateConditions(need, NEED_FIELDS, id + ".information_need");
        filters.properties().forEach(entry -> assertEquals(entry.getValue(), need.path(entry.getKey()),
                id + ": information_need must preserve request filter " + entry.getKey()));

        JsonNode expected = object(scenario, "expected", id);
        String scope = text(expected, "judgment_scope", id);
        assertTrue(scope.contains(catalog.path("dataset_id").stringValue()),
                id + ": judgment_scope must identify the catalog snapshot");
        Set<String> relevantIds = stringSet(expected, "relevant_product_ids", id, true);
        Set<String> catalogIds = new HashSet<>();
        Set<String> annotatedMatches = new HashSet<>();
        for (JsonNode product : products) {
            String productId = text(product, "id", "product");
            catalogIds.add(productId);
            if (bool(product, "active", productId) && matches(product, need) && matches(product, filters)) {
                annotatedMatches.add(productId);
            }
        }
        assertTrue(catalogIds.containsAll(relevantIds), id + ": expected IDs must exist in the referenced catalog");
        assertEquals(annotatedMatches, relevantIds,
                id + ": expected IDs must exactly match explicit information_need and filters, independent of ranking");
        boolean zero = bool(expected, "expected_zero_results", id);
        assertEquals(relevantIds.isEmpty(), zero, id + ": zero-result flag disagrees with relevant IDs");
        assertEquals("no_answer".equals(type), zero, id + ": no-answer cases must remain a separate cohort");

        JsonNode correction = object(expected, "correction", id);
        assertFalse(bool(correction, "auto_apply", id), id + ": never silently replace the original query");
        Set<String> suggestions = stringSet(correction, "acceptable_suggestions", id, true);
        boolean requiresCorrection = requiresCorrection(scenario);
        assertEquals(requiresCorrection ? "suggest_only" : "no_spelling_suggestion",
                text(correction, "policy", id), id + ": correction policy must match the scenario type");
        assertEquals(requiresCorrection, !suggestions.isEmpty(), id + ": correction suggestions disagree with policy");
        assertFalse(suggestions.contains(request.path("q").stringValue()), id + ": a correction must differ from the original query");
        assertEquals(requiresCorrection ? "after_explicit_suggestion_acceptance" : "original_request",
                text(expected, "retrieval_evaluation_stage", id), id + ": original and accepted-suggestion results must stay separate");
        assertEquals(requiresCorrection, bool(expected, "also_report_original_query_metrics", id),
                id + ": accepted-suggestion evaluation must also report the original query separately");
    }

    private static boolean requiresCorrection(JsonNode scenario) {
        return CORRECTION_TYPES.contains(scenario.path("type").stringValue());
    }

    private static void validateConditions(JsonNode conditions, Set<String> allowedFields, String context) {
        for (var entry : conditions.properties()) {
            String key = entry.getKey();
            assertTrue(allowedFields.contains(key), context + ": unsupported condition " + key);
            if (key.equals("price_min") || key.equals("price_max")) {
                integer(conditions, key, context, 0);
            } else {
                text(conditions, key, context);
            }
        }
        if (conditions.has("price_min") && conditions.has("price_max")) {
            assertTrue(conditions.path("price_min").longValue() <= conditions.path("price_max").longValue(),
                    context + ": price_min must not exceed price_max");
        }
    }

    private static boolean matches(JsonNode product, JsonNode conditions) {
        return conditions.properties().stream().allMatch(entry -> switch (entry.getKey()) {
            case "price_min" -> product.path("price_krw").longValue() >= entry.getValue().longValue();
            case "price_max" -> product.path("price_krw").longValue() <= entry.getValue().longValue();
            case "material_contains" -> product.path("material").stringValue().contains(entry.getValue().stringValue());
            default -> product.path(entry.getKey()).equals(entry.getValue());
        });
    }

    private static void uniqueField(List<JsonNode> rows, String field, boolean ignoreCase, String context) {
        Set<String> values = new HashSet<>();
        for (JsonNode row : rows) {
            String value = text(row, field, context);
            assertTrue(values.add(ignoreCase ? value.toUpperCase(Locale.ROOT) : value),
                    context + ": duplicate " + field + " " + value);
        }
    }

    private static JsonNode object(JsonNode node, String field, String context) {
        JsonNode value = node.path(field);
        assertTrue(value.isObject(), context + "." + field + " must be an object");
        return value;
    }

    private static List<JsonNode> array(JsonNode node, String field, String context) {
        JsonNode value = node.path(field);
        assertTrue(value.isArray(), context + "." + field + " must be an array");
        return List.copyOf(value.values());
    }

    private static Set<String> stringSet(JsonNode node, String field, String context, boolean allowEmpty) {
        List<JsonNode> values = array(node, field, context);
        assertTrue(allowEmpty || !values.isEmpty(), context + "." + field + " must not be empty");
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            assertTrue(value.isString() && !value.stringValue().isBlank(), context + "." + field + " must contain nonblank strings");
            assertTrue(result.add(value.stringValue()), context + "." + field + " contains duplicate " + value.stringValue());
        }
        return result;
    }

    private static String text(JsonNode node, String field, String context) {
        JsonNode value = node.path(field);
        assertTrue(value.isString() && !value.stringValue().isBlank(), context + "." + field + " must be a nonblank string");
        return value.stringValue();
    }

    private static long integer(JsonNode node, String field, String context, long minimum) {
        JsonNode value = node.path(field);
        assertTrue(value.isIntegralNumber() && value.canConvertToLong(), context + "." + field + " must be an integer within long range");
        long number = value.longValue();
        assertTrue(number >= minimum, context + "." + field + " must be >= " + minimum);
        return number;
    }

    private static boolean bool(JsonNode node, String field, String context) {
        JsonNode value = node.path(field);
        assertTrue(value.isBoolean(), context + "." + field + " must be a boolean");
        return value.booleanValue();
    }
}
