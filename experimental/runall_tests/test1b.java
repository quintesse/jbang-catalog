///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test1b {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test1b.java");
        Thread.sleep(2000);
        System.out.println("This is \u001b[31mstill test1b.java");
        Thread.sleep(2000);
        System.out.println("test1b.java done.");
        Thread.sleep(2000);
    }
}
