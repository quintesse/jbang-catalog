///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 14+

import static java.lang.System.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class cat {
    public static void main(String... args) throws IOException {
        for (String arg : args) {
            Files.copy(Path.of(arg), out);
        }
    }
}
