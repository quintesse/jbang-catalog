///usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class runall {
    private static String CSI = "\u001b[";
    private static String NORMAL = CSI + "0m";
    private static String CLR_EOL = CSI + "0K";
    private static String CUR_HIDE = CSI + "?25l";
    private static String CUR_SHOW = CSI + "?25h";

    private static String UP(int n) {
        if (n <= 0) {
            return "";
        }
        return CSI + n + "A";
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        String command = isWindows() ? "powershell -Command jbang {} ; exit $LastExitCode" : "jbang";
        List<String> scripts = new ArrayList<>();
        boolean parseOptions = true;
        int width = 80;
        int threads = 5;
        String outputFile = null;
        for (int i = 0; i < args.length; i++) {
            if (!parseOptions) {
                scripts.add(args[i]);
            } else if ("-c".equals(args[i]) || "--command".equals(args[i]) ) {
                // Handle the -c/--command option
                if (i + 1 >= args.length) {
                    break;
                }
                command = args[++i];
            } else if ("-o".equals(args[i]) || "--output".equals(args[i]) ) {
                // Handle the -o/--output option
                if (i + 1 >= args.length) {
                    break;
                }
                outputFile = args[++i];
            } else if ("-w".equals(args[i]) || "--width".equals(args[i]) ) {
                // Handle the -w/--width option
                if (i + 1 >= args.length) {
                    break;
                }
                width = Integer.parseInt(args[++i]);
            } else if ("-t".equals(args[i]) || "--threads".equals(args[i]) ) {
                // Handle the -t/--threads option
                if (i + 1 >= args.length) {
                    break;
                }
                threads = Integer.parseInt(args[++i]);
            } else {
                // No more options
                scripts.add(args[i]);
                parseOptions = false;
            }
        }

        if (scripts.isEmpty()) {
            System.err.println("runall is a tool that given a set of arguments will run a command on each of them in parallel.");
            System.err.println("By default the command is 'jbang' and the arguments would be Java scripts to build and run.");
            System.err.println("Usage: runall [options...] <script>...");
            System.err.println("");
            System.err.println("   -c|--command <command>");
            System.err.println("   -o|--output <output_file>");
            System.err.println("   -t|--threads <num_threads>");
            System.err.println("   -w|--width <output_width>");
            System.exit(1);
        }

        List<AtomicReference<String>> working = new CopyOnWriteArrayList<>();
        List<AtomicReference<String>> finished = new CopyOnWriteArrayList<>();

        ReentrantLock lock = new ReentrantLock();
        Condition update = lock.newCondition();

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // If outputFile is specified, create text file to write output to
        PrintWriter out = outputFile != null ? new PrintWriter(new FileWriter(outputFile)) : null;

        // Submit all scripts to run
        String[] cmd = command.split(" ");
        for (String script : scripts) {
            AtomicReference<String> message = new AtomicReference<>();
            Consumer<String> msg = (String txt) -> {
                String line = "[" + makeName(script) + "] " + txt;
                if (out != null) {
                    out.println(line);
                }
                message.set(line);
                lock.lock();
                try {
                    update.signalAll();
                } finally {
                    lock.unlock();
                }
            };
            msg.accept("Waiting...");
            executor.submit(() -> {
                working.add(message);
                try {
                    msg.accept("Running...");
                    ProcessBuilder pb = new ProcessBuilder(makeCommand(cmd, script));
        			pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    br.lines().forEach(line -> msg.accept(line));
                    //br.lines().forEach(line -> System.out.println(line));
                    int exitCode = p.waitFor();
                    if (exitCode != 0) {
                        msg.accept("ERR: Exited with code " + exitCode);
                    } else {
                        msg.accept("OK: Completed successfully.");
                    }
                } catch (Exception e) {
                    msg.accept("ERR: Failed with: " + e.getMessage());
                }
                working.remove(message);
                finished.add(message);
                if (out != null) {
                    out.flush();
                }
            });
        }

        // Wait for showMessages and print messages
        int numFinished = 0;
        int numWorking = 0;
        System.out.println("Starting tasks...");
        while (finished.size() < scripts.size()) {
            lock.lock();
            try {
                update.await();
            } finally {
                lock.unlock();
            }
            System.out.print(CUR_HIDE + UP(numWorking));
            for (int i = numFinished; i < finished.size(); i++, numFinished++) {
                String msg = normalize(finished.get(i).get(), width);
                System.out.println(msg + CLR_EOL);
            }
            numWorking = 0;
            for (AtomicReference<String> msgRef : working) {
                String msg = normalize(msgRef.get(), width);
                System.out.println(msg + NORMAL + CLR_EOL);
                numWorking++;
            }
            System.out.print(CUR_SHOW);
        }
        
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        if (out != null) {
            out.close();
        }
    }

    private static String makeName(String script) {
        try {
            return Paths.get(script).getFileName().toString();
        } catch (InvalidPathException e) {
            return script;
        }
    }

    private static List<String> makeCommand(String[] cmd, String script) {
        List<String> command = new ArrayList<>(Arrays.asList(cmd));
        boolean replaced = false;
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.contains("{}")) {
                command.set(i, arg.replace("{}", "\"" + script + "\""));
                replaced = true;
            }
        }
        if (!replaced) {
            command.add(script);
        }
        return command;
    }

    private static String normalize(String txt, int width) {
        txt = stripNonVizEscapeSequences(txt);
        txt = truncate(txt, width);
        return txt;
    }

    private static String stripNonVizEscapeSequences(String txt) {
        return txt.replaceAll("\u001b\\[[?>]?[;\\d]*[A-Za-ln-z]", "");
    }

    private static String truncate(String txt, int width) {
        if (width <= 0) {
            return txt;
        }
        // Limit text length to width characters not counting escape sequences
        Pattern pat = Pattern.compile("\u001b\\[[?>]?[;\\d]*[A-Za-z]|.");
        Matcher m = pat.matcher(txt);
        StringBuilder result = new StringBuilder();
        int visibleLength = 0;
        while (m.find()) {
            // Append the escape sequence itself
            if (m.group().length() == 1) {
                visibleLength++;
                if (visibleLength >= width) {
                    break;
                }
            }
            result.append(m.group());
        }
        return result.toString();
    }

    private static boolean isWindows() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "");
        return os.startsWith("win");
    }
}
