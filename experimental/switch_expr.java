///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 14+

public class switch_expr {

    enum Fubar {
        Foo,
        Bar,
        Baz;
    }

    public static void main(String[] args) {
        Fubar f = Fubar.Foo;
        int result = switch (f) {
            case Foo -> 0;
            case Bar -> 1;
            case Baz -> 2;
        };
        System.out.println(result);
        int result2 = switch (f) {
            case Foo -> 0;
            case Bar -> 1;
            default -> {
                int l = f.name().length();
                yield l;
            }
        };
        System.out.println(result2);
    }
}
