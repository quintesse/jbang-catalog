///usr/bin/env jbang "$0" "$@" ; exit $?

import static java.lang.System.*;

void print(Object msg) {
    out.print(Objects.toString(msg));
}

void printAll(Stream<?> items) {
    items.forEach(item -> println(Objects.toString(item)));
}

void println(Object msg) {
    out.println(Objects.toString(msg));
}

void printf(String msg, Object... args) {
    out.printf(msg, args);
}

Stream<String> lines() {
    return new BufferedReader(new InputStreamReader(System.in)).lines();
}

