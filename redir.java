/// usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.regex.Pattern;

public class redir {
    private static Pattern directivePattern = Pattern.compile("^//[A-Z]{3,} .*$");
    private static Pattern projectDirectivePattern = Pattern.compile("^[A-Z]{3,} .*$");

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println(
                    "Either strips all directives from a JBang script or only the ones specified\n" +
                    "and replaces them with the directives passed on the standard input.\n");
            System.err.println("Usage: redir [-d|--directive <name>] [-s|--strip] [-v|--view] <filename>");
            System.exit(1);
        }
        String inFileName = null;
        String outFileName = null;
        String directive = null;
        boolean strip = false;
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                if ("-d".equals(arg) || "--directive".equals(arg)) {
                    if (i + 1 < args.length) {
                        directive = args[++i];
                    } else {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                } else if ("-o".equals(arg) || "--output".equals(arg)) {
                    if (i + 1 < args.length) {
                        outFileName = args[++i];
                    } else {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                } else if ("-s".equals(arg) || "--strip".equals(arg)) {
                    strip = true;
                } else if ("-l".equals(arg) || "--list".equals(arg)) {
                    list = true;
                } else {
                    System.err.println("Error: Unknown option " + arg);
                    System.exit(1);
                }
            } else {
                inFileName = arg;
            }
        }
        if (inFileName == null) {
            System.err.println("Error: Missing filename");
            System.exit(1);
        }
        Path inFile = Paths.get(inFileName);
        Path outFile = Files.createTempFile("redir", ".tmp");
        boolean first = true;
        boolean project = inFile.getFileName().toString().equals("build.jbang");
        Pattern pattern = project ? projectDirectivePattern : directivePattern;
        if (directive != null) {
            directive = directive.trim().toUpperCase();
            if (!project && !directive.startsWith("//")) {
                directive = "//" + directive;
            }
        }
        // Copy all lines from input file to output file except the ones
        // matching the directive or all directives if none specified
        try (var reader = Files.newBufferedReader(inFile);
             var writer = Files.newBufferedWriter(outFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                boolean matches = directive != null ?
                        line.equals(directive) || line.startsWith(directive.toUpperCase() + " ")
                        : project || pattern.matcher(line).matches();
                if (matches) {
                    if (first && !strip && !list) {
                        // copy all lines from stdin to the output file
                        try (var stdin = System.in;
                             var stdinReader = new BufferedReader(new InputStreamReader(stdin))) {
                            String stdinLine;
                            while ((stdinLine = stdinReader.readLine()) != null) {
                                writer.write(stdinLine);
                                writer.newLine();
                            }
                        }
                    }
                    first = false;
                    if (list) {
                        System.out.println(line);
                    }
                    continue;
                }
                writer.write(line);
                writer.newLine();
            }
            // now add the new directive if any
            if (directive != null) {
                writer.write("// " + directive);
                writer.newLine();
            }
        }
        if (strip || !list) {
            Path outFilePath = outFileName != null ? Paths.get(outFileName) : inFile;
            Files.move(outFile, outFilePath, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.delete(outFile);
        }
    }
}
