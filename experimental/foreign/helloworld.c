// Compile using:
//    gcc -shared -fPIC -o libhelloworld.so helloworld.c

#include <stdio.h>

#include "helloworld.h"

void helloworld() {
	printf("hello world!\n");
}

