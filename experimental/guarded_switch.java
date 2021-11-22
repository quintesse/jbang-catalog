///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//JAVAC_OPTIONS --enable-preview --release 17
//JAVA_OPTIONS --enable-preview

public class guarded_switch {

    public static void main(String[] args) {
        String arg = args.length > 0 ? args[0] : null;
        Object txt = arg != null && arg.matches("\\d+") ? Long.parseLong(arg) : arg;
        String result = switch (txt) {
            case null -> "Nothing";
            case String s && (s.length() > 5) -> "Long String";
            case String s -> "Short String";
            case default -> "Not a String";
        };
        System.out.println(result);
    }
}
