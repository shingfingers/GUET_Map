# GUET_Map ngrok 部署脚本
# 使用前请确保已下载 ngrok.exe 到 C:\ngrok\

Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "  GUET_Map ngrok 部署工具" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

# 检查ngrok是否已安装
if (-not (Test-Path "C:\ngrok\ngrok.exe")) {
    Write-Host "❌ 错误: ngrok未安装" -ForegroundColor Red
    Write-Host ""
    Write-Host "请先下载ngrok:" -ForegroundColor Yellow
    Write-Host "  1. 访问: https://ngrok.com/download" -ForegroundColor White
    Write-Host "  2. 下载 Windows 版本" -ForegroundColor White
    Write-Host "  3. 解压到: C:\ngrok\" -ForegroundColor White
    Write-Host ""
    pause
    exit
}

Write-Host "✅ ngrok已安装" -ForegroundColor Green

# 配置authtoken
Write-Host ""
Write-Host "📝 配置ngrok authtoken..." -ForegroundColor Yellow
C:\ngrok\ngrok.exe config add-authtoken 3FYwTsuWQ0HLuKDPg8SG3ha2fqE_7xHLZdHqFzLY8UVuT7D8

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ authtoken配置成功" -ForegroundColor Green
} else {
    Write-Host "❌ authtoken配置失败" -ForegroundColor Red
    pause
    exit
}

# 启动后端
Write-Host ""
Write-Host "🚀 启动后端服务..." -ForegroundColor Yellow
Write-Host "   (在新窗口中运行)" -ForegroundColor Gray

Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd C:\Users\33678\Desktop\GUET_Map\backend; .\gradlew run"

Write-Host ""
Write-Host "⏳ 等待后端启动（15秒）..." -ForegroundColor Yellow
Start-Sleep -Seconds 15

# 启动ngrok
Write-Host ""
Write-Host "🌐 启动ngrok内网穿透..." -ForegroundColor Yellow
Write-Host "   (在新窗口中运行)" -ForegroundColor Gray

Start-Process powershell -ArgumentList "-NoExit", "-Command", "C:\ngrok\ngrok.exe http 8080"

Write-Host ""
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "  部署步骤完成！" -ForegroundColor Green
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "📋 下一步操作：" -ForegroundColor Yellow
Write-Host "  1. 等待后端服务完全启动（看到 'Application started'）" -ForegroundColor White
Write-Host "  2. 查看ngrok窗口中显示的 HTTPS URL" -ForegroundColor White
Write-Host "  3. 将URL复制并发给AI助手，格式类似：" -ForegroundColor White
Write-Host "     https://xxxx-xx-xx-xxx-xxx.ngrok-free.dev" -ForegroundColor Cyan
Write-Host ""
Write-Host "按任意键关闭此窗口..." -ForegroundColor Gray
pause
