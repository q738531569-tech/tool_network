package com.installer.toolbox.core.agent

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.wifi.WifiManager
import androidx.annotation.RequiresApi
import com.installer.toolbox.core.network.util.IpUtil
import com.installer.toolbox.core.agent.DeepSeekClient.ToolCall
import com.installer.toolbox.core.agent.DeepSeekClient.ToolDef
import com.installer.toolbox.core.capture.CaptureBus
import com.installer.toolbox.core.capture.PacketCaptureService
import com.installer.toolbox.core.capture.TrafficSnapshot
import com.installer.toolbox.core.isapi.HikvisionApi
import com.installer.toolbox.core.isapi.OnvifClient
import com.installer.toolbox.core.isapi.OnvifDiscovery
import com.installer.toolbox.core.isapi.PasswordProbe
import com.installer.toolbox.core.agent.support.AiMemory
import com.installer.toolbox.core.agent.support.AiNotes
import com.installer.toolbox.core.agent.support.CredentialVault
import com.installer.toolbox.core.agent.support.FeedbackApi
import com.installer.toolbox.core.agent.support.FeedbackHelper
import com.installer.toolbox.core.network.diagnose.DnsTool
import com.installer.toolbox.core.network.diagnose.PingTool
import com.installer.toolbox.core.network.diagnose.PortScanner
import com.installer.toolbox.core.network.diagnose.SnmpProbe
import com.installer.toolbox.core.network.diagnose.SubnetCalculator
import com.installer.toolbox.core.network.diagnose.TracerouteTool
import com.installer.toolbox.core.network.diagnose.WakeOnLan
import com.installer.toolbox.core.network.diagnose.WeakNetworkDetector
import com.installer.toolbox.core.network.diagnose.PlatformTarget
import com.installer.toolbox.core.network.diagnose.resolvePlatform
import com.installer.toolbox.core.network.discovery.BarcodeUtils
import com.installer.toolbox.core.network.discovery.DeviceScanner
import com.installer.toolbox.core.network.scan.ArpSpoofDetector
import com.installer.toolbox.core.network.scan.BleScanner
import com.installer.toolbox.core.network.scan.GatewayConflictDetector
import com.installer.toolbox.core.network.scan.LanInterfaceResolver
import com.installer.toolbox.core.network.scan.LanSilencer
import com.installer.toolbox.core.network.scan.LoopDetector
import com.installer.toolbox.core.network.scan.NmapScanner
import com.installer.toolbox.core.network.scan.PcdnDetector
import com.installer.toolbox.core.network.scan.PcdnNetworkScanner
import com.installer.toolbox.core.network.scan.TrafficAudit
import com.installer.toolbox.core.network.wifi.DfsChecker
import com.installer.toolbox.core.network.wifi.WifiAnalyzer
import com.installer.toolbox.core.network.wifi.WifiRadar
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI Agent 工具注册表 + 执行器（M5.1）。
 *
 * 把 APP 内的诊断能力（扫描 / ping / DNS / 端口 / 摄像头等）暴露给 DeepSeek，
 * 让模型在对话中主动调用工具、拿到结果继续分析，形成"提问 → 调用 → 分析"闭环。
 *
 * 设计约定：
 * - [definitions] 只描述工具（OpenAI function calling JSON Schema，中文 description）；
 * - [execute] 按 call.name 分发到具体实现，所有执行包 try/catch 且运行在 IO 线程，
 *   任何失败都返回 "执行失败：{原因}" 字符串而不是抛异常，保证 Agent 循环不中断；
 * - 耗时工具（scan_lan 20-60 秒）在 description 中明确提示，结果长度受控。
 */
object AgentTools {

    /** 局域网扫描结果最多列出的设备数（防结果过长撑爆上下文） */
    private const val MAX_DEVICES = 20

    /** 周边 AP 最多列出的数量 */
    private const val MAX_APS = 15

    /** 摄像头逐个读型号的最大数量（防过多串行请求） */
    private const val MAX_CAMERAS = 10

    /** BLE 扫描结果最多列出的设备数（防结果过长撑爆上下文） */
    private const val MAX_BLE = 20

    /** 注册全部工具定义（无参数工具也给出空 object schema，兼容各家实现） */
    fun definitions(context: Context): List<ToolDef> = listOf(
        ToolDef(
            name = "network_context",
            description = "获取当前网络环境：Wi-Fi 子网段、网关、DNS、SSID、信号强度等，用于定位网络问题前先了解现场。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "ping_device",
            description = "对目标 IP 或域名做 TCP Ping 探测（默认 443 端口），返回丢包率、平均/最小/最大延迟、成功次数，用于判断目标是否可达、网络质量如何。",
            parameters = objSchema(
                "target" to ("string" to "目标 IP 或域名，必填"),
                "count" to ("number" to "探测次数，1-10，默认 4"),
                "port" to ("number" to "探测端口，1-65535，默认 443"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "dns_query",
            description = "查询域名的 DNS 记录（A / AAAA / MX / NS / TXT），返回记录列表与耗时，用于排查域名解析失败、解析结果异常等问题。",
            parameters = objSchema(
                "host" to ("string" to "要查询的域名，必填"),
                required = listOf("host")
            )
        ),
        ToolDef(
            name = "scan_ports",
            description = "扫描指定 IP 或域名上的开放 TCP 端口（范围限制 1-1024，默认 1-1024），返回开放端口及常见服务名，用于判断设备开了哪些服务、排查端口不通。注意：扫描 1024 个端口约需数秒到十几秒。",
            parameters = objSchema(
                "target" to ("string" to "目标 IP 或域名，必填"),
                "start" to ("number" to "起始端口，1-1024，默认 1"),
                "end" to ("number" to "结束端口，1-1024，默认 1024"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "trace_route",
            description = "对目标 IP 或域名做路由追踪（TCP 变通 traceroute，无需 root），逐跳探测到目标的连通性与耗时，返回每一跳的耗时与是否到达，用于定位网络断在哪一段、哪一跳开始不通。注意：Android 上中间路由 IP 无法获取（只返回跳数与耗时）。",
            parameters = objSchema(
                "target" to ("string" to "目标 IP 或域名，必填"),
                "port" to ("number" to "探测端口，1-65535，默认 80"),
                "maxHops" to ("number" to "最大跳数，1-30，默认 15"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "scan_lan",
            description = "扫描局域网指定网段（默认当前 Wi-Fi /24 网段 254 台主机），返回在线设备列表（IP、MAC、厂商、开放端口、是否疑似 PCDN 设备）。注意：全子网扫描较慢，可能耗时 20-60 秒（超过 60 秒自动中断并提示超时），请耐心等待结果。subnet 可选：指定网段（如 192.168.2.0/24）——手机需与该网段二层可达（连上二级路由 Wi-Fi 后扫其网段，或主路由 VLAN 互通时直接扫），否则会全超时。",
            parameters = objSchema(
                "subnet" to ("string" to "可选：目标网段 CIDR，如 192.168.2.0/24；留空扫当前网段")
            )
        ),
        ToolDef(
            name = "arp_scan",
            description = "主动 ARP 全网扫描（需 root）：用链路层 ARP 发现当前子网全部在线设备，**包括端口全关/禁 ping 的设备**（比 scan_lan 的 TCP 探测更全）。返回设备清单（IP/MAC/厂商/开放端口）。约 10-30 秒（超过 30 秒自动中断并提示超时）。未检测到 root 时不可用（可改用 scan_lan，但端口不开放的设备扫不到）。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "discover_cameras",
            description = "通过 ONVIF WS-Discovery 协议发现局域网内的摄像头等视频设备，并尝试匿名读取每台设备的型号与厂商。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "wifi_info",
            description = "获取当前 Wi-Fi 连接详情（SSID、信号强度、频段、信道、链路速率）并扫描周边 AP 列表。注意：扫描周边 AP 需要定位权限且系统位置服务开启，未授权时该项会返回权限提示。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "get_system_status",
            description = "检测 root 权限并查询手机系统开关状态：返回开头明确『已获取 root 权限』或『未检测到 root 权限』，随后是 Wi-Fi/蓝牙/移动数据/飞行模式开关状态（非 root 时系统开关不可用）。用户问手机有没有 root / 能否用 root 功能时先调用本工具。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "set_wifi",
            description = "开启或关闭手机 Wi-Fi 开关（需 root，未检测到 root 时返回提示）。【警告】关闭 Wi-Fi 会立刻断开手机网络，导致 AI 与云端连接中断、对话失败！除非用户明确说\"关 Wi-Fi/断网\"，否则不要调用；执行后提醒用户重新开启。参数 on：true 开启 / false 关闭。",
            parameters = objSchema(
                "on" to ("boolean" to "true 开启 / false 关闭，必填"),
                required = listOf("on")
            )
        ),
        ToolDef(
            name = "set_bluetooth",
            description = "开启或关闭手机蓝牙开关（需 root，未检测到 root 时返回提示）。不影响网络连接。参数 on：true 开启 / false 关闭。",
            parameters = objSchema(
                "on" to ("boolean" to "true 开启 / false 关闭，必填"),
                required = listOf("on")
            )
        ),
        ToolDef(
            name = "set_mobile_data",
            description = "开启或关闭手机移动数据（需 root，未检测到 root 时返回提示）。【警告】若当前仅靠移动数据联网，关闭会断网导致 AI 对话失败；除非用户明确要求，否则不要调用。参数 on：true 开启 / false 关闭。",
            parameters = objSchema(
                "on" to ("boolean" to "true 开启 / false 关闭，必填"),
                required = listOf("on")
            )
        ),
        ToolDef(
            name = "set_airplane_mode",
            description = "开启或关闭手机飞行模式（需 root，未检测到 root 时返回提示）。【警告】开启飞行模式会立刻切断全部网络（Wi-Fi/蜂窝），导致 AI 对话中断失败！除非用户明确说\"开飞行模式\"，否则严禁调用；执行后提醒用户关闭。参数 on：true 开启 / false 关闭。",
            parameters = objSchema(
                "on" to ("boolean" to "true 开启 / false 关闭，必填"),
                required = listOf("on")
            )
        ),
        ToolDef(
            name = "detect_arp_spoof",
            description = "检测局域网 ARP 欺骗/IP-MAC 冲突：抓 ARP 包统计同一 IP 的多个 MAC，含网关 MAC 对比；无 root 自动降级为 ARP 表对比（精度较低），目标设备启用 ARP 防护/静态绑定/防火墙拦截时可能检测不到。durationSec 为 root 抓包时长（3-30 秒，默认 8）。",
            parameters = objSchema(
                "durationSec" to ("number" to "root 抓包时长秒数，3-30，默认 8")
            )
        ),
        ToolDef(
            name = "detect_gateway_conflict",
            description = "网关/IP 冲突检测：①扫描局域网找多个'疑似网关'（开放 53/67 端口的设备，双路由/乱接会导致 IP 冲突、上网不稳）②检测'一 MAC 多 IP'（同设备多 IP/疑似 IP 冲突）。与 detect_arp_spoof（一 IP 多 MAC）互补。返回当前网关、疑似网关列表、冲突提示。无参数，root 时全量扫描更准；可能耗时 20-60 秒（超过 60 秒自动中断并提示超时）。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "detect_loop",
            description = "局域网环路/广播风暴检测（需 root + 内置 tcpdump，无需 Termux）：抓 N 秒链路层帧，统计广播/组播帧占比与包速率，并给出广播帧源 MAC TOP10（定位风暴元凶，伪装 MAC 设备会频繁换源分散）。判断是否存在环路（交换机/网线错误成环、双路由互连导致的广播风暴）。正常网络广播+组播占比一般 <10%。风暴可能间歇性发作：一次检测正常不代表没有，怀疑时可加长抓包时长（如 60-120 秒）覆盖间歇期。未检测到 root 时不可用（返回提示，可改用观察网速/登录交换机排查）。",
            parameters = objSchema(
                "durationSec" to ("number" to "抓包时长秒数，3-120，默认 8；怀疑间歇性风暴时用 60-120 秒长抓包")
            )
        ),
        ToolDef(
            name = "scan_ble",
            description = "扫描周边蓝牙 BLE 设备（名称/地址/信号/厂商数据），并识别厂商（设备名关键词 + manufacturerData Company ID，如小米/华为/苹果/三星等）。排查物联网设备与干扰。需要蓝牙和定位权限。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "nmap_scan",
            description = "用 nmap 深度扫描目标（内置 nmap，root 即可，无需 Termux）：服务与版本探测。参数 target 必填（IP 或域名），options 可选（默认 -Pn -sV --top-ports 100 --open，-Pn 跳过主机发现避免网关误报 host down）。注意：扫描耗时较长；未检测到 root 时不可用（可改用 scan_ports 端口扫描）。",
            parameters = objSchema(
                "target" to ("string" to "目标 IP/域名，必填"),
                "options" to ("string" to "nmap 参数，可选"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "detect_pcdn",
            description = "深度检测指定/当前网段可疑 PCDN 设备（检出从严，漏报优先于误报）：特征 TCP 端口 + 主机名关键词 + 一 MAC 多 IP + BT DHT 协议 ping（P2P 节点确认）+ 主路由 UPnP 端口映射表核对 + 多 IP 设备网页身份识别（摄像头先识别排除路由误判，但摄像头同样可能内置/被植入 PCDN，协议级硬证据照样算可疑），输出置信度（高=协议级证据，中/低=待流量审计确认）。耗时约 20-60 秒（超过 60 秒自动中断并提示超时）。subnet 可选：指定网段（如 192.168.2.0/24，需二层可达）。",
            parameters = objSchema(
                "subnet" to ("string" to "可选：目标网段 CIDR，如 192.168.2.0/24；留空检测当前网段")
            )
        ),
        ToolDef(
            name = "detect_pcdn_network",
            description = "全网一键检测可疑 PCDN 设备（检出从严）：先特征层宽进列出可疑（特征端口/主机名/多 IP/DHT 协议/UPnP 映射），再对可疑设备做 ARP 中间人流量审计确认（上行占比/UDP 占比/分布式 IP/IDC 目标/并发连接/STUN/UPnP 映射/DNS 域名自报等特征，总分≥4 且至少 1 个特征命中即疑似，需人工复核）。摄像头会先做识别排除路由误判（多镜头摄像头不再误标成路由器），但摄像头同样可能内置/被植入 PCDN——协议级硬证据照样列可疑。适合大型局域网，避免逐台排查。耗时视设备数与可疑设备数而定（特征扫描约 20-60 秒 + 每台审计约 perDeviceSec 秒；总耗时可能 1-2 分钟，超过 120 秒自动中断并提示超时）。perDeviceSec 为阶段2 单台审计时长（1-120 秒，默认 15）。subnet 可选：指定网段（需二层可达）。",
            parameters = objSchema(
                "perDeviceSec" to ("number" to "阶段2 单台流量审计时长秒数，1-120，默认 15"),
                "subnet" to ("string" to "可选：目标网段 CIDR，如 192.168.2.0/24；留空检测当前网段")
            )
        ),
        ToolDef(
            name = "audit_traffic",
            description = "对指定设备做 ARP 中间人流量审计（需 root；内置 arpspoof/tcpdump，无需 Termux）：统计该设备上下行流量与 DNS 域名特征，综合评分判定是否疑似 PCDN 上行占用（总分≥4 且至少 1 个特征命中、且上行量达标才判疑似，检出从严需人工复核；结果带运营商口径对照）。未检测到 root 时不可用（可改用 detect_pcdn_network 特征扫描）。",
            parameters = objSchema(
                "target" to ("string" to "目标设备 IP，必填"),
                "durationSec" to ("number" to "审计时长秒数，1-120，默认 60（长时对齐运营商单日口径，短窗口占比噪声大）"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "passthrough_audit",
            description = "借道审计（PCDN 藏在租户私接二级路由器 NAT 后面、特征扫描查不出时用）：对疑似二级路由/多 IP 设备做双向 ARP+IPv6 欺骗，引其 WAN 侧流量到本机抓包，按源端口聚类还原 NAT 后下挂设备（源端口→上行字节→目的 IP/IDC），判定其内网侧是否藏 PCDN——PCDN 盒子在 NAT 后也照常上传，其流量特征会透过 NAT 出现在该路由 WAN 流量里。需 root。target 填疑似二级路由的 IP（可由 detect_gateway_conflict/detect_pcdn 结果获得）。",
            parameters = objSchema(
                "target" to ("string" to "疑似二级路由/多 IP 设备的 IP，必填"),
                "durationSec" to ("number" to "审计时长秒数，1-120，默认 60（借道还原下挂设备建议 ≥60 秒）"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "capture_traffic",
            description = "在本机启动 VpnService 抓包统计 N 秒，返回流量概况（总包/字节、速率、TCP/UDP/ICMP 分布、广播/组播包与占比、广播风暴状态）。注意：需用户在系统弹窗授权 VPN 连接；返回的是本机流量（无法看到局域网其他设备）。",
            parameters = objSchema(
                "durationSec" to ("number" to "抓包时长秒数，3-30，默认 10")
            )
        ),
        ToolDef(
            name = "submit_feedback",
            description = "把用户的口头反馈/建议提交到开发者服务器（用户说『帮我反馈』『提个建议』『报个问题』时调用）。提交内容附带诊断信息（App 版本/机型/系统/root/功能使用统计/崩溃日志条数），开发者据此优化工具。include_network 可选：附带现场网络信息（Wi-Fi 名称/网关等）前必须征得用户同意。无需 root，约 2-5 秒。",
            parameters = objSchema(
                "type" to ("string" to "反馈类型：问题反馈 / 功能建议 / 体验优化 / 其他"),
                "content" to ("string" to "反馈内容（用户原始诉求，尽量完整原意）"),
                "include_network" to ("boolean" to "是否附带现场网络信息；默认 false，true 前必须征得用户同意"),
                required = listOf("type", "content")
            )
        ),
        ToolDef(
            name = "get_diagnostic_info",
            description = "收集本机诊断信息：App 版本/设备/系统/root 状态/累计启动次数/功能使用统计/崩溃日志条数。用于帮用户排查工具本身的问题、判断版本新旧、整理反馈材料。include_network 可选：附带现场网络信息（默认 false，true 前征得用户同意）。无需 root。",
            parameters = objSchema(
                "include_network" to ("boolean" to "是否附带现场网络信息；默认 false")
            )
        ),
        ToolDef(
            name = "root_shell",
            description = "以 root 权限在本机执行任意 shell 命令（极度危险：可修改系统/网络配置、读取或删除数据）。系统会自动弹审批框给用户确认（默认每步审批），无需提前长篇说明，回复里一句话带过命令目的即可。适合装维场景：查内核日志/改路由表/读写系统文件等 root 工具覆盖不到的排查操作。未检测到 root 时不可用。",
            parameters = objSchema(
                "command" to ("string" to "要执行的完整 shell 命令字符串，必填"),
                required = listOf("command")
            )
        ),
        ToolDef(
            name = "remember_info",
            description = "把对话中发现的**重要信息**写入记忆（按会话隔离：只在本会话后续对话生效，换新客户开新会话不串用）：现场网络拓扑、设备位置/用途、排查结论、用户偏好等。使用时机：用户说『记住…』，或排查得到可复用的关键结论时主动记忆。禁止记忆密码/API Key/密钥等敏感凭据；单条 200 字内，重要才记，不要事无巨细。",
            parameters = objSchema(
                "text" to ("string" to "记忆内容（简洁、可复用的事实或结论，如『192.168.10.23/27 是同一台双镜头摄像头』）"),
                required = listOf("text")
            )
        ),
        ToolDef(
            name = "write_md",
            description = "把完整的现场资料/排查报告写成 .md 文件自动沉淀（像开发 agent 写文档一样；按会话隔离，清空会话后仍保留、可通过 read_md 读回）：客户网络拓扑、设备清单、完整排查过程与结论、给用户的整改建议等长篇内容适合写 md，简短结论用 remember_info 即可。禁止写入密码/API Key/密钥等敏感凭据。",
            parameters = objSchema(
                "filename" to ("string" to "文件名（如『客户A_拓扑排查』，自动补 .md 后缀）"),
                "content" to ("string" to "markdown 内容（完整资料正文）"),
                required = listOf("filename", "content")
            )
        ),
        ToolDef(
            name = "read_md",
            description = "读取本会话 AI 生成/沉淀的 .md 资料文件（清空会话后重新聊天时，先调用本工具不传 filename 列出文件清单，再读取需要的文件找回上下文）。返回内容截断 8000 字符。",
            parameters = objSchema(
                "filename" to ("string" to "可选：要读取的文件名（可省略 .md 后缀）；留空=列出本会话全部 md 文件清单与摘要")
            )
        ),
        ToolDef(
            name = "save_credential",
            description = "把客户现场的密码/账号凭据（摄像头密码、路由器密码、WiFi 密码、ONVIF 账号等）存入**本机加密保险库**（AES 加密落盘、按会话隔离、清空会话保留/删除会话连带删）。使用时机：用户告知或排查中发现任何密码/口令时**立即调用**（不要犹豫，先存库再继续）。存库后：①在回复中复述密码时**必须打码**（如『ab***56』），绝不明文写出；②调用 remember_info 只记录密码**位置**（如『摄像头 .23 密码已存保险库』），不记值。",
            parameters = objSchema(
                "label" to ("string" to "凭据用途标签，如『摄像头密码』『路由器后台密码』『WiFi 密码』，必填"),
                "type" to ("string" to "凭据类型：camera / router / wifi / onvif / other，必填"),
                "device" to ("string" to "关联设备（IP 或名称），如『192.168.10.23』，可空"),
                "value" to ("string" to "密码/凭据值，必填"),
                required = listOf("label", "type", "value")
            )
        ),
        ToolDef(
            name = "get_credential",
            description = "从本机加密保险库读取本会话已存的凭据密码值（仅在需要执行操作时读取，如 camera_operate 改配置前取密码）。读取后**不得在回复中明文写出密码值**，只描述使用结果。参数留空=列出本会话凭据清单（含 id，不含密码值）。",
            parameters = objSchema(
                "id" to ("string" to "可选：凭据 id（由清单/注入文本提供）；留空=列出本会话凭据清单")
            )
        ),
        ToolDef(
            name = "mask_credential",
            description = "把当前会话**对话消息历史**（用户消息与 AI 回复都包括）中出现的密码明文替换为 ***，防止密码残留在本机对话记录文件里。使用时机：识别到用户消息里出现密码、或发现此前回复/消息中复述过密码明文时**立即调用**（可与 save_credential 连续调用：先存库、再打码）。",
            parameters = objSchema(
                "value" to ("string" to "可选：要打码的密码值（用户刚发出的那条密码明文）；留空=自动打码本会话保险库中已存的全部密码值")
            )
        ),
        ToolDef(
            name = "camera_operate",
            description = "**【安全】此工具会修改摄像头/录像机配置（改 IP/密码/重启/恢复出厂），必须先向用户确认已取得设备所有者授权！**对指定摄像头执行配置操作：change_ip（改 IP，需 newIp/mask/gateway，可 dhcp）、change_password（改密码，需 newPassword）、reboot（重启）、factory_reset（恢复出厂）、activate（海康未激活设备激活，需 password）、reset_password（海康免旧密码重置，需 securityCode + newPassword）、get_interfaces（查询网络接口，只读）。ONVIF 操作默认账密 admin/admin，可用 username/password 覆盖。",
            parameters = objSchema(
                "ip" to ("string" to "摄像头 IP，必填"),
                "operation" to ("string" to "操作类型，必填：change_ip / change_password / reboot / factory_reset / activate / reset_password / get_interfaces"),
                "username" to ("string" to "用户名，默认 admin"),
                "password" to ("string" to "密码，默认 admin；activate 时为新设备激活密码"),
                "newPassword" to ("string" to "新密码（change_password / reset_password 用）"),
                "newIp" to ("string" to "新 IP（change_ip 用）"),
                "mask" to ("string" to "子网掩码，如 255.255.255.0（change_ip 用）"),
                "gateway" to ("string" to "网关（change_ip 用）"),
                "dhcp" to ("boolean" to "是否启用 DHCP（change_ip 用，默认 false）"),
                "securityCode" to ("string" to "海康安全码/重置码（reset_password 用）"),
                required = listOf("ip", "operation")
            )
        ),
        ToolDef(
            name = "camera_password_probe",
            description = "摄像头弱口令探测（非爆破）：对常见默认口令（约 50 组：通用高频 + 海康/大华/雄迈/TP-LINK/宇视等品牌，逐个慢速尝试、非穷举）验证 ONVIF 认证，探测成功返回账密并提示改密；全部未中返回未发现。**支持自定义口令**：传 username + passwords（如用户/现场知道某品牌默认口令）则用自定义组合探测，不传用内置表。用于检查摄像头是否使用默认弱口令。",
            parameters = objSchema(
                "ip" to ("string" to "摄像头 IP，必填"),
                "port" to ("number" to "ONVIF 端口，默认 80"),
                "username" to ("string" to "自定义用户名（可选，配合 passwords 使用，如 root）"),
                "passwords" to ("string" to "自定义密码列表（可选，逗号分隔，如 12345,888888；与 username 组成探测组合，缺省用内置表）"),
                required = listOf("ip")
            )
        ),
        ToolDef(
            name = "ssh_exec",
            description = "远程执行命令（SSH 或 Telnet 统一，**无需 root**，任何手机都可用）：用账密登录目标设备（路由器/光猫/服务器等）执行一条命令并返回输出。用于查询设备状态/配置（如登录路由器查看端口转发、DHCP 列表、重启服务等）。protocol 可选 ssh/telnet（默认 ssh，Telnet 用于老设备）；需要用户提供设备账密，密码仅本次会话使用不保存；执行有风险命令前先确认。**注意：只适合单条命令；设备需要多轮交互（登录后二次确认/菜单选择/逐条问答）时改用 ssh_open + ssh_send 交互式会话。**",
            parameters = objSchema(
                "host" to ("string" to "目标主机 IP，必填"),
                "port" to ("number" to "端口（SSH 默认 22，Telnet 默认 23）"),
                "protocol" to ("string" to "协议：ssh / telnet，默认 ssh"),
                "username" to ("string" to "用户名，必填"),
                "password" to ("string" to "密码，必填（仅本次调用使用，不保存；Telnet 部分设备可空）"),
                "command" to ("string" to "要执行的命令，必填"),
                required = listOf("host", "username", "password", "command")
            )
        ),
        ToolDef(
            name = "ssh_open",
            description = "建立交互式远程会话（SSH/Telnet，保持连接，**无需 root**，任何手机都可用）：用于登录后需要**多轮交互**才能完成操作的设备——如光猫/老交换机登录后的二次确认、菜单选择、逐条命令问答。返回 sessionId 与登录提示；之后用 ssh_send 逐条交互，用完 ssh_close 关闭。账密仅会话内存使用不保存。",
            parameters = objSchema(
                "host" to ("string" to "目标主机 IP，必填"),
                "port" to ("number" to "端口（SSH 默认 22，Telnet 默认 23）"),
                "protocol" to ("string" to "协议：ssh / telnet，默认 ssh"),
                "username" to ("string" to "用户名（Telnet 部分设备可空）"),
                "password" to ("string" to "密码（Telnet 部分设备可空）"),
                required = listOf("host")
            )
        ),
        ToolDef(
            name = "ssh_send",
            description = "向已建立的交互式会话发送一行输入（命令/菜单选择/交互应答），等待约 1.2 秒后返回设备新输出。会话空闲超过 5 分钟自动过期，需重新 ssh_open。",
            parameters = objSchema(
                "sessionId" to ("string" to "ssh_open 返回的会话 id，必填"),
                "input" to ("string" to "要发送的输入（命令或交互应答），必填"),
                required = listOf("sessionId", "input")
            )
        ),
        ToolDef(
            name = "ssh_close",
            description = "关闭交互式远程会话（ssh_open 建立的），释放连接。交互完成后务必调用。",
            parameters = objSchema(
                "sessionId" to ("string" to "ssh_open 返回的会话 id，必填"),
                required = listOf("sessionId")
            )
        ),
        ToolDef(
            name = "barcode_generate",
            description = "校验文本/链接/数字能否生成二维码或条码（QR/Code128/EAN13/Code39），返回格式与编码校验结果（如 EAN-13 需 12-13 位数字、Code128 内容长度限制）。实际码图在 App 的『二维码/条码功能』页生成展示。",
            parameters = objSchema(
                "content" to ("string" to "要生成的内容（文本/链接/数字），必填"),
                "format" to ("string" to "格式：qr / code128 / ean13 / code39，默认 qr"),
                required = listOf("content")
            )
        ),
        ToolDef(
            name = "subnet_calc",
            description = "计算 IP 子网信息：网络地址/广播地址/可用主机范围/主机数/掩码/通配符/是否私有。参数 prefix_or_mask 为 CIDR 前缀（如 24）或掩码（如 255.255.255.0）。",
            parameters = objSchema(
                "ip" to ("string" to "IP 地址，必填"),
                "prefix_or_mask" to ("string" to "CIDR 前缀（如 24）或子网掩码（如 255.255.255.0），必填"),
                required = listOf("ip", "prefix_or_mask")
            )
        ),
        ToolDef(
            name = "silence_device",
            description = "让指定设备静默断网（Wi-Fi 杀手原理）：**双向 ARP+IPv6 欺骗**（同时骗目标与网关两侧）把该设备外网流量引到手机后丢弃，设备仍连着 Wi-Fi 但无法上网（有线/无线均有效；目标启用 ARP 防火墙也断——网关侧兜底）。需 root + 设备所有者授权！仅当路由器侧有静态绑定/ARP 防火墙或 AP 隔离时可能无效；停止后目标约 1 分钟内自动恢复。**先向用户确认已获授权再调用！** 参数 target：目标设备 IP（必填）；mode：静默强度 standard（标准 500ms 发包，多数设备够用）/ flood（洪泛 30ms 高频压制，标准档断不掉或断一下又恢复的设备用）/ jam（信道阻塞 0ms 忙循环占满空口，压制最狠，对付断一下又恢复的顽固设备，**可能让同 Wi-Fi 其他设备一起卡，仅限授权测试**），默认 standard。",
            parameters = objSchema(
                "target" to ("string" to "目标设备 IP，必填"),
                "mode" to ("string" to "静默强度：standard/flood/jam，默认 standard（jam 有副作用：可能让同 Wi-Fi 其他设备一起卡，仅限授权测试）"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "silence_network",
            description = "让全网（除本机与网关外）所有在线设备静默断网，用于测速时排除其他设备占用带宽（Wi-Fi 杀手原理：**双向 ARP+IPv6 欺骗**，目标启用 ARP 防火墙也断——网关侧兜底）。需 root + 授权；仅当路由器侧静态绑定/ARP 防火墙或 AP 隔离时可能无效；停止后目标约 1 分钟内自动恢复。**先向用户确认已获授权再调用！** 参数 mode：静默强度 standard/flood/jam（同 silence_device，默认 standard；jam 可能让同 Wi-Fi 其他设备一起卡，仅限授权测试）。",
            parameters = objSchema(
                "mode" to ("string" to "静默强度：standard/flood/jam，默认 standard（jam 有副作用：可能让同 Wi-Fi 其他设备一起卡，仅限授权测试）")
            )
        ),
        ToolDef(
            name = "kick_device",
            description = "强制踢下线：向指定设备发送 802.11 断开帧，让该设备认为已与 Wi-Fi 断开而自动断联（**仅对 Wi-Fi 无线设备有效**，有线设备无效）。需 root + 授权；部分芯片/驱动不支持注入管理帧可能无效。**先向用户确认已获授权再调用！** 参数 target：目标设备 IP（必填）。",
            parameters = objSchema(
                "target" to ("string" to "目标设备 IP，必填"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "dfs_check",
            description = "航空信号检测（DFS 信道风险）：检测当前连接及周边 5G AP 是否使用 DFS 信道（UNII-2 52-64 / UNII-2e 100-140，与航空雷达共用频谱，雷达信号会触发 AP 自动切换信道并静默约 60 秒，导致间歇性断网）。返回当前信道判定、DFS AP 列表、推荐的非 DFS 信道（36/40/44/48/149/153/157/161/165）。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "wifi_radar",
            description = "Wi-Fi 雷达（找 AP/漫游优化）：扫描周边 AP，按信号强度排序并给出每个 AP 的距离（支持 802.11mc FTM 的 AP 返回真实米数，否则按 RSSI 估算），含 SSID/BSSID/信道/加密。用于判断哪个 AP 信号最好或最近（如多 AP 环境下找主路由、评估漫游）。无参数。",
            parameters = emptyObjectSchema()
        ),
        ToolDef(
            name = "export_report",
            description = "把结果文本导出为报告文件保存到手机 Download/装维通 目录（txt，文件名自动带时间戳），返回保存路径。用于把诊断/检测结果落盘留档。参数 file_name：报告名（默认“装维通报告”）；content：报告正文（必填）。",
            parameters = objSchema(
                "file_name" to ("string" to "报告名，默认“装维通报告”"),
                "content" to ("string" to "报告正文，必填"),
                required = listOf("content")
            )
        ),
        ToolDef(
            name = "weak_network_detect",
            description = "持续探测目标网络稳定性（TCP 探测，无需 root）：统计丢包率、时延（最小/平均/最大）与抖动 jitter，判定结论（良好/一般/弱）。默认持续 10 秒（可传 duration_sec，3-60 秒）。可传 target 自定义目标（IP 或域名），或传 platform 测主流平台（如 抖音，按平台官方域名探测）。用于定位断流/卡顿/时延波动等网络不稳问题（如用户反馈「网卡」「总掉线」时先跑本工具）。",
            parameters = objSchema(
                "target" to ("string" to "目标 IP 或域名，与 platform 二选一"),
                "platform" to ("string" to "预设主流平台名（抖音/微信/B站/淘宝/京东/王者荣耀/腾讯视频/爱奇艺/快手/微博），与 target 二选一，platform 优先"),
                "duration_sec" to ("number" to "持续探测秒数，3-60，默认 10")
            )
        ),
        ToolDef(
            name = "snmp_query",
            description = "用 SNMP v2c 查询设备的系统信息（名称/描述/运行时间/位置/联系人），用于快速了解支持 SNMP 的路由器、交换机、打印机等设备（默认 community 为 public，无需 root）。参数 target：设备 IP（必填）；community：SNMP 团体名（默认 public）。",
            parameters = objSchema(
                "target" to ("string" to "设备 IP，必填"),
                "community" to ("string" to "SNMP community 团体名，默认 public"),
                required = listOf("target")
            )
        ),
        ToolDef(
            name = "wake_on_lan",
            description = "向局域网广播魔术包（magic packet）唤醒指定 MAC 的电脑（需目标设备 BIOS/网卡开启网络唤醒，且目标处于关机/休眠状态；无需 root）。参数 mac：目标网卡 MAC（必填，格式 XX:XX:XX:XX:XX:XX）。",
            parameters = objSchema(
                "mac" to ("string" to "目标网卡 MAC，格式 XX:XX:XX:XX:XX:XX，必填"),
                required = listOf("mac")
            )
        ),
        ToolDef(
            name = "route_control",
            description = "root 策略路由控制：把本机到局域网网段的流量强制绑定到指定接口（特殊网络场景用，如局域网无外网时确保诊断流量走局域网而非移动数据）。常规诊断工具已自动绑定局域网接口，一般无需手动改路由；Android 系统可能覆盖自定义规则。需 root。",
            parameters = objSchema(
                "action" to ("string" to "操作类型，必填：list（查看策略路由规则）/ add（添加规则）/ del（删除规则）"),
                "subnet" to ("string" to "目标局域网网段（如 192.168.1.0/24），默认局域网网段，add/del 用"),
                "iface" to ("string" to "绑定到的网络接口名（如 wlan0/br0/eth0），默认局域网接口名，add/del 用"),
                required = listOf("action")
            )
        )
    )

    /** 主线程 Handler：工具过程日志回调统一切主线程（execute 在 IO 线程跑，
     *  跨线程写 Compose state 会触发 Compose 1.7 "Unsupported concurrent change" 崩溃） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 工具日志安全转发：切主线程保证 UI state 写入合法 */
    private fun postLog(onLog: (String) -> Unit, msg: String) {
        mainHandler.post { runCatching { onLog(msg) } }
    }

    /**
     * 执行一次工具调用，返回结果字符串（JSON 或文本）。
     *
     * 所有工具实现内部均 try/catch，任何失败返回 "执行失败：{原因}"；
     * 整体切到 [Dispatchers.IO] 执行。
     */
    suspend fun execute(
        context: Context,
        call: ToolCall,
        onMaskRequest: (List<String>) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        // 工具过程可视化：生命周期 + root 命令执行日志（AI 页实时滚动显示，人用功能页同源）
        postLog(onLog, "▶ 开始工具 ${call.name}（参数 ${call.arguments.toString().take(200)}）")
        // 执行期间 root 命令日志挂到回调（完成后恢复 null，防影响其他场景）
        val prevListener = RootShell.logListener
        RootShell.logListener = onLog
        try {
            val result = try {
                when (call.name) {
                "network_context" -> execNetworkContext(context)
                "ping_device" -> execPingDevice(call.arguments)
                "trace_route" -> execTraceRoute(call.arguments)
                "dns_query" -> execDnsQuery(call.arguments)
                "scan_ports" -> execScanPorts(call.arguments)
                // 注意：全子网 254 主机并发探测，可能耗时 20-60 秒
                "scan_lan" -> execScanLan(context, call.arguments)
                "arp_scan" -> execArpScan(context)
                "discover_cameras" -> execDiscoverCameras(context)
                "wifi_info" -> execWifiInfo(context)
                "get_system_status" -> RootShell.systemStatusSummary()
                "set_wifi" -> execSetWifi(call.arguments)
                "set_bluetooth" -> execSetBluetooth(call.arguments)
                "set_mobile_data" -> execSetMobileData(call.arguments)
                "set_airplane_mode" -> execSetAirplane(call.arguments)
                "detect_arp_spoof" -> execDetectArp(context, call.arguments)
                "detect_gateway_conflict" -> execGatewayConflict(context)
                "detect_loop" -> execDetectLoop(call.arguments)
                "scan_ble" -> execScanBle(context)
                "nmap_scan" -> execNmapScan(call.arguments, context)
                "detect_pcdn" -> execDetectPcdn(context, call.arguments)
                "detect_pcdn_network" -> execDetectPcdnNetwork(context, call.arguments)
                "audit_traffic" -> execAuditTraffic(call.arguments, context)
                "passthrough_audit" -> execPassthroughAudit(call.arguments, context)
                "submit_feedback" -> execSubmitFeedback(call.arguments, context)
                "get_diagnostic_info" -> execGetDiagnosticInfo(call.arguments, context)
                "root_shell" -> execRootShell(call.arguments)
                "remember_info" -> execRememberInfo(call.arguments, context)
                "write_md" -> execWriteMd(call.arguments, context)
                "read_md" -> execReadMd(call.arguments, context)
                "save_credential" -> execSaveCredential(call.arguments, context)
                "get_credential" -> execGetCredential(call.arguments, context)
                "mask_credential" -> execMaskCredential(call.arguments, context, onMaskRequest)
                "capture_traffic" -> execCaptureTraffic(context, call.arguments)
                "camera_operate" -> execCameraOperate(context, call.arguments)
                "camera_password_probe" -> execCameraPasswordProbe(call.arguments)
                "ssh_exec" -> execSsh(call.arguments)
                "ssh_open" -> execSshOpen(call.arguments)
                "ssh_send" -> execSshSend(call.arguments)
                "ssh_close" -> execSshClose(call.arguments)
                "barcode_generate" -> execBarcodeGenerate(call.arguments)
                "subnet_calc" -> execSubnetCalc(call.arguments)
                "silence_device" -> execSilenceDevice(call.arguments, context)
                "silence_network" -> execSilenceNetwork(context, call.arguments)
                "kick_device" -> execKickDevice(call.arguments, context)
                "dfs_check" -> execDfsCheck(context)
                "wifi_radar" -> execWifiRadar(context)
                "export_report" -> execExportReport(call.arguments, context)
                "weak_network_detect" -> execWeakNetworkDetect(call.arguments)
                "snmp_query" -> execSnmpQuery(call.arguments)
                "wake_on_lan" -> execWakeOnLan(call.arguments)
                "route_control" -> execRouteControl(call.arguments)
                else -> "未知工具：${call.name}"
                }
            } catch (e: Exception) {
                "执行失败：${e.message ?: e.javaClass.simpleName}"
            }
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            val failed = result.startsWith("执行失败") || result.startsWith("错误") ||
                result.startsWith("参数错误") || result.startsWith("未检测到 root")
            postLog(onLog, if (failed) "✖ 工具 ${call.name} 异常（耗时 ${elapsed}s）：${result.take(200)}" else "✔ 工具 ${call.name} 完成（耗时 ${elapsed}s）")
            result
        } catch (e: Exception) {
            val msg = "执行失败：${e.message ?: e.javaClass.simpleName}"
            postLog(onLog, "✖ 工具 ${call.name} 异常：$msg")
            msg
        } finally {
            RootShell.logListener = prevListener
        }
    }

    // ---- 工具实现 ----

    /**
     * 弱信号警告（现场反馈：RSSI < -75dBm 时 arp_scan/网关冲突检测等全超时无提示，易误判设备下线）。
     * 扫描类工具输出 note 前置此警告，让 AI 与用户知晓结果可能失真。
     */
    private fun weakSignalWarn(context: Context): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val rssi = wifi?.connectionInfo?.rssi ?: return null
            if (rssi < -75) {
                "⚠ 当前 Wi-Fi 信号弱（RSSI ${rssi}dBm）：扫描/探测可能超时或漏设备，结果仅供初步参考。"
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** network_context：当前网络环境（子网 / 网关 / DNS / SSID / 信号；root 时附加局域网接口与默认路由信息） */
    private fun execNetworkContext(context: Context): String {
        val subnet = try {
            DeviceScanner.currentSubnet(context, RootShell::run)
        } catch (e: Exception) {
            "未知（${e.message}）"
        }
        val dhcp = try {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo
        } catch (e: Exception) {
            null
        }
        val wifi = WifiAnalyzer.currentConnection(context)
        return JSONObject().apply {
            put("subnet", subnet)
            put("gateway", dhcp?.let { intToIp(it.gateway) } ?: "未知")
            put("dns", dhcp?.let { intToIp(it.dns1) } ?: "未知")
            if (wifi != null) {
                put("ssid", wifi.ssid)
                put("bssid", wifi.bssid)
                put("rssiDbm", wifi.rssiDbm)
                put("band", wifi.band)
                put("channel", wifi.channel)
                put("linkSpeedMbps", wifi.linkSpeedMbps)
            } else {
                put("wifiConnected", false)
            }
            // root 增强：局域网接口信息 + 默认路由接口 + 局域网是否无外网判定
            if (RootShell.isAvailable()) {
                val lan = try {
                    LanInterfaceResolver.resolve(RootShell::run)
                } catch (e: Exception) {
                    null
                }
                val routeOut = try {
                    RootShell.run("ip route get 8.8.8.8")
                } catch (e: Exception) {
                    ""
                }
                val defaultRouteIface = Regex("dev (\\S+)").find(routeOut)?.groupValues?.get(1)
                if (lan != null) {
                    put("lanInterface", lan.name)
                    put("lanIp", lan.ip)
                    put("lanSubnet", lan.subnet)
                    put("lanGateway", lan.gateway ?: JSONObject.NULL)
                }
                if (defaultRouteIface != null) {
                    put("defaultRouteIface", defaultRouteIface)
                }
                // 默认路由接口存在且 ≠ 局域网接口名 → 默认流量走移动数据（局域网无外网）
                val lanNoInternet = lan != null && defaultRouteIface != null && defaultRouteIface != lan.name
                put("lanNoInternet", lanNoInternet)
                if (lanNoInternet) {
                    put("note", "局域网无外网，默认流量走移动数据")
                }
            }
        }.toString()
    }

    /** ping_device：TCP Ping 统计（root 时绑定局域网接口，确保局域网诊断不误走移动数据） */
    private suspend fun execPingDevice(args: JSONObject): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target"
        val count = args.optInt("count", 4).coerceIn(1, 10)
        val port = args.optInt("port", 443).coerceIn(1, 65535)
        val iface = lanIface()
        val r = PingTool.ping(target, count = count, port = port, timeoutMs = 2000, bindIp = iface?.ip)
        return JSONObject().apply {
            put("target", r.target)
            put("via", viaText(iface))
            put("sent", r.sent)
            put("received", r.received)
            put("lossPercent", r.lossPercent)
            put("avgMs", r.avgMs ?: JSONObject.NULL)
            put("minMs", r.minMs ?: JSONObject.NULL)
            put("maxMs", r.maxMs ?: JSONObject.NULL)
        }.toString()
    }

    /** trace_route：路由追踪（TCP 变通 traceroute，无需 root；root 时绑定局域网接口） */
    private suspend fun execTraceRoute(args: JSONObject): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target"
        val port = args.optInt("port", 80).coerceIn(1, 65535)
        val maxHops = args.optInt("maxHops", 15).coerceIn(1, 30)
        val iface = lanIface()
        val hops = TracerouteTool.trace(target, port = port, maxHops = maxHops, timeoutMs = 3000, bindIp = iface?.ip)
        val reached = hops.lastOrNull()?.reached == true
        return JSONObject().apply {
            put("target", target)
            put("via", viaText(iface))
            put("port", port)
            put("maxHops", maxHops)
            put("reached", reached)
            put("hops", hops.size)
            put(
                "hopsDetail",
                JSONArray().apply {
                    hops.forEach { h ->
                        put(
                            JSONObject().apply {
                                put("hop", h.hop)
                                put("elapsedMs", h.elapsedMs)
                                put("reached", h.reached)
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    /** dns_query：DNS 记录列表（root 时绑定局域网接口，确保局域网 DNS 诊断不误走移动数据） */
    private suspend fun execDnsQuery(args: JSONObject): String {
        val host = args.optString("host").trim()
        if (host.isEmpty()) return "参数错误：缺少 host"
        val iface = lanIface()
        val r = DnsTool.query(host, bindIp = iface?.ip)
        return JSONObject().apply {
            put("host", r.host)
            put("via", viaText(iface))
            put("status", r.status)
            put("elapsedMs", r.elapsedMs)
            put(
                "records",
                JSONArray().apply {
                    r.records.forEach { rec ->
                        put(
                            JSONObject().apply {
                                put("name", rec.name)
                                put("type", rec.type)
                                put("rdata", rec.rdata)
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    /** scan_ports：开放端口列表（范围限制 1-1024；root 时绑定局域网接口） */
    private suspend fun execScanPorts(args: JSONObject): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target"
        val start = args.optInt("start", 1).coerceIn(1, 1024)
        val end = args.optInt("end", 1024).coerceIn(1, 1024)
        val s = minOf(start, end)
        val e = maxOf(start, end)
        val iface = lanIface()
        val r = PortScanner.scan(target, startPort = s, endPort = e, bindIp = iface?.ip)
        return JSONObject().apply {
            put("target", r.target)
            put("via", viaText(iface))
            put("scannedCount", r.scannedCount)
            put("elapsedMs", r.elapsedMs)
            put(
                "openPorts",
                JSONArray().apply {
                    r.openPorts.forEach { p ->
                        put(
                            JSONObject().apply {
                                put("port", p.port)
                                put("service", p.service ?: JSONObject.NULL)
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    /** scan_lan：全子网设备列表（可能耗时 20-60 秒；超 20 台截断并注明；60 秒外层超时兜底） */
    private suspend fun execScanLan(context: Context, args: JSONObject): String {
        // 可选指定网段（多网段扫描）：如 192.168.2.0/24——手机需与该网段二层可达
        // （连上二级路由 Wi-Fi 后扫其网段，或主路由 VLAN 互通时直接扫）
        val subnetOverride = args.optString("subnet").trim().ifEmpty { null }
        val subnet = subnetOverride ?: DeviceScanner.currentSubnet(context, RootShell::run)
        val devices = withTimeoutOrNull(60_000L) {
            DeviceScanner(context).scan(subnet = subnet, onProgress = { _, _ -> })
        } ?: return "扫描超时（60 秒上限）：全子网探测耗时过长，请稍后重试或改用 arp_scan（需 root，更快）"
        val limited = devices.take(MAX_DEVICES)
        return JSONObject().apply {
            put("subnet", subnet)
            put("deviceCount", devices.size)
            put(
                "devices",
                JSONArray().apply {
                    limited.forEach { d ->
                        put(
                            JSONObject().apply {
                                put("ip", d.ip)
                                put("mac", d.mac ?: JSONObject.NULL)
                                put("vendor", d.vendor ?: JSONObject.NULL)
                                put("hostname", d.hostname ?: JSONObject.NULL)
                                put(
                                    "openPorts",
                                    JSONArray().apply { d.openPorts.forEach { put(it) } }
                                )
                                put("pcdn", d.pcdn)                            }
                        )
                    }
                }
            )
            val note = listOfNotNull(
                weakSignalWarn(context),
                if (devices.size > MAX_DEVICES) "设备数超过 $MAX_DEVICES 台，仅列出前 $MAX_DEVICES 台" else null
            ).joinToString("\n")
            if (note.isNotEmpty()) put("note", note)
        }.toString()
    }

    /** arp_scan：主动 ARP 全网扫描（需 root，链路层发现，端口全关设备也能扫到；30 秒外层超时兜底） */
    private suspend fun execArpScan(context: Context): String {
        if (!RootShell.isAvailable()) {
            return JSONObject().apply {
                put("ok", false)
                put("message", "未检测到 root 权限：主动 ARP 扫描不可用（需 root 读 ARP 表）。可改用 scan_lan 做 TCP 探测，但端口不开放的设备扫不到。")
            }.toString()
        }
        val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
        val devices = withTimeoutOrNull(30_000L) {
            DeviceScanner(context).scan(
                subnet = subnet,
                rootRun = RootShell::run,
                enableMdns = false,
                onProgress = { _, _ -> }
            )
        } ?: return "ARP 扫描超时（30 秒上限）：请稍后重试"
        val limited = devices.take(MAX_DEVICES)
        return JSONObject().apply {
            put("ok", true)
            put("subnet", subnet)
            put("deviceCount", devices.size)
            put(
                "note",
                listOfNotNull(
                    weakSignalWarn(context),
                    "ARP 链路层发现：包含端口全关/禁 ping 的在线设备"
                ).joinToString("\n")
            )
            put(
                "devices",
                JSONArray().apply {
                    limited.forEach { d ->
                        put(
                            JSONObject().apply {
                                put("ip", d.ip)
                                put("mac", d.mac ?: JSONObject.NULL)
                                put("vendor", d.vendor ?: JSONObject.NULL)
                                put("hostname", d.hostname ?: JSONObject.NULL)
                                put(
                                    "openPorts",
                                    JSONArray().apply { d.openPorts.forEach { put(it) } }
                                )
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    /** discover_cameras：ONVIF 发现 + 匿名读型号 */
    private suspend fun execDiscoverCameras(context: Context): String {
        val result = OnvifDiscovery.discover(context, timeoutMs = 6000)
        if (result.error != null) return "ONVIF 发现失败：${result.error}"
        val limited = result.devices.take(MAX_CAMERAS)
        return JSONObject().apply {
            put("deviceCount", result.devices.size)
            put(
                "devices",
                JSONArray().apply {
                    limited.forEach { d ->
                        val info = try {
                            OnvifClient.deviceInfo(d.ip)
                        } catch (e: Exception) {
                            null
                        }
                        put(
                            JSONObject().apply {
                                put("ip", d.ip)
                                put("model", info?.model?.ifBlank { null } ?: JSONObject.NULL)
                                put("manufacturer", info?.manufacturer ?: JSONObject.NULL)
                                put("types", d.types)
                            }
                        )
                    }
                }
            )
            if (result.devices.size > MAX_CAMERAS) {
                put("note", "发现设备超过 $MAX_CAMERAS 台，仅列出前 $MAX_CAMERAS 台")
            }
        }.toString()
    }

    /** wifi_info：当前连接 + 周边 AP 扫描（需定位权限，失败返回权限提示） */
    private suspend fun execWifiInfo(context: Context): String {
        val current = JSONObject().apply {
            val conn = WifiAnalyzer.currentConnection(context)
            if (conn != null) {
                put("ssid", conn.ssid)
                put("bssid", conn.bssid)
                put("rssiDbm", conn.rssiDbm)
                put("linkSpeedMbps", conn.linkSpeedMbps)
                put("frequency", conn.frequency)
                put("band", conn.band)
                put("channel", conn.channel)
            } else {
                put("connected", false)
            }
        }
        // scanAround 无定位权限时抛 IllegalStateException，返回提示而非中断
        val apScan = try {
            val aps = WifiAnalyzer.scanAround(context)
            JSONObject().apply {
                put("apCount", aps.size)
                put(
                    "aps",
                    JSONArray().apply {
                        aps.take(MAX_APS).forEach { ap ->
                            put(
                                JSONObject().apply {
                                    put("ssid", ap.ssid)
                                    put("bssid", ap.bssid)
                                    put("levelDbm", ap.levelDbm)
                                    put("frequency", ap.frequency)
                                    put("channel", ap.channel)
                                    put("security", ap.security)
                                }
                            )
                        }
                    }
                )
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("error", e.message ?: "扫描周边 Wi-Fi 失败")
            }
        }
        return JSONObject().apply {
            put("current", current)
            put("apScan", apScan)
        }.toString()
    }

    // ---- 工具辅助 ----

    /**
     * 解析当前局域网接口信息（需 root）。
     * 无 root 或解析失败返回 null，调用方据此回退到"默认路由"行为。
     */
    private fun lanIface(): LanInterfaceResolver.LanIface? =
        if (RootShell.isAvailable()) {
            try {
                LanInterfaceResolver.resolve(RootShell::run)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

    /** 诊断流量出口标注：绑定局域网接口时返回 "接口名(IP)"，否则 "默认路由" */
    private fun viaText(iface: LanInterfaceResolver.LanIface?): String =
        if (iface != null) "${iface.name}(${iface.ip})" else "默认路由"

    /** 无参数工具的 JSON Schema */
    private fun emptyObjectSchema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    /** 构造 object 类型 JSON Schema：properties 为 name → (type, description) 映射 */
    private fun objSchema(
        vararg props: Pair<String, Pair<String, String>>,
        required: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                props.forEach { (name, spec) ->
                    put(
                        name,
                        JSONObject().apply {
                            put("type", spec.first)
                            put("description", spec.second)
                        }
                    )
                }
            }
        )
        if (required.isNotEmpty()) {
            put(
                "required",
                JSONArray().apply { required.forEach { put(it) } }
            )
        }
    }

    /** DhcpInfo 的小端 int IP 转点分十进制；0 视为无值返回 null */
    private fun intToIp(v: Int): String? {
        if (v == 0) return null
        val n = Integer.reverseBytes(v)
        return "${(n ushr 24) and 0xFF}.${(n ushr 16) and 0xFF}.${(n ushr 8) and 0xFF}.${n and 0xFF}"
    }

    // ---- 极客工具：ARP 欺骗检测 / BLE 扫描 / nmap 深度扫描 ----

    /** detect_arp_spoof：ARP 欺骗 / IP-MAC 冲突检测（需 root，rootRun 注入 RootShell） */
    private suspend fun execDetectArp(context: Context, args: JSONObject): String {
        val durationMs = args.optInt("durationSec", 8).coerceIn(3, 30) * 1000L
        val r = ArpSpoofDetector.detect(context, durationMs = durationMs, rootRun = RootShell::run)
        return JSONObject().apply {
            put("gatewayMac", r.gatewayMac ?: JSONObject.NULL)
            put(
                "conflicts",
                JSONArray().apply {
                    r.conflicts.forEach { c ->
                        put(
                            JSONObject().apply {
                                put("ip", c.ip)
                                put("macs", JSONArray().apply { c.macs.forEach { put(it) } })
                            }
                        )
                    }
                }
            )
            put("note", r.note ?: JSONObject.NULL)
        }.toString()
    }

    /** scan_ble：周边 BLE 设备列表（需蓝牙 + 定位权限） */
    private suspend fun execScanBle(context: Context): String {
        val r = BleScanner.scan(context)
        if (r.error != null) return "蓝牙扫描失败：${r.error}"
        return JSONObject().apply {
            put("deviceCount", r.devices.size)
            put(
                "devices",
                JSONArray().apply {
                    r.devices.take(MAX_BLE).forEach { d ->
                        put(
                            JSONObject().apply {
                                put("name", d.name ?: JSONObject.NULL)
                                put("address", d.address)
                                put("rssi", d.rssi)
                                put("vendor", d.vendor ?: JSONObject.NULL)
                                put("manufacturerData", d.manufacturerData ?: JSONObject.NULL)
                            }
                        )
                    }
                }
            )
            if (r.devices.size > MAX_BLE) {
                put("note", "设备数超过 $MAX_BLE 台，仅列出前 $MAX_BLE 台")
            }
        }.toString()
    }

    /** nmap_scan：内置 nmap 深度扫描（需 root，无需 Termux；内置失败降级 Termux nmap） */
    private suspend fun execNmapScan(args: JSONObject, context: Context): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target（目标 IP 或域名）"
        // 无 root：内置 nmap 无法执行，明确提示并给出降级方案（不返回晦涩的 su 失败文本）
        if (!RootShell.isAvailable()) {
            return JSONObject().apply {
                put("ok", false)
                put("target", target)
                put("message", "未检测到 root 权限：nmap 深度扫描不可用（内置 nmap 需 root 执行）。可改用 scan_ports 做常用端口扫描（免 root）。")
            }.toString()
        }
        val options = args.optString("options").ifBlank { "-Pn -sV --top-ports 100 --open" }
        val r = NmapScanner.scan(context, target, options, rootRun = RootShell::run)
        return JSONObject().apply {
            put("target", target)
            put(
                "ports",
                JSONArray().apply {
                    r.ports.forEach { p ->
                        put(
                            JSONObject().apply {
                                put("port", p.port)
                                put("state", p.state)
                                put("service", p.service ?: JSONObject.NULL)
                                put("version", p.version ?: JSONObject.NULL)
                            }
                        )
                    }
                }
            )
            put("note", r.note ?: JSONObject.NULL)
            put("rawPreview", r.raw.take(600))
        }.toString()
    }

    /** detect_pcdn：指定/当前子网深度检测可疑 PCDN 设备（可能耗时 20-60 秒；60 秒外层超时兜底） */
    private suspend fun execDetectPcdn(context: Context, args: JSONObject): String {
        val subnet = args.optString("subnet").trim().ifEmpty { null }
        val r = withTimeoutOrNull(60_000L) {
            PcdnDetector.detect(
                context.applicationContext,
                // 与设备发现页一致：root 走 ARP 模式，端口全关设备也能扫到
                rootRun = if (RootShell.isAvailable()) RootShell::run else null,
                subnetOverride = subnet
            )
        } ?: return "PCDN 检测超时（60 秒上限）：请稍后重试"
        return JSONObject().apply {
            put("scanned", r.scanned)
            val note = listOfNotNull(weakSignalWarn(context), r.note).joinToString("\n")
            put("note", note.ifBlank { JSONObject.NULL })
            put(
                "devices",
                JSONArray().apply {
                    r.devices.forEach { d ->
                        put(
                            JSONObject().apply {
                                put("ip", d.ip)
                                put("mac", d.mac ?: JSONObject.NULL)
                                put("vendor", d.vendor ?: JSONObject.NULL)
                                put("hostname", d.hostname ?: JSONObject.NULL)
                                put(
                                    "openPorts",
                                    JSONArray().apply { d.openPorts.forEach { put(it) } }
                                )
                                put("confidence", d.confidence)
                                put(
                                    "reasons",
                                    JSONArray().apply { d.reasons.forEach { put(it) } }
                                )
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    /**
     * detect_pcdn_network：全网一键检测（两阶段，可能耗时 1-2 分钟；120 秒外层超时兜底）。
     * 先特征扫描再对可疑设备并发流量审计；无 root 时自动降级为仅特征扫描。
     */
    private suspend fun execDetectPcdnNetwork(context: Context, args: JSONObject): String {
        val perDeviceSec = args.optInt("perDeviceSec", 15).coerceIn(1, 120)
        val subnet = args.optString("subnet").trim().ifEmpty { null }
        val r = withTimeoutOrNull(120_000L) {
            PcdnNetworkScanner.scanNetwork(
                context = context.applicationContext,
                rootRun = RootShell::run,
                perDeviceSec = perDeviceSec,
                subnetOverride = subnet
            )
        } ?: return "PCDN 全网检测超时（120 秒上限）：设备较多或审计时长较长，请稍后重试或减少审计时长"
        return JSONObject().apply {
            put("scanned", r.scanned)
            put("audited", r.audited)
            put("likelyCount", r.likelyCount)
            val note = listOfNotNull(weakSignalWarn(context), r.note).joinToString("\n")
            put("note", note.ifBlank { JSONObject.NULL })
            put(
                "devices",
                JSONArray().apply {
                    r.devices.forEach { d ->
                        put(
                            JSONObject().apply {
                                put("ip", d.ip)
                                put("mac", d.mac ?: JSONObject.NULL)
                                put("vendor", d.vendor ?: JSONObject.NULL)
                                put("hostname", d.hostname ?: JSONObject.NULL)
                                put("confidence", d.confidence)
                                put("verdict", PcdnNetworkScanner.verdict(d))
                                put(
                                    "portReasons",
                                    JSONArray().apply { d.portReasons.forEach { put(it) } }
                                )
                                put(
                                    "audit",
                                    d.audit?.let { a ->
                                        JSONObject().apply {
                                            put("pcdnLikely", a.pcdnLikely)
                                            put("note", a.note ?: JSONObject.NULL)
                                        }
                                    } ?: JSONObject.NULL
                                )
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    /** audit_traffic：ARP 中间人流量审计（需 root + 授权；内置 arpspoof/tcpdump） */
    private suspend fun execAuditTraffic(args: JSONObject, context: Context): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target（目标设备 IP）"
        if (!IpUtil.isValidIp(target)) {
            return "参数错误：target 不是合法 IPv4（如 192.168.1.100）"
        }
        val durationSec = args.optInt("durationSec", 60).coerceIn(1, 120)
        val r = TrafficAudit.audit(
            context = context,
            targetIp = target,
            durationMs = durationSec * 1000L,
            rootRun = RootShell::run
        )
        return JSONObject().apply {
            put("target", r.targetIp)
            put("gateway", r.gatewayIp ?: JSONObject.NULL)
            put("pcdnLikely", r.pcdnLikely)
            put("note", r.note ?: JSONObject.NULL)
            put(
                "stats",
                r.stats?.let { s ->
                    JSONObject().apply {
                        put("sentPackets", s.sentPackets)
                        put("sentBytes", s.sentBytes)
                        put("recvPackets", s.recvPackets)
                        put("recvBytes", s.recvBytes)
                        put("upRatio", s.upRatio)
                    }
                } ?: JSONObject.NULL
            )
        }.toString()
    }

    /**
     * passthrough_audit：借道审计（PCDN 藏在二级路由 NAT 后面时）。
     * 底层复用 [TrafficAudit.audit]（双向 ARP+IPv6 欺骗 + tcpdump 抓目标 WAN 流量 +
     * NAT 源端口聚类还原下挂设备），语义上强调"借道"：目标填疑似二级路由 IP。
     */
    private suspend fun execPassthroughAudit(args: JSONObject, context: Context): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target（疑似二级路由/多 IP 设备 IP）"
        if (!IpUtil.isValidIp(target)) {
            return "参数错误：target 不是合法 IPv4（如 192.168.1.100）"
        }
        val durationSec = args.optInt("durationSec", 60).coerceIn(1, 120)
        val r = TrafficAudit.audit(
            context = context,
            targetIp = target,
            durationMs = durationSec * 1000L,
            rootRun = RootShell::run
        )
        return JSONObject().apply {
            put("mode", "借道审计（双向 ARP+IPv6 欺骗 + NAT 源端口聚类还原下挂设备）")
            put("target", r.targetIp)
            put("gateway", r.gatewayIp ?: JSONObject.NULL)
            put("pcdnLikely", r.pcdnLikely)
            put("note", r.note ?: JSONObject.NULL)
            put(
                "stats",
                r.stats?.let { s ->
                    JSONObject().apply {
                        put("sentPackets", s.sentPackets)
                        put("sentBytes", s.sentBytes)
                        put("recvPackets", s.recvPackets)
                        put("recvBytes", s.recvBytes)
                        put("upRatio", s.upRatio)
                    }
                } ?: JSONObject.NULL
            )
        }.toString()
    }

    // ---- 反馈与诊断工具 ----

    /** submit_feedback：把用户反馈提交到开发者服务器（复用 FeedbackApi，附带诊断信息） */
    private suspend fun execSubmitFeedback(args: JSONObject, context: Context): String {
        val type = args.optString("type", "其他").trim().ifEmpty { "其他" }
        val content = args.optString("content").trim()
        if (content.isEmpty()) return "参数错误：缺少 content（反馈内容）——请先向用户确认反馈内容"
        val includeNetwork = args.optBoolean("include_network", false)
        val r = FeedbackApi.submitFeedback(
            context = context,
            type = type,
            content = content,
            includeDiagnostic = true,
            includeNetwork = includeNetwork
        )
        return r.fold(
            onSuccess = { "✅ 反馈已提交成功（类型：$type）。感谢反馈，开发者会在后续版本持续优化。" },
            onFailure = { "反馈提交失败：${it.message ?: it.javaClass.simpleName}——请检查网络后重试；用户也可在设置页『反馈与建议』里再次提交或复制留档。" }
        )
    }

    /** get_diagnostic_info：本机诊断信息（版本/设备/统计/崩溃），排查工具问题用 */
    private fun execGetDiagnosticInfo(args: JSONObject, context: Context): String {
        val includeNetwork = args.optBoolean("include_network", false)
        return FeedbackHelper.buildDiagnosticText(context, includeNetwork = includeNetwork, includeUsage = true)
    }

    /**
     * root_shell：本机 root 权限执行 shell 命令（极度危险）。
     * 审批由调用方（App 层 toolGate）负责——本函数只做执行与结果封装；
     * 未检测到 root 返回明确不可用说明。输出截断防超长撑爆上下文。
     */
    private fun execRootShell(args: JSONObject): String {
        if (!RootShell.isAvailable()) {
            return "未检测到 root 权限：root_shell 不可用（需 Magisk/KernelSU 授权本应用）"
        }
        val command = args.optString("command").trim()
        if (command.isEmpty()) return "参数错误：缺少 command（要执行的 shell 命令）"
        if (command.length > 2000) return "参数错误：命令过长（超过 2000 字符），请精简后重试"
        val out = try {
            RootShell.run(command)
        } catch (e: Exception) {
            return "root 命令执行异常：${e.message ?: e.javaClass.simpleName}"
        }
        val truncated = if (out.length > 8000) out.take(8000) + "\n……（输出过长已截断）" else out
        return JSONObject().apply {
            put("command", command.take(500))
            put("output", truncated)
            put("exit", "见输出（RootShell 失败前缀『命令退出码 N』）")
        }.toString()
    }

    /**
     * remember_info：AI 主动记忆重要信息（跨会话保留，新会话自动注入 system 提示词）。
     * 关联当前活跃会话 id（AiViewModel 维护在 prefs "agent" 的 active_session_id），
     * 删除该会话时相应记忆一并删除。
     */
    private fun execRememberInfo(args: JSONObject, context: Context): String {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return "参数错误：缺少 text（记忆内容）"
        if (text.length > 200) return "记忆内容过长（超过 200 字），请精简为关键事实"
        val activeSession = activeSessionId(context)
        AiMemory.add(context, text, activeSession)
        return JSONObject().apply {
            put("ok", true)
            put("message", "已记忆：$text（删除本会话时该记忆将一并清除）")
        }.toString()
    }

    /**
     * write_md：AI 自动把完整资料/报告写成 .md 落盘（按会话隔离目录，
     * 清空会话保留、删除会话连带清理；禁止敏感凭据——提示词层约束）。
     */
    private fun execWriteMd(args: JSONObject, context: Context): String {
        val fileName = args.optString("filename").trim()
        val content = args.optString("content")
        val sessionId = activeSessionId(context)
        if (sessionId == null) return "错误：未定位到当前会话（请先打开 AI 对话）"
        if (fileName.isEmpty()) return "参数错误：缺少 filename"
        if (content.isBlank()) return "参数错误：缺少 content（md 内容）"
        val f = try {
            AiNotes.write(context, sessionId, fileName, content)
        } catch (e: Exception) {
            return "写入失败：${e.message ?: e.javaClass.simpleName}"
        }
        return JSONObject().apply {
            put("ok", true)
            put("file", f.name)
            put("size", f.length())
            put("message", "已写入资料档案：${f.name}（${f.length()} 字节，清空会话仍保留，可用 read_md 读回）")
        }.toString()
    }

    /** read_md：列出本会话资料清单（无 filename）或读取指定 md（截断 8000 字符） */
    private fun execReadMd(args: JSONObject, context: Context): String {
        val sessionId = activeSessionId(context)
        if (sessionId == null) return "错误：未定位到当前会话（请先打开 AI 对话）"
        val fileName = args.optString("filename").trim()
        return if (fileName.isEmpty()) {
            val files = AiNotes.list(context, sessionId)
            if (files.isEmpty()) {
                "本会话暂无资料档案（AI 排查告一段落时会自动用 write_md 沉淀资料）。"
            } else {
                buildString {
                    append("本会话资料档案共 ${files.size} 份：")
                    files.forEachIndexed { i, f ->
                        val head = runCatching { f.readText().take(200) }.getOrDefault("")
                        val firstLine = head.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                        append("\n").append(i + 1).append(". ").append(f.name)
                            .append("（").append(f.length()).append(" 字节")
                        if (firstLine.isNotBlank()) append("，摘要：").append(firstLine.take(60))
                        append("）")
                    }
                }
            }
        } else {
            val text = AiNotes.read(context, sessionId, fileName)
                ?: return "未找到文件：$fileName（可用 read_md 不带参数列出清单）"
            text.take(8000) + if (text.length > 8000) "\n……（内容过长已截断）" else ""
        }
    }

    /** 当前活跃会话 id（AiViewModel 维护在 prefs "agent"） */
    private fun activeSessionId(context: Context): String? = try {
        context.getSharedPreferences("agent", Context.MODE_PRIVATE)
            .getString("active_session_id", null)
    } catch (e: Exception) {
        null
    }

    /**
     * mask_credential：AI 识别到密码后调用——把当前会话消息历史（用户消息 + AI 回复）
     * 中的密码明文打码。打码动作由 App 层（AiViewModel）执行并落盘（本类通过回调请求）。
     */
    private fun execMaskCredential(
        args: JSONObject,
        context: Context,
        onMaskRequest: (List<String>) -> Unit
    ): String {
        val value = args.optString("value").trim()
        val values = if (value.isEmpty()) {
            val sid = activeSessionId(context) ?: return "错误：未定位到当前会话（请先打开 AI 对话）"
            CredentialVault.allValues(context, sid)
        } else {
            listOf(value)
        }
        if (values.isEmpty()) return "本会话保险库暂无密码值可打码（可先调用 save_credential 存库，或传 value 参数指定密码值）"
        onMaskRequest(values)
        return JSONObject().apply {
            put("ok", true)
            put("count", values.size)
            put("message", "已请求把本会话消息历史中的 ${values.size} 个密码明文替换为 ***（App 已同步修改对话记录，翻文件也看不到密码）。")
        }.toString()
    }

    // ---- 凭据保险库（加密存储，会话隔离；与明文记忆刻意分开） ----

    /**
     * save_credential：把现场密码/账号存入加密保险库（AES 落盘、按会话隔离、
     * 清空会话保留、删除会话连带删）。存库成功后提示模型：回复打码 + 记忆只记位置。
     */
    private fun execSaveCredential(args: JSONObject, context: Context): String {
        val sessionId = activeSessionId(context)
        if (sessionId == null) return "错误：未定位到当前会话（请先打开 AI 对话）"
        val label = args.optString("label").trim()
        val value = args.optString("value").trim()
        if (label.isEmpty() || value.isEmpty()) return "参数错误：缺少 label（用途标签）或 value（凭据值）"
        val type = args.optString("type").trim().lowercase().let {
            if (it in CredentialVault.TYPES) it else "other"
        }
        val device = args.optString("device").trim()
        return try {
            val meta = CredentialVault.add(context, sessionId, label, type, device, value)
            JSONObject().apply {
                put("ok", true)
                put("id", meta.id)
                put("message", "已加密存入本会话凭据保险库：${meta.label}${if (device.isNotBlank()) "（$device）" else ""}。" +
                    "注意：①回复中复述密码必须打码（如 ab***56），绝不明文写出；" +
                    "②调用 remember_info 只记录密码位置（如『${meta.label}已存保险库』），不记值。")
            }.toString()
        } catch (e: Exception) {
            "存入保险库失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** get_credential：列出本会话凭据清单（无 id）或按 id 读密码值（仅操作时用，回复中不得明文写出） */
    private fun execGetCredential(args: JSONObject, context: Context): String {
        val sessionId = activeSessionId(context)
        if (sessionId == null) return "错误：未定位到当前会话（请先打开 AI 对话）"
        val id = args.optString("id").trim()
        return if (id.isEmpty()) {
            val items = CredentialVault.list(context, sessionId)
            if (items.isEmpty()) {
                "本会话保险库暂无凭据（AI 发现密码会主动存入）。"
            } else {
                buildString {
                    append("本会话凭据保险库共 ${items.size} 条：")
                    items.forEachIndexed { i, it ->
                        append("\n").append(i + 1).append(". ").append(it.label)
                        if (it.device.isNotBlank()) append("（").append(it.device).append("）")
                        append(" 类型：").append(it.type)
                        append(" 凭据id：").append(it.id)
                    }
                }
            }
        } else {
            val value = CredentialVault.getValue(context, id)
                ?: return "未找到凭据 id：$id（可用 get_credential 不带参数列出清单）"
            "已读取凭据值（**禁止在回复中明文写出该值**，仅供后续工具调用使用，如 camera_operate 的 password 参数）：" + value
        }
    }

    // ---- 系统控制工具（需 root） ----

    /** set_wifi：开关 Wi-Fi */
    private fun execSetWifi(args: JSONObject): String {
        if (!RootShell.isAvailable()) return "未检测到 root 权限，无法开关 Wi-Fi"
        val on = args.optBoolean("on")
        val r = RootShell.setWifi(on)
        return if (r.startsWith("root 执行失败") || r.startsWith("命令退出码")) {
            "开关 Wi-Fi 失败：$r"
        } else {
            "已${if (on) "开启" else "关闭"} Wi-Fi（root 命令执行成功）"
        }
    }

    /** set_bluetooth：开关蓝牙 */
    private fun execSetBluetooth(args: JSONObject): String {
        if (!RootShell.isAvailable()) return "未检测到 root 权限，无法开关蓝牙"
        val on = args.optBoolean("on")
        val r = RootShell.setBluetooth(on)
        return if (r.startsWith("root 执行失败") || r.startsWith("命令退出码")) {
            "开关蓝牙失败：$r"
        } else {
            "已${if (on) "开启" else "关闭"} 蓝牙（root 命令执行成功）"
        }
    }

    /** set_mobile_data：开关移动数据 */
    private fun execSetMobileData(args: JSONObject): String {
        if (!RootShell.isAvailable()) return "未检测到 root 权限，无法开关移动数据"
        val on = args.optBoolean("on")
        val r = RootShell.setMobileData(on)
        return if (r.startsWith("root 执行失败") || r.startsWith("命令退出码")) {
            "开关移动数据失败：$r"
        } else {
            "已${if (on) "开启" else "关闭"} 移动数据（root 命令执行成功）"
        }
    }

    /** set_airplane_mode：开关飞行模式 */
    private fun execSetAirplane(args: JSONObject): String {
        if (!RootShell.isAvailable()) return "未检测到 root 权限，无法开关飞行模式"
        val on = args.optBoolean("on")
        val r = RootShell.setAirplaneMode(on)
        return if (r.startsWith("root 执行失败") || r.startsWith("命令退出码")) {
            "开关飞行模式失败：$r"
        } else {
            "已${if (on) "开启" else "关闭"} 飞行模式（root 命令执行成功）"
        }
    }

    // ---- 抓包 / 摄像头操作 / IP 计算器工具 ----

    /**
     * capture_traffic：在本机 VpnService 抓包统计 N 秒。
     *
     * 采集方式：服务运行期间持续消费 [CaptureBus] 的周期快照并聚合各周期计数
     * （包数/字节/协议分布/广播组播），超时后取最后快照的速率与风暴状态。
     * - 服务已在运行时不重启，直接采集；
     * - 否则先检查 VPN 授权（[VpnService.prepare] 返回非 null 表示未授权），
     *   启动后等服务真正运行（VPN 建立成功）再采集；
     * - 本工具启动的服务必须在 finally 中 [PacketCaptureService.stop] 配对停止。
     */
    private suspend fun execCaptureTraffic(context: Context, args: JSONObject): String {
        val durationSec = args.optInt("durationSec", 10).coerceIn(3, 30)
        val startedHere = !PacketCaptureService.isRunning()
        if (startedHere) {
            // 返回非 null 表示用户尚未授权 VPN 连接（系统弹窗只能由界面发起）
            if (VpnService.prepare(context) != null) {
                return errJson("需先在界面授权 VPN 连接")
            }
            PacketCaptureService.start(context)
            // 等待服务真正运行（VPN 建立成功）；3 秒内未就绪视为启动失败
            var ready = false
            repeat(10) {
                if (PacketCaptureService.isRunning()) {
                    ready = true
                    return@repeat
                }
                delay(300)
            }
            if (!ready) {
                PacketCaptureService.stop(context)
                return errJson("抓包服务启动失败（可能未授予通知权限或 VPN 建立被系统拒绝）")
            }
        }
        return try {
            // 采集 durationSec 秒：消费快照并聚合，超时后 last 为最后一个周期快照
            var totalPackets = 0L
            var totalBytes = 0L
            var tcpPackets = 0L
            var udpPackets = 0L
            var icmpPackets = 0L
            var otherPackets = 0L
            var broadcastPackets = 0L
            var multicastPackets = 0L
            var last: TrafficSnapshot? = null
            withTimeoutOrNull(durationSec * 1000L) {
                while (true) {
                    val s = CaptureBus.flow.first()
                    last = s
                    totalPackets += s.totalPackets
                    totalBytes += s.totalBytes
                    tcpPackets += s.tcpPackets
                    udpPackets += s.udpPackets
                    icmpPackets += s.icmpPackets
                    otherPackets += s.otherPackets
                    broadcastPackets += s.broadcastPackets
                    multicastPackets += s.multicastPackets
                }
            }
            val snap = last
            if (snap == null) {
                // 无快照：服务未产出数据（如授权被撤销）
                JSONObject().apply {
                    put("ok", false)
                    put("durationSec", durationSec)
                    put("error", "抓包未产生流量快照")
                    put("note", "仅含本机收发的包；可确认 VPN 授权后重试")
                }.toString()
            } else {
                JSONObject().apply {
                    put("ok", true)
                    put("durationSec", durationSec)
                    put("totalPackets", totalPackets)
                    put("totalBytes", totalBytes)
                    // 速率取最后一个周期（当前速率）
                    put("packetsPerSec", snap.packetsPerSec)
                    put("bytesPerSec", snap.bytesPerSec)
                    put("tcpPackets", tcpPackets)
                    put("udpPackets", udpPackets)
                    put("icmpPackets", icmpPackets)
                    put("otherPackets", otherPackets)
                    put("broadcastPackets", broadcastPackets)
                    put("multicastPackets", multicastPackets)
                    put(
                        "broadcastRatio",
                        if (totalPackets > 0) (broadcastPackets + multicastPackets).toDouble() / totalPackets else 0.0
                    )
                    put("stormState", snap.stormState.name)
                    put("note", "本机流量统计；广播风暴状态 NORMAL/WARNING/STORM；仅含本机收发的包")
                }.toString()
            }
        } finally {
            // start 与 stop 必须配对：本工具启动的服务一定要停掉
            if (startedHere) {
                PacketCaptureService.stop(context)
            }
        }
    }

    /**
     * camera_operate：对摄像头执行配置操作（改 IP / 改密码 / 重启 / 恢复出厂 /
     * 海康激活 / 海康免旧密码重置 / 查询接口），全部包 try/catch 返回 JSON。
     * 注意：必须先向用户确认已取得设备所有者授权！
     */
    private suspend fun execCameraOperate(context: Context, args: JSONObject): String {
        val ip = args.optString("ip").trim()
        if (ip.isEmpty()) return errJson("参数错误：缺少 ip")
        val operation = args.optString("operation").trim()
        if (operation.isEmpty()) return errJson("参数错误：缺少 operation")
        // ONVIF 默认账密 admin/admin（与 OnvifClient 注释一致），可用参数覆盖
        val username = args.optString("username").ifBlank { "admin" }
        val password = args.optString("password").ifBlank { "admin" }
        return try {
            when (operation) {
                // 改 IP：先 getInterfaces 拿接口 token，再 setNetworkInterfaces
                "change_ip" -> {
                    val dhcp = args.optBoolean("dhcp")
                    val newIp = args.optString("newIp").trim()
                    val mask = args.optString("mask").trim()
                    val gateway = args.optString("gateway").trim()
                    if (!dhcp && (newIp.isEmpty() || mask.isEmpty() || gateway.isEmpty())) {
                        errJson("参数错误：change_ip 需要 newIp/mask/gateway（或 dhcp=true）")
                    } else {
                        val ifaces = OnvifClient.getInterfaces(ip, user = username, password = password)
                        val token = ifaces.firstOrNull()?.token
                        if (token == null) {
                            errJson("无法获取设备网络接口（认证失败或设备不支持 ONVIF）")
                        } else {
                            val r = OnvifClient.setNetworkInterfaces(
                                ip, user = username, password = password,
                                token = token, ip = newIp, mask = mask, gateway = gateway, dhcp = dhcp
                            )
                            onvifResultJson(r, operation)
                        }
                    }
                }
                // 改密码：ONVIF SetUser
                "change_password" -> {
                    val newPassword = args.optString("newPassword").trim()
                    if (newPassword.isEmpty()) {
                        errJson("参数错误：change_password 需要 newPassword")
                    } else {
                        onvifResultJson(
                            OnvifClient.setUserPassword(ip, user = username, password = password, newPassword = newPassword),
                            operation
                        )
                    }
                }
                // 重启：ONVIF SystemReboot
                "reboot" -> onvifResultJson(OnvifClient.reboot(ip, user = username, password = password), operation)
                // 恢复出厂：ONVIF SetSystemFactoryDefault（Hard 全量默认）
                "factory_reset" -> onvifResultJson(
                    OnvifClient.factoryReset(ip, user = username, password = password),
                    operation
                )
                // 海康未激活设备激活：SADP activate 命令
                "activate" -> {
                    val activatePassword = args.optString("password").trim()
                    if (activatePassword.isEmpty()) {
                        errJson("参数错误：activate 需要 password（激活密码）")
                    } else {
                        val r = HikvisionApi.command(
                            "activate",
                            JSONObject().apply {
                                put("targetIp", ip)
                                put("password", activatePassword)
                                put("timeoutSeconds", 6L)
                            }
                        )
                        hikResultJson(r, operation)
                    }
                }
                // 海康免旧密码重置：SADP resetpassword（需要安全码 + 新密码 + 设备 MAC）
                "reset_password" -> {
                    val code = args.optString("securityCode").trim()
                    val newPassword = args.optString("newPassword").trim()
                    if (code.isEmpty() || newPassword.isEmpty()) {
                        errJson("参数错误：reset_password 需要 securityCode 和 newPassword")
                    } else {
                        // 优先用调用方传入的 targetMac；未提供则扫子网找该 IP 的 MAC
                        val mac = args.optString("targetMac").trim().ifBlank { scanMacForIp(context, ip) }
                        if (mac.isEmpty()) {
                            errJson("无法获取目标设备 MAC（扫描未发现该 IP），请确认设备在线或直接传入 targetMac")
                        } else {
                            val r = HikvisionApi.command(
                                "resetpassword",
                                JSONObject().apply {
                                    put("targetIp", ip)
                                    put("targetMac", mac)
                                    put("code", code)
                                    put("password", newPassword)
                                    put("timeoutSeconds", 6L)
                                }
                            )
                            hikResultJson(r, operation)
                        }
                    }
                }
                // 查询网络接口（只读）：ONVIF GetNetworkInterfaces
                "get_interfaces" -> {
                    val ifaces = OnvifClient.getInterfaces(ip, user = username, password = password)
                    if (ifaces.isEmpty()) {
                        errJson("获取接口失败（认证失败或设备不支持 ONVIF）")
                    } else {
                        JSONObject().apply {
                            put("ok", true)
                            put("operation", operation)
                            put(
                                "interfaces",
                                JSONArray().apply {
                                    ifaces.forEach { iface ->
                                        put(
                                            JSONObject().apply {
                                                put("token", iface.token)
                                                put("ip", iface.ip)
                                                put("prefix", iface.prefix)
                                                put("gateway", iface.gateway)
                                                put("dhcp", iface.dhcp)
                                            }
                                        )
                                    }
                                }
                            )
                            put("note", "需设备所有者授权")
                        }.toString()
                    }
                }
                else -> errJson("未知操作：$operation（支持 change_ip / change_password / reboot / factory_reset / activate / reset_password / get_interfaces）")
            }
        } catch (e: Exception) {
            errJson("操作失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * camera_password_probe：摄像头弱口令探测（非爆破）。
     * 对常见默认口令逐个慢速验证 ONVIF 认证，成功即返回账密；全部未中如实告知。
     */
    private suspend fun execCameraPasswordProbe(args: JSONObject): String {
        val ip = args.optString("ip").trim()
        if (ip.isEmpty()) return errJson("参数错误：缺少 ip")
        if (!IpUtil.isValidIp(ip)) return errJson("参数错误：ip 不是合法 IPv4（如 192.168.1.100）")
        val port = args.optInt("port", 80)
        // 自定义口令：username + passwords（逗号分隔）→ 组合探测；缺省用内置表
        val username = args.optString("username").trim().ifEmpty { null }
        val passwords = args.optString("passwords").split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val custom: List<Pair<String, String>>? = if (username != null && passwords.isNotEmpty()) {
            passwords.map { username to it }
        } else {
            null
        }
        val total = custom?.size ?: PasswordProbe.credentialCount
        val result = PasswordProbe.probeOnvif(ip, port, credentials = custom)
        return JSONObject().apply {
            put("ok", true)
            put("ip", ip)
            put("probeCount", total)
            put("usingCustom", custom != null)
            put("weakPassword", result != null)
            if (result != null) {
                put("username", result.username)
                put("password", result.password)
                put(
                    "warning",
                    if (result.password.isEmpty()) {
                        "⚠ 该摄像头密码为空（未设密码），建议立即设置密码！"
                    } else {
                        "⚠ 该摄像头使用弱口令，建议立即修改！"
                    }
                )
            } else {
                put("message", if (custom != null) "自定义口令（${username} × ${passwords.size} 组）均未匹配（非爆破，共试 $total 组）" else "常见弱口令均未匹配（非爆破，共试 $total 组）")
            }
        }.toString()
    }

    /** ssh_exec：SSH/Telnet 登录远程设备执行命令（账密仅本次使用，不保存） */
    private suspend fun execSsh(args: JSONObject): String {
        val host = args.optString("host").trim()
        val username = args.optString("username").trim()
        val password = args.optString("password")
        val command = args.optString("command").trim()
        if (host.isEmpty() || username.isEmpty() || password.isEmpty() || command.isEmpty()) {
            return errJson("参数错误：需要 host/username/password/command")
        }
        // 协议：默认 ssh；port 按协议给默认值
        val protocol = RemoteExecutor.protocolOf(args.optString("protocol", "ssh"))
            ?: return errJson("参数错误：protocol 仅支持 ssh / telnet")
        val port = args.optInt("port", if (protocol == RemoteExecutor.Protocol.SSH) 22 else 23)
        val r = withTimeoutOrNull(20_000L) {
            RemoteExecutor.execute(
                protocol, host, port, username, password, command, timeoutMs = 15_000L
            )
        } ?: return errJson("远程执行超时（20 秒）")
        return JSONObject().apply {
            put("ok", r.ok)
            put("host", host)
            put("protocol", protocol.name.lowercase())
            put("exitCode", r.exitCode)
            if (r.output.isNotBlank()) {
                // 结果防撑爆上下文：截断 2000 字符
                put("output", r.output.take(2000))
            }
            r.error?.let { put("error", it) }
        }.toString()
    }

    /** ssh_open：建立交互式远程会话（登录后多轮交互场景，账密仅内存使用不保存） */
    private suspend fun execSshOpen(args: JSONObject): String {
        val host = args.optString("host").trim()
        if (host.isEmpty()) return errJson("参数错误：缺少 host")
        val protocol = RemoteExecutor.protocolOf(args.optString("protocol", "ssh"))
            ?: return errJson("参数错误：protocol 仅支持 ssh / telnet")
        val port = args.optInt("port", if (protocol == RemoteExecutor.Protocol.SSH) 22 else 23)
        val username = args.optString("username")
        val password = args.optString("password")
        return try {
            val (sessionId, banner) = withTimeoutOrNull(15_000L) {
                SshSessionPool.open(
                    if (protocol == RemoteExecutor.Protocol.SSH) InteractiveTerminal.Protocol.SSH
                    else InteractiveTerminal.Protocol.TELNET,
                    host, port, username, password
                )
            } ?: return errJson("建立会话超时（15 秒）")
            JSONObject()
                .put("ok", true)
                .put("sessionId", sessionId)
                .put("host", host)
                .put("protocol", protocol.name.lowercase())
                .put("output", banner.take(2000))
                .toString()
        } catch (e: Exception) {
            errJson("会话建立失败：${e.message ?: "连接失败"}")
        }
    }

    /** ssh_send：向交互式会话发送一行输入并返回新输出 */
    private suspend fun execSshSend(args: JSONObject): String {
        val sessionId = args.optString("sessionId").trim()
        val input = args.optString("input")
        if (sessionId.isEmpty() || input.isEmpty()) return errJson("参数错误：需要 sessionId/input")
        val output = withTimeoutOrNull(8_000L) {
            SshSessionPool.send(sessionId, input)
        } ?: return errJson("交互超时（8 秒）")
        return JSONObject()
            .put("ok", true)
            .put("output", output.take(2000))
            .toString()
    }

    /** ssh_close：关闭交互式会话释放连接 */
    private fun execSshClose(args: JSONObject): String {
        val sessionId = args.optString("sessionId").trim()
        if (sessionId.isEmpty()) return errJson("参数错误：缺少 sessionId")
        SshSessionPool.close(sessionId)
        return JSONObject().put("ok", true).put("closed", sessionId).toString()
    }

    /**
     * barcode_generate：校验内容能否生成二维码/条码（ZXing 实际编码验证）。
     * 返回格式与校验结果；实际码图在 App『二维码/条码功能』页生成展示。
     */
    private suspend fun execBarcodeGenerate(args: JSONObject): String {
        val content = args.optString("content").trim()
        if (content.isEmpty()) return errJson("参数错误：缺少 content")
        val format = when (args.optString("format", "qr").lowercase()) {
            "qr" -> com.google.zxing.BarcodeFormat.QR_CODE
            "code128" -> com.google.zxing.BarcodeFormat.CODE_128
            "ean13" -> com.google.zxing.BarcodeFormat.EAN_13
            "code39" -> com.google.zxing.BarcodeFormat.CODE_39
            else -> return errJson("参数错误：format 仅支持 qr / code128 / ean13 / code39")
        }
        // 用实际编码验证可生成性（EAN-13 位数不足 / Code128 超长会编码失败）
        val ok = BarcodeUtils.encode(content, format) != null
        return JSONObject().apply {
            put("ok", ok)
            put("format", format.name)
            put("contentLength", content.length)
            put(
                "message",
                if (ok) {
                    "内容可生成 ${format.name} 码（可在 App『二维码/条码功能』页生成展示）"
                } else {
                    "内容不符合 ${format.name} 格式要求（EAN-13 需 12-13 位数字；Code128 内容不宜过长）"
                }
            )
        }.toString()
    }

    /** detect_gateway_conflict：网关/IP 冲突检测（多网关 + 一 MAC 多 IP，独立于 ARP 欺骗检测；60 秒外层超时兜底） */
    private suspend fun execGatewayConflict(context: Context): String {
        val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
        val devices = withTimeoutOrNull(60_000L) {
            DeviceScanner(context).scan(
                subnet = subnet,
                rootRun = if (RootShell.isAvailable()) RootShell::run else null,
                enableMdns = false
            )
        } ?: return "网关/IP 冲突检测超时（60 秒上限）：请稍后重试"
        // 当前网关 + 本机 IP（WifiManager）
        var gateway: String? = null
        var selfIp: String? = null
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            fun intToIp(v: Int): String? =
                if (v == 0) null else "${v and 0xFF}.${(v ushr 8) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 24) and 0xFF}"
            gateway = wifi.dhcpInfo?.gateway?.let { intToIp(it) }
            selfIp = wifi.connectionInfo?.ipAddress?.let { intToIp(it) }
        } catch (e: Exception) {
        }
        val r = GatewayConflictDetector.detect(devices, gateway, selfIp)
        return JSONObject().apply {
            put("ok", true)
            put("subnet", subnet)
            put("knownGateway", r.knownGateway ?: JSONObject.NULL)
            weakSignalWarn(context)?.let { put("note", it) }
            r.conflictNote?.let { put("conflictNote", it) }
            put(
                "gateways",
                JSONArray().apply {
                    r.gateways.forEach { gw ->
                        put(
                            JSONObject().apply {
                                put("ip", gw.ip)
                                put("mac", gw.mac ?: JSONObject.NULL)
                                put("vendor", gw.vendor ?: JSONObject.NULL)
                                put("gatewayPorts", JSONArray().apply { gw.gatewayPorts.forEach { put(it) } })
                                put("isCurrentGateway", gw.ip == r.knownGateway)
                            }
                        )
                    }
                }
            )
            put(
                "macMultiIps",
                JSONArray().apply {
                    r.macMultiIps.forEach { m ->
                        put(
                            JSONObject().apply {
                                put("mac", m.mac)
                                put("vendor", m.vendor ?: JSONObject.NULL)
                                put("ips", JSONArray().apply { m.ips.forEach { put(it) } })
                            }
                        )
                    }
                }
            )
            r.note?.let { put("note", it) }
        }.toString()
    }

    /** detect_loop：环路/广播风暴检测（root tcpdump 抓包统计广播/组播占比） */
    private suspend fun execDetectLoop(args: JSONObject): String {
        val sec = args.optInt("durationSec", 8).coerceIn(3, 30)
        val r = LoopDetector.detect(
            rootRun = if (RootShell.isAvailable()) RootShell::run else null,
            durationSec = sec
        )
        return JSONObject().apply {
            put("ok", true)
            put("totalPackets", r.totalPackets)
            put("broadcastCount", r.broadcastCount)
            put("multicastCount", r.multicastCount)
            put("packetsPerSec", "%.1f".format(r.packetsPerSec))
            put("broadcastRatioPercent", "%.0f".format(r.broadcastRatio * 100))
            put("loopLikely", r.loopLikely)
            r.note?.let { put("note", it) }
        }.toString()
    }

    /** 扫当前子网找指定 IP 的 MAC（reset_password 用）；找不到返回空串 */
    private suspend fun scanMacForIp(context: Context, ip: String): String {
        return try {
            val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
            val devices = withTimeoutOrNull(30_000L) { DeviceScanner(context).scan(subnet) }
            devices?.firstOrNull { it.ip == ip }?.mac.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    /** subnet_calc：IP 子网计算（纯本地计算，不触网） */
    private fun execSubnetCalc(args: JSONObject): String {
        val ip = args.optString("ip").trim()
        val prefixOrMask = args.optString("prefix_or_mask").trim()
        if (ip.isEmpty() || prefixOrMask.isEmpty()) {
            return errJson("参数错误：缺少 ip 或 prefix_or_mask")
        }
        val r = SubnetCalculator.calculate(ip, prefixOrMask)
            ?: return errJson("输入格式有误")
        return JSONObject().apply {
            put("ok", true)
            put("ip", r.ip)
            put("prefix", r.prefix)
            put("mask", r.mask)
            put("network", r.network)
            put("broadcast", r.broadcast)
            put("firstHost", r.firstHost)
            put("lastHost", r.lastHost)
            put("totalHosts", r.totalHosts)
            put("usableHosts", r.usableHosts)
            put("wildcard", r.wildcard)
            put("isPrivate", r.isPrivate)
            put("isReserved", r.isReserved)
            put("ipClass", r.ipClass)
        }.toString()
    }

    // ---- 静默模式 / 踢下线工具（Wi-Fi 杀手原理，需 root + 授权） ----

    /** 本机 IP + 网关（WifiManager DHCP，小端 int 反转） */
    private fun dhcpIp(context: Context): Pair<String?, String?> {
        return try {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            fun toIp(v: Int): String? =
                if (v == 0) null else {
                    val n = Integer.reverseBytes(v)
                    "${(n ushr 24) and 0xFF}.${(n ushr 16) and 0xFF}.${(n ushr 8) and 0xFF}.${n and 0xFF}"
                }
            toIp(wifi?.dhcpInfo?.ipAddress ?: 0) to toIp(wifi?.dhcpInfo?.gateway ?: 0)
        } catch (e: Exception) {
            null to null
        }
    }

    /** silence_device：单台静默（ARP 欺骗断网，有线/无线均有效；IPv6 网络自动叠加 NDP 欺骗） */
    private suspend fun execSilenceDevice(args: JSONObject, context: Context): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target（目标设备 IP）"
        val mode = args.optString("mode", "standard").trim().ifEmpty { "standard" }
        if (mode !in listOf("standard", "flood", "jam")) return "参数错误：mode 只能是 standard/flood/jam"
        if (!RootShell.isAvailable()) {
            return "未检测到 root 权限：静默功能不可用。可先确认网络授权后再尝试"
        }
        val r = LanSilencer.start(context, RootShell::run, listOf(target), mode = mode)
        return if (r.ok) r.message else "静默失败：${r.message}"
    }

    /** silence_network：全网静默（排除本机与网关，用于测速时排除其他设备占用带宽） */
    private suspend fun execSilenceNetwork(context: Context, args: JSONObject): String {
        val mode = args.optString("mode", "standard").trim().ifEmpty { "standard" }
        if (mode !in listOf("standard", "flood", "jam")) return "参数错误：mode 只能是 standard/flood/jam"
        if (!RootShell.isAvailable()) {
            return "未检测到 root 权限：全网静默不可用"
        }
        val (selfIp, gateway) = dhcpIp(context)
        val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
        // 扫描在线设备（最多 60 秒），排除本机与网关
        val devices = withTimeoutOrNull(60_000L) {
            DeviceScanner(context).scan(
                subnet = subnet,
                rootRun = RootShell::run,
                enableMdns = false,
                onProgress = { _, _ -> }
            )
        } ?: return "全网扫描超时（60 秒上限），未完成静默"
        val targets = devices.map { it.ip }
            .filter { it != selfIp && it != gateway }
        if (targets.isEmpty()) return "未扫描到可静默的设备（排除本机与网关后为空）"
        val r = LanSilencer.start(context, RootShell::run, targets, gateway, mode = mode)
        return if (r.ok) r.message else "全网静默失败：${r.message}"
    }

    /** kick_device：强制踢下线（deauth，仅 Wi-Fi 无线设备有效） */
    private suspend fun execKickDevice(args: JSONObject, context: Context): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target（目标设备 IP）"
        if (!RootShell.isAvailable()) {
            return "未检测到 root 权限：踢下线功能不可用"
        }
        // 查目标 MAC（扫描在线设备，最多 60 秒）
        val subnet = DeviceScanner.currentSubnet(context, RootShell::run)
        val devices = withTimeoutOrNull(60_000L) {
            DeviceScanner(context).scan(
                subnet = subnet,
                rootRun = RootShell::run,
                enableMdns = false,
                onProgress = { _, _ -> }
            )
        } ?: emptyList()
        val host = devices.firstOrNull { it.ip == target }
        val mac = host?.mac
        if (mac == null || mac.isBlank()) {
            return "未获取到设备 $target 的 MAC（设备可能不在线或未解析到），无法踢下线"
        }
        val bssid = WifiAnalyzer.currentConnection(context)?.bssid
        if (bssid == null || bssid == "未知") {
            return "未获取到当前 AP 的 BSSID：请确认已连接 Wi-Fi 后重试"
        }
        return LanSilencer.kick(context, RootShell::run, mac, bssid)
    }

    /** dfs_check：航空信号检测（DFS 信道风险判定） */
    private suspend fun execDfsCheck(context: Context): String {
        return try {
            val r = DfsChecker.check(context)
            JSONObject().apply {
                put("ok", true)
                put("connectedChannel", r.connectedChannel ?: JSONObject.NULL)
                put("connectedIsDfs", r.connectedIsDfs)
                put("dfsApCount", r.dfsAps.size)
                put("nonDfsApCount", r.nonDfsAps.size)
                put(
                    "dfsAps",
                    JSONArray().apply {
                        r.dfsAps.take(10).forEach { ap ->
                            put(
                                JSONObject().apply {
                                    put("ssid", ap.ssid)
                                    put("channel", ap.channel)
                                    put("rssi", ap.levelDbm)
                                }
                            )
                        }
                    }
                )
                put(
                    "recommendedChannels",
                    JSONArray().apply { DfsChecker.NON_DFS_CHANNELS.forEach { put(it) } }
                )
                put("summary", r.summary)
            }.toString()
        } catch (e: Exception) {
            "航空信号检测失败：${e.message ?: e.javaClass.simpleName}（请确认已连接 Wi-Fi 且已授予定位权限）"
        }
    }

    /** wifi_radar：周边 AP 雷达数据（含 RTT 真实距离，供 AI 判断 AP 位置/信号） */
    private suspend fun execWifiRadar(context: Context): String {
        return try {
            val points = WifiRadar.scan(context)
            JSONObject().apply {
                put("ok", true)
                put("apCount", points.size)
                put(
                    "aps",
                    JSONArray().apply {
                        points.forEach { p ->
                            put(
                                JSONObject().apply {
                                    put("ssid", p.ap.ssid)
                                    put("bssid", p.ap.bssid)
                                    put("rssi", p.ap.levelDbm)
                                    put("channel", p.ap.channel)
                                    put("frequency", p.ap.frequency)
                                    put("security", p.ap.security)
                                    p.distanceMeters?.let { put("distanceMeters", "%.1f".format(it)) }
                                    put("distanceHint", WifiRadar.distanceText(p))
                                }
                            )
                        }
                    }
                )
            }.toString()
        } catch (e: Exception) {
            "Wi-Fi 雷达扫描失败：${e.message ?: e.javaClass.simpleName}（请确认已授予定位权限）"
        }
    }

    /** weak_network_detect：持续探测目标网络稳定性（丢包率/时延/抖动 jitter）；支持 platform 预设平台或 target 自定义目标 */
    private suspend fun execWeakNetworkDetect(args: JSONObject): String {
        // platform 优先：传入平台名 → 解析为平台域名；未知平台名直接报错
        val platformName = args.optString("platform").trim()
        var platform: PlatformTarget? = null
        if (platformName.isNotEmpty()) {
            platform = resolvePlatform(platformName)
            if (platform == null) {
                return "参数错误：未知平台名（可选：抖音/微信/B站/淘宝/京东/王者荣耀/腾讯视频/爱奇艺/快手/微博）"
            }
        }
        val target = platform?.host ?: args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target（目标 IP 或域名）或 platform（预设平台名）"
        val durationSec = args.optInt("duration_sec", 10).coerceIn(3, 60)
        val r = WeakNetworkDetector.detect(
            target = target,
            durationSec = durationSec,
            rootRun = if (RootShell.isAvailable()) RootShell::run else null
        )
        return JSONObject().apply {
            put("platform", platform?.name ?: JSONObject.NULL)
            put("target", r.target)
            put("sent", r.sent)
            put("received", r.received)
            put("loss_percent", r.lossPercent)
            put("avg_ms", r.avgMs ?: JSONObject.NULL)
            put("min_ms", r.minMs ?: JSONObject.NULL)
            put("max_ms", r.maxMs ?: JSONObject.NULL)
            put("jitter_ms", r.jitterMs ?: JSONObject.NULL)
            put("probe_mode", r.probeMode)
            put("verdict", r.verdict)
            put("summary", r.summary)
        }.toString()
    }

    /** snmp_query：SNMP v2c 查询设备系统信息（名称/描述/运行时间/位置/联系人） */
    private suspend fun execSnmpQuery(args: JSONObject): String {
        val target = args.optString("target").trim()
        if (target.isEmpty()) return "参数错误：缺少 target（设备 IP）"
        val community = args.optString("community", "public").trim().ifEmpty { "public" }
        // 绑定局域网接口：多网络时查询不走移动数据默认路由（否则局域网设备必超时）
        val r = SnmpProbe.query(ip = target, community = community, bindIp = lanIface()?.ip)
        return JSONObject().apply {
            put("ip", r.ip)
            put("community", r.community)
            put("sys_name", r.sysName ?: JSONObject.NULL)
            put("sys_descr", r.sysDescr ?: JSONObject.NULL)
            put("sys_uptime", r.sysUpTime ?: JSONObject.NULL)
            put("sys_location", r.sysLocation ?: JSONObject.NULL)
            put("sys_contact", r.sysContact ?: JSONObject.NULL)
            put("error", r.error ?: JSONObject.NULL)
        }.toString()
    }

    /** wake_on_lan：向局域网广播 magic packet 唤醒指定 MAC 的电脑（需目标开启网络唤醒） */
    private suspend fun execWakeOnLan(args: JSONObject): String {
        val mac = args.optString("mac").trim()
        if (mac.isEmpty()) return "参数错误：缺少 mac（目标网卡 MAC，格式 XX:XX:XX:XX:XX:XX）"
        val ok = WakeOnLan.wake(mac, bindIp = lanIface()?.ip)
        return JSONObject().apply {
            put("ok", ok)
            put(
                "message",
                if (ok) "唤醒包已发送到局域网（目标需开启网络唤醒）" else "MAC 格式错误或发送异常"
            )
        }.toString()
    }

    /**
     * route_control：root 策略路由控制（list 查看规则 / add 绑定局域网接口 / del 清理）。
     * 特殊网络场景（如局域网无外网）下把本机到局域网网段的流量强制绑定到局域网接口，
     * 避免诊断流量误走移动数据。需 root；Android 系统可能覆盖自定义规则。
     */
    private fun execRouteControl(args: JSONObject): String {
        if (!RootShell.isAvailable()) {
            return JSONObject().apply {
                put("ok", false)
                put("message", "未检测到 root 权限：策略路由控制不可用（需 root 执行 ip rule / ip route）")
            }.toString()
        }
        val action = args.optString("action").trim().lowercase()
        return when (action) {
            "list" -> {
                val rules = RootShell.run("ip rule show")
                val routes = RootShell.run("ip route show table all")
                // RootShell.run 失败以特定前缀开头，识别后返回中文提示
                val failed = rules.startsWith("root 执行失败") || rules.startsWith("命令退出码") ||
                    routes.startsWith("root 执行失败") || routes.startsWith("命令退出码")
                if (failed) {
                    JSONObject().apply {
                        put("ok", false)
                        put("action", "list")
                        put("message", "查看策略路由规则失败：$rules")
                    }.toString()
                } else {
                    JSONObject().apply {
                        put("ok", true)
                        put("action", "list")
                        put("output", ("ip rule show：\n$rules\n\nip route show table all：\n$routes").take(2000))
                    }.toString()
                }
            }
            "add" -> {
                val lan = lanIface()
                if (lan == null) {
                    JSONObject().apply {
                        put("ok", false)
                        put("message", "未解析到局域网接口：无法添加策略路由（请确认已连接 Wi-Fi/有线且 root 可用）")
                    }.toString()
                } else {
                    val subnet = args.optString("subnet").ifBlank { lan.subnet }
                    val iface = args.optString("iface").ifBlank { lan.name }
                    val ruleOut = RootShell.run("ip rule add from ${lan.ip} lookup 100")
                    val routeCmd = if (lan.gateway != null) {
                        "ip route add default via ${lan.gateway} dev $iface table 100"
                    } else {
                        "ip route add $subnet dev $iface table 100"
                    }
                    val routeOut = RootShell.run(routeCmd)
                    JSONObject().apply {
                        put("ok", true)
                        put("action", "add")
                        put("lanInterface", lan.name)
                        put("lanIp", lan.ip)
                        put("subnet", subnet)
                        put("iface", iface)
                        put("output", "ip rule add from ${lan.ip} lookup 100 → $ruleOut\n$routeCmd → $routeOut")
                        put("note", "已尝试添加策略路由（本机流量强制绑定局域网接口）。Android 系统可能覆盖自定义规则；常规诊断已自动绑定接口，无需手动改路由；删除用 action=del。")
                    }.toString()
                }
            }
            "del" -> {
                val lan = lanIface()
                if (lan == null) {
                    JSONObject().apply {
                        put("ok", false)
                        put("message", "未解析到局域网接口：无法删除策略路由")
                    }.toString()
                } else {
                    val ruleOut = RootShell.run("ip rule del from ${lan.ip} lookup 100")
                    val flushOut = RootShell.run("ip route flush table 100")
                    JSONObject().apply {
                        put("ok", true)
                        put("action", "del")
                        put("lanIp", lan.ip)
                        put("output", "ip rule del from ${lan.ip} lookup 100 → $ruleOut\nip route flush table 100 → $flushOut")
                        put("note", "已尝试删除策略路由规则并清空 table 100。")
                    }.toString()
                }
            }
            else -> JSONObject().apply {
                put("ok", false)
                put("message", "参数错误：action 只能是 list / add / del")
            }.toString()
        }
    }

    /** export_report：把结果文本导出为 txt 报告到 Download/装维通（ReportWriter 统一实现） */
    private suspend fun execExportReport(args: JSONObject, context: Context): String {
        val fileName = args.optString("file_name").trim().ifEmpty { "装维通报告" }
        val content = args.optString("content")
        if (content.isBlank()) return errJson("参数错误：缺少 content（报告正文）")
        val path = ReportWriter.writeReport(context, fileName, content)
        return if (path.startsWith(ReportWriter.PATH_PREFIX)) {
            JSONObject().apply {
                put("ok", true)
                put("path", path)
            }.toString()
        } else {
            errJson(path)
        }
    }

    // ---- 工具辅助 ----

    /** 统一错误 JSON：{ok:false, error:"..."} */
    private fun errJson(error: String): String = JSONObject().apply {
        put("ok", false)
        put("error", error)
    }.toString()

    /** ONVIF 调用结果转 JSON（统一带 operation 与授权提示） */
    private fun onvifResultJson(r: OnvifClient.OnvifResult, operation: String): String = JSONObject().apply {
        put("ok", r.ok)
        put("operation", operation)
        if (r.ok) {
            put("result", r.raw ?: "操作成功")
        } else {
            put("error", r.error ?: "操作失败")
        }
        put("note", "需设备所有者授权")
    }.toString()

    /** 海康 SADP/ISAPI 调用结果转 JSON（统一带 operation 与授权提示） */
    private fun hikResultJson(r: HikvisionApi.HikResult, operation: String): String = JSONObject().apply {
        put("ok", r.ok)
        put("operation", operation)
        if (r.ok) {
            put("result", r.result ?: "操作成功")
        } else {
            put("error", r.error ?: "操作失败")
        }
        put("note", "需设备所有者授权")
    }.toString()
}

/**
 * 报告导出写入（唯一实现）：把结果文本写成 txt 存到 Download/装维通 目录。
 *
 * App 各结果页的『导出报告』按钮与 AI 工具 export_report 共用本对象，逻辑只此一份：
 * - Android 10+（SDK >= 29）：MediaStore.Downloads 集合，无需存储权限；
 * - Android 9-（SDK < 29）：公共下载目录 + WRITE_EXTERNAL_STORAGE 运行时权限
 *   （Manifest 已声明 maxSdkVersion=28，由调用方先申请权限再写入）。
 *
 * 任何失败都不抛异常，返回可读错误信息。
 */
object ReportWriter {

    /** 导出子目录名（Download/装维通/） */
    const val SUB_DIR = "装维通"

    /** 成功路径前缀（返回给 UI / AI 的显示路径） */
    const val PATH_PREFIX = "下载/$SUB_DIR/"

    /**
     * 把 [text] 写入 Download/装维通/{fileName}_yyyyMMdd_HHmm.txt。
     * @return 成功：显示路径（下载/装维通/xxx.txt）；失败：可读错误信息。
     */
    suspend fun writeReport(context: Context, fileName: String, text: String): String =
        withContext(Dispatchers.IO) {
            val base = fileName.trim().ifEmpty { "装维通报告" }
            val time = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val displayName = "${base}_$time.txt"
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    writeViaMediaStore(context, displayName, text)
                } else {
                    writeToPublicDir(context, displayName, text)
                }
                PATH_PREFIX + displayName
            } catch (e: Exception) {
                "导出失败：${e.message ?: e.javaClass.simpleName}"
            }
        }

    /** Android 10+：MediaStore.Downloads 集合（免权限）；调用处已检查 SDK_INT>=29 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(context: Context, displayName: String, text: String) {
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$SUB_DIR/")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在下载目录创建文件")
        resolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw IllegalStateException("无法打开文件输出流")
    }

    /** Android 9-：公共下载目录 + WRITE_EXTERNAL_STORAGE 权限（调用方先申请） */
    private fun writeToPublicDir(context: Context, displayName: String, text: String) {
        val app = context.applicationContext
        if (app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("需要存储权限")
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录")
        }
        File(dir, displayName).writeText(text, Charsets.UTF_8)
    }
}
