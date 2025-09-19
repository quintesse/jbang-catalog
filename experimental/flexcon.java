///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+

public class flexcon extends Parent {
    protected String bar;

    public flexcon() {
        this.bar = "bar";
        // Commenting the super() call to see the effect of the
        // flexible constructor bodies
        super();
    }

    public void printText() {
        System.out.println("Value of foo = " + foo + " and bar = " + bar);
    }

    public static void main(String... args) {
        flexcon f = new flexcon();
    }
}

class Parent {
    protected String foo;

    public Parent() {
        this.foo = "foo";
        printText();
    }

    public void printText() {
        System.out.println("Value of foo = " + foo);
    }
}