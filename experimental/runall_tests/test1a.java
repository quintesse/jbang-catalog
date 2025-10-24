///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test1a {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test1a.java");
        Thread.sleep(3000);
        System.out.println("This is \u001b[1mstill\u001b[0m test1a.java");
        Thread.sleep(3000);
        System.out.println("test1a.java done.");
        Thread.sleep(3000);
    }
}
