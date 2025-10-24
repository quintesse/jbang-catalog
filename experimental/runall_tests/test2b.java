///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test2b {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test2b.java");
        Thread.sleep(2000);
        for (int i = 0; i < 100; i+=4) {
            System.out.println("test2b.java progress " + i + "%");
            Thread.sleep(700);
        }
    }
}
