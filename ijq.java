///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.aesh:aesh:3.18.0 org.aesh:readline:3.18.0
//DEPS com.google.code.gson:gson:2.14.0
//JAVA 17+

import org.aesh.AeshConsoleRunner;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;
import org.aesh.readline.prompt.Prompt;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interactive JSON Query Shell - A shell-like tool for querying JSON data
 */
public class ijq {
    
    private static SelectionManager selectionManager = new SelectionManager();
    private static AeshConsoleRunner runner;
    
    public static void main(String[] args) throws Exception {
        // Print welcome message
        System.out.println("Interactive JSON Query Shell (ijq)");
        System.out.println("Type 'help' for available commands, 'exit' to quit");
        System.out.println();
        
        // Process command line arguments if any
        if (args.length > 0) {
            // TODO: Handle command line arguments for initial loading
        }
        
        AeshConsoleRunner.builder()
            .command(PrintCommand.class)
            .command(LoadCommand.class)
            .command(ScanCommand.class)
            .command(SelectCommand.class)
            .command(SetCommand.class)
            .command(UseCommand.class)
            .command(HelpCommand.class)
            .promptSupplier(() -> Prompt.builder().line(selectionManager.getPrompt()).build())
            .addExitCommand()
            .start();
    }
    
    // ==================== Data Model ====================
    
    /**
     * Interface for JSON items that can be lazy-loaded
     */
    interface JsonItem {
        JsonElement getValue();
        Path getOrigin();
    }
    
    /**
     * Lazy-loaded JSON item from a file
     */
    static class LazyJsonItem implements JsonItem {
        private final Path origin;
        private JsonElement value;
        private boolean loaded = false;
        
        public LazyJsonItem(Path origin) {
            this.origin = origin;
        }
        
        @Override
        public JsonElement getValue() {
            if (!loaded) {
                try {
                    String content = Files.readString(origin);
                    value = JsonParser.parseString(content);
                    loaded = true;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load JSON from: " + origin, e);
                }
            }
            return value;
        }
        
        @Override
        public Path getOrigin() {
            return origin;
        }
    }
    
    /**
     * Direct JSON item without file origin
     */
    static class DirectJsonItem implements JsonItem {
        private final JsonElement value;
        
        public DirectJsonItem(JsonElement value) {
            this.value = value;
        }
        
        @Override
        public JsonElement getValue() {
            return value;
        }
        
        @Override
        public Path getOrigin() {
            return null;
        }
    }
    
    /**
     * Represents a selection of JSON items
     */
    static class Selection {
        private final String id;
        private final List<JsonItem> items;
        
        public Selection(String id, List<JsonItem> items) {
            this.id = id;
            this.items = new ArrayList<>(items);
        }
        
        public String getId() {
            return id;
        }
        
        public List<JsonItem> getItems() {
            return items;
        }
        
        public int size() {
            return items.size();
        }

        public String toString() {
            return id + "[#" + items.size() + "]";
        }
    }
    
    /**
     * Manages selections and their IDs
     */
    static class SelectionManager {
        private final Map<String, Selection> selections = new LinkedHashMap<>();
        private int nextId = 1;
        private String currentId = null;
        
        public SelectionManager() {
            // Initialize with empty selection
            createSelection(new ArrayList<>());
        }
        
        public Selection createSelection(List<JsonItem> items) {
            String id = "$" + nextId++;
            Selection selection = new Selection(id, items);
            selections.put(id, selection);
            selections.put("$$", selection); // Always update current alias
            currentId = id;
            return selection;
        }
        
        public Selection getCurrentSelection() {
            return selections.get(currentId);
        }
        
        public Selection getSelection(String id) {
            if (id == null || id.equals("$$")) {
                return getCurrentSelection();
            }
            return selections.get(id);
        }
        
        public void setNamedSelection(String name, Selection source) {
            if (name.matches("\\$\\d+")) {
                throw new IllegalArgumentException("Cannot use numbered IDs for named selections");
            }
            selections.put(name, new Selection(name, source.getItems()));
            currentId = name;
            selections.put("$$", selections.get(name));
        }
        
        public void setCurrentSelection(String id) {
            Selection sel = selections.get(id);
            if (sel == null) {
                throw new IllegalArgumentException("Selection not found: " + id);
            }
            currentId = id;
            selections.put("$$", sel);
        }
        
        public String getPrompt() {
            Selection current = getCurrentSelection();
            return String.format("\u001B[32m%s>\u001B[0m ", current);
        }
    }
    
    // ==================== Commands ====================
    
    @CommandDefinition(name = "print", description = "Print all currently selected items")
    public static class PrintCommand implements Command {
        
        @Option(name = "details", shortName = 'd', hasValue = false,
                description = "Include origin information for each item")
        private boolean details;
        
        @Arguments(arity = "0..*", description = "Optional TOP/LIMIT/WHERE clauses (same syntax as select)")
        private List<String> args;
        
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            Selection current = selectionManager.getCurrentSelection();
            List<JsonItem> items = current.getItems();
            
            if (args != null && !args.isEmpty()) {
                String query = String.join(" ", args);
                try {
                    // Use a temporary, implicit SELECT to apply the same
                    // TOP/LIMIT/WHERE clauses, without creating a new selection.
                    SelectQuery selectQuery = SelectQueryParser.parse(query);
                    items = selectQuery.execute(items);
                } catch (Exception e) {
                    invocation.println("Error executing query: " + e.getMessage());
                    return CommandResult.FAILURE;
                }
            }
            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            
            JsonArray array = new JsonArray();
            for (JsonItem item : items) {
                if (details) {
                    JsonObject wrapper = new JsonObject();
                    wrapper.add("value", item.getValue());
                    Path origin = item.getOrigin();
                    wrapper.addProperty("origin", origin != null ? origin.toString() : null);
                    array.add(wrapper);
                } else {
                    array.add(item.getValue());
                }
            }
            
            invocation.println(gson.toJson(array));
            
            return CommandResult.SUCCESS;
        }
    }
    
    @CommandDefinition(name = "load", description = "Load JSON from a file")
    public static class LoadCommand implements Command {
        
        @Arguments(arity = "0..*", description = "Path to JSON file")
        private List<String> args;
        
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            if (args == null || args.isEmpty()) {
                invocation.println("Error: Please provide a file path");
                return CommandResult.FAILURE;
            }
            
            String pathStr = String.join(" ", args);
            Path path = Paths.get(pathStr);
            
            if (!Files.exists(path)) {
                invocation.println("Error: File not found: " + path);
                return CommandResult.FAILURE;
            }
            
            if (!Files.isRegularFile(path)) {
                invocation.println("Error: Not a file: " + path);
                return CommandResult.FAILURE;
            }
            
            try {
                String content = Files.readString(path);
                JsonElement element = JsonParser.parseString(content);
                
                List<JsonItem> items = new ArrayList<>();
                if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
                    for (JsonElement elem : array) {
                        items.add(new DirectJsonItem(elem));
                    }
                } else {
                    items.add(new DirectJsonItem(element));
                }
                
                Selection newSelection = selectionManager.createSelection(items);
                invocation.println("Loaded " + items.size() + " item(s) into " + newSelection.getId());
                
            } catch (IOException e) {
                invocation.println("Error reading file: " + e.getMessage());
                return CommandResult.FAILURE;
            } catch (JsonSyntaxException e) {
                invocation.println("Error parsing JSON: " + e.getMessage());
                return CommandResult.FAILURE;
            }
            
            return CommandResult.SUCCESS;
        }
    }
    
    @CommandDefinition(name = "scan", description = "Scan and load JSON files matching a pattern")
    public static class ScanCommand implements Command {
        
        @Arguments(arity = "0..*", description = "Path pattern (supports globbing)")
        private List<String> args;
        
        private static boolean containsGlobbing(String str) {
            return str.contains("*") || str.contains("?") || 
                   str.contains("[") || str.contains("]") || 
                   str.contains("{") || str.contains("}");
        }
        
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            if (args == null || args.isEmpty()) {
                invocation.println("Error: Please provide a path pattern");
                return CommandResult.FAILURE;
            }
            
            String pattern = String.join(" ", args);
            List<JsonItem> items = new ArrayList<>();
            
            try {
                Path startPath;
                String globPattern;
                
                // Check if pattern contains any globbing characters
                if (!containsGlobbing(pattern)) {
                    // No globbing - treat as directory path and append **/*.json for recursive scan
                    startPath = Paths.get(pattern);
                    String normalizedPattern = pattern.replaceAll("\\\\", "/");
                    // Handle trailing path separator
                    normalizedPattern = normalizedPattern.endsWith("/")
                        ? normalizedPattern.substring(0, normalizedPattern.length() - 1)
                        : normalizedPattern;
                    globPattern = normalizedPattern + "/**.json";
                } else {
                    // Pattern contains globbing - find longest prefix without globbing
                    String separator = pattern.contains("/") ? "/" : "\\";
                    String[] parts = pattern.split("[/\\\\]");
                    StringBuilder prefixBuilder = new StringBuilder();
                    
                    for (int i = 0; i < parts.length; i++) {
                        if (containsGlobbing(parts[i])) {
                            break;
                        }
                        if (i > 0) {
                            prefixBuilder.append(separator);
                        }
                        prefixBuilder.append(parts[i]);
                    }
                    
                    String prefix = prefixBuilder.toString();
                    if (prefix.isEmpty()) {
                        startPath = Paths.get(".");
                    } else {
                        startPath = Paths.get(prefix);
                    }
                    globPattern = pattern;
                }
                
                if (!Files.exists(startPath)) {
                    invocation.println("Error: Base path does not exist: " + startPath);
                    return CommandResult.FAILURE;
                }

                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

                try (Stream<Path> paths = Files.walk(startPath)) {
                    List<Path> matchedPaths = paths
                        .filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(p))
                        .collect(Collectors.toList());
                    
                    for (Path path : matchedPaths) {
                        items.add(new LazyJsonItem(path));
                    }
                }
                
                Selection newSelection = selectionManager.createSelection(items);
                invocation.println("Scanned " + items.size() + " file(s) into " + newSelection.getId());
                
            } catch (IOException e) {
                invocation.println("Error scanning files: " + e.getMessage());
                return CommandResult.FAILURE;
            }
            
            return CommandResult.SUCCESS;
        }
    }
    
    @CommandDefinition(name = "select", description = "Filter and transform the current selection")
    public static class SelectCommand implements Command {
        
        @Arguments(arity = "0..*", description = "SELECT query (e.g., 'select * where field = value')")
        private List<String> args;
        
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            if (args == null || args.isEmpty()) {
                invocation.println("Error: Please provide a SELECT query");
                return CommandResult.FAILURE;
            }
            
            String query = String.join(" ", args);
            
            try {
                SelectQuery selectQuery = SelectQueryParser.parse(query);
                Selection current = selectionManager.getCurrentSelection();
                List<JsonItem> results = selectQuery.execute(current.getItems());
                
                Selection newSelection = selectionManager.createSelection(results);
                invocation.println("Selected " + results.size() + " item(s) into " + newSelection.getId());
                
            } catch (Exception e) {
                invocation.println("Error executing query: " + e.getMessage());
                return CommandResult.FAILURE;
            }
            
            return CommandResult.SUCCESS;
        }
    }
    
    @CommandDefinition(name = "set", description = "Name a selection")
    public static class SetCommand implements Command {
        
        @Arguments(arity = "0..*", description = "SET command (e.g., 'set $mydata = $1' or 'set $mydata')")
        private List<String> args;
        
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            if (args == null || args.isEmpty()) {
                invocation.println("Error: Please provide a SET command");
                return CommandResult.FAILURE;
            }
            
            String command = String.join(" ", args);

            try {
                if (command.contains("=")) {
                    // set $X = $Y
                    String[] parts = command.split("=");
                    if (parts.length != 2) {
                        invocation.println("Error: Invalid SET syntax");
                        return CommandResult.FAILURE;
                    }
                    
                    String targetId = parts[0].trim();
                    String sourceId = parts[1].trim();
                    
                    if (!targetId.startsWith("$") || !sourceId.startsWith("$")) {
                        invocation.println("Error: Selection IDs must start with $");
                        return CommandResult.FAILURE;
                    }
                    
                    Selection source = selectionManager.getSelection(sourceId);
                    if (source == null) {
                        invocation.println("Error: Source selection not found: " + sourceId);
                        return CommandResult.FAILURE;
                    }
                    
                    selectionManager.setNamedSelection(targetId, source);
                    invocation.println("Set " + targetId + " = " + sourceId + 
                                     " (" + source.size() + " items)");
                    
                } else {
                    // set $X - associate name with current selection
                    String targetId = command.trim();
                    if (!targetId.startsWith("$")) {
                        invocation.println("Error: Selection ID must start with $");
                        return CommandResult.FAILURE;
                    }
                    
                    Selection current = selectionManager.getCurrentSelection();
                    selectionManager.setNamedSelection(targetId, current);
                    invocation.println("Set " + targetId + " = " + current.getId() + 
                                     " (" + current.size() + " items)");
                }
                
            } catch (IllegalArgumentException e) {
                invocation.println("Error: " + e.getMessage());
                return CommandResult.FAILURE;
            }
            
            return CommandResult.SUCCESS;
        }
    }
    
    @CommandDefinition(name = "use", description = "Switch to a named or numbered selection")
    public static class UseCommand implements Command {
        
        @Arguments(arity = "0..*", description = "Selection ID to switch to (e.g., 'use $mydata' or 'use $1')")
        private List<String> args;
        
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            if (args == null || args.isEmpty()) {
                invocation.println("Error: Please provide a selection ID");
                return CommandResult.FAILURE;
            }
            
            String targetId = args.get(0).trim();
            
            if (!targetId.startsWith("$")) {
                invocation.println("Error: Selection ID must start with $");
                return CommandResult.FAILURE;
            }
            
            try {
                selectionManager.setCurrentSelection(targetId);
                Selection current = selectionManager.getCurrentSelection();
                invocation.println("Switched to " + targetId + " (" + current.size() + " items)");
            } catch (IllegalArgumentException e) {
                invocation.println("Error: " + e.getMessage());
                return CommandResult.FAILURE;
            }
            
            return CommandResult.SUCCESS;
        }
    }
    
    @CommandDefinition(name = "help", description = "Show help information")
    public static class HelpCommand implements Command {
        
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            invocation.println("Interactive JSON Query Shell (ijq)");
            invocation.println("=================================");
            invocation.println("");
            invocation.println("COMMANDS:");
            invocation.println("");
            invocation.println("  print [-d] [TOP/LIMIT/WHERE clauses]");
            invocation.println("    Print items in the current selection");
            invocation.println("    -d : Include origin information for each item");
            invocation.println("    Supports the same TOP, LIMIT and WHERE clauses as select:");
            invocation.println("      print top 10");
            invocation.println("      print where field = 'value'");
            invocation.println("      print limit 5,10 where field > 100");
            invocation.println("");
            invocation.println("  load <path>");
            invocation.println("    Load JSON from a file. If the file contains an array,");
            invocation.println("    each element becomes an item. Otherwise, the entire");
            invocation.println("    content becomes a single item.");
            invocation.println("");
            invocation.println("  scan <pattern>");
            invocation.println("    Scan for JSON files matching a glob pattern.");
            invocation.println("    Each matched file becomes an item (lazy-loaded).");
            invocation.println("    Example: scan db/metadata/**/*.json");
            invocation.println("");
            invocation.println("  select <query>");
            invocation.println("    Filter and transform the current selection using SQL-like syntax.");
            invocation.println("    Examples:");
            invocation.println("      select *");
            invocation.println("      select field1, field2");
            invocation.println("      select {field1, field2 as name}");
            invocation.println("      select * where field = 'value'");
            invocation.println("      select top 10 *");
            invocation.println("      select limit 5,10 * where field > 100");
            invocation.println("");
            invocation.println("  set <command>");
            invocation.println("    Manage named selections:");
            invocation.println("      set $name = $1    : Create/update named selection from $1");
            invocation.println("      set $name         : Name the current selection");
            invocation.println("");
            invocation.println("  use $name");
            invocation.println("    Switch to a named or numbered selection, making it current");
            invocation.println("");
            invocation.println("  exit");
            invocation.println("    Exit the shell");
            invocation.println("");
            invocation.println("SELECTIONS:");
            invocation.println("  Each operation creates a new selection with an ID: $1, $2, $3, ...");
            invocation.println("  $$ always refers to the current/latest selection");
            invocation.println("  The prompt shows: <selection-id>[#<item-count>]>");
            invocation.println("");
            
            return CommandResult.SUCCESS;
        }
    }
    
    // ==================== Query Parser and Executor ====================
    
    static class SelectQuery {
        boolean selectAll = false;
        List<FieldSelection> fields = new ArrayList<>();
        WhereClause whereClause = null;
        Integer limitOffset = null;
        Integer limitCount = null;
        
        public List<JsonItem> execute(List<JsonItem> items) {
            List<JsonItem> result = new ArrayList<>(items);
            
            // Apply WHERE clause
            if (whereClause != null) {
                result = result.stream()
                    .filter(item -> whereClause.evaluate(item.getValue()))
                    .collect(Collectors.toList());
            }
            
            // Apply LIMIT
            if (limitOffset != null || limitCount != null) {
                int offset = limitOffset != null ? limitOffset : 0;
                int count = limitCount != null ? limitCount : result.size();
                result = result.stream()
                    .skip(offset)
                    .limit(count)
                    .collect(Collectors.toList());
            }
            
            // Apply field selection
            if (!selectAll && !fields.isEmpty()) {
                result = result.stream()
                    .map(item -> {
                        JsonElement value = item.getValue();
                        if (!value.isJsonObject()) {
                            return new DirectJsonItem(JsonNull.INSTANCE);
                        }
                        
                        JsonObject obj = value.getAsJsonObject();
                        
                        if (fields.size() == 1 && fields.get(0).alias == null) {
                            // Single field without alias - return the value directly
                            String fieldName = fields.get(0).name;
                            JsonElement fieldValue = obj.get(fieldName);
                            return new DirectJsonItem(fieldValue != null ? fieldValue : JsonNull.INSTANCE);
                        } else {
                            // Multiple fields or aliased field - return object
                            JsonObject newObj = new JsonObject();
                            for (FieldSelection field : fields) {
                                JsonElement fieldValue = obj.get(field.name);
                                if (fieldValue != null) {
                                    String outputName = field.alias != null ? field.alias : field.name;
                                    newObj.add(outputName, fieldValue);
                                }
                            }
                            return new DirectJsonItem(newObj);
                        }
                    })
                    .collect(Collectors.toList());
            }
            
            return result;
        }
    }
    
    static class FieldSelection {
        String name;
        String alias;
        
        public FieldSelection(String name, String alias) {
            this.name = name;
            this.alias = alias;
        }
    }
    
    static class WhereClause {
        private final Expression expression;
        
        public WhereClause(Expression expression) {
            this.expression = expression;
        }
        
        public boolean evaluate(JsonElement element) {
            return expression.evaluate(element);
        }
    }
    
    interface Expression {
        boolean evaluate(JsonElement element);
    }
    
    static class ComparisonExpression implements Expression {
        String field;
        String operator;
        String value;
        boolean valueIsNull;
        
        public ComparisonExpression(String field, String operator, String value) {
            this(field, operator, value, false);
        }

        public ComparisonExpression(String field, String operator, String value, boolean valueIsNull) {
            this.field = field;
            this.operator = operator;
            this.value = value;
            this.valueIsNull = valueIsNull;
        }
        
        @Override
        public boolean evaluate(JsonElement element) {
            JsonElement fieldValue;

            if (field.equals("*")) {
                // '*' refers to the item's own value, so comparisons work even
                // after a single-field projection turned items into literals
                // (e.g. numbers/strings/null) rather than JSON objects.
                fieldValue = element;
            } else {
                if (!element.isJsonObject()) {
                    return false;
                }
                JsonObject obj = element.getAsJsonObject();
                fieldValue = obj.get(field);
            }

            boolean fieldIsNull = fieldValue == null || fieldValue.isJsonNull();
            
            if (valueIsNull) {
                // Comparing against the null literal: only equality/inequality
                // are meaningful (already validated at parse time).
                return operator.equals("=") ? fieldIsNull : !fieldIsNull;
            }

            if (fieldIsNull) {
                // A missing/null field cannot equal any given non-null value,
                // but it is considered not-equal ('<>') to it.
                return operator.equals("<>");
            }
            
            String fieldStr = fieldValue.isJsonPrimitive() ? 
                fieldValue.getAsString() : fieldValue.toString();
            
            return switch (operator) {
                case "=" -> fieldStr.equals(value);
                case "<>" -> !fieldStr.equals(value);
                case ">" -> compareNumeric(fieldStr, value) > 0;
                case "<" -> compareNumeric(fieldStr, value) < 0;
                case ">=" -> compareNumeric(fieldStr, value) >= 0;
                case "<=" -> compareNumeric(fieldStr, value) <= 0;
                case "contains" -> fieldStr.contains(value);
                default -> false;
            };
        }
        
        private int compareNumeric(String a, String b) {
            try {
                double aNum = Double.parseDouble(a);
                double bNum = Double.parseDouble(b);
                return Double.compare(aNum, bNum);
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        }
    }
    
    static class LogicalExpression implements Expression {
        Expression left;
        String operator; // "and", "or"
        Expression right;
        
        public LogicalExpression(Expression left, String operator, Expression right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }
        
        @Override
        public boolean evaluate(JsonElement element) {
            if (operator.equalsIgnoreCase("and")) {
                return left.evaluate(element) && right.evaluate(element);
            } else if (operator.equalsIgnoreCase("or")) {
                return left.evaluate(element) || right.evaluate(element);
            }
            return false;
        }
    }
    
    static class NotExpression implements Expression {
        Expression expression;
        
        public NotExpression(Expression expression) {
            this.expression = expression;
        }
        
        @Override
        public boolean evaluate(JsonElement element) {
            return !expression.evaluate(element);
        }
    }
    
    static class SelectQueryParser {
        
        public static SelectQuery parse(String query) {
            SelectQuery sq = new SelectQuery();
            String[] tokens = tokenize(query);
            int pos = 0;
            
            // Check for TOP or LIMIT
            if (pos < tokens.length && tokens[pos].equalsIgnoreCase("top")) {
                pos++;
                if (pos >= tokens.length) {
                    throw new IllegalArgumentException("Expected number after TOP");
                }
                sq.limitCount = Integer.parseInt(tokens[pos++]);
                sq.limitOffset = 0;
            } else if (pos < tokens.length && tokens[pos].equalsIgnoreCase("limit")) {
                pos++;
                if (pos >= tokens.length) {
                    throw new IllegalArgumentException("Expected numbers after LIMIT");
                }
                // Note: tokenize() always splits ',' into its own token, so a
                // "limit offset,count" spec arrives as separate tokens
                // ("offset", ",", "count") rather than a single "offset,count"
                // token; handle both forms explicitly.
                int firstNumber = Integer.parseInt(tokens[pos++]);
                if (pos < tokens.length && tokens[pos].equals(",")) {
                    pos++;
                    if (pos >= tokens.length) {
                        throw new IllegalArgumentException("Expected count after ',' in LIMIT");
                    }
                    sq.limitOffset = firstNumber;
                    sq.limitCount = Integer.parseInt(tokens[pos++]);
                } else {
                    sq.limitOffset = 0;
                    sq.limitCount = firstNumber;
                }
            }
            
            // Parse field selection
            if (pos < tokens.length && tokens[pos].equals("*")) {
                sq.selectAll = true;
                pos++;
            } else if (pos < tokens.length && tokens[pos].equals("{")) {
                // Field list in braces
                pos++;
                while (pos < tokens.length && !tokens[pos].equals("}")) {
                    String fieldName = tokens[pos++];
                    String alias = null;
                    
                    if (pos < tokens.length && tokens[pos].equalsIgnoreCase("as")) {
                        pos++;
                        if (pos < tokens.length) {
                            alias = tokens[pos++];
                        }
                    }
                    
                    sq.fields.add(new FieldSelection(fieldName, alias));
                    
                    if (pos < tokens.length && tokens[pos].equals(",")) {
                        pos++;
                    }
                }
                if (pos < tokens.length && tokens[pos].equals("}")) {
                    pos++;
                }
            } else if (pos < tokens.length) {
                // Single field or comma-separated fields
                while (pos < tokens.length && !tokens[pos].equalsIgnoreCase("where")) {
                    String fieldName = tokens[pos++];
                    if (fieldName.equals(",")) continue;
                    
                    String alias = null;
                    if (pos < tokens.length && tokens[pos].equalsIgnoreCase("as")) {
                        pos++;
                        if (pos < tokens.length && !tokens[pos].equalsIgnoreCase("where")) {
                            alias = tokens[pos++];
                        }
                    }
                    
                    sq.fields.add(new FieldSelection(fieldName, alias));
                    
                    if (pos < tokens.length && tokens[pos].equals(",")) {
                        pos++;
                    }
                }
            }
            
            // Parse WHERE clause
            if (pos < tokens.length && tokens[pos].equalsIgnoreCase("where")) {
                pos++;
                sq.whereClause = new WhereClause(parseExpression(tokens, pos));
            }
            
            return sq;
        }
        
        private static Expression parseExpression(String[] tokens, int startPos) {
            // Simple implementation - parse basic comparisons and AND/OR
            // This is a simplified parser; a full implementation would handle parentheses

            List<String> exprTokens = new ArrayList<>();
            for (int i = startPos; i < tokens.length; i++) {
                exprTokens.add(tokens[i]);
            }

            return parseExpressionTokens(exprTokens);
        }

        // Parses a (sub-)list of WHERE tokens, recursing so that any number of
        // AND/OR clauses (and a leading NOT) are honored instead of only the
        // first split.
        private static Expression parseExpressionTokens(List<String> exprTokens) {
            if (exprTokens.isEmpty()) {
                throw new IllegalArgumentException("Empty WHERE clause");
            }

            // Handle NOT: it binds only to the single comparison that follows it,
            // not to any subsequent AND/OR clauses.
            if (exprTokens.get(0).equalsIgnoreCase("not")) {
                List<String> rest = exprTokens.subList(1, exprTokens.size());
                if (rest.size() < 3) {
                    throw new IllegalArgumentException("Invalid expression after NOT");
                }
                Expression notExpr = new NotExpression(parseSimpleExpression(rest.subList(0, 3)));
                if (rest.size() > 3) {
                    String op = rest.get(3);
                    if (op.equalsIgnoreCase("and") || op.equalsIgnoreCase("or")) {
                        Expression right = parseExpressionTokens(rest.subList(4, rest.size()));
                        return new LogicalExpression(notExpr, op.toLowerCase(), right);
                    }
                    throw new IllegalArgumentException("Expected AND/OR after NOT expression");
                }
                return notExpr;
            }

            // Look for AND/OR operators, recursing on both sides so that any
            // further AND/OR clauses are preserved rather than dropped.
            for (int i = 0; i < exprTokens.size(); i++) {
                if (exprTokens.get(i).equalsIgnoreCase("or")) {
                    List<String> left = exprTokens.subList(0, i);
                    List<String> right = exprTokens.subList(i + 1, exprTokens.size());
                    return new LogicalExpression(
                        parseExpressionTokens(left),
                        "or",
                        parseExpressionTokens(right)
                    );
                }
            }

            for (int i = 0; i < exprTokens.size(); i++) {
                if (exprTokens.get(i).equalsIgnoreCase("and")) {
                    List<String> left = exprTokens.subList(0, i);
                    List<String> right = exprTokens.subList(i + 1, exprTokens.size());
                    return new LogicalExpression(
                        parseExpressionTokens(left),
                        "and",
                        parseExpressionTokens(right)
                    );
                }
            }

            return parseSimpleExpression(exprTokens);
        }
        
        private static Expression parseSimpleExpression(List<String> tokens) {
            if (tokens.size() < 3) {
                throw new IllegalArgumentException("Invalid expression");
            }
            
            String field = tokens.get(0);
            String operator = tokens.get(1);
            String rawValue = tokens.get(2);
            
            // Word operators (like "contains") are matched case-insensitively,
            // unlike the symbolic operators (=, <>, >, <, >=, <=).
            if (operator.equalsIgnoreCase("contains")) {
                operator = "contains";
            }
            
            // An unquoted 'null' token is the NULL literal, distinct from the
            // string "null" (which would be quoted).
            if (rawValue.equalsIgnoreCase("null")) {
                if (!operator.equals("=") && !operator.equals("<>")) {
                    throw new IllegalArgumentException(
                        "Only '=' and '<>' can be used to compare against null");
                }
                return new ComparisonExpression(field, operator, null, true);
            }
            
            String value = rawValue;
            // Remove quotes from value if present
            if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            
            return new ComparisonExpression(field, operator, value);
        }
        
        private static String[] tokenize(String query) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            char quoteChar = 0;
            
            for (int i = 0; i < query.length(); i++) {
                char c = query.charAt(i);
                
                if (c == '\'' || c == '"') {
                    if (!inQuotes) {
                        inQuotes = true;
                        quoteChar = c;
                        current.append(c);
                    } else if (c == quoteChar) {
                        inQuotes = false;
                        current.append(c);
                        tokens.add(current.toString());
                        current = new StringBuilder();
                    } else {
                        current.append(c);
                    }
                } else if (inQuotes) {
                    current.append(c);
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current = new StringBuilder();
                    }
                } else if (c == ',' || c == '{' || c == '}' || c == '(' || c == ')') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current = new StringBuilder();
                    }
                    tokens.add(String.valueOf(c));
                } else if (c == '=' || c == '>' || c == '<') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current = new StringBuilder();
                    }
                    // Check for multi-char operators
                    if (i + 1 < query.length()) {
                        char next = query.charAt(i + 1);
                        if ((c == '<' && next == '>') || 
                            (c == '>' && next == '=') || 
                            (c == '<' && next == '=')) {
                            tokens.add(String.valueOf(c) + next);
                            i++;
                            continue;
                        }
                    }
                    tokens.add(String.valueOf(c));
                } else {
                    current.append(c);
                }
            }
            
            if (current.length() > 0) {
                tokens.add(current.toString());
            }
            
            return tokens.toArray(new String[0]);
        }
    }
}
