///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.junit.jupiter:junit-jupiter-engine:5.12.2
//DEPS org.junit.platform:junit-platform-console:1.12.2
//DEPS com.google.code.gson:gson:2.14.0

//DEPS ../ijq.java

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.platform.console.ConsoleLauncher;

// JUnit5 Test class for ijq
public class ijqTest {

    private static JsonElement json(String content) {
        return JsonParser.parseString(content);
    }

    // ==================== SelectQueryParser ====================

    @Test
    public void testParseSelectAll() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("*");
        assertTrue(sq.selectAll);
        assertTrue(sq.fields.isEmpty());
        assertNull(sq.whereClause);
        assertNull(sq.limitCount);
    }

    @Test
    public void testParseSingleFieldNoAlias() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("name");
        assertFalse(sq.selectAll);
        assertEquals(1, sq.fields.size());
        assertEquals("name", sq.fields.get(0).name);
        assertNull(sq.fields.get(0).alias);
    }

    @Test
    public void testParseFieldWithAlias() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("name as n");
        assertEquals(1, sq.fields.size());
        assertEquals("name", sq.fields.get(0).name);
        assertEquals("n", sq.fields.get(0).alias);
    }

    @Test
    public void testParseCommaSeparatedFields() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("a, b");
        assertEquals(2, sq.fields.size());
        assertEquals("a", sq.fields.get(0).name);
        assertEquals("b", sq.fields.get(1).name);
    }

    @Test
    public void testParseFieldListInBraces() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("{a, b as c}");
        assertEquals(2, sq.fields.size());
        assertEquals("a", sq.fields.get(0).name);
        assertNull(sq.fields.get(0).alias);
        assertEquals("b", sq.fields.get(1).name);
        assertEquals("c", sq.fields.get(1).alias);
    }

    @Test
    public void testParseTop() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("top 5 *");
        assertTrue(sq.selectAll);
        assertEquals(0, sq.limitOffset);
        assertEquals(5, sq.limitCount);
    }

    @Test
    public void testParseLimitSingleCount() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("limit 3 *");
        assertEquals(0, sq.limitOffset);
        assertEquals(3, sq.limitCount);
    }

    @Test
    public void testParseLimitOffsetAndCount() {
        // Note: the tokenizer always splits on ',', so "limit offset,count" is not
        // parsed as a single token here; only the plain "limit N" form works reliably.
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("limit 4 *");
        assertEquals(0, sq.limitOffset);
        assertEquals(4, sq.limitCount);
    }

    @Test
    public void testParseWhereEquals() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where field = 'value'");
        assertTrue(sq.whereClause.evaluate(json("{\"field\": \"value\"}")));
        assertFalse(sq.whereClause.evaluate(json("{\"field\": \"other\"}")));
    }

    @Test
    public void testParseWhereWithAnd() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where a = '1' and b = '2'");
        assertTrue(sq.whereClause.evaluate(json("{\"a\": \"1\", \"b\": \"2\"}")));
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"1\", \"b\": \"3\"}")));
    }

    @Test
    public void testParseWhereWithOr() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where a = '1' or a = '2'");
        assertTrue(sq.whereClause.evaluate(json("{\"a\": \"1\"}")));
        assertTrue(sq.whereClause.evaluate(json("{\"a\": \"2\"}")));
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"3\"}")));
    }

    @Test
    public void testParseWhereWithNot() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where not a = '1'");
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"1\"}")));
        assertTrue(sq.whereClause.evaluate(json("{\"a\": \"2\"}")));
    }

    // ==================== Known bugs (currently FAIL; fix ijq.java to make them pass) ====================

    @Test
    public void testBug_whereWithThreeAndConditionsDropsThirdClause() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where a = '1' and b = '2' and c = '3'");
        // a and b match, but c does not -> the whole AND chain should be false
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"1\", \"b\": \"2\", \"c\": \"wrong\"}")),
                "third AND clause ('c') is being ignored by the parser");
    }

    @Test
    public void testBug_whereMixedAndOrDropsAndClause() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where a = '1' and b = '2' or c = '3'");
        // a matches but b does not (so the AND group is false), and c does not match either
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"1\", \"b\": \"no\", \"c\": \"no\"}")),
                "'b' clause of the AND group is being ignored, so the OR wrongly evaluates to true");
    }

    @Test
    public void testBug_whereNotFollowedByAndDropsSecondClause() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where not a = '1' and b = '2'");
        // not(a = '1') is true, but b != '2' so the AND should make the whole thing false
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"2\", \"b\": \"no\"}")),
                "'and b = ...' clause after NOT is being ignored by the parser");
    }

    @Test
    public void testBug_limitOffsetCommaCountSyntaxIsBroken() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("limit 2,3 *");
        assertEquals(2, sq.limitOffset, "offset should be parsed from 'limit 2,3'");
        assertEquals(3, sq.limitCount, "count should be parsed from 'limit 2,3'");
        assertTrue(sq.selectAll, "'*' should still be recognized as select-all after the LIMIT clause");
    }

    @Test
    public void testBug_notEqualsOperatorFalseForMissingField() {
        JsonObject obj = json("{\"other\": 1}").getAsJsonObject();
        assertTrue(new ijq.ComparisonExpression("missing", "<>", "5").evaluate(obj),
                "missing field should be considered not-equal ('<>') to any given value");
    }

    // ==================== ComparisonExpression ====================

    @Test
    public void testComparisonNumericOperators() {
        JsonObject obj = json("{\"n\": 10}").getAsJsonObject();
        assertTrue(new ijq.ComparisonExpression("n", ">", "5").evaluate(obj));
        assertTrue(new ijq.ComparisonExpression("n", "<", "20").evaluate(obj));
        assertTrue(new ijq.ComparisonExpression("n", ">=", "10").evaluate(obj));
        assertTrue(new ijq.ComparisonExpression("n", "<=", "10").evaluate(obj));
        assertTrue(new ijq.ComparisonExpression("n", "<>", "5").evaluate(obj));
        assertFalse(new ijq.ComparisonExpression("n", "=", "5").evaluate(obj));
    }

    @Test
    public void testComparisonMissingOrNullField() {
        JsonObject obj = json("{\"a\": null}").getAsJsonObject();
        assertFalse(new ijq.ComparisonExpression("a", "=", "x").evaluate(obj));
        assertFalse(new ijq.ComparisonExpression("missing", "=", "x").evaluate(obj));
    }

    @Test
    public void testComparisonNonObjectElement() {
        assertFalse(new ijq.ComparisonExpression("a", "=", "x").evaluate(json("[1,2,3]")));
    }

    // ==================== '*' field refers to whole element ====================

    @Test
    public void testComparisonStarFieldMatchesLiteralValue() {
        assertTrue(new ijq.ComparisonExpression("*", "=", "x").evaluate(json("\"x\"")));
        assertFalse(new ijq.ComparisonExpression("*", "=", "x").evaluate(json("\"y\"")));
    }

    @Test
    public void testComparisonStarFieldNullLiteralOnProjectedValues() {
        ijq.ComparisonExpression notNull = new ijq.ComparisonExpression("*", "<>", null, true);
        assertTrue(notNull.evaluate(json("\"x\"")));
        assertTrue(notNull.evaluate(json("42")));
        assertFalse(notNull.evaluate(json("null")));

        ijq.ComparisonExpression isNull = new ijq.ComparisonExpression("*", "=", null, true);
        assertFalse(isNull.evaluate(json("\"x\"")));
        assertTrue(isNull.evaluate(json("null")));
    }

    @Test
    public void testComparisonStarFieldNumericOrdering() {
        assertTrue(new ijq.ComparisonExpression("*", ">", "5").evaluate(json("10")));
        assertFalse(new ijq.ComparisonExpression("*", ">", "5").evaluate(json("1")));
    }

    @Test
    public void testExecuteWhereFilterAfterSingleFieldProjectionUsingStar() {
        // "select some_field" turns each item into the literal field value; a
        // subsequent "where * <> null" must be able to filter those literals.
        ijq.SelectQuery projection = ijq.SelectQueryParser.parse("b");
        List<ijq.JsonItem> projected = projection.execute(items(
                "{\"b\": \"x\"}", "{\"b\": null}", "{\"a\": 1}", "{\"b\": \"y\"}"));

        ijq.SelectQuery filter = ijq.SelectQueryParser.parse("* where * <> null");
        List<ijq.JsonItem> result = filter.execute(projected);

        assertEquals(2, result.size());
        assertEquals("x", result.get(0).getValue().getAsString());
        assertEquals("y", result.get(1).getValue().getAsString());
    }

    // ==================== NULL literal comparisons ====================

    @Test
    public void testParseWhereEqualsNullMatchesNullOrMissingField() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where b = null");
        assertTrue(sq.whereClause.evaluate(json("{\"a\": 1, \"b\": null}")));
        assertTrue(sq.whereClause.evaluate(json("{\"a\": 1}")));
        assertFalse(sq.whereClause.evaluate(json("{\"a\": 1, \"b\": \"x\"}")));
    }

    @Test
    public void testParseWhereNotEqualsNullMatchesPresentNonNullField() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where b <> null");
        assertFalse(sq.whereClause.evaluate(json("{\"a\": 1, \"b\": null}")));
        assertFalse(sq.whereClause.evaluate(json("{\"a\": 1}")));
        assertTrue(sq.whereClause.evaluate(json("{\"a\": 1, \"b\": \"x\"}")));
    }

    @Test
    public void testParseWhereNullIsCaseInsensitiveAndUnquoted() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where b = NULL");
        assertTrue(sq.whereClause.evaluate(json("{\"b\": null}")));
    }

    @Test
    public void testParseWhereQuotedNullStringIsNotTreatedAsNullLiteral() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where b = 'null'");
        // Quoted 'null' is the literal string "null", not the JSON null literal.
        assertTrue(sq.whereClause.evaluate(json("{\"b\": \"null\"}")));
        assertFalse(sq.whereClause.evaluate(json("{\"b\": null}")));
        assertFalse(sq.whereClause.evaluate(json("{\"a\": 1}")));
    }

    @Test
    public void testParseWhereOrderingOperatorWithNullThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ijq.SelectQueryParser.parse("* where b > null"));
        assertThrows(IllegalArgumentException.class,
                () -> ijq.SelectQueryParser.parse("* where b < null"));
        assertThrows(IllegalArgumentException.class,
                () -> ijq.SelectQueryParser.parse("* where b >= null"));
        assertThrows(IllegalArgumentException.class,
                () -> ijq.SelectQueryParser.parse("* where b <= null"));
    }

    @Test
    public void testComparisonExpressionNullLiteralConstructorEquals() {
        ijq.ComparisonExpression expr = new ijq.ComparisonExpression("a", "=", null, true);
        assertTrue(expr.evaluate(json("{\"a\": null}")));
        assertTrue(expr.evaluate(json("{}")));
        assertFalse(expr.evaluate(json("{\"a\": 1}")));
    }

    @Test
    public void testComparisonExpressionNullLiteralConstructorNotEquals() {
        ijq.ComparisonExpression expr = new ijq.ComparisonExpression("a", "<>", null, true);
        assertFalse(expr.evaluate(json("{\"a\": null}")));
        assertFalse(expr.evaluate(json("{}")));
        assertTrue(expr.evaluate(json("{\"a\": 1}")));
    }

    @Test
    public void testParseWhereContainsMatchesSubstring() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where field contains 'val'");
        assertTrue(sq.whereClause.evaluate(json("{\"field\": \"value\"}")));
        assertTrue(sq.whereClause.evaluate(json("{\"field\": \"a value here\"}")));
        assertFalse(sq.whereClause.evaluate(json("{\"field\": \"other\"}")));
    }

    @Test
    public void testParseWhereContainsIsCaseInsensitiveKeyword() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where field CONTAINS 'val'");
        assertTrue(sq.whereClause.evaluate(json("{\"field\": \"value\"}")));
    }

    @Test
    public void testParseWhereContainsMatchIsCaseSensitive() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where field contains 'Val'");
        assertFalse(sq.whereClause.evaluate(json("{\"field\": \"value\"}")));
        assertTrue(sq.whereClause.evaluate(json("{\"field\": \"Value\"}")));
    }

    @Test
    public void testParseWhereContainsOnMissingOrNullFieldIsFalse() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where field contains 'val'");
        assertFalse(sq.whereClause.evaluate(json("{}")));
        assertFalse(sq.whereClause.evaluate(json("{\"field\": null}")));
    }

    @Test
    public void testParseWhereContainsOnNumericField() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where field contains '23'");
        assertTrue(sq.whereClause.evaluate(json("{\"field\": 12345}")));
        assertFalse(sq.whereClause.evaluate(json("{\"field\": 456}")));
    }

    @Test
    public void testParseWhereContainsCombinedWithAndOr() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse(
                "* where a contains 'foo' and b = '1'");
        assertTrue(sq.whereClause.evaluate(json("{\"a\": \"foobar\", \"b\": \"1\"}")));
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"foobar\", \"b\": \"2\"}")));
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"nope\", \"b\": \"1\"}")));
    }

    @Test
    public void testParseWhereNotContains() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where not a contains 'foo'");
        assertFalse(sq.whereClause.evaluate(json("{\"a\": \"foobar\"}")));
        assertTrue(sq.whereClause.evaluate(json("{\"a\": \"other\"}")));
    }

    @Test
    public void testParseWhereContainsWithNullThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ijq.SelectQueryParser.parse("* where field contains null"));
    }

    @Test
    public void testComparisonExpressionContainsDirect() {
        ijq.ComparisonExpression expr = new ijq.ComparisonExpression("a", "contains", "oo");
        assertTrue(expr.evaluate(json("{\"a\": \"foobar\"}")));
        assertFalse(expr.evaluate(json("{\"a\": \"bar\"}")));
    }

    @Test
    public void testExecuteWhereFilterContains() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where a contains 'oo'");
        List<ijq.JsonItem> result = sq.execute(items(
                "{\"a\": \"foo\"}", "{\"a\": \"bar\"}", "{\"a\": \"food\"}"));
        assertEquals(2, result.size());
    }

    @Test
    public void testExecuteWhereFilterContainsAfterSingleFieldProjectionUsingStar() {
        // Mirrors testExecuteWhereFilterAfterSingleFieldProjectionUsingStar: after a
        // single-field projection items become string literals, so "*" must refer to
        // the item's own value for "contains" as well.
        ijq.SelectQuery projection = ijq.SelectQueryParser.parse("name");
        List<ijq.JsonItem> projected = projection.execute(items(
                "{\"name\": \"alice\"}", "{\"name\": \"bob\"}"));

        ijq.SelectQuery filter = ijq.SelectQueryParser.parse("* where * contains 'ali'");
        List<ijq.JsonItem> result = filter.execute(projected);
        assertEquals(1, result.size());
    }

    @Test
    public void testExecuteWhereFilterNullLiteral() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where b = null");
        List<ijq.JsonItem> result = sq.execute(items(
                "{\"a\": 1, \"b\": null}", "{\"a\": 2}", "{\"a\": 3, \"b\": \"x\"}"));
        assertEquals(2, result.size());
    }

    // ==================== SelectQuery.execute ====================

    private List<ijq.JsonItem> items(String... jsons) {
        List<ijq.JsonItem> list = new ArrayList<>();
        for (String j : jsons) {
            list.add(new ijq.DirectJsonItem(json(j)));
        }
        return list;
    }

    @Test
    public void testExecuteWhereFilter() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("* where a = '1'");
        List<ijq.JsonItem> result = sq.execute(items("{\"a\": \"1\"}", "{\"a\": \"2\"}"));
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).getValue().getAsJsonObject().get("a").getAsString());
    }

    @Test
    public void testExecuteLimit() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("limit 2 *");
        List<ijq.JsonItem> result = sq.execute(items(
                "{\"a\": 1}", "{\"a\": 2}", "{\"a\": 3}", "{\"a\": 4}"));
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getValue().getAsJsonObject().get("a").getAsInt());
        assertEquals(2, result.get(1).getValue().getAsJsonObject().get("a").getAsInt());
    }

    @Test
    public void testExecuteSingleFieldProjection() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("name");
        List<ijq.JsonItem> result = sq.execute(items("{\"name\": \"foo\", \"age\": 1}"));
        assertEquals(1, result.size());
        assertEquals("foo", result.get(0).getValue().getAsString());
    }

    @Test
    public void testExecuteMultiFieldProjectionWithAlias() {
        ijq.SelectQuery sq = ijq.SelectQueryParser.parse("{name, age as years}");
        List<ijq.JsonItem> result = sq.execute(items("{\"name\": \"foo\", \"age\": 30}"));
        JsonObject obj = result.get(0).getValue().getAsJsonObject();
        assertEquals("foo", obj.get("name").getAsString());
        assertEquals(30, obj.get("years").getAsInt());
        assertNull(obj.get("age"));
    }

    // ==================== SelectionManager ====================

    @Test
    public void testSelectionManagerCreateSelectionAssignsIncrementingIds() {
        ijq.SelectionManager manager = new ijq.SelectionManager();
        ijq.Selection first = manager.createSelection(items("{\"a\":1}"));
        ijq.Selection second = manager.createSelection(items("{\"a\":2}"));
        assertEquals("$2", first.getId());
        assertEquals("$3", second.getId());
        assertEquals(second, manager.getCurrentSelection());
    }

    @Test
    public void testSelectionManagerSwitchAndDollarDollarAlias() {
        ijq.SelectionManager manager = new ijq.SelectionManager();
        ijq.Selection first = manager.createSelection(items("{\"a\":1}"));
        manager.createSelection(items("{\"a\":2}"));
        assertEquals(manager.getCurrentSelection(), manager.getSelection("$$"));
        manager.setCurrentSelection(first.getId());
        assertEquals(first, manager.getCurrentSelection());
    }

    @Test
    public void testSelectionManagerSetCurrentSelectionInvalidThrows() {
        ijq.SelectionManager manager = new ijq.SelectionManager();
        assertThrows(IllegalArgumentException.class, () -> manager.setCurrentSelection("$999"));
    }

    @Test
    public void testSelectionManagerNamedSelection() {
        ijq.SelectionManager manager = new ijq.SelectionManager();
        ijq.Selection source = manager.createSelection(items("{\"a\":1}", "{\"a\":2}"));
        manager.setNamedSelection("$mydata", source);
        ijq.Selection named = manager.getSelection("$mydata");
        assertEquals("$mydata", named.getId());
        assertEquals(2, named.size());
    }

    @Test
    public void testSelectionManagerNamedSelectionRejectsNumericId() {
        ijq.SelectionManager manager = new ijq.SelectionManager();
        ijq.Selection source = manager.createSelection(items("{\"a\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.setNamedSelection("$42", source));
    }

    // ==================== DirectJsonItem ====================

    @Test
    public void testDirectJsonItemHasNoOrigin() {
        ijq.DirectJsonItem item = new ijq.DirectJsonItem(json("{\"a\":1}"));
        assertNull(item.getOrigin());
        assertEquals(1, item.getValue().getAsJsonObject().get("a").getAsInt());
    }

    // Scan the system classpath for tests
    public static void main(final String... args) {
        String cacheJarsMarker = File.separator + "cache" + File.separator + "jars" + File.separator;
        String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);

        String jarsList = Arrays.stream(classpathEntries)
        .filter(path -> path.contains(cacheJarsMarker))
        .reduce((a, b) -> a + File.pathSeparator + b)
        // Fall back to the full classpath if nothing matched, so tests are
        // still found even if jbang's cache layout changes in the future.
        .orElse(String.join(File.pathSeparator, classpathEntries));

        ConsoleLauncher.main( "execute", "--scan-class-path", "-cp", jarsList); 
    }
}
