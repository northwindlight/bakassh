#ifndef BAKASSH_TEST_LOG_H
#define BAKASSH_TEST_LOG_H
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int priority, const char *tag, const char *fmt, ...) {
    (void) priority; (void) tag; (void) fmt;
    return 0;
}
#endif
