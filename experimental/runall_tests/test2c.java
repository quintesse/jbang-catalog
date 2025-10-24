///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test2c {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test2c.java");
        Thread.sleep(4000);
        for (int i = 0; i < 100; i+=10) {
            System.out.println("test2c.java progress " + i + "%");
            Thread.sleep(1500);
        }
    }
}
