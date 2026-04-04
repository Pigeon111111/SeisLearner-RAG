#!/usr/bin/env python3
"""
MinerU API 服务包装器 - 官方在线API版 (v2.1)
============================================
调用 MinerU 官方在线 API (https://mineru.net) 进行文档解析，
不再依赖本地 Docker 容器或 GPU。

三级解析策略（按优先级）：
1. Token 精准解析 API（/api/v4/extract/task）— 需登录注册获取Token，高质量公式/表格/OCR
2. Agent 轻量 API（/api/v1/agent/parse/file）— 免登录、IP 限频
3. PyMuPDF 本地降级 — 毫秒级 PDF 解析，无网络依赖

环境变量：
- MINERU_API_TOKEN: MinerU 官方 Token（从 https://mineru.net 用户中心获取）
- MINERU_USE_LOCAL: 设为 "true" 强制使用本地 PyMuPDF 模式
- MINERU_HOST: 监听地址（默认 0.0.0.0）
- MINERU_PORT: 监听端口（默认 8000）
"""

import os
import sys
import json
import time
import tempfile
import logging
from typing import List, Optional
from fastapi import FastAPI, UploadFile, File, HTTPException, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel
import uvicorn
import httpx

# ============== 日志配置 ==============
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
logger = logging.getLogger("mineru-api")

# ============== 配置 ==============
MINERU_API_BASE = "https://mineru.net/api"
MINERU_TOKEN = os.getenv("MINERU_API_TOKEN", "")
FORCE_LOCAL = os.getenv("MINERU_USE_LOCAL", "").lower() in ("true", "1", "yes")
USE_ONLINE_API = bool(MINERU_TOKEN) and not FORCE_LOCAL

# ============== 尝试导入PyMuPDF（第三级降级）============
try:
    import fitz  # PyMuPDF
    PYMUPDF_AVAILABLE = True
    logger.info("PyMuPDF 可用 (第三级降级模式)")
except ImportError:
    PYMUPDF_AVAILABLE = False

# ============== 应用状态 ==============
app = FastAPI(
    title="MinerU API 服务 (在线版)",
    description="通过 MinerU 官方在线 API 提供文档解析服务，支持 PDF/图片/DOCX/PPTX",
    version="2.1.0"
)


# ============== 数据模型 ==============
class ParseRequest(BaseModel):
    """解析请求模型"""
    lang_list: List[str] = ["ch", "en"]
    formula_enable: bool = True
    table_enable: bool = True
    return_md: bool = True
    return_middle_json: bool = False


class ParseResponse(BaseModel):
    """解析响应模型"""
    status: str
    message: str
    markdown_content: Optional[str] = None
    middle_json: Optional[dict] = None
    error: Optional[str] = None


# ============== 核心解析函数 ==============

def parse_pdf_with_pymupdf(pdf_path: str) -> str:
    """
    第三级降级：使用 PyMuPDF(fitz) 快速解析 PDF 为 Markdown
    - 速度: 毫秒级（普通 PDF < 1 秒）
    - 无需网络、无需 GPU
    - 不支持公式识别和 OCR
    - 仅限 PDF 格式
    
    v2.2 改进：优先使用 get_text("text") 提取纯文本（更好的中文编码支持），
    仅当需要检测图片位置时才回退到 dict 模式。
    """
    doc = fitz.open(pdf_path)
    md_parts = []
    md_parts.append(f"# {os.path.basename(pdf_path)}\n")

    total_chars = 0

    for page_num, page in enumerate(doc):
        if page_num > 0:
            md_parts.append("\n---\n")
        md_parts.append(f"## 第 {page_num + 1} 页\n")

        # 策略A：优先用 get_text("text") 获取原始文本流（最佳中文兼容性）
        raw_text = page.get_text("text", flags=fitz.TEXT_PRESERVE_WHITESPACE | fitz.TEXT_MEDIABOX_CLIP)
        
        if raw_text and raw_text.strip():
            # 清理文本：去除多余空白行，保留段落结构
            lines = [l.rstrip() for l in raw_text.split("\n") if l.strip()]
            if lines:
                md_parts.append("\n".join(lines) + "\n")
                total_chars += sum(len(l) for l in lines)

        # 策略B：检测图片块位置（补充标注）
        try:
            blocks = page.get_text("dict")["blocks"]
            image_count = 0
            for block in blocks:
                if "image" in block:
                    bbox = block["bbox"]
                    # 过滤掉太小的图片（可能是图标/装饰元素）
                    w = int(bbox[2] - bbox[0])
                    h = int(bbox[3] - bbox[1])
                    if w > 50 and h > 50:
                        md_parts.append(
                            f"[图片 (位置: x={int(bbox[0])}, y={int(bbox[1])}, "
                            f"w={w}, h={h})]\n"
                        )
                        image_count += 1
        except Exception:
            pass  # 图片检测失败不影响主流程

    page_count = doc.page_count
    doc.close()

    result = "\n".join(md_parts)
    
    # 日志：报告提取质量
    logger.info(
        f"[PyMuPDF] 解析完成: {page_count}页, "
        f"有效文字约{total_chars}字符, 总输出{len(result)}字符"
    )
    
    # 如果提取的文字极少，可能是扫描件或特殊编码PDF
    if total_chars < 20:
        logger.warning(
            f"[PyMuPDF] 文字提取过少({total_chars}字符)，"
            f"该PDF可能为扫描件或使用了特殊字体编码。"
            f"建议配置 MINERU_API_TOKEN 启用OCR解析。"
        )

    return result


async def parse_with_token_api(file_path: str, filename: str,
                                formula_enable: bool = True,
                                table_enable: bool = True,
                                is_ocr: bool = False) -> dict:
    """
    第一级：调用 MinerU Token 精准解析 API (/api/v4/extract/task)

    需要用户在 mineru.net 注册并获取 Token。
    支持公式识别、表格结构化提取、OCR 等高级功能。
    每天 2000 页免费额度。

    流程：
    1. POST /api/v4/extract/task 创建任务 → 获取上传 URL + task_id
    2. PUT 上传文件到 OSS 临时 URL
    3. GET /api/v4/extract/task/{task_id} 轮询结果
    4. 返回 Markdown 内容

    Args:
        file_path: 本地文件路径
        filename: 原始文件名
        formula_enable: 是否启用公式识别
        table_enable: 是否启用表格识别
        is_ocr: 是否启用 OCR

    Returns:
        dict: 包含 status, message, markdown_content 的字典
    Raises:
        Exception: API 调用失败时抛出异常（由上层捕获后降级）
    """
    headers = {
        "Authorization": f"Bearer {MINERU_TOKEN}",
    }

    async with httpx.AsyncClient(timeout=120.0) as client:
        # === Step 1: 创建任务，获取上传 URL + task_id ===
        logger.info(f"[Token API v4] Step 1: 创建解析任务 - {filename}")
        create_resp = await client.post(
            f"{MINERU_API_BASE}/v4/extract/task",
            headers=headers,
            json={
                "filename": filename,
                "language": "ch",
                "enable_formula": formula_enable,
                "enable_table": table_enable,
                "is_ocr": is_ocr,
                "return_md": True,
            }
        )
        create_result = create_resp.json()

        if create_result.get("code") != 0:
            raise Exception(
                f"[Token API] 创建任务失败: {create_result.get('msg', 'unknown error')} "
                f"(code: {create_result.get('code')})"
            )

        task_id = create_result["data"]["task_id"]
        upload_url = create_result["data"].get("file_url", "")
        logger.info(f"[Token API v4] task_id={task_id}, upload_url={'yes' if upload_url else 'N/A'}")

        # === Step 2: 上传文件 ===
        if upload_url:
            logger.info(f"[Token API v4] Step 2: 上传文件 ({os.path.getsize(file_path)} bytes)")
            with open(file_path, "rb") as f:
                file_data = f.read()

            upload_resp = await client.put(upload_url, content=file_data)
            if upload_resp.status_code not in (200, 201):
                raise Exception(f"[Token API] 文件上传失败: HTTP {upload_resp.status_code}")

            logger.info("[Token API v4] 文件上传成功")
        else:
            logger.warning("[Token API v4] 未收到上传 URL，可能直接进入处理队列")

        # === Step 3: 轮询任务结果 ===
        logger.info(f"[Token API v4] Step 3: 轮询结果...")
        max_attempts = 60   # 最多等 2 分钟
        poll_interval = 2  # 每 2 秒查一次
        markdown_content = None
        state = ""

        for attempt in range(1, max_attempts + 1):
            await asyncio.sleep(poll_interval)

            query_resp = await client.get(
                f"{MINERU_API_BASE}/v4/extract/task/{task_id}",
                headers=headers,
            )
            query_result = query_resp.json()

            state = query_result.get("data", {}).get("state", "unknown")
            logger.info(f"[Token API v4] 轮询 #{attempt}: state={state}")

            if state == "done":
                # Token API 可能直接返回 content 或提供下载 URL
                data = query_result.get("data", {})
                markdown_content = data.get("markdown_content") or data.get("md_content", "")
                # 如果没有内容但有URL，尝试下载
                if not markdown_content:
                    md_url = data.get("markdown_url") or data.get("md_url", "")
                    if md_url:
                        dl_resp = await client.get(md_url)
                        if dl_resp.status_code == 200:
                            markdown_content = dl_resp.text
                break
            elif state == "failed":
                err_msg = query_result.get("data", {}).get("error_message", "") or \
                          query_result.get("data", {}).get("message", "未知错误")
                raise Exception(f"[Token API] 解析任务失败: {err_msg}")
            elif state == "error":
                raise Exception(f"[Token API] 解析任务出错: {query_result.get('data', {})}")

        if state != "done":
            raise Exception(
                f"[Token API] 解析超时（已等待 {max_attempts * poll_interval}s），最后状态: {state}"
            )

        if not markdown_content:
            raise Exception("[Token API] 解析完成但未获取到 Markdown 内容")

        logger.info(f"[Token API v4] 解析完成! Markdown 大小: {len(markdown_content)} chars")

        return {
            "status": "success",
            "message": f"文档解析成功(MinerU Token精准API v4)",
            "markdown_content": markdown_content,
        }


async def parse_with_agent_api(file_path: str, filename: str,
                                 formula_enable: bool = True,
                                 table_enable: bool = True,
                                 is_ocr: bool = False) -> dict:
    """
    第二级：Agent 轻量解析 API (/api/v1/agent/parse/file)

    免登录、IP 限频。无需 Token 即可使用。
    适合非 PDF 格式或 Token 未配置时的中间降级方案。

    流程与 Token API 类似但端点不同：
    1. POST /api/v1/agent/parse/file → 获取上传 URL + task_id
    2. PUT 上传文件
    3. GET /api/v1/agent/parse/{task_id} 轮询结果
    4. 下载 Markdown 结果
    """
    async with httpx.AsyncClient(timeout=120.0) as client:
        # === Step 1: 申请上传 ===
        logger.info(f"[Agent API v1] Step 1: 申请文件上传 - {filename}")
        apply_resp = await client.post(
            f"{MINERU_API_BASE}/v1/agent/parse/file",
            json={
                "file_name": filename,
                "language": "ch",
                "enable_formula": formula_enable,
                "enable_table": table_enable,
                "is_ocr": is_ocr,
                # Agent 免登录 API 限制20页，page_range 格式如 "1-10" 或 "1-20"
                "page_range": "1-20",
            }
        )
        apply_result = apply_resp.json()

        if apply_result.get("code") != 0:
            raise Exception(
                f"[Agent API] 申请上传失败: {apply_result.get('msg', 'unknown error')} "
                f"(code: {apply_result.get('code')})"
            )

        task_id = apply_result["data"]["task_id"]
        upload_url = apply_result["data"].get("file_url", "")
        logger.info(f"[Agent API v1] task_id={task_id}, upload_url={'yes' if upload_url else 'N/A'}")

        # === Step 2: 上传文件 ===
        if upload_url:
            logger.info(f"[Agent API v1] Step 2: 上传文件 ({os.path.getsize(file_path)} bytes)")
            with open(file_path, "rb") as f:
                file_data = f.read()
            upload_resp = await client.put(upload_url, content=file_data)
            if upload_resp.status_code not in (200, 201):
                raise Exception(f"[Agent API] 文件上传失败: HTTP {upload_resp.status_code}")
            logger.info("[Agent API v1] 文件上传成功")
        else:
            logger.warning("[Agent API v1] 未收到上传 URL")

        # === Step 3: 轮询结果 ===
        logger.info(f"[Agent API v1] Step 3: 轮询结果...")
        max_attempts = 60
        poll_interval = 2
        markdown_url = None
        state = ""

        for attempt in range(1, max_attempts + 1):
            await asyncio.sleep(poll_interval)

            query_resp = await client.get(
                f"{MINERU_API_BASE}/v1/agent/parse/{task_id}",
            )
            query_result = query_resp.json()

            state = query_result.get("data", {}).get("state", "unknown")
            logger.info(f"[Agent API v1] 轮询 #{attempt}: state={state}")

            if state == "done":
                markdown_url = query_result["data"].get("markdown_url", "")
                break
            elif state == "failed":
                raise Exception(f"[Agent API] 解析任务失败: {query_result.get('data', {})}")

        if state != "done":
            raise Exception(
                f"[Agent API] 解析超时（已等待 {max_attempts * poll_interval}s），最后状态: {state}"
            )

        # === Step 4: 下载结果 ===
        if not markdown_url:
            raise Exception("[Agent API] 解析完成但未获取到 Markdown URL")

        logger.info(f"[Agent API v1] Step 4: 下载结果 - {markdown_url[:80]}...")
        download_resp = await client.get(markdown_url)

        if download_resp.status_code != 200:
            raise Exception(f"[Agent API] 下载结果失败: HTTP {download_resp.status_code}")

        markdown_content = download_resp.text
        logger.info(f"[Agent API v1] 解析完成! Markdown 大小: {len(markdown_content)} chars")

        return {
            "status": "success",
            "message": f"文档解析成功(MinerU Agent免登录API)",
            "markdown_content": markdown_content,
        }


# 需要导入 asyncio 用于异步 sleep
import asyncio

# ============== 兼容旧代码的占位符 ==============
class MockParser:
    def parse(self, file_path, **kwargs):
        return f"模拟解析结果: {file_path}"

PDFParser = MockParser
ImageParser = MockParser
MarkdownParser = MockParser


def get_parser_for_mimetype(mime_type: str):
    """根据 MIME 类型获取对应的解析器（兼容旧接口）"""
    if mime_type == "application/pdf":
        return PDFParser()
    elif mime_type in ["image/png", "image/jpeg", "image/jpg", "image/tiff"]:
        return ImageParser()
    elif mime_type == "text/markdown":
        return MarkdownParser()
    else:
        raise ValueError(f"不支持的文件类型: {mime_type}")


# ============== API 端点 ==============

@app.get("/")
async def root():
    """根端点，返回服务状态和配置信息"""
    return {
        "service": "MinerU API Service (Online Edition)",
        "version": "2.1.0",
        "mode": _get_current_mode(),
        "token_set": bool(MINERU_TOKEN),
        "pymupdf_available": PYMUPDF_AVAILABLE,
        "force_local_mode": FORCE_LOCAL,
        "strategy_priority": [
            "1. Token 精准 API (v4/extract/task)" if MINERU_TOKEN else "1. [跳过] 未设Token",
            "2. Agent 免登录 API (v1/agent/parse/file)",
            "3. PyMuPDF 本地降级 (仅PDF)" if PYMUPDF_AVAILABLE else "3. [不可用] PyMuPDF未安装",
        ],
        "endpoints": {
            "POST /file_parse": "解析上传的文档文件",
            "POST /batch_parse": "批量解析多个文件",
            "GET /health": "健康检查",
        }
    }


def _get_current_mode() -> str:
    """返回当前生效的模式描述"""
    if FORCE_LOCAL:
        return "local_pymupdf_forced"
    if MINERU_TOKEN:
        return "online_token_primary"
    return "online_agent_fallback"


@app.get("/health")
async def health_check():
    """健康检查端点"""
    return {
        "status": "healthy",
        "mode": _get_current_mode(),
        "token_api_ready": bool(MINERU_TOKEN),
        "agent_api_ready": True,   # Agent API 总是可用（免登录）
        "pymupdf_fallback_ready": PYMUPDF_AVAILABLE,
    }


@app.post("/file_parse", response_model=ParseResponse)
async def parse_file(
    file: UploadFile = File(...),
    lang_list: str = Query("ch,en", description="语言列表"),
    formula_enable: bool = Query(True, description="启用公式识别"),
    table_enable: bool = Query(True, description="启用表格识别"),
    return_md: bool = Query(True, description="返回 Markdown"),
    return_middle_json: bool = Query(False, description="返回中间 JSON"),
):
    """
    解析上传的文档文件

    支持的格式：PDF, PNG, JPG, JPEG, DOCX, PPTX 等

    三级解析策略（按优先级自动选择）：
    ┌─────────────────────────────────────────────────────┐
    │  ① Token 精准 API  (需 MINERU_API_TOKEN)           │
    │     ↓ 失败                                         │
    │  ② Agent 免登录 API  (无需任何配置)               │
    │     ↓ 失败                                        │
    │  ③ PyMuPDF 本地降级  (仅 PDF，毫秒级)              │
    └─────────────────────────────────────────────────────┘
    """
    try:
        # 验证文件
        if not file or not file.filename:
            raise HTTPException(status_code=400, detail="未提供文件")

        # 保存到临时文件
        suffix = os.path.splitext(file.filename)[1] or ".bin"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp_file:
            content = await file.read()
            if len(content) == 0:
                raise HTTPException(status_code=400, detail="上传的文件为空")
            tmp_file.write(content)
            tmp_path = tmp_file.name

        try:
            result = None
            last_error = ""

            # ════════════════════════════════════════
            # 策略1: Token 精准解析 API（第一优先级）
            # ════════════════════════════════════════
            if USE_ONLINE_API:
                logger.info(f"[策略1-Token API] 尝试精准解析: {file.filename}")
                try:
                    result = await parse_with_token_api(
                        file_path=tmp_path,
                        filename=file.filename,
                        formula_enable=formula_enable,
                        table_enable=table_enable,
                        is_ocr=False,
                    )
                    logger.info(f"[策略1-Token API] ✅ 成功!")
                except httpx.TimeoutException as e:
                    last_error = f"Token API 超时: {e}"
                    logger.warning(f"[策略1-Token API] ⏰ {last_error}")
                except Exception as e:
                    last_error = f"Token API 失败: {e}"
                    logger.warning(f"[策略1-Token API] ❌ {last_error}")

            # ════════════════════════════════════════
            # 策略2: Agent 免登录 API（第二优先级）
            # ════════════════════════════════════════
            if result is None and not FORCE_LOCAL:
                logger.info(f"[策略2-Agent API] 尝试免登录解析: {file.filename}")
                try:
                    result = await parse_with_agent_api(
                        file_path=tmp_path,
                        filename=file.filename,
                        formula_enable=formula_enable,
                        table_enable=table_enable,
                        is_ocr=False,
                    )
                    logger.info(f"[策略2-Agent API] ✅ 成功!")
                except httpx.TimeoutException as e:
                    last_error = f"Agent API 超时: {e}"
                    logger.warning(f"[策略2-Agent API] ⏰ {last_error}")
                except Exception as e:
                    last_error = f"Agent API 失败: {e}"
                    logger.warning(f"[策略2-Agent API] ❌ {last_error}")

            # ════════════════════════════════════════
            # 策略3: PyMuPDF 本地降级（第三优先级，仅 PDF）
            # ════════════════════════════════════════
            if result is None and file.content_type == "application/pdf" and PYMUPDF_AVAILABLE:
                logger.info(f"[策略3-PyMuPDF] 本地降级解析: {file.filename}")
                try:
                    markdown_content = parse_pdf_with_pymupdf(tmp_path)
                    result = {
                        "status": "success",
                        "message": "PDF解析成功(PyMuPDF本地降级)",
                        "markdown_content": markdown_content,
                    }
                    logger.info(f"[策略3-PyMuPDF] ✅ 成功!")
                except Exception as e:
                    last_error = f"PyMuPDF 也失败了: {e}"
                    logger.error(f"[策略3-PyMuPDF] ❌ {last_error}")

            # ════════════════════════════════════════
            # 所有策略均失败
            # ════════════════════════════════════════
            if result is None:
                reasons = []
                if not MINERU_TOKEN and not FORCE_LOCAL:
                    reasons.append("Token未设置 → 跳过策略1")
                if FORCE_LOCAL:
                    reasons.append("强制本地模式 → 跳过在线API")
                if file.content_type != "application/pdf":
                    reasons.append(f"文件类型{file.content_type}不支持PyMuPDF(仅PDF)")
                if not PYMUPDF_AVAILABLE:
                    reasons.append("PyMuPDF未安装")
                if last_error:
                    reasons.append(f"最后错误: {last_error}")

                raise HTTPException(
                    status_code=503,
                    detail=f"所有解析方式均不可用。{'; '.join(reasons)}"
                )

            return ParseResponse(
                status=result["status"],
                message=result["message"],
                markdown_content=result.get("markdown_content") if return_md else None,
                middle_json=None,
            )

        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("解析过程异常")
        raise HTTPException(status_code=500, detail=f"解析异常: {str(e)}")


@app.post("/batch_parse")
async def batch_parse(files: List[UploadFile] = File(...)):
    """批量解析多个文件（每个文件独立走三级策略）"""
    results = []
    for file in files:
        try:
            response = await parse_file(
                file=file,
                lang_list="ch,en",
                formula_enable=True,
                table_enable=True,
                return_md=True,
                return_middle_json=False,
            )
            results.append({
                "filename": file.filename,
                "status": "success",
                "result": response.dict(),
            })
        except HTTPException as e:
            results.append({
                "filename": file.filename,
                "status": "error",
                "error": e.detail,
            })
        except Exception as e:
            results.append({
                "filename": file.filename,
                "status": "error",
                "error": str(e),
            })

    return {"results": results}


if __name__ == "__main__":
    host = os.getenv("MINERU_HOST", "0.0.0.0")
    port = int(os.getenv("MINERU_PORT", "8000"))

    print("=" * 60)
    print("  MinerU API Service v2.1 (官方在线API版)")
    print("=" * 60)
    if FORCE_LOCAL:
        mode_str = "强制本地PyMuPDF"
    elif MINERU_TOKEN:
        mode_str = "在线Token API(主) → Agent API(备) → PyMuPDF(兜底)"
    else:
        mode_str = "Agent API(主) → PyMuPDF(兜底)"
    print(f"  模式: {mode_str}")
    print(f"  Token: {'已配置' if MINERU_TOKEN else '未配置'}")
    print(f"  PyMuPDF: {'可用' if PYMUPDF_AVAILABLE else '不可用'}")
    print(f"  强制本地: {'是' if FORCE_LOCAL else '否'}")
    print(f"  监听: {host}:{port}")
    print("=" * 60)

    uvicorn.run(app, host=host, port=port, log_level="info")
