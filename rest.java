//usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS info.picocli:picocli:4.3.2
//DEPS com.google.code.gson:gson:2.8.6
//DEPS org.yaml:snakeyaml:1.26
//DEPS com.konghq:unirest-java:3.7.02

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import org.yaml.snakeyaml.Yaml;

import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.RequestBodyEntity;
import kong.unirest.Unirest;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.MissingParameterException;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ParseResult;

@Command(name = "rest",
    mixinStandardHelpOptions = true,
    version = "rest 0.1",
    description = "REST API access commands",
    subcommands = {
        Create.class,
        Delete.class,
        Get.class,
        Filter.class,
        Mapp.class,
        Post.class,
        Print.class,
        Read.class,
        Select.class,
        ToArray.class,
        ToObject.class,
        Write.class
    },
    subcommandsRepeatable = true)
public class rest {

    @Option(names = { "--url" }, description = "Alfresco REST API server URL")
    String apiUrl;

    @Option(names = { "--user", "-u" }, description = "Name of Alfresco user with admin rights")
    String user;

    @Option(names = { "--password", "-p" }, description = "Password of Alfresco user with admin rights")
    String password;

    @Option(names = { "--insecure", "-i" }, description = "Ignore SSL errors")
    Boolean insecure;

    @Option(names = { "--config", "-c" }, description = "Connection configuration to use")
    String config;

    @Option(names = { "--verbose", "-v" }, description = "Enable verbose output")
    boolean[] verboses;

    AppConfig appConfig;

    Server activeServer = new Server();

    ArrayDeque<List<Object>> resultsStack = new ArrayDeque<>(Collections.singleton(Collections.singletonList(Util.newObject())));

    List<Object> results() {
        return resultsStack.peek();
    }

    boolean verbose;

    List<Object> elems(int index) {
        int idx = index >= 0 ? resultsStack.size() - index - 1 : -index - 1;
        if (idx > 0) {
            return Util.asArray(resultsStack.toArray()[idx]);
        } else {
            return results();
        }
    }

    void pushResult(Object obj) {
        resultsStack.push(Collections.singletonList(obj));
    }

    void pushResults(List<Object> obj) {
        resultsStack.push(obj);
    }

    String getApiUrl(String api) {
        if (activeServer.url == null) {
            throw new IllegalArgumentException("Missing API URL. Either specify it on the command line or in the configuration file.");
        }
        return activeServer.url + api;
    }

    HttpResponse<JsonNode> apiDelete(String api, Object body) {
        String url = getApiUrl(api);

        logApiRequest("DELETE", api, body);

        RequestBodyEntity req = Unirest.delete(url).body(body);
        if (activeServer.user != null && activeServer.password != null) {
            req.basicAuth(activeServer.user, activeServer.password);
        }

        HttpResponse<JsonNode> res = req.asJson();

        logApiResult(res);
        
        return res;
    }

    HttpResponse<JsonNode> apiGet(String api) {
        String url = getApiUrl(api);

        logApiRequest("GET", api, null);

        GetRequest req = Unirest.get(url);
        if (activeServer.user != null && activeServer.password != null) {
            req.basicAuth(activeServer.user, activeServer.password);
        }

        HttpResponse<JsonNode> res = req.asJson();

        logApiResult(res);
        
        return res;
    }

    HttpResponse<JsonNode> apiPost(String api, Object body) {
        String url = getApiUrl(api);

        logApiRequest("POST", api, body);

        RequestBodyEntity req = Unirest.post(url).body(body);
        if (activeServer.user != null && activeServer.password != null) {
            req.basicAuth(activeServer.user, activeServer.password);
        }

        HttpResponse<JsonNode> res = req.asJson();

        logApiResult(res);
        
        return res;
    }

    void logApiRequest(String action, String api, Object body) {
        if (verbose) {
            String url = getApiUrl(api);
            System.err.println(action + " " + url);
            if (verboses != null && verboses.length > 1 && body != null) {
                System.err.println("Body: " + body);
            }
        }
    }

    void logApiResult(HttpResponse<JsonNode> res) {
        if (verbose) {
            System.err.println("Response: " + Util.status(res) + " " + Util.statusText(res));
            if (verboses != null && verboses.length > 2) {
                System.err.println("Result: " + res.getBody().toPrettyString());
            }
        }
    }

    void logApiResult() {
        if (verbose) {
            System.err.println("Response: NONE (Dry Run)");
        }
    }

    public static void main(String... args) {
        rest app = new rest();
        int exitCode = new CommandLine(app)
            .setExecutionStrategy(app::executionStrategy)
            .setExecutionExceptionHandler(app.new ExecHandler())
            .execute(args);
        System.exit(exitCode);
    }

    private int executionStrategy(ParseResult parseResult) {
        verbose = verboses != null && verboses.length > 0;

        Gson gson = new Gson();
        File file = new File(System.getProperty("user.home"), ".restcfg");
        if (file.isFile()) {
            try (JsonReader reader = new JsonReader(new FileReader(file))) {
                appConfig = gson.fromJson(reader, AppConfig.class);
            } catch (IOException ex) {
                System.err.println("Error reading configuration file. Ignoring. (" + ex.getMessage() + ")");
            }
            if (appConfig != null) {
                String srv = config != null ? config : appConfig.defaultServer;
                if (srv != null) {
                    if (appConfig.servers != null) {
                        activeServer = appConfig.servers.get(srv);
                    }
                }
            }
        }

        boolean insec = insecure != null ? insecure : activeServer.insecure;
        Unirest.config().verifySsl(!insec);

        String usr = user != null ? user : activeServer.user;
        String pwd = password != null ? password : activeServer.password;
        String url = apiUrl != null ? apiUrl : activeServer.url;

        Server srv = new Server();
        srv.url = url;
        srv.user = usr;
        srv.password = pwd;
        activeServer = srv;

        return new CommandLine.RunLast().execute(parseResult);
    }

    class ExecHandler implements IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parse) throws Exception {
            if (verbose) {
                cmd.getErr().print("ERROR: ");
                ex.printStackTrace(cmd.getErr());
            } else {
                cmd.getErr().println("ERROR: " + ex.toString());
            }
            return cmd.getExitCodeExceptionMapper() != null
                ? cmd.getExitCodeExceptionMapper().getExitCode(ex)
                : cmd.getCommandSpec().exitCodeOnExecutionException();
        }
    }
}

@Command(name = "create", mixinStandardHelpOptions = true, description = "Create new object")
class Create implements Callable<Integer> {
    @ParentCommand
    private rest app;

    @Option(names = { "--literal", "-l" }, description = "Use literal value as starting point")
    private String literal;

    @Option(names = { "--value", "-v" }, description = "Set key=value properties on object where value is a literal")
    private String[] props;

    @Option(names = { "--select", "-s" }, description = "Set key=path properties on object where path selects a value form a previous result")
    private String[] selects;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = Collections.singletonList(Util.newObject());
        List<Object> results = elems.stream()
            .map(elem -> {
                Map<String, Object> obj;
                if (literal != null) {
                    obj = Util.newObject(literal);
                } else {
                    obj = Util.asObject(Util.jsonClone(elem));
                }
                if (props != null) {
                    for (String prop : props) {
                        String[] kv = prop.split("=", 2);
                        String key = kv[0];
                        String val = kv.length == 2 ? kv[1] : key;
                        Util.set(obj, key, val);
                    }
                }
                if (selects != null) {
                    for (String select : selects) {
                        String[] kp = select.split("=", 2);
                        String key = kp[0];
                        String path = kp.length == 2 ? kp[1] : key;
                        String val = Util.select(elem, path).map(Object::toString).collect(Collectors.joining());
                        Util.set(obj, key, val);
                    }
                }
                return obj;
            })
            .collect(Collectors.toList());
        app.pushResults(results);
        return 0;
    }
}

@Command(name = "delete", mixinStandardHelpOptions = true, description = "Perform REST API delete request")
class Delete implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Parameters(index = "0", description = "REST API resource path", arity = "1")
    private String api;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Option(names = { "--dry-run" }, description = "Only shows what will happen but doesn't perform any changes")
    private boolean dryRun;

    @Option(names = { "--no-body" }, description = "No body will be sent")
    private boolean noBody;

    @Option(names = { "--ignore-result", "-i" }, description = "Ignores the result of the post")
    private boolean ignoreResult;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        List<Object> results = elems.stream()
            .map(elem -> {
                String newapi = Util.replaceVars(api, elem);
                Object result;
                if (!dryRun) {
                    HttpResponse<JsonNode> response = app.apiDelete(newapi, noBody ? null : elem);
                    result = response.getBody().isArray() ? response.getBody().getArray().toList() : response.getBody().getObject().toMap();
                    if (!Util.ok(response)) {
                        System.err.println(Util.statusText(response));
                    }
                } else {
                    app.logApiRequest("DELETE", newapi, noBody ? null : elem);
                    app.logApiResult();
                    // Not really logical but it might be useful
                    result = elem;
                }
                return result;
            })
            .collect(Collectors.toList());
        if (!ignoreResult) {
            app.pushResults(results);
        }
        return 0;
    }
}

@Command(name = "get", mixinStandardHelpOptions = true, description = "Retrieve JSON from REST API request")
class Get implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Parameters(index = "0", description = "REST API resource path", arity = "1")
    private String api;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        List<Object> results = elems.stream()
            .map(elem -> {
                String newapi = Util.replaceVars(api, elem);
                HttpResponse<JsonNode> response = app.apiGet(newapi);
                Object result = response.getBody().isArray() ? response.getBody().getArray().toList() : response.getBody().getObject().toMap();
                if (!Util.ok(response)) {
                    System.err.println(Util.statusText(response));
                }
                return result;
            })
            .collect(Collectors.toList());
        app.pushResults(results);
        return 0;
    }
}

@Command(name = "filter", mixinStandardHelpOptions = true, description = "Filter values")
class Filter implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Parameters(description = "Condition to apply", arity = "*")
    private String[] conditions;

    @Override
    public Integer call() throws Exception {
        List<Pair<String, Pattern>> patterns = Arrays.stream(conditions)
            .map(test -> test.split("=", 2))
            .filter(test -> test.length == 2)
            .map(pop -> new Pair<String, Pattern>(pop[0], Pattern.compile(pop[1])))
            .collect(Collectors.toList());
        List<Object> elems = app.elems(fromIndex);
        app.pushResults(elems.stream()
            .filter(elem -> Util.match(elem, patterns))
            .collect(Collectors.toList()));
        return 0;
    }
}

@Command(name = "map", mixinStandardHelpOptions = true, description = "Transform values")
class Mapp implements Callable<Integer> {
    @ParentCommand
    private rest app;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Option(names = { "--literal", "-l" }, description = "Use literal value as starting point")
    private String literal;

    @Option(names = { "--value", "-v" }, description = "Set key=value properties on object where value is a literal")
    private String[] props;

    @Option(names = { "--select", "-s" }, description = "Set key=path properties on object where path selects a value form a previous result")
    private String[] selects;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        List<Object> results = elems.stream()
            .map(elem -> {
                Map<String, Object> obj;
                if (literal != null) {
                    obj = Util.newObject(literal);
                } else {
                    obj = Util.asObject(Util.jsonClone(elem));
                }
                if (props != null) {
                    for (String prop : props) {
                        String[] kv = prop.split("=", 2);
                        String key = kv[0];
                        String val = kv.length == 2 ? kv[1] : key;
                        Util.set(obj, key, val);
                    }
                }
                if (selects != null) {
                    for (String select : selects) {
                        String[] kp = select.split("=", 2);
                        String key = kp[0];
                        String path = kp.length == 2 ? kp[1] : key;
                        String val = Util.select(elem, path).map(Object::toString).collect(Collectors.joining());
                        Util.set(obj, key, val);
                    }
                }
                return obj;
            })
            .collect(Collectors.toList());
        app.pushResults(results);
        return 0;
    }
}

@Command(name = "post", mixinStandardHelpOptions = true, description = "Post values")
class Post implements Callable<Integer> {
    @ParentCommand
    private rest app;

    @Parameters(index = "0", description = "REST API resource path", arity = "1")
    private String api;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Option(names = { "--dry-run" }, description = "Only shows what will happen but doesn't perform any changes")
    private boolean dryRun;

    @Option(names = { "--no-body" }, description = "No body will be sent")
    private boolean noBody;

    @Option(names = { "--ignore-result", "-i" }, description = "Ignores the result of the post")
    private boolean ignoreResult;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        List<Object> results = elems.stream()
            .map(elem -> {
                String newapi = Util.replaceVars(api, elem);
                Object result;
                if (!dryRun) {
                    HttpResponse<JsonNode> response = app.apiPost(newapi, noBody ? null : elem);
                    result = response.getBody().isArray() ? response.getBody().getArray().toList() : response.getBody().getObject().toMap();
                    if (!Util.ok(response)) {
                        System.err.println(Util.statusText(response));
                    }
                } else {
                    app.logApiRequest("POST", newapi, noBody ? null : elem);
                    app.logApiResult();
                    // Not really logical but it might be useful
                    result = elem;
                }
                return result;
            })
            .collect(Collectors.toList());
        if (!ignoreResult) {
            app.pushResults(results);
        }
        return 0;
    }
}

@Command(name = "print", mixinStandardHelpOptions = true, description = "Print result")
class Print implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @ArgGroup(exclusive = true)
    Format format;

    @Option(names = { "--raw", "-r" }, description = "Print raw output, don't prettify")
    private boolean raw;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        for (Object obj : elems) {
            if (format != null && format.yaml) {
                Yaml yaml = new Yaml();
                if (!raw && obj instanceof Map) {
                    System.out.println(yaml.dump(obj));
                } else if (!raw && obj instanceof Collection) {
                    System.out.println(yaml.dump(obj));
                } else if (obj != null) {
                    System.out.println(obj.toString());
                }
            } else {
                if (!raw && obj instanceof Map) {
                    System.out.println(new JSONObject(Util.asObject(obj)).toString(2));
                } else if (!raw && obj instanceof Collection) {
                    System.out.println(new JSONArray(Util.asArray(obj)).toString(2));
                } else if (obj != null) {
                    System.out.println(obj.toString());
                }
            }
        }
        return 0;
    }
}

@Command(name = "read", mixinStandardHelpOptions = true, description = "Read JSON from file")
class Read implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Parameters(index = "0", description = "File to read, use '-' to read from stdin", arity = "1")
    private String path;

    @ArgGroup(exclusive = true)
    Format format;

    @Override
    public Integer call() throws Exception {
        Format fmt = Util.determineFormat(format, path);
        if ("-".equals(path)) {
            try (InputStreamReader reader = new InputStreamReader(System.in)) {
                Object obj;
                if (fmt.yaml) {
                    Yaml yaml = new Yaml();
                    obj = yaml.load(reader);
                } else {
                    Gson gson = new Gson();
                    obj = gson.fromJson(reader, Object.class);
                }
                app.pushResult(obj);
            }
        } else {
            try (FileReader reader = new FileReader(path)) {
                Object obj;
                if (fmt.yaml) {
                    Yaml yaml = new Yaml();
                    obj = yaml.load(reader);
                } else {
                    Gson gson = new Gson();
                    obj = gson.fromJson(reader, Object.class);
                }
                app.pushResult(obj);
            }
        }
        return 0;
    }
}

@Command(name = "select", mixinStandardHelpOptions = true, description = "Select values")
class Select implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Parameters(index = "0", description = "JSON value selector", arity = "?")
    private String select;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        app.pushResults(elems.stream()
            .flatMap(elem -> Util.select(elem, select))
            .collect(Collectors.toList()));
        return 0;
    }
}

@Command(name = "to-array", mixinStandardHelpOptions = true, description = "Set previous result as active")
class ToArray implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        if (elems.size() == 1 && elems.get(0) instanceof List) {
            app.pushResults(elems);
        } else {
            if (elems.isEmpty()) {
                app.pushResult(Util.newArray());
            } else {
                app.pushResult(Util.newArray(elems));
            }
        }
        return 0;
    }
}

@Command(name = "to-object", mixinStandardHelpOptions = true, description = "Set previous result as active")
class ToObject implements Callable<Integer> {

    @ParentCommand
    private rest app;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @Option(names = { "--key" }, description = "The path to a property to use as key")
    private String keyPath;

    @Option(names = { "--value" }, description = "The path to a property to use as value", defaultValue = ".")
    private String valuePath;

    @Override
    public Integer call() throws Exception {
        List<Object> elems = app.elems(fromIndex);
        if (elems.size() == 1 && elems.get(0) instanceof Map) {
            app.pushResults(elems);
        } else {
            Map<String, Object> obj = Util.newObject();
            if (!elems.isEmpty()) {
                int idx = 0;
                for (Object elem : elems) {
                    String key;
                    if (keyPath == null) {
                        key = Integer.toString(idx++);
                    } else {
                        key = Util.select(elem, keyPath)
                            .map(e -> e.toString())
                            .collect(Collectors.joining());
                    }
                    Object value = Util.select(elem, valuePath).findFirst().get();
                    obj.put(key, value);
                }
            }
            app.pushResult(obj);
        }
        return 0;
    }
}

@Command(name = "write", mixinStandardHelpOptions = true, description = "Write JSON to file")
class Write implements Callable<Integer> {
    @ParentCommand
    private rest app;

    @Parameters(index = "0", description = "File to write", arity = "1")
    private String path;

    @Option(names = { "--from", "-f" }, description = "Use indicated result to operate on", defaultValue = "-1")
    private int fromIndex;

    @ArgGroup(exclusive = true)
    Format format;

    @Option(names = { "--raw", "-r" }, description = "Write raw output, don't prettify")
    private boolean raw;

    @Override
    public Integer call() throws Exception {
        Format fmt = Util.determineFormat(format, path);
        if (fmt.yaml) {
            Yaml yaml = new Yaml();
            List<Object> elems = app.elems(fromIndex);
            try (FileWriter writer = new FileWriter(path)) {
                elems.forEach(elem -> yaml.dump(elem, writer));
            }
        } else {
            GsonBuilder builder = new GsonBuilder();
            if (!raw) {
                builder.setPrettyPrinting();
            }
            Gson gson = builder.create();

            List<Object> elems = app.elems(fromIndex);
            try (FileWriter writer = new FileWriter(path)) {
                elems.forEach(elem -> gson.toJson(elem, writer));
            }
        }
        return 0;
    }
}

class Format {
    @Option(names = { "--json" }, description = "Set JSON format")
    boolean json;

    @Option(names = { "--yaml" }, description = "Set YAML format")
    boolean yaml;
}

class Pair<U,V> {
    public final U first;
    public final V second;
    public Pair(U first, V second) {
        this.first = first;
        this.second = second;
    }
}

class Server {
    String url;
    String user;
    String password;
    boolean insecure;
}

class AppConfig {
    Map<String,Server> servers;
    String defaultServer;
}

class Util {

    public static Map<String, Object> newObject() {
        return new HashMap<>();
    }

    public static Format determineFormat(Format format, String path) {
        if (format != null) {
            return format;
        } else {
            Format fmt = new Format();
            fmt.json = path.endsWith(".json");
            fmt.yaml = path.endsWith(".yaml") || path.endsWith(".yml");
            return fmt;
        }
    }

    public static Map<String, Object> newObject(Map<String, Object> map) {
        return new HashMap<>(map);
    }

    public static Map<String, Object> newObject(String literal) {
        return asObject(new JSONObject(literal).toMap());
    }

    public static Map<String, Object> asObject(Object obj) {
        return (Map<String, Object>)obj;
    }

    public static List<Object> newArray() {
        return new ArrayList<>();
    }

    public static List<Object> newArray(List<Object> items) {
        return new ArrayList<>(items);
    }

    public static List<Object> newArray(String literal) {
        return asArray(new JSONArray(literal).toList());
    }

    public static List<Object> asArray(Object obj) {
        return (List<Object>)obj;
    }

    static int status(HttpResponse<JsonNode> response) {
        if (response.getStatus() >= 200 && response.getStatus() < 300 && response.getParsingError().isPresent()) {
            return 400;
        }
        return response.getStatus();
    }

    static boolean ok(HttpResponse<JsonNode> response) {
        return (Util.status(response) >= 200 && Util.status(response) < 300);
    }

    static String statusText(HttpResponse<JsonNode> response) {
        if (response.getStatus() == 200 && response.getParsingError().isPresent()) {
            return response.getParsingError().get().toString();
        }
        return response.getStatusText();
    }

    static Stream<Object> select(Object start, String path) {
        Stream<Object> nodes;
        Object node = start;
        if (node == null || path == null || path.isEmpty()) {
            nodes = node != null ? Stream.of(node) : Stream.empty();
        } else {
            String elems[] = path.split("/", 2);
            String key = elems[0];
            String index = null;
            if (key.endsWith("]")) {
                int p = key.indexOf("[");
                if (p >= 0) {
                    index = key.substring(p + 1, key.length() - 1);
                    key = key.substring(0, p);
                }
            }
            if (!key.isEmpty() && !key.equals(".")) {
                if (!(node instanceof Map)) {
                    return Stream.empty();
                }
                node = asObject(node).get(key);
            }
            if (index != null) {
                if (!(node instanceof List)) {
                    return Stream.empty();
                }
                if (index.equals("*")) {
                    nodes = asArray(node).stream();
                } else {
                    node = asArray(node).get(Integer.parseInt(index));
                    nodes = node != null ? Stream.of(node) : Stream.empty();
                }
            } else {
                nodes = node != null ? Stream.of(node) : Stream.empty();
            }
            if (elems.length == 2) {
                nodes = nodes.flatMap(nd -> select(nd, elems[1]).filter(Objects::nonNull));
            }
        }
        return nodes;
    }

    static Map<String, Object> createOrGet(Map<String, Object> obj, String  path) {
        String[] parts = path.split("/", 2);
        Map<String, Object> subobj = asObject(obj.get(parts[0]));
        if (subobj == null) {
            subobj = Util.newObject();
            obj.put(parts[0], subobj);
        }
        if (parts.length == 2) {
            subobj = createOrGet(subobj, parts[1]);
        }
        return subobj;
    }

    static void set(Object start, String path, Object value) {
        if (start instanceof Map) {
            Map<String, Object> obj = asObject(start);
            int p = path.lastIndexOf("/");
            if (p >= 0) {
                String objPath = path.substring(0, p);
                String key = path.substring(p + 1);
                Map<String, Object> subobj = createOrGet(obj, objPath);
                subobj.put(key, value);
            } else {
                obj.put(path, value);
            }
        }
    }

    static boolean match(Object entry, List<Pair<String, Pattern>> patterns) {
        return patterns.stream().anyMatch(p -> {
            List<Object> values = select(entry, p.first).collect(Collectors.toList());
            return values.stream().anyMatch(value -> p.second.matcher(value.toString()).find());
        });
    }

    private static final Pattern varPattern = Pattern.compile("\\{([^\\}]*)\\}");

    public static String replaceVars(String text, Object elem) {
        return Util.replaceAll(text, varPattern, m -> {
            String path = m.group(1);
            String result = Util.select(elem, path).map(Object::toString).collect(Collectors.joining());
            return result;
        });
    }

    public static String replaceAll(String input, Pattern regex, Function<MatchResult, String> replacer) {
        StringBuffer resultString = new StringBuffer();
        Matcher matcher = regex.matcher(input);
        while (matcher.find()) {
            matcher.appendReplacement(resultString, replacer.apply(matcher.toMatchResult()));
        }
        matcher.appendTail(resultString);
        return resultString.toString();
    }

    public static Object jsonClone(Object obj) {
        // HACK Pretty ugly way to create a clone, but whatever works
        Gson gson = new Gson();
        String json = gson.toJson(obj, Map.class);
        Object newobj = gson.fromJson(json, Object.class);
        return newobj;
    }
}