// Probe 4, native side: the cost of fsync() and of fcntl(F_FULLFSYNC) per file on macOS, so the
// Java numbers (ProbeFsync) can be matched to one or the other.
// Build and run: cc -O2 -o fsync_probe fsync_probe.c && ./fsync_probe <dir>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static void run(const char *dir, size_t size, int n, int full) {
    char *buf = malloc(size);
    memset(buf, 'x', size);
    double total = 0, worst = 0;
    for (int i = 0; i < n; i++) {
        char path[1024];
        snprintf(path, sizeof path, "%s/f%d", dir, i);
        int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0644);
        if (write(fd, buf, size) != (ssize_t) size) { perror("write"); exit(1); }
        double t = now();
        if (full ? fcntl(fd, F_FULLFSYNC) : fsync(fd)) { perror("sync"); exit(1); }
        double d = now() - t;
        total += d;
        if (d > worst) worst = d;
        close(fd);
        unlink(path);
    }
    printf("%-11s %8zu bytes  n=%3d  mean %8.3f ms  worst %8.3f ms\n",
           full ? "F_FULLFSYNC" : "fsync", size, n, total / n, worst);
    free(buf);
}

int main(int argc, char **argv) {
    const char *dir = argc > 1 ? argv[1] : ".";
    size_t sizes[] = {4096, 1 << 20, 64 << 20};
    int counts[] = {200, 50, 10};
    for (int full = 0; full <= 1; full++)
        for (int i = 0; i < 3; i++) run(dir, sizes[i], counts[i], full);
    return 0;
}

// Output (2026-10-08, Apple M1 Max, internal SSD, APFS):
// fsync           4096 bytes  n=200  mean    0.032 ms  worst    0.075 ms
// fsync        1048576 bytes  n= 50  mean    0.198 ms  worst    0.247 ms
// fsync       67108864 bytes  n= 10  mean    1.452 ms  worst    1.658 ms
// F_FULLFSYNC     4096 bytes  n=200  mean    5.005 ms  worst   53.782 ms
// F_FULLFSYNC  1048576 bytes  n= 50  mean    7.321 ms  worst    9.190 ms
// F_FULLFSYNC 67108864 bytes  n= 10  mean   17.749 ms  worst   23.545 ms
