//usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS com.sparkjava:spark-core:2.9.1
//DEPS com.google.code.gson:gson:2.8.6
//DEPS com.konghq:unirest-java:3.6.00
// //DEPS org.apache.httpcomponents:fluent-hc:4.5.12

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

import com.google.gson.Gson;

import spark.Request;
import spark.Spark;

public class example_server {
    private final List<Example> examples = retrieveExamples();

    public static void main(String args[]) {
        new example_server().run();
    }

    void run() {
        Spark.port(8080);

        Spark.get("/info", (req, res) -> {
            var ex = getExample(req);
            return (new Gson()).toJson(ex);
        });

        Spark.get("/zip", (req, res) -> {
            var ex = getExample(req);
            var url = ex.repo + "/archive/" + ex.ref + ".zip";
//            InputStream zipStream = org.apache.http.client.fluent.Request.Get(url).execute().returnContent().asStream();
            res.redirect(url);
            return "";
        });

        Spark.exception(IllegalArgumentException.class, (e, request, response) -> {
            response.status(400);
            response.body("Bad Request : " + e.getMessage());
        });

        Spark.exception(Exception.class, (e, request, response) -> {
            response.status(500);
            response.body("Internal Server Error : " + e.getMessage());
        });
    }

    private Example getExample(Request req) {
        var mission = getRequiredParam(req, "mission");
        var runtime = getRequiredParam(req, "runtime");
        var runtimeVersion = getRequiredParam(req, "runtimeVersion");
        Optional<Example> result = examples
            .stream()
            .filter(ex -> mission.equals(ex.metadata.mission)
                && runtime.equals(ex.metadata.runtime)
                && runtimeVersion.equals(ex.metadata.version))
            .findAny();
        return result.get();
    }

    private String getRequiredParam(Request req, String name) {
        if (req.queryParams().contains(name)) {
            return req.queryParams(name);
        } else {
            throw new IllegalArgumentException("Missing parameter: " + name);
        }
    }

    private static List<Example> retrieveExamples() {
        String url = "https://raw.githubusercontent.com/fabric8-launcher/launcher-booster-catalog/master/catalog.json";
        int status;
        GetRequest req = Unirest.get(url);
        HttpResponse<Example[]> response = req.asObject(Example[].class);
        status = response.getStatus();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Failed to retrieve example catalog");
        }
        return Arrays.asList(response.getBody());
    }
}

class Example {
    String name;
    String description;
    String repo;
    String ref;
    boolean ignore;
    ExampleMetadata metadata;
}

class ExampleMetadata {
    String mission;
    String runtime;
    String version;
}
