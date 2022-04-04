// Compile using:
//    gcc -shared -fPIC -o libecho.so echo.c

#include <stdio.h>

#include "echo.h"

void echo(struct Echo e) {
	for (int i=0; i<e.repeat; i++) {
		printf("%s\n", e.message);
	}
}
