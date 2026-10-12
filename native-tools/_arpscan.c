/*
 * arpscan：局域网 ARP 扫描（root 通道，libpcap）。
 *
 * 对 /24 网段 1-254 逐个发 ARP 请求（广播 who-has），收集 ARP 响应，
 * 输出在线设备的 "IP\tMAC" 对（stdout，每行一条）。
 *
 * 特点：链路层探测，只要设备在线就会响应 ARP（即使防火墙关端口/禁 ping），
 * 解决"端口全关扫不到设备"的问题。用法：arpscan <iface> [timeout_秒]（默认 4s）。
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <net/if.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <pcap.h>

/* 以太网头（1 字节对齐，否则编译器填充导致帧错位） */
struct __attribute__((packed)) eth_hdr_t {
    unsigned char dst[6];
    unsigned char src[6];
    unsigned short type;
};

/* ARP 报文（28 字节，无 padding，必须 packed） */
struct __attribute__((packed)) arp_pkt_t {
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

/* 获取本机接口 IP */
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

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <iface> [timeout_sec]\n", argv[0]);
        return 1;
    }
    const char *ifname = argv[1];
    int timeout = argc > 2 ? atoi(argv[2]) : 4;
    if (timeout <= 0) timeout = 4;

    unsigned char my_mac[6];
    if (get_iface_mac(ifname, my_mac) < 0) {
        fprintf(stderr, "cannot get iface mac\n");
        return 1;
    }
    uint32_t my_ip = 0;
    get_iface_ip(ifname, &my_ip);

    char errbuf[PCAP_ERRBUF_SIZE];
    pcap_t *p = pcap_open_live(ifname, 65535, 0, 100, errbuf);
    if (!p) {
        fprintf(stderr, "pcap_open_live: %s\n", errbuf);
        return 1;
    }

    uint32_t base = ntohl(my_ip) & 0xFFFFFF00; /* 网段（主机序） */
    unsigned char bcast[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
    unsigned char pkt[42];

    /* 逐个发 ARP 请求（广播 who-has），节流避免瞬时淹没 */
    for (int i = 1; i <= 254; i++) {
        uint32_t target = htonl(base | i);
        if (target == my_ip) continue; /* 跳过自己 */
        memset(pkt, 0, sizeof(pkt));
        struct eth_hdr_t *eth = (struct eth_hdr_t *)pkt;
        struct arp_pkt_t *arp = (struct arp_pkt_t *)(pkt + 14);
        memcpy(eth->dst, bcast, 6);
        memcpy(eth->src, my_mac, 6);
        eth->type = htons(0x0806);
        arp->htype = htons(1);
        arp->ptype = htons(0x0800);
        arp->hlen = 6;
        arp->plen = 4;
        arp->op = htons(1); /* ARPOP_REQUEST */
        memcpy(arp->sha, my_mac, 6);
        arp->spa = my_ip;
        memset(arp->tha, 0, 6);
        arp->tpa = target;
        pcap_inject(p, pkt, 42);
        if (i % 64 == 0) usleep(20000);
    }

    /* 收响应：ARP reply（op=2）且 spa 是网段内地址（排除自己） */
    time_t start = time(NULL);
    while (time(NULL) - start < timeout) {
        struct pcap_pkthdr *hdr;
        const unsigned char *data;
        int r = pcap_next_ex(p, &hdr, &data);
        if (r == 1 && hdr->caplen >= 42) {
            if (data[12] == 0x08 && data[13] == 0x06) {
                unsigned int op = ((unsigned int)data[20] << 8) | data[21];
                if (op == 2) {
                    uint32_t spa = ((uint32_t)data[28] << 24) | ((uint32_t)data[29] << 16) |
                                   ((uint32_t)data[30] << 8) | data[31];
                    if (spa != my_ip && (ntohl(spa) & 0xFFFFFF00) == base) {
                        struct in_addr s;
                        s.s_addr = spa;
                        printf("%s\t%02x:%02x:%02x:%02x:%02x:%02x\n",
                               inet_ntoa(s),
                               data[22], data[23], data[24],
                               data[25], data[26], data[27]);
                        fflush(stdout);
                    }
                }
            }
        }
    }
    pcap_close(p);
    return 0;
}
