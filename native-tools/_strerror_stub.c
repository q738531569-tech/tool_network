/*
 * 兼容桩：libpcap 1.10.5 编译时误判系统为 GNU strerror_r（bionic 实际只有 XSI 版），
 * 链接期报 undefined symbol __gnu_strerror_r。此桩转发到 bionic 的 strerror_r
 * （XSI 语义：写 buf 返回 0），并返回 buf 模拟 GNU 版返回指针。
 * 仅用于 Android bionic 交叉编译 arpspoof/deauth 等自研工具。
 */
#include <string.h>

char *__gnu_strerror_r(int errnum, char *buf, size_t buflen) {
    strerror_r(errnum, buf, buflen); /* bionic XSI：int strerror_r(int, char *, size_t) */
    return buf;
}
