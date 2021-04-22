///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 14+

//DEPS com.sparkjava:spark-core:2.9.1
//DEPS com.google.code.gson:gson:2.8.6
//DEPS com.konghq:unirest-java:3.6.00
//DEPS org.apache.httpcomponents:fluent-hc:4.5.12
//DEPS org.apache.commons:commons-compress:1.20
//DEPS io.fabric8:maven-model-helper:14

//FILES META-INF/services/ZipEntryFilter=services.txt

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

import com.google.gson.Gson;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import io.fabric8.maven.Maven;
import spark.Request;
import spark.Spark;

public class example_server extends AbstractFilefilter {
    private static final Metadata metadata = retrieveMetadata();
    private static final List<Example> examples = retrieveExamples();
    private static final List<ZipEntryFilter> filters = retrieveFilters();

    public static void main(String args[]) {
        new example_server().run();
    }

    void run() {
        Spark.port(8080);

        Spark.get("/missions", (req, res) -> {
            System.out.println("Missions list requested");
            return (new Gson()).toJson(metadata.missions);
        });

        Spark.get("/runtimes", (req, res) -> {
            System.out.println("Runtimes list requested");
            return (new Gson()).toJson(metadata.runtimes);
        });

        Spark.get("/info", (req, res) -> {
            var ex = getExample(req);
            System.out.println("Example info requested: " + ex.metadata.asId());
            return (new Gson()).toJson(ex);
        });

        Spark.get("/zip", (req, res) -> {
            var ex = getExample(req);
            System.out.println("Example ZIP download requested: " + ex.metadata.asId());
            var url = ex.repo + "/archive/" + ex.ref + ".zip";
            if (!filters.isEmpty()) {
                File tmpInFile = File.createTempFile("example_server_in_", ".zip");
                File tmpOutFile = File.createTempFile("example_server_out_", ".zip");
                try {
                    org.apache.http.client.fluent.Request.Get(url).execute().saveContent(tmpInFile);
                    try (var zos = new ZipArchiveOutputStream(tmpOutFile)) {
                        try (var zipFile = new ZipFile(tmpInFile)) {
                            var entries = zipFile.getEntries();
                            while (entries.hasMoreElements()) {
                                var zipEntry = entries.nextElement();
                                if (!zipEntry.isDirectory()) {
                                    applyFilters(zos, zipEntry, zipFile.getInputStream(zipEntry), req.queryMap().toMap());
                                }
                            }
                        }
                        // Allow for new files to be added
                        while (applyFilters(zos, null, null, req.queryMap().toMap())) {
                        }
                    }
                    // Return filtered ZIP file
                    try (var zis = Files.newInputStream(tmpOutFile.toPath()); var zos = res.raw().getOutputStream()) {
                        res.raw().setContentType("application/octet-stream");
                        res.raw().setHeader("Content-Disposition",
                                "attachment; filename=" + ex.metadata.asId() + ".zip");
                        zis.transferTo(zos);
                        zos.flush();
                    }
                } finally {
                    tmpInFile.delete();
                    tmpOutFile.delete();
                }
            } else {
                res.redirect(url);
            }
            return null;
        });

        Spark.exception(IllegalArgumentException.class, (e, req, res) -> {
            res.status(400);
            res.body("Bad Request : " + e.getMessage());
            System.err.println("ERROR 400 Bad Request: " + e.getMessage());
            e.printStackTrace(System.err);
        });

        Spark.exception(Exception.class, (e, req, res) -> {
            res.status(500);
            res.body("Internal Server Error : " + e.toString());
            System.err.println("ERROR 500 Internal Server Error: " + e.toString());
            e.printStackTrace(System.err);
        });
    }

    private Example getExample(Request req) {
        var mission = getRequiredParam(req, "mission");
        var runtime = getRequiredParam(req, "runtime");
        var runtimeVersion = getRequiredParam(req, "runtimeVersion");
        Optional<Example> result = examples.stream().filter(ex -> mission.equals(ex.metadata.mission)
                && runtime.equals(ex.metadata.runtime) && runtimeVersion.equals(ex.metadata.version)).findAny();
        return result.get();
    }

    private String getRequiredParam(Request req, String name) {
        if (req.queryParams().contains(name)) {
            return req.queryParams(name);
        } else {
            throw new IllegalArgumentException("Missing parameter: " + name);
        }
    }

    private boolean applyFilters(ZipArchiveOutputStream zos,
            ZipArchiveEntry entry,
            InputStream zis,
            Map<String, String[]> params)
            throws IOException {
        var isVar = new InputStream[1];
        var entryVar = new Path[1];
        try {
            isVar[0] = zis;
            if (entry != null) {
                entryVar[0] = Paths.get(entry.getName());
            }

            for (ZipEntryFilter filter : filters) {
                filter.filter(entryVar, isVar, params);
                if (entryVar[0] == null || isVar[0] == null) {
                    break;
                }
            }

            if (entryVar[0] != null) {
                if (entry == null || !entryVar[0].toString().equals(entry.getName())) {
                    entry = new ZipArchiveEntry(entryVar[0].toString());
                }
                zos.putArchiveEntry(entry);
                isVar[0].transferTo(zos);
                zos.closeArchiveEntry();
                return true;
            } else {
                return false;
            }
        } finally {
            if (isVar[0] != null) {
                isVar[0].close();
            }
        }
    }

    private static Metadata retrieveMetadata() {
        String url = "https://raw.githubusercontent.com/fabric8-launcher/launcher-booster-catalog/master/metadata.json";
        return retrieveObjects(Metadata.class, url);
    }

    private static List<Example> retrieveExamples() {
        String url = "https://raw.githubusercontent.com/fabric8-launcher/launcher-booster-catalog/master/catalog.json";
        return Arrays.asList((Example[])retrieveObjects(Example.class.arrayType(), url));
    }

    private static <T> T retrieveObjects(Class<T> klass, String url) {
        int status;
        GetRequest req = Unirest.get(url);
        HttpResponse<T> response = req.asObject(klass);
        status = response.getStatus();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Failed to retrieve url " + url);
        }
        return response.getBody();
    }

    private static List<ZipEntryFilter> retrieveFilters() {
        var res = new ArrayList<ZipEntryFilter>();
        res.add(new StripRootFolderFilter());
        res.addAll(ServiceLoader
            .load(ZipEntryFilter.class)
            .stream()
            .map(p -> p.get())
            .collect(Collectors.toList()));
        return res;
    }

    @Override
    public boolean accept(Path entry, Map<String, String[]> params) {
        boolean hasParams = params.containsKey("projectName")
            || params.containsKey("projectVersion")
            || params.containsKey("groupId")
            || params.containsKey("artifactId");
        return entry.toString().equals("pom.xml") && hasParams;
    }
    
    @Override
    public void filter(File file, Map<String, String[]> params) {
        var model = Maven.readModel(file.toPath());
        if (params.containsKey("projectName")) model.setName(params.get("projectName")[0]);
        if (params.containsKey("projectVersion")) model.setVersion(params.get("projectVersion")[0]);
        if (params.containsKey("groupId")) model.setGroupId(params.get("groupId")[0]);
        if (params.containsKey("artifactId")) model.setArtifactId(params.get("artifactId")[0]);
        Maven.writeModel(model, file.toPath());
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

    public String asId() {
        return mission + "_" + runtime + "_" + version;
    }
}

class Metadata {
    List<Mission> missions;
    List<Runtime> runtimes;
}

class Mission {
    String id;
    String name;
    String description;
    Map<String, String> metadata;
}

class Runtime {
    String id;
    String name;
    String description;
    String icon;
    Map<String, String> metadata;
    List<Version> versions;
}

class Version {
    String id;
    String name;
}

interface ZipEntryFilter {
    /**
     * Can filter the contents of the given ZipEntry. Return the InputStream
     * unchanged if no changes need to be made. Return null to skip the entry
     * (removing it).
     * 
     * @param entry  A 1-element array containing the Path of the entry to be
     *               filtered
     * @param in     A 1-element array containing the InputStream of the contents to
     *               be filtered
     * @param params The query parameters that were passed to the request for ZIP download
     * @return The contents to use
     * @throws IOException
     */
    void filter(Path[] entry, InputStream[] in, Map<String, String[]> params) throws IOException;
}

class StripRootFolderFilter implements ZipEntryFilter {
    @Override
    public void filter(Path[] entry, InputStream[] in, Map<String, String[]> params) throws IOException {
        if (entry[0] != null) {
            if (entry[0].getNameCount() > 1) {
                entry[0] = entry[0].subpath(1, entry[0].getNameCount());
            } else {
                entry[0] = null;
            }
        }
    }
}

abstract class AbstractFilefilter implements ZipEntryFilter {
    @Override
    public void filter(Path[] entry, InputStream[] in, Map<String, String[]> params) throws IOException {
        if (entry[0] != null && accept(entry[0], params)) {
            File tmpFile = File.createTempFile("example_server_file_", "tmp");
            try (var out = Files.newOutputStream(tmpFile.toPath())) {
                in[0].transferTo(out);
            }
            in[0].close();

            // Transform file
            filter(tmpFile, params);

            in[0] = Files.newInputStream(tmpFile.toPath());
        }
    }

    abstract public boolean accept(Path entry, Map<String, String[]> params);

    abstract public void filter(File file, Map<String, String[]> params);
}
