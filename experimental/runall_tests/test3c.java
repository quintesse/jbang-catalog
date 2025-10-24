///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test3c {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test3c.java");
        Thread.sleep(4000);
        for (int i = 0; i < 50; i+=10) {
            for (int j = 0; j < i; j++) {
                System.out.print("#");
            }
            System.out.println();
            Thread.sleep(700);
        }
    }
}
