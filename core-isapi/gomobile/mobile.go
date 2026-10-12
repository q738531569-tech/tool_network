// Package mobile 导出海康 SADP/ISAPI 能力，供 Android 端（gomobile bind）调用。
// 所有函数返回 JSON：{"ok":true,"result":"..."} 或 {"ok":false,"error":"..."}。
package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/cameronnewman/hikvision-tooling/internal/crypto"
	"github.com/cameronnewman/hikvision-tooling/internal/isapi"
	"github.com/cameronnewman/hikvision-tooling/internal/logger"
	"github.com/cameronnewman/hikvision-tooling/internal/sadp"
)

type reply struct {
	OK     bool   `json:"ok"`
	Error  string `json:"error,omitempty"`
	Result string `json:"result,omitempty"`
}

func fail(err error) string {
	b, _ := json.Marshal(reply{OK: false, Error: err.Error()})
	return string(b)
}

func ok(result string) string {
	b, _ := json.Marshal(reply{OK: true, Result: result})
	return string(b)
}

// SADPDiscover 通过 SADP 组播发现局域网内的海康设备，result 为设备 JSON 数组。
func SADPDiscover(timeoutSeconds int) string {
	scanner := sadp.NewScanner(time.Duration(timeoutSeconds)*time.Second, logger.NewNop())
	devices, err := scanner.Discover()
	if err != nil {
		return fail(err)
	}
	if devices == nil {
		devices = []*sadp.Device{}
	}
	b, err := json.Marshal(devices)
	if err != nil {
		return fail(err)
	}
	return ok(string(b))
}

func isapiClient(host string, port int, username, password string, insecure bool, timeoutSeconds int) *isapi.Client {
	base := fmt.Sprintf("http://%s:%d", host, port)
	return isapi.New(base, isapi.Options{
		Username:           username,
		Password:           password,
		Timeout:            time.Duration(timeoutSeconds) * time.Second,
		InsecureSkipVerify: insecure,
	})
}

func isapiRequest(method, host string, port int, path, body, username, password string, insecure bool, timeoutSeconds int) string {
	client := isapiClient(host, port, username, password, insecure, timeoutSeconds)
	req, err := http.NewRequestWithContext(context.Background(), method, client.BaseURL+path, strings.NewReader(body))
	if err != nil {
		return fail(err)
	}
	req.Header.Set("Content-Type", "application/xml")
	resp, err := client.HTTPClient.Do(req)
	if err != nil {
		return fail(err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024))
	if err != nil {
		return fail(err)
	}
	out, _ := json.Marshal(map[string]any{"status": resp.StatusCode, "body": string(data)})
	return ok(string(out))
}

// ISAPIGet 通用 GET。
func ISAPIGet(host string, port int, path, username, password string, insecure bool, timeoutSeconds int) string {
	return isapiRequest(http.MethodGet, host, port, path, "", username, password, insecure, timeoutSeconds)
}

// ISAPIPut 通用 PUT（改 IP、改密码、恢复出厂等配置操作）。
func ISAPIPut(host string, port int, path, body, username, password string, insecure bool, timeoutSeconds int) string {
	return isapiRequest(http.MethodPut, host, port, path, body, username, password, insecure, timeoutSeconds)
}

// ISAPIPost 通用 POST（激活设备等）。
func ISAPIPost(host string, port int, path, body, username, password string, insecure bool, timeoutSeconds int) string {
	return isapiRequest(http.MethodPost, host, port, path, body, username, password, insecure, timeoutSeconds)
}

// ISAPIDeviceInfo 获取设备信息（/ISAPI/System/deviceInfo），result 为解析后的 JSON。
func ISAPIDeviceInfo(host string, port int, username, password string, insecure bool, timeoutSeconds int) string {
	client := isapiClient(host, port, username, password, insecure, timeoutSeconds)
	info, err := client.DeviceInfo(context.Background())
	if err != nil {
		return fail(err)
	}
	b, _ := json.Marshal(info)
	return ok(string(b))
}

// sadpOpts SADP 命令参数（JSON）。
type sadpOpts struct {
	TargetIP   string `json:"targetIp"`
	TargetMAC  string `json:"targetMac"`
	Password   string `json:"password"`
	Code       string `json:"code"`
	NewIP      string `json:"newIp"`
	NewMask    string `json:"newMask"`
	NewGateway string `json:"newGateway"`
	NewPort    int    `json:"newPort"`
	DHCP       bool   `json:"dhcp"`
	TimeoutSec int    `json:"timeoutSeconds"`
	Unicast    bool   `json:"unicast"`
}

// SADPCommand 发送 SADP 命令：activate / update / reboot / restore /
// resetpassword / securitycode / exchangecode / inquiry 等。
// optsJSON 为 sadpOpts 的 JSON。
func SADPCommand(cmdName, optsJSON string) string {
	var o sadpOpts
	if err := json.Unmarshal([]byte(optsJSON), &o); err != nil {
		return fail(err)
	}
	timeout := time.Duration(o.TimeoutSec) * time.Second
	if o.TimeoutSec <= 0 {
		timeout = 5 * time.Second
	}
	scanner := sadp.NewScanner(timeout, logger.NewNop())
	resp, err := scanner.SendCommand(cmdName, sadp.SendOptions{
		TargetIP:   o.TargetIP,
		TargetMAC:  o.TargetMAC,
		Password:   o.Password,
		Code:       o.Code,
		NewIP:      o.NewIP,
		NewMask:    o.NewMask,
		NewGateway: o.NewGateway,
		NewPort:    o.NewPort,
		DHCP:       o.DHCP,
		Timeout:    timeout,
		Unicast:    o.Unicast,
	})
	if err != nil {
		return fail(err)
	}
	return ok(resp)
}

// GenerateResetCode 生成旧固件（<5.3.0）密码重置码。
// serial 需去掉型号前缀，date 格式 YYYYMMDD（设备内部时钟）。
func GenerateResetCode(serial, date string) string {
	return crypto.GenerateResetCode(serial, date)
}

// ParseInt 字符串转 int（供 Java 层简化调用）。
func ParseInt(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}
