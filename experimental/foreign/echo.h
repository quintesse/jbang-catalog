#ifndef echo_h
#define echo_h

struct Echo {
    char *message;
    int repeat;
};

extern void echo(struct Echo);

#endif /* echo_h */
