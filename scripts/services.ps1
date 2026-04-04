#!/usr/bin/env pwsh
<#
.SYNOPSIS
    SeisLearner 服务管理脚本 — 启停/查看所有项目服务状态

.DESCRIPTION
    管理本项目的 4 个服务：
    1. PostgreSQL (Docker)
    2. 后端 Spring Boot (:8080)
    3. 前端 Vite/React (:5173)
    4. MinerU 解析服务 (:8000)

.EXAMPLE
    .\services.ps1 status        # 查看所有服务状态
    .\services.ps1 start         # 启动所有服务
    .\services.ps1 stop          # 停止所有服务
    .\services.ps1 restart       # 重启所有服务
    .\services.ps1 start -Name pg # 只启动 PostgreSQL
    .\services.ps1 stop -Name backend mineru  # 停止后端和MinerU
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("start", "stop", "restart", "status", "gui")]
    [string]$Action = "status",

    [Parameter(Mandatory = $false)]
    [string[]]$Name = @()
)

# ═══════════════════════════════════════════
# 配置区
# ══════════════════════════════════════════
$Script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$Script:StateDir   = Join-Path $PSScriptRoot ".service-state"

$Script:Services = @{
    "pg"      = @{
        DisplayName  = "PostgreSQL (Docker)"
        Description  = "数据库服务，端口 5434"
        Type         = "docker"
        ContainerName = "seislearner-pg"
        StartOrder   = 1
    }
    "mineru"  = @{
        DisplayName  = "MinerU 解析服务"
        Description  = "PDF 文档解析，端口 8000"
        Type         = "python"
        WorkDir      = $Script:ProjectRoot
        Command      = "python mineru_api_service.py"
        StartOrder   = 2
    }
    "backend" = @{
        DisplayName  = "后端 Spring Boot"
        Description  = "Java 后端 API，端口 8080"
        Type         = "mvn"
        WorkDir      = Join-Path $Script:ProjectRoot "seislearner"
        Command      = ".\mvnw.cmd spring-boot:run"
        StartOrder   = 3
    }
    "frontend"= @{
        DisplayName  = "前端 Vite/React"
        Description  = "React 前端 UI，端口 5173"
        Type         = "npm"
        WorkDir      = Join-Path $Script:ProjectRoot "ui"
        Command      = "npm run dev"
        StartOrder   = 4
    }
}

# ══════════════════════════════════════════
# 工具函数
# ══════════════════════════════════════════

function Ensure-StateDir {
    if (-not (Test-Path $Script:StateDir)) {
        New-Item -ItemType Directory -Path $Script:StateDir -Force | Out-Null
    }
}

function Get-PidFile($svcName) {
    Join-Path $Script:StateDir "$svcName.pid"
}

function Get-LogFile($svcName) {
    Join-Path $Script:StateDir "$svcName.log"
}

function Write-SvcPid($svcName, $procId) {
    Ensure-StateDir
    $procId | Out-File (Get-PidFile $svcName) -Encoding utf8 -NoNewline
}

function Read-SvcPid($svcName) {
    $f = Get-PidFile $svcName
    if (Test-Path $f) {
        $p = Get-Content $f -Raw -ErrorAction SilentlyContinue
        if ($p -match '^\d+$') { return [int]$p.Trim() }
    }
    return $null
}

function Remove-SvcState($svcName) {
    $pf = Get-PidFile $svcName
    $lf = Get-LogFile $svcName
    if (Test-Path $pf) { Remove-Item $pf -Force -ErrorAction SilentlyContinue }
    if (Test-Path $lf) { Remove-Item $lf -Force -ErrorAction SilentlyContinue }
}

function Test-ProcessAlive($procId) {
    if (-not $procId) { return $false }
    try {
        $p = Get-Process -Id $procId -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

# ══════════════════════════════════════════
# 状态检测（每个服务一个函数）
# ══════════════════════════════════════════

function Get-PgStatus {
    $c = $Script:Services["pg"].ContainerName
    try {
        $r = docker ps --filter "name=$c" --format "{{.Status}}" 2>$null
        if ($r) {
            if ($r -match "Up") {
                $portMapping = docker port $c 2>$null | Select-String "543"
                return @{ Status = "running"; Detail = $r; Pid = $null; PortInfo = ($portMapping -join ", ") }
            } else {
                return @{ Status = "stopped"; Detail = $r; Pid = $null; PortInfo = "" }
            }
        }
        # 检查是否存在但未运行
        $ex = docker ps -a --filter "name=$c" --format "{{.Status}}" 2>$null
        if ($ex) {
            return @{ Status = "stopped"; Detail = "容器已创建但未运行"; Pid = $null; PortInfo = "" }
        }
        return @{ Status = "not_found"; Detail = "容器不存在"; Pid = $null; PortInfo = "" }
    } catch {
        return @{ Status = "error"; Detail = $_.Exception.Message; Pid = $null; PortInfo = "" }
    }
}

function Get-NormalSvcStatus($svcKey) {
    $svc = $Script:Services[$svcKey]
    $savedPid = Read-SvcPid $svcKey

    # 先检查保存的 PID 是否还活着
    if ($savedPid -and (Test-ProcessAlive $savedPid)) {
        $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
        return @{
            Status   = "running"
          Detail   = "PID: $savedPid, 运行中"
            Pid       = $savedPid
            StartTime = $proc.StartTime.ToString("HH:mm:ss")
          PortInfo  = ""
        }
    }

    # PID 不活了或没有保存的 PID → 通过进程名/命令行查找
    $searchTerm = switch ($svc.Type) {
        "python" { "mineru_api_service\.py" }
        "mvn"    { "spring-boot:run" }
        "npm"    { "vite|rolldown-vite" }
        default  { $svc.Command.Split(" ")[0] }
    }

    $procs = Get-CimInstance Win32_Process |
        Where-Object { $_.CommandLine -match $searchTerm -and $_.CommandLine -notlike "*services*" } |
        Sort-Object CreationDate |
        Select-Object -Last 1

    if ($procs) {
        $procId = $procs.ProcessId
        Write-SvcPid $svcKey $procId
        $startTime = [DateTime]::Parse($procs.CreationDate).ToString("HH:mm:ss")
        return @{
            Status   = "running"
            Detail   = "PID: $procId, 自 $startTime 启动"
            Pid       = $procId
            StartTime = $startTime
          PortInfo  = ""
        }
    }

    # 清理过期的 PID 文件
    if ($savedPid) { Remove-SvcState $svcKey }

    return @{ Status = "stopped"; Detail = "未运行"; Pid = $null; PortInfo = "" }
}

function Get-ServiceStatus($svcKey) {
    if ($svcKey -eq "pg") { return Get-PgStatus }
    return Get-NormalSvcStatus $svcKey
}

# ══════════════════════════════════════════
# 启动操作
# ══════════════════════════════════════════

function Start-PgService {
    $c = $Script:Services["pg"].ContainerName
    Write-Host "[PostgreSQL] 启动 Docker 容器..." -ForegroundColor Cyan

    # 检查是否已存在但未运行
    $exists = docker ps -a --filter "name=$c" --format "{{.Names}}" 2>$null
    if ($exists) {
        docker start $c 2>&1 | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host "  容器不存在，请先执行数据库初始化：" -ForegroundColor Yellow
        Write-Host "  docker run -d --name $c -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=seislearner_123 -e POSTGRES_DB=seislearner -p 5434:5432 -v seislearner_pgdata:/var/lib/postgresql/data postgres:16" -ForegroundColor Yellow
        return $false
    }

    Start-Sleep -Seconds 2
    $st = Get-PgStatus
    if ($st.Status -eq "running") {
        Write-Host "[PostgreSQL] OK — $($st.Detail)" -ForegroundColor Green
        return $true
    } else {
        Write-Host "[PostgreSQL] 失败 — $($st.Detail)" -ForegroundColor Red
        return $false
    }
}

function Start-NormalService($svcKey) {
    $svc = $Script:Services[$svcKey]
    $displayName = $svc.DisplayName

    # 检查是否已在运行
    $current = Get-NormalSvcStatus $svcKey
    if ($current.Status -eq "running") {
        Write-Host "[$displayName] 已在运行 (PID: $($current.Pid))" -ForegroundColor Yellow
        return $true
    }

    Write-Host "[$displayName] 启动中..." -ForegroundColor Cyan
    $workDir = $svc.WorkDir

    # 验证工作目录存在
    if (-not (Test-Path $workDir)) {
        Write-Host "[$displayName] 错误: 目录不存在 $workDir" -ForegroundColor Red
        return $false
    }

    # 提取监听端口（用于启动后检测）
    $listenPort = switch ($svcKey) {
        "mineru"   { 8000 }
        "backend"  { 8080 }
        "frontend" { 5173 }
        default    { $null }
    }

    Ensure-StateDir

    if ($svc.Type -eq "python") {
        # ── Python 服务：直接启动 python 进程，不经过 cmd /c ──
        # 启动成功判断完全依赖：端口是否开始监听
        $scriptPath = Join-Path $workDir $svc.Command.Split(" ")[1]
        $logPath = Get-LogFile $svcKey

        # 直接用 Start-Process 启动 python，-RedirectStandardOutput/Error 写日志
        # 避免通过 cmd /c 的 >> 重定向，减少中间层
        Start-Process python -ArgumentList "-u", "`"$scriptPath`"" `
            -WorkingDirectory $workDir `
            -WindowStyle Minimized `
            -RedirectStandardOutput $logPath `
            -RedirectStandardError $logPath `
            -PassThru | Out-Null

    } elseif ($svc.Type -eq "mvn") {
        $startedProc = Start-Process cmd -ArgumentList "/c", $svc.Command `
            -WorkingDirectory $workDir `
            -WindowStyle Minimized `
            -PassThru
        if (-not $startedProc -or $startedProc.Id -eq $null -or $startedProc.Id -eq 0) {
            Write-Host "[$displayName] 启动失败 (Start-Process 返回空)" -ForegroundColor Red
            return $false
        }
        Write-SvcPid $svcKey $startedProc.Id

    } elseif ($svc.Type -eq "npm") {
        $startedProc = Start-Process cmd -ArgumentList "/c", $svc.Command `
            -WorkingDirectory $workDir `
            -WindowStyle Minimized `
            -PassThru
        if (-not $startedProc -or $startedProc.Id -eq $null -or $startedProc.Id -eq 0) {
            Write-Host "[$displayName] 启动失败 (Start-Process 返回空)" -ForegroundColor Red
            return $false
        }
        Write-SvcPid $svcKey $startedProc.Id
    }

    # ── 等待服务就绪 ──────────────────────────────────
    $waitSec = switch ($svcKey) { "mineru" { 15 } "backend" { 20 } "frontend" { 12 } default { 5 } }
    Write-Host "  等待 ${waitSec}s 让服务初始化（端口 ${listenPort}）..." -ForegroundColor Gray

    $foundPort = $false
    $realPid = $null

    for ($i = 1; $i -le $waitSec; $i++) {
        Start-Sleep -Seconds 1

        if ($listenPort) {
            $netstatLine = netstat -ano | Select-String "LISTENING" | Select-String ":${listenPort} "
            if ($netstatLine) {
                $parts = $netstatLine -split '\s+'
                $candidatePid = $parts[-1]
                if ($candidatePid -match '^\d+$') {
                    $realPid = [int]$candidatePid
                    $foundPort = $true
                    break
                }
            }
        }

        Write-Host "    [$i/$waitSec] 等待端口 ${listenPort}..." -ForegroundColor DarkGray
    }

    # ── 最终状态确认 ───────────────────────────────────
    if ($svc.Type -eq "python") {
        # Python 服务：端口就绪 = 启动成功
        if ($foundPort -and $realPid) {
            Write-SvcPid $svcKey $realPid  # 保存真实 Python PID
            Write-Host "[$displayName] OK — 端口 ${listenPort} 已监听，PID: $realPid" -ForegroundColor Green
            return $true
        }
        # 端口未监听 → 查日志找原因
        $errLog = Get-LogFile $svcKey
        $errInfo = if (Test-Path $errLog) {
            (Get-Content $errLog -Encoding UTF8 -Tail 15 -ErrorAction SilentlyContinue) -join "`n"
        } else { "" }
        Write-Host "[$displayName] 启动失败: 端口 ${listenPort} 未监听" -ForegroundColor Red
        if ($errInfo) {
            Write-Host "  日志最后几行:" -ForegroundColor DarkGray
            foreach ($line in ($errInfo -split "`n")) {
                if ($line.Trim()) { Write-Host "    $line" -ForegroundColor DarkGray }
            }
        }
        Remove-SvcState $svcKey
        return $false
    }

    # npm / mvn 类型：进程 + 状态双重检测
    $check = Get-NormalSvcStatus $svcKey
    if ($check.Status -eq "running") {
        Write-Host "[$displayName] OK — $($check.Detail)" -ForegroundColor Green
        return $true
    } else {
        Write-Host "[$displayName] 进程存在但检测到停止状态" -ForegroundColor Yellow
        return $true
    }
}

function Start-ServiceByKey($svcKey) {
    if ($svcKey -eq "pg") { return Start-PgService }
    return Start-NormalService $svcKey
}

# ══════════════════════════════════════════
# 停止操作
# ══════════════════════════════════════════

function Stop-PgService {
    $c = $Script:Services["pg"].ContainerName
    Write-Host "[PostgreSQL] 停止 Docker 容器..." -ForegroundColor Cyan
    $r = docker stop $c 2>&1
    Write-Host "  $r" -ForegroundColor $(if ($LASTEXITCODE -eq 0) { "Green" } else { "Red" })
    return $LASTEXITCODE -eq 0
}

function Stop-NormalService($svcKey) {
    $svc = $Script:Services[$svcKey]
    $displayName = $svc.DisplayName

    $current = Get-NormalSvcStatus $svcKey
    if ($current.Status -ne "running") {
        Write-Host "[$displayName] 未在运行" -ForegroundColor Yellow
        return $true
    }

    Write-Host "[$displayName] 停止 (PID: $($current.Pid))..." -ForegroundColor Cyan

    try {
        # 先尝试优雅关闭
        Stop-Process -Id $current.Pid -Force -ErrorAction Stop
        Start-Sleep -Seconds 1

        # 如果还活着，再杀一次
        if (Test-ProcessAlive $current.Pid) {
            Stop-Process -Id $current.Pid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 1
        }

        # 杀死可能残留的子进程
        $searchTerm = switch ($svc.Type) {
            "python" { "mineru_api_service" }
            "mvn"    { "java.*seislearner|spring-boot" }
            "npm"    { "node.*vite|node.*rolldow" }
            default  { $svc.Command.Split(" ")[0] }
        }

        Get-CimInstance Win32_Process |
            Where-Object { $_.CommandLine -match $searchTerm } |
            ForEach-Object {
                Write-Host "  终止子进程 PID: $($_.ProcessId)" -ForegroundColor DarkGray
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            }

        Remove-SvcState $svcKey
        Write-Host "[$displayName] 已停止" -ForegroundColor Green
        return $true
    } catch {
        Write-Host "[$displayName] 停止出错: $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

function Stop-ServiceByKey($svcKey) {
    if ($svcKey -eq "pg") { return Stop-PgService }
    return Stop-NormalService $svcKey
}

# ══════════════════════════════════════════
# 输出格式化
# ══════════════════════════════════════════

function Format-StatusTable($statusDict) {
    $lines = @()
    foreach ($key in @("pg", "mineru", "backend", "frontend")) {
        $svc = $Script:Services[$key]
        $st  = $statusDict[$key]

        $icon = switch ($st.Status) {
            "running"   { [char]0x2713 }  # ✓
            "stopped"   { [char]0x2717 }  # ✗
            "not_found" { [char]0x2298 }  # ⊘
            "error"     { [char]0x2757 }  # ❗
            default     { "?" }
        }

        $color = switch ($st.Status) {
            "running"   { "Green" }
            "stopped"   { "Red" }
            "not_found" { "DarkYellow" }
            "error"     { "Red" }
            default     { "White" }
        }

        $statusText = switch ($st.Status) {
            "running"   { "运行中" }
            "stopped"   { "已停止" }
            "not_found" { "未安装" }
            "error"     { "异常" }
            default     { $st.Status }
        }

        $lines += [PSCustomObject]@{
            Icon = $icon
            Name = $svc.DisplayName
            Status = $statusText
            Detail = $st.Detail
        }
    }
    return $lines
}

function Show-StatusHeader {
    Write-Host ""
    Write-Host ("═" * 65) -ForegroundColor DarkGray
    Write-Host "  SeisLearner 服务控制面板  |  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor White
    Write-Host ("═" * 65) -ForegroundColor DarkGray
    Write-Host ""
}

# ══════════════════════════════════════════
# 主逻辑分发
# ══════════════════════════════════════════

switch ($Action) {

    "status" {
        $allStatus = @{}
        foreach ($key in $Script:Services.Keys) {
            $allStatus[$key] = Get-ServiceStatus $key
        }

        # JSON 模式：只输出纯 JSON，不输出任何表格或装饰文字
        if ($env:SVC_OUTPUT_JSON -eq "1") {
            $jsonObj = @{}
            foreach ($key in $Script:Services.Keys) {
                $st = $allStatus[$key]
                $svc = $Script:Services[$key]
                $jsonObj[$key] = @{
                    key         = $key
                    displayName  = $svc.DisplayName
                    description  = $svc.Description
                    type         = $svc.Type
                    status       = $st.Status
                    detail       = $st.Detail
                    pid          = $st.Pid
                    startTime    = $st.StartTime
                    portInfo     = $st.PortInfo
                }
            }
            # 直接输出到 stdout（唯一输出），用 Compress 避免换行问题
            Write-Output ($jsonObj | ConvertTo-Json -Depth 3 -Compress)
            return  }

        # 人机模式：显示格式化表格
        Show-StatusHeader
        Format-StatusTable $allStatus | Format-Table -AutoSize -HideTableHeaders

        Write-Host ""
        Write-Host "  快速操作:" -ForegroundColor DarkGray
        Write-Host "    .\scripts\services.ps1 start           启动全部" -ForegroundColor DarkGray
        Write-Host "    .\scripts\services.ps1 stop            停止全部" -ForegroundColor DarkGray
        Write-Host "    .\scripts\services.ps1 restart         重启全部" -ForegroundColor DarkGray
        Write-Host "    .\scripts\services.ps1 gui             打开可视化面板" -ForegroundColor DarkGray
        Write-Host ""
    }

    "start" {
        $targets = if ($Name.Count -gt 0) { $Name } else { @("pg", "mineru", "backend", "frontend") }

        # 按 StartOrder 排序
        $ordered = $targets | Sort-Object { $Script:Services[$_].StartOrder }

        # JSON 模式：静默所有 Write-Host 输出，避免污染 stdout
        if ($env:SVC_OUTPUT_JSON -eq "1") {
            # 临时禁用 Write-Host：将所有文本输出重定向到 $null
            # Write-Host 写的是 Information 流（流号 6），用 >$null 无法捕获
            # 方案：保存原函数并替换为空操作
            function global:Write-Host { param([object]$Object, [switch]$NoNewline, [ConsoleColor]$ForegroundColor, [ConsoleColor]$BackgroundColor) }

            $results = @()
            foreach ($key in $ordered) {
                if (-not $Script:Services.ContainsKey($key)) {
                    $results += @{ key=$key; success=$false; message="未知服务"; status="unknown" }
                    continue
                }
                # 抑制子函数的所有 Write-Host，只捕获返回值
                $ok = Start-ServiceByKey $key 2>$null
                $results += @{ key=$key; success=$ok; message=if ($ok) { "已启动" } else { "启动可能有问题" } ; status="pending" }
                Start-Sleep -Milliseconds 300
            }

            # 操作完成后查最终状态
            $finalStatus = @{}
            foreach ($key in $ordered) {
                if ($Script:Services.ContainsKey($key)) {
                    $finalStatus[$key] = Get-ServiceStatus $key
                }
            }

            # 更新结果中的实际状态
            foreach ($r in $results) {
                if ($finalStatus.ContainsKey($r.key)) {
                    $r.status = $finalStatus[$r.key].Status
                }
            }

            # 构造并输出纯 JSON
            $jsonObj = @{
                action     = "start"
                results    = @()
                finalState = @{}
            }
            foreach ($r in $results) {
                $jsonObj.results += @{
                    key      = $r.key
                    success  = [bool]$r.success
                    message  = $r.message
                    status   = $r.status
                }
            }
            foreach ($key in $finalStatus.Keys) {
                $st = $finalStatus[$key]
                $jsonObj.finalState[$key] = @{
                    status   = $st.Status
                    detail   = $st.Detail
                    pid      = $st.Pid
                    portInfo = $st.PortInfo
                }
            }
            # 用 [Console]::WriteLine 直接写 stdout，绕过 PowerShell 管道
            [Console]::Out.WriteLine(($jsonObj | ConvertTo-Json -Depth 4 -Compress))
            return
        }

        # 人机模式：显示格式化进度
        Show-StatusHeader

        $results = @()
        foreach ($key in $ordered) {
            if (-not $Script:Services.ContainsKey($key)) {
                Write-Host "未知服务: $key" -ForegroundColor Red
                continue
            }
            $ok = Start-ServiceByKey $key
            $results += @{ key=$key; success=$ok }
            Write-Host ""
        }

        Write-Host "--- 最终状态 ---" -ForegroundColor DarkGray
        foreach ($key in $ordered) {
            $st = Get-ServiceStatus $key
            $marker = if ($st.Status -eq "running") { "[OK]" } else { "[--]" }
            $color = if ($st.Status -eq "running") { "Green" } else { "Red" }
            Write-Host "  $marker $($Script:Services[$key].DisplayName): $($st.Status)" -ForegroundColor $color
        }
    }

    "stop" {
        $targets = if ($Name.Count -gt 0) { $Name } else { @("frontend", "backend", "mineru", "pg") }

        # 停止顺序与启动相反
        $reversed = $targets | Sort-Object { -$Script:Services[$_].StartOrder }

        # JSON 模式：静默所有输出
        if ($env:SVC_OUTPUT_JSON -eq "1") {
            function global:Write-Host { param([object]$Object, [switch]$NoNewline, [ConsoleColor]$ForegroundColor, [ConsoleColor]$BackgroundColor) }

            $results = @()
            foreach ($key in $reversed) {
                if (-not $Script:Services.ContainsKey($key)) {
                    $results += @{ key=$key; success=$false; message="未知服务"; status="unknown" }
                    continue
                }
                Stop-ServiceByKey $key 2>$null | Out-Null
                $results += @{ key=$key; success=$true; message="已停止"; status="pending" }
                Start-Sleep -Milliseconds 300
            }

            $finalStatus = @{}
            foreach ($key in $reversed) {
                if ($Script:Services.ContainsKey($key)) {
                    $finalStatus[$key] = Get-ServiceStatus $key
                }
            }

            foreach ($r in $results) {
                if ($finalStatus.ContainsKey($r.key)) {
                    $r.status = $finalStatus[$r.key].Status
                }
            }

            $jsonObj = @{
                action     = "stop"
                results    = @()
                finalState = @{}
            }
            foreach ($r in $results) {
                $jsonObj.results += @{
                    key      = $r.key
                    success  = [bool]$r.success
                    message  = $r.message
                    status   = $r.status
                }
            }
            foreach ($key in $finalStatus.Keys) {
                $st = $finalStatus[$key]
                $jsonObj.finalState[$key] = @{
                    status   = $st.Status
                    detail   = $st.Detail
                    pid      = $st.Pid
                    portInfo = $st.PortInfo
                }
            }
            [Console]::Out.WriteLine(($jsonObj | ConvertTo-Json -Depth 4 -Compress))
            return
        }

        Show-StatusHeader
        foreach ($key in $reversed) {
            if (-not $Script:Services.ContainsKey($key)) {
                Write-Host "未知服务: $key" -ForegroundColor Red
                continue
            }
            Stop-ServiceByKey $key | Out-Null
            $displayName = $Script:Services[$key].DisplayName
            Write-Host "  [OK] $displayName : 已停止" -ForegroundColor Green
        }
        Write-Host "所有指定服务已停止。" -ForegroundColor Cyan
    }

    "restart" {
        $targets = if ($Name.Count -gt 0) { $Name } else { @("pg", "mineru", "backend", "frontend") }

        if ($env:SVC_OUTPUT_JSON -eq "1") {
            # JSON 模式：直接在当前进程中执行 stop + start，收集结果
            # 静默所有 Write-Host
            function global:Write-Host { param([object]$Object, [switch]$NoNewline, [ConsoleColor]$ForegroundColor, [ConsoleColor]$BackgroundColor) }

            $allResults = @()

            # Stop 阶段
            $stopOrder = $targets | Sort-Object { -$Script:Services[$_].StartOrder }
            foreach ($key in $stopOrder) {
                if (-not $Script:Services.ContainsKey($key)) { continue }
                Stop-ServiceByKey $key 2>$null | Out-Null
                Start-Sleep -Milliseconds 300
            }

            # 等待进程完全退出
            Start-Sleep -Seconds 2

            # Start 阶段（静默输出）
            $startOrder = $targets | Sort-Object { $Script:Services[$_].StartOrder }
            foreach ($key in $startOrder) {
                if (-not $Script:Services.ContainsKey($key)) { continue }
                $ok = Start-ServiceByKey $key 2>$null
                $allResults += @{ key=$key; success=$ok }
                Start-Sleep -Milliseconds 500
            }

            # 查最终状态
            $finalStatus = @{}
            foreach ($key in $startOrder) {
                if ($Script:Services.ContainsKey($key)) {
                    $finalStatus[$key] = Get-ServiceStatus $key
                }
            }

            $jsonObj = @{
                action     = "restart"
                results    = @()
                finalState = @{}
            }
            foreach ($r in $allResults) {
                $jsonObj.results += @{
                    key      = $r.key
                    success  = [bool]$r.success
                    message  = if ($r.success) { "已重启" } else { "重启可能有问题" }
                    status   = if ($finalStatus.ContainsKey($r.key)) { $finalStatus[$r.key].Status } else { "unknown" }
                }
            }
            foreach ($key in $finalStatus.Keys) {
                $st = $finalStatus[$key]
                $jsonObj.finalState[$key] = @{
                    status   = $st.Status
                    detail   = $st.Detail
                    pid      = $st.Pid
                    portInfo = $st.PortInfo
                }
            }
            [Console]::Out.WriteLine(($jsonObj | ConvertTo-Json -Depth 4 -Compress))
            return
        }

        # 人机模式：显示进度信息
        Show-StatusHeader
        Write-Host "→ 先停止..." -ForegroundColor Yellow
        & $PSCommandPath -Action stop @args
        Write-Host ""
        Write-Host "→ 再启动..." -ForegroundColor Yellow
        Start-Sleep -Seconds 2
        & $PSCommandPath -Action start @args
    }

    "gui" {
        # 启动 Web GUI
        $guiScript = Join-Path $PSScriptRoot "server.py"
        if (Test-Path $guiScript) {
            Write-Host "启动可视化管理界面..." -ForegroundColor Cyan
            Start-Process python -ArgumentList $guiScript -WorkingDirectory $PSScriptRoot -WindowStyle Normal
            Start-Sleep -Seconds 2
            Write-Host "界面地址: http://localhost:19988" -ForegroundColor Green
        } else {
            Write-Host "GUI 服务文件不存在: $guiScript" -ForegroundColor Red
            Write-Host "请先运行 scripts/setup_gui.ps1 生成" -ForegroundColor Yellow
        }
    }

    default {
        Write-Host "未知操作: $Action" -ForegroundColor Red
        Write-Host "可用操作: start, stop, restart, status, gui"
    }
}
