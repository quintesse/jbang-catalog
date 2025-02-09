//DEPS org.apache.maven:maven-model:3.9.9

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.*;
import java.nio.file.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class mvn2jbang {

    static Model readModel(Path pom) throws IOException, XmlPullParserException {
        try (BufferedReader br = Files.newBufferedReader(pom)) {
            Model model = readModel(br);
            model.setPomFile(pom.toFile());
            return model;
        }
    }

    static Model readModel(Reader rdr) throws IOException, XmlPullParserException {
        try (Reader reader = rdr) {
            MavenXpp3Reader mavenXpp3Reader = new MavenXpp3Reader();
            return mavenXpp3Reader.read(reader);
        }
    }

    static void writeTags(PrintWriter writer, Model model, boolean usePrefix) {
        String prefix = usePrefix ? "//" : "";
        writer.println(prefix + "DEPS " + model.getDependencies().stream()
                .map(d -> d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion())
                .collect(Collectors.joining("\n//DEPS ")));
        if (model.getGroupId() != null && model.getArtifactId() != null && model.getVersion() != null) {
            writer.println(prefix + "GAV " + model.getGroupId() + ":" + model.getArtifactId() + ":" + model.getVersion());
        }
    }

    public static void main(String... args) {
        if (args.length == 1 && ("--help".equals(args[0])) || args.length > 2) {
            System.err.println("Usage: mvn2jbang [<pom_file_or_folder>] [<output_file>]");
            System.exit(1);
        }
        try {
            Path pom = Paths.get(args.length > 0 ? args[0] : "pom.xml");
            Path output = Paths.get(args.length > 1 ? args[1] : "build.jbang");
            if (!Files.exists(pom)) {
                System.err.println("Pom file not found: " + pom);
                System.exit(2);
            }
            Model model = readModel(pom);
            boolean isJavaFile = output.toString().endsWith(".java");
            boolean append = isJavaFile && Files.exists(output);
            Path output2 = append ? Paths.get(output.toString() + ".tmp") : output;
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output2))) {
                writeTags(writer, model, isJavaFile);
                if (append) {
                    try (Stream<String> lines = Files.lines(output)) {
                        lines.forEach(writer::println);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error writing output: " + e.getMessage());
                System.exit(4);
            }
            if (append) {
                Files.move(output, Paths.get(output.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                Files.move(output2, output);
            }
        } catch (IOException | XmlPullParserException e) {
            System.err.println("Error reading pom: " + e.getMessage());
            System.exit(3);
        }
    }
}
