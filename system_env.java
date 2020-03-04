//usr/bin/env jbang "$0" "$@" ; exit $?

public class system_env {
    public static void main(String[] args) {
        System.getenv().entrySet().stream().forEach(e -> System.out.println(e.getKey() + "=" + e.getValue()));
    }
}