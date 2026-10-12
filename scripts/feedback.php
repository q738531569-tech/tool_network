<?php
/**
 * 装维工具箱 反馈/自动收集接口（服务器 192.168.10.88 iot 站 8090，POST JSON）
 * 数据存 MySQL（tb_feedback，库 toolbox）；数据库不可用时降级写本地文件兜底。
 * 请求：{"key":"toolbox-fb-2026-v1","type":"feedback|auto","fbType":"问题反馈","text":"...",
 *        "diagnostic":"...","app":"1.5.1","device":"...","android":"...","root":true,
 *        "appStarts":N,"usage":{...},"crash":["..."],"ts":123}
 * 返回：{"ok":true,"id":自增ID}
 */
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') { exit; }

$body = file_get_contents('php://input');
$json = json_decode($body, true);
if (!is_array($json)) {
    echo json_encode(array('ok' => false, 'msg' => 'bad json'), JSON_UNESCAPED_UNICODE);
    exit;
}
// 简单密钥防垃圾提交（App 内置同一 key）
if (!isset($json['key']) || $json['key'] !== 'toolbox-fb-2026-v1') {
    echo json_encode(array('ok' => false, 'msg' => 'bad key'), JSON_UNESCAPED_UNICODE);
    exit;
}
if (strlen($body) > 256 * 1024) {
    echo json_encode(array('ok' => false, 'msg' => 'too large'), JSON_UNESCAPED_UNICODE);
    exit;
}

$type = preg_replace('/[^a-zA-Z0-9_\-]/', '', isset($json['type']) ? $json['type'] : 'unknown');
$fbType = isset($json['fbType']) ? mb_substr($json['fbType'], 0, 32) : '';
$content = isset($json['text']) ? mb_substr($json['text'], 0, 20000) : '';
$diagnostic = isset($json['diagnostic']) ? mb_substr($json['diagnostic'], 0, 20000) : '';
$app = isset($json['app']) ? mb_substr($json['app'], 0, 32) : '';
$device = isset($json['device']) ? mb_substr($json['device'], 0, 64) : '';
$android = isset($json['android']) ? mb_substr($json['android'], 0, 32) : '';
$root = isset($json['root']) ? ($json['root'] ? 1 : 0) : 0;
$appStarts = isset($json['appStarts']) ? intval($json['appStarts']) : 0;
$usage = isset($json['usage']) ? json_encode($json['usage'], JSON_UNESCAPED_UNICODE) : '';
$crash = isset($json['crash']) ? json_encode($json['crash'], JSON_UNESCAPED_UNICODE) : '';
$ip = isset($_SERVER['REMOTE_ADDR']) ? $_SERVER['REMOTE_ADDR'] : '';
$ua = isset($_SERVER['HTTP_USER_AGENT']) ? mb_substr($_SERVER['HTTP_USER_AGENT'], 0, 255) : '';
$ts = isset($json['ts']) ? intval($json['ts']) : (time() * 1000);

$insertId = 0;
$dbOk = false;
try {
    $db = @new mysqli('127.0.0.1', 'toolbox', 'CHANGE_ME', 'toolbox');
    if (!$db->connect_errno) {
        $db->set_charset('utf8mb4');
        // 表不存在时自动创建（幂等）
        $db->query("CREATE TABLE IF NOT EXISTS tb_feedback (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            ftype VARCHAR(16) NOT NULL DEFAULT 'feedback',
            fb_type VARCHAR(32) NOT NULL DEFAULT '',
            content TEXT, diagnostic TEXT,
            app VARCHAR(32), device VARCHAR(64), android VARCHAR(32),
            root_flag TINYINT(1) DEFAULT 0, app_starts INT DEFAULT 0,
            usage_json TEXT, crash_json TEXT,
            client_ip VARCHAR(45), user_agent VARCHAR(255),
            ts BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $stmt = $db->prepare("INSERT INTO tb_feedback
            (ftype, fb_type, content, diagnostic, app, device, android, root_flag, app_starts, usage_json, crash_json, client_ip, user_agent, ts)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        $stmt->bind_param('sssssssiissssi', $type, $fbType, $content, $diagnostic, $app, $device, $android, $root, $appStarts, $usage, $crash, $ip, $ua, $ts);
        if ($stmt->execute()) {
            $insertId = $db->insert_id;
            $dbOk = true;
        }
        $stmt->close();
        $db->close();
    }
} catch (Exception $e) {
    $dbOk = false;
}

if (!$dbOk) {
    // 数据库不可用：降级写本地文件兜底（每份一个 txt）
    $dir = __DIR__ . '/feedback_fallback';
    if (!is_dir($dir)) { @mkdir($dir, 0777, true); }
    $fid = date('Ymd_His') . '_' . substr(md5(uniqid('', true)), 0, 6);
    $txt = "=== {$type} ===\n时间: " . date('Y-m-d H:i:s') . "\nIP: {$ip}\nUA: {$ua}\n"
        . ($fbType ? "反馈类型: {$fbType}\n" : '')
        . ($app ? "App: {$app}\n" : '')
        . ($device ? "设备: {$device}\n" : '')
        . ($android ? "系统: {$android}\n" : '')
        . $content . "\n" . ($diagnostic ? "\n--- 诊断 ---\n{$diagnostic}\n" : '')
        . "\n--- 原始 JSON ---\n" . $body . "\n";
    @file_put_contents($dir . '/' . $fid . '.txt', $txt, LOCK_EX);
    echo json_encode(array('ok' => true, 'id' => $fid, 'storage' => 'file'), JSON_UNESCAPED_UNICODE);
    exit;
}
echo json_encode(array('ok' => true, 'id' => $insertId, 'storage' => 'mysql'), JSON_UNESCAPED_UNICODE);
