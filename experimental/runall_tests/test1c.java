///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test1c {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test1c.java");
        Thread.sleep(5000);
        System.out.println("This is \u001b[32mstill\u001b[0m test1c.java");
        Thread.sleep(5000);
        System.out.println("test1c.java done.");
        Thread.sleep(5000);
    }
}
