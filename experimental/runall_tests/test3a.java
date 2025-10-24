///usr/bin/env jbang "$0" "$@" ; exit $?

package runall_tests;

public class test3a {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("This is test3a.java");
        Thread.sleep(3000);
        for (int i = 0; i < 50; i+=2) {
            for (int j = 0; j < i; j++) {
                System.out.print("#");
            }
            System.out.println();
            Thread.sleep(300);
        }
    }
}
