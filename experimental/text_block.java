///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 15+

public class text_block {

    public static void main(String[] args) {
        String txt = """
            First line
            Second line
            Third line
            """;
        System.out.println(txt);
        String txt2 = """
            First line
            Second line
            Third line
        """;
        System.out.println(txt2);
        String txt3 = """
            A single very \
            very very very \
            long line.""";
        System.out.println(txt3);
    }
}
