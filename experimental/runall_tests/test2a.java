///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test2a {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test2a.java");
        Thread.sleep(3000);
        for (int i = 0; i < 100; i+=5) {
            System.out.println("test2a.java progress " + i + "%");
            Thread.sleep(1000);
        }
    }
}
