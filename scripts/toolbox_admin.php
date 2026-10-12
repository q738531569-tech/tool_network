<?php
/**
 * 装维工具箱 反馈管理页（http://your-server.example:8090/toolbox/admin/）
 * 单文件：密码登录 + 反馈列表（筛选/搜索/分页）+ 详情（诊断/使用统计/崩溃日志）。
 * 数据源：MySQL toolbox.tb_feedback（App 反馈/自动收集接口写入）。
 * 2026-08-16：修复筛选/搜索 500（count 查询占位符未 bind）+ UI 美化。
 */
session_start();

// ---- 配置 ----
$ADMIN_PWD = 'CHANGE_ME';        // 登录密码（可自行修改）
$DB_HOST = '127.0.0.1';
$DB_USER = 'toolbox';
$DB_PASS = 'CHANGE_ME';
$DB_NAME = 'toolbox';

// ---- 登录/登出 ----
if (isset($_GET['logout'])) { session_destroy(); header('Location: index.php'); exit; }
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['pwd'])) {
    if ($_POST['pwd'] === $ADMIN_PWD) { $_SESSION['admin'] = 1; header('Location: index.php'); exit; }
    $loginErr = '密码错误';
}
if (empty($_SESSION['admin'])) {
    ?><!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>装维通 · 反馈管理</title>
    <style>
        * { box-sizing: border-box; }
        body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #e8f1ff 0%, #f0f2f5 60%); }
        .login { background: #fff; padding: 40px 36px; border-radius: 18px; box-shadow: 0 8px 32px rgba(22,119,255,.12); width: 340px; text-align: center; }
        .logo { width: 56px; height: 56px; border-radius: 16px; background: linear-gradient(135deg, #1677ff, #69b1ff); color: #fff; font-size: 26px; line-height: 56px; margin: 0 auto 14px; }
        h1 { font-size: 19px; color: #1a1a2e; margin: 0 0 6px; }
        .sub { font-size: 12px; color: #999; margin-bottom: 22px; }
        input[type=password] { width: 100%; padding: 12px 14px; border: 1.5px solid #e0e6ef; border-radius: 10px; font-size: 14px; margin-bottom: 14px; outline: none; transition: border-color .2s; }
        input[type=password]:focus { border-color: #1677ff; }
        button { width: 100%; padding: 12px; background: linear-gradient(135deg, #1677ff, #4096ff); color: #fff; border: 0; border-radius: 10px; font-size: 15px; cursor: pointer; transition: opacity .2s; }
        button:hover { opacity: .9; }
        .err { color: #e74c3c; font-size: 13px; margin-bottom: 12px; }
    </style></head><body>
    <div class="login">
        <div class="logo">🛠</div>
        <h1>装维通 · 反馈管理</h1>
        <div class="sub">装维工具箱后台管理</div>
        <?php if (isset($loginErr)) echo '<div class="err">'.$loginErr.'</div>'; ?>
        <form method="post"><input type="password" name="pwd" placeholder="管理密码" autofocus><button type="submit">登 录</button></form>
    </div></body></html><?php
    exit;
}

// ---- 数据库 ----
$db = @new mysqli($DB_HOST, $DB_USER, $DB_PASS, $DB_NAME);
if ($db->connect_errno) { die('数据库连接失败：' . $db->connect_error); }
$db->set_charset('utf8mb4');

// ---- 详情页 ----
$id = isset($_GET['id']) ? intval($_GET['id']) : 0;
if ($id > 0) {
    $row = $db->query("SELECT * FROM tb_feedback WHERE id=$id")->fetch_assoc();
    if (!$row) { echo '记录不存在'; exit; }
    ?><!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>反馈详情 #<?= $row['id'] ?> · 装维通</title>
    <style>
        * { box-sizing: border-box; }
        body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; background: #f0f2f5; margin: 0; padding: 20px; }
        .wrap { max-width: 900px; margin: 0 auto; }
        .top { background: linear-gradient(135deg, #1677ff, #4096ff); color: #fff; border-radius: 14px; padding: 18px 22px; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
        .top h1 { font-size: 17px; margin: 0; }
        .card { background: #fff; border-radius: 14px; padding: 18px 22px; margin-bottom: 12px; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
        .card h1 { font-size: 14px; color: #1a1a2e; margin: 0 0 12px; padding-left: 8px; border-left: 3px solid #1677ff; }
        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        td { padding: 7px 10px; border-bottom: 1px solid #f5f6f8; vertical-align: top; }
        td.k { width: 110px; color: #999; white-space: nowrap; }
        pre { background: #f6f8fa; padding: 12px; border-radius: 10px; font-size: 12px; overflow-x: auto; white-space: pre-wrap; word-break: break-all; margin: 0; line-height: 1.6; }
        .tag { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; }
        .tag.feedback { background: #e6f4ff; color: #1677ff; }
        .tag.auto { background: #f6ffed; color: #52c41a; }
        a.back { color: #fff; text-decoration: none; font-size: 13px; background: rgba(255,255,255,.2); padding: 6px 12px; border-radius: 8px; }
    </style></head><body><div class="wrap">
    <div class="top">
        <h1>反馈详情 #<?= $row['id'] ?> <span class="tag <?= $row['ftype'] ?>" style="margin-left:6px"><?= $row['ftype'] === 'auto' ? '自动收集' : '用户反馈' ?></span></h1>
        <a class="back" href="index.php">← 返回列表</a>
    </div>
    <div class="card"><table>
        <tr><td class="k">反馈类型</td><td><?= htmlspecialchars($row['fb_type'] ?: '—') ?></td></tr>
        <tr><td class="k">App 版本</td><td><?= htmlspecialchars($row['app'] ?: '—') ?></td></tr>
        <tr><td class="k">设备</td><td><?= htmlspecialchars($row['device'] ?: '—') ?></td></tr>
        <tr><td class="k">系统</td><td><?= htmlspecialchars($row['android'] ?: '—') ?></td></tr>
        <tr><td class="k">root</td><td><?= $row['root_flag'] ? '已获取' : '未获取' ?></td></tr>
        <tr><td class="k">累计启动</td><td><?= $row['app_starts'] ?> 次</td></tr>
        <tr><td class="k">IP</td><td><?= htmlspecialchars($row['client_ip'] ?: '—') ?></td></tr>
        <tr><td class="k">提交时间</td><td><?= $row['created_at'] ?></td></tr>
    </table></div>
    <?php if ($row['content']): ?><div class="card"><h1>反馈内容</h1><pre><?= htmlspecialchars($row['content']) ?></pre></div><?php endif; ?>
    <?php if ($row['diagnostic']): ?><div class="card"><h1>诊断信息</h1><pre><?= htmlspecialchars($row['diagnostic']) ?></pre></div><?php endif; ?>
    <?php if ($row['usage_json'] && $row['usage_json'] !== '{}' && $row['usage_json'] !== 'null'): ?>
        <div class="card"><h1>功能使用统计</h1><pre><?php
        $u = json_decode($row['usage_json'], true);
        if (is_array($u)) {
            arsort($u);
            foreach ($u as $k => $v) echo htmlspecialchars($k) . "：{$v} 次\n";
        } else { echo htmlspecialchars($row['usage_json']); }
        ?></pre></div>
    <?php endif; ?>
    <?php if ($row['crash_json'] && $row['crash_json'] !== '[]' && $row['crash_json'] !== 'null'): ?>
        <div class="card"><h1>崩溃日志</h1><?php
        $cs = json_decode($row['crash_json'], true);
        if (is_array($cs)) { foreach ($cs as $c) { echo '<pre>' . htmlspecialchars($c) . '</pre>'; } }
        else { echo '<pre>' . htmlspecialchars($row['crash_json']) . '</pre>'; }
        ?></div>
    <?php endif; ?>
    </div></body></html><?php
    exit;
}

// ---- 列表页 ----
$type = isset($_GET['type']) ? preg_replace('/[^a-z]/', '', $_GET['type']) : '';
$kw = isset($_GET['kw']) ? trim($_GET['kw']) : '';
$page = isset($_GET['page']) ? max(1, intval($_GET['page'])) : 1;
$per = 20;
$offset = ($page - 1) * $per;

$where = array();
$params = array();
$types = '';
if ($type === 'feedback' || $type === 'auto') { $where[] = 'ftype=?'; $params[] = $type; $types .= 's'; }
if ($kw !== '') {
    $where[] = '(content LIKE ? OR device LIKE ? OR app LIKE ? OR fb_type LIKE ?)';
    $like = "%$kw%";
    array_push($params, $like, $like, $like, $like);
    $types .= 'ssss';
}
$whereSql = $where ? ('WHERE ' . implode(' AND ', $where)) : '';

// 统计
$stats = array('total' => 0, 'feedback' => 0, 'auto' => 0, 'week' => 0);
$r = $db->query("SELECT COUNT(*) c, SUM(ftype='feedback') f, SUM(ftype='auto') a, SUM(created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)) w FROM tb_feedback")->fetch_assoc();
if ($r) { $stats = array('total' => (int)$r['c'], 'feedback' => (int)$r['f'], 'auto' => (int)$r['a'], 'week' => (int)$r['w']); }

// 计数查询同样走 prepare/bind（修复 500：直接 query 拼接含 ? 占位符的 WHERE 会 SQL 语法错误，
// 点筛选/搜索即崩——占位符必须 bind 参数后执行）
$countStmt = $db->prepare("SELECT COUNT(*) c FROM tb_feedback $whereSql");
if ($types !== '') {
    $countStmt->bind_param($types, ...$params);
}
$countStmt->execute();
$count = (int)$countStmt->get_result()->fetch_assoc()['c'];
$pages = max(1, (int)ceil($count / $per));
if ($page > $pages) { $page = $pages; $offset = ($page - 1) * $per; }

$stmt = $db->prepare("SELECT id, ftype, fb_type, content, device, app, android, root_flag, created_at FROM tb_feedback $whereSql ORDER BY id DESC LIMIT $per OFFSET $offset");
if ($types !== '') {
    $stmt->bind_param($types, ...$params);
}
$stmt->execute();
$rows = $stmt->get_result()->fetch_all(MYSQLI_ASSOC);
?>
<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>装维通 · 反馈管理</title>
<style>
    * { box-sizing: border-box; }
    body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; background: #f0f2f5; margin: 0; padding: 20px; }
    .wrap { max-width: 1100px; margin: 0 auto; }
    .top { background: linear-gradient(135deg, #1677ff, #4096ff); color: #fff; border-radius: 14px; padding: 16px 22px; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
    .top h1 { font-size: 17px; margin: 0; }
    .top .logout { font-size: 13px; color: #fff; text-decoration: none; background: rgba(255,255,255,.18); padding: 6px 14px; border-radius: 8px; }
    .top .logout:hover { background: rgba(255,255,255,.3); }
    .stats { display: flex; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
    .stat { background: #fff; border-radius: 14px; padding: 16px 20px; box-shadow: 0 1px 6px rgba(0,0,0,.05); flex: 1; min-width: 130px; display: flex; align-items: center; gap: 12px; }
    .stat .ico { width: 42px; height: 42px; border-radius: 12px; font-size: 20px; line-height: 42px; text-align: center; flex-shrink: 0; }
    .ico.i1 { background: #e6f4ff; } .ico.i2 { background: #fff7e6; } .ico.i3 { background: #f6ffed; } .ico.i4 { background: #f9f0ff; }
    .stat b { font-size: 24px; display: block; color: #1a1a2e; line-height: 1.1; }
    .stat span { font-size: 12px; color: #999; }
    .bar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
    .pills { display: flex; gap: 6px; background: #fff; padding: 4px; border-radius: 10px; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
    .pills a { padding: 6px 16px; border-radius: 8px; font-size: 13px; text-decoration: none; color: #666; }
    .pills a.on { background: #1677ff; color: #fff; }
    .bar form { display: flex; gap: 8px; align-items: center; }
    .bar input[type=text] { padding: 8px 12px; border: 1.5px solid #e0e6ef; border-radius: 10px; font-size: 13px; width: 200px; outline: none; }
    .bar input[type=text]:focus { border-color: #1677ff; }
    .bar button { padding: 8px 16px; border: 0; border-radius: 10px; background: #1677ff; color: #fff; font-size: 13px; cursor: pointer; }
    .table-card { background: #fff; border-radius: 14px; overflow: hidden; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 11px 14px; font-size: 13px; text-align: left; }
    th { background: #fafbfc; color: #8a94a6; font-weight: 600; white-space: nowrap; border-bottom: 1px solid #f0f2f5; }
    td { border-bottom: 1px solid #f7f8fa; }
    tr:hover td { background: #f5f9ff; }
    td.sum { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #555; }
    .tag { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; }
    .tag.feedback { background: #e6f4ff; color: #1677ff; }
    .tag.auto { background: #f6ffed; color: #52c41a; }
    .root { color: #52c41a; font-weight: 600; }
    .page { margin-top: 14px; display: flex; gap: 6px; align-items: center; justify-content: center; font-size: 13px; }
    .page a { color: #1677ff; text-decoration: none; padding: 6px 14px; background: #fff; border-radius: 10px; box-shadow: 0 1px 4px rgba(0,0,0,.06); }
    .page a.dis { color: #ccc; pointer-events: none; }
    .page span { color: #888; }
    .empty { background: #fff; border-radius: 14px; padding: 50px; text-align: center; color: #999; font-size: 14px; }
    a.detail { color: #1677ff; text-decoration: none; font-weight: 500; }
    a.detail:hover { text-decoration: underline; }
</style></head><body><div class="wrap">
<div class="top">
    <h1>🛠 装维通 · 反馈管理</h1>
    <a class="logout" href="index.php?logout=1">退出登录</a>
</div>
<div class="stats">
    <div class="stat"><div class="ico i1">📊</div><div><b><?= $stats['total'] ?></b><span>累计反馈</span></div></div>
    <div class="stat"><div class="ico i2">💬</div><div><b><?= $stats['feedback'] ?></b><span>用户反馈</span></div></div>
    <div class="stat"><div class="ico i3">🤖</div><div><b><?= $stats['auto'] ?></b><span>自动收集</span></div></div>
    <div class="stat"><div class="ico i4">📅</div><div><b><?= $stats['week'] ?></b><span>近 7 天</span></div></div>
</div>
<div class="bar">
    <div class="pills">
        <a class="<?= $type === '' ? 'on' : '' ?>" href="index.php">全部</a>
        <a class="<?= $type === 'feedback' ? 'on' : '' ?>" href="index.php?type=feedback">用户反馈</a>
        <a class="<?= $type === 'auto' ? 'on' : '' ?>" href="index.php?type=auto">自动收集</a>
    </div>
    <form method="get" action="index.php">
        <?php if ($type) echo '<input type="hidden" name="type" value="' . $type . '">'; ?>
        <input type="text" name="kw" value="<?= htmlspecialchars($kw) ?>" placeholder="🔍 搜索内容/设备/版本">
        <button type="submit">搜索</button>
    </form>
</div>
<?php if (!$rows): ?>
    <div class="empty">暂无数据（App 提交反馈或自动上报后这里会出现记录）</div>
<?php else: ?>
<div class="table-card">
<table>
    <tr><th>ID</th><th>类型</th><th>反馈类型</th><th>内容摘要</th><th>设备</th><th>App</th><th>系统</th><th>root</th><th>时间</th><th></th></tr>
    <?php foreach ($rows as $row): ?>
    <tr>
        <td><?= $row['id'] ?></td>
        <td><span class="tag <?= $row['ftype'] ?>"><?= $row['ftype'] === 'auto' ? '自动' : '反馈' ?></span></td>
        <td><?= htmlspecialchars($row['fb_type'] ?: '—') ?></td>
        <td class="sum" title="<?= htmlspecialchars($row['content']) ?>"><?= htmlspecialchars(mb_substr($row['content'], 0, 60)) ?: '—' ?></td>
        <td><?= htmlspecialchars(mb_substr($row['device'], 0, 24)) ?></td>
        <td><?= htmlspecialchars($row['app']) ?></td>
        <td><?= htmlspecialchars($row['android']) ?></td>
        <td><?= $row['root_flag'] ? '<span class="root">√</span>' : '' ?></td>
        <td style="color:#888"><?= $row['created_at'] ?></td>
        <td><a class="detail" href="index.php?id=<?= $row['id'] ?>">详情</a></td>
    </tr>
    <?php endforeach; ?>
</table>
</div>
<div class="page">
    <?php if ($page > 1): ?><a href="index.php?page=<?= $page - 1 ?>&type=<?= $type ?>&kw=<?= urlencode($kw) ?>">‹ 上一页</a><?php else: ?><a class="dis">‹ 上一页</a><?php endif; ?>
    <span>第 <?= $page ?> / <?= $pages ?> 页（共 <?= $count ?> 条）</span>
    <?php if ($page < $pages): ?><a href="index.php?page=<?= $page + 1 ?>&type=<?= $type ?>&kw=<?= urlencode($kw) ?>">下一页 ›</a><?php else: ?><a class="dis">下一页 ›</a><?php endif; ?>
</div>
<?php endif; ?>
</div></body></html>
