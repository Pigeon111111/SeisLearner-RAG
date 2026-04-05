#!/usr/bin/env python3
"""
MinerU API 服务包装器 - 官方在线API版 (v2.2)
============================================
调用 MinerU 官方在线 API (https://mineru.net) 进行文档解析，
不再依赖本地 Docker 容器或 GPU。

v2.2 新增：PDF图片提取功能
- 从PDF中提取图片并保存到本地
- 在Markdown中嵌入图片路径
- 支持静态文件访问

三级解析策略（按优先级）：
1. Token 精准解析 API（/api/v4/extract/task）— 需登录注册获取Token，高质量公式/表格/OCR
2. Agent 轻量 API（/api/v1/agent/parse/file）— 免登录、IP 限频
3. PyMuPDF 本地降级 — 毫秒级 PDF 解析，无网络依赖，支持图片提取

环境变量：
- MINERU_API_TOKEN: MinerU 官方 Token（从 https://mineru.net 用户中心获取）
- MINERU_USE_LOCAL: 设为 "true" 强制使用本地 PyMuPDF 模式
- MINERU_HOST: 监听地址（默认 0.0.0.0）
- MINERU_PORT: 监听端口（默认 8000）
- MINERU_IMAGE_DIR: 图片存储目录（默认 ./extracted_images）
"""

import os
import sys
import json
import time
import tempfile
import logging
import hashlib
import base64
from typing import List, Optional, Tuple
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, HTTPException, Query
from fastapi.responses import JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
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

# 图片存储目录
IMAGE_DIR = Path(os.getenv("MINERU_IMAGE_DIR", "./extracted_images"))
IMAGE_DIR.mkdir(parents=True, exist_ok=True)

# ============== 尝试导入PyMuPDF（第三级降级）============
try:
    import fitz  # PyMuPDF
    PYMUPDF_AVAILABLE = True
    logger.info("PyMuPDF 可用 (第三级降级模式，支持图片提取)")
except ImportError:
    PYMUPDF_AVAILABLE = False

# ============== 应用状态 ==============
app = FastAPI(
    title="MinerU API 服务 (在线版)",
    description="通过 MinerU 官方在线 API 提供文档解析服务，支持 PDF/图片/DOCX/PPTX，支持图片提取",
    version="2.2.0"
)

# 挂载静态文件目录，用于访问提取的图片
app.mount("/images", StaticFiles(directory=str(IMAGE_DIR)), name="images")


# ============== 数据模型 ==============
class ParseRequest(BaseModel):
    """解析请求模型"""
    lang_list: List[str] = ["ch", "en"]
    formula_enable: bool = True
    table_enable: bool = True
    return_md: bool = True
    return_middle_json: bool = False
    extract_images: bool = True  # 是否提取图片


class ParseResponse(BaseModel):
    """解析响应模型"""
    status: str
    message: str
    markdown_content: Optional[str] = None
    middle_json: Optional[dict] = None
    error: Optional[str] = None
    images: Optional[List[dict]] = None  # 提取的图片列表


# ============== 图片提取函数 ==============

def extract_images_from_pdf(pdf_path: str, doc_id: str) -> Tuple[List[dict], dict]:
    """
    从PDF中提取所有图片并保存到本地
    
    Args:
        pdf_path: PDF文件路径
        doc_id: 文档ID，用于组织图片目录
        
    Returns:
        Tuple[List[dict], dict]: (图片信息列表, 图片路径映射 {page_num: [image_paths]})
    """
    doc = fitz.open(pdf_path)
    images_info = []
    image_map = {}  # page_num -> [image_paths]
    
    # 为该文档创建独立目录
    doc_image_dir = IMAGE_DIR / doc_id
    doc_image_dir.mkdir(parents=True, exist_ok=True)
    
    for page_num in range(doc.page_count):
        page = doc[page_num]
        image_list = page.get_images(full=True)
        page_images = []
        
        for img_index, img in enumerate(image_list):
            try:
                xref = img[0]
                base_image = doc.extract_image(xref)
                
                if base_image is None:
                    continue
                    
                image_bytes = base_image["image"]
                image_ext = base_image["ext"]
                
                # 过滤太小的图片（可能是图标、装饰元素）
                if len(image_bytes) < 1024:  # 小于1KB
                    continue
                
                # 生成唯一文件名
                image_hash = hashlib.md5(image_bytes).hexdigest()[:12]
                image_filename = f"page{page_num + 1}_img{img_index + 1}_{image_hash}.{image_ext}"
                image_path = doc_image_dir / image_filename
                
                # 保存图片
                with open(image_path, "wb") as f:
                    f.write(image_bytes)
                
                # 记录图片信息
                image_url = f"/images/{doc_id}/{image_filename}"
                image_info = {
                    "filename": image_filename,
                    "url": image_url,
                    "page": page_num + 1,
                    "index": img_index + 1,
                    "size": len(image_bytes),
                    "ext": image_ext,
                }
                images_info.append(image_info)
                page_images.append(image_url)
                
                logger.debug(f"提取图片: page={page_num + 1}, index={img_index + 1}, size={len(image_bytes)}")
                
            except Exception as e:
                logger.warning(f"提取图片失败: page={page_num + 1}, index={img_index + 1}, error={e}")
                continue
        
        if page_images:
            image_map[page_num + 1] = page_images
    
    doc.close()
    
    logger.info(f"从PDF提取图片完成: doc_id={doc_id}, 总图片数={len(images_info)}")
    return images_info, image_map


def parse_pdf_with_pymupdf(pdf_path: str, doc_id: str = None, extract_images: bool = True) -> dict:
    """
    第三级降级：使用 PyMuPDF(fitz) 快速解析 PDF 为 Markdown
    - 速度: 毫秒级（普通 PDF < 1 秒）
    - 无需网络、无需 GPU
    - 支持图片提取
    - 不支持公式识别和 OCR
    
    v2.2 改进：
    - 提取图片并保存到本地
    - 在Markdown中嵌入图片链接
    """
    if doc_id is None:
        doc_id = hashlib.md5(pdf_path.encode()).hexdigest()[:12]
    
    doc = fitz.open(pdf_path)
    md_parts = []
    md_parts.append(f"# {os.path.basename(pdf_path)}\n")

    total_chars = 0
    images_info = []
    image_map = {}
    
    # 提取图片
    if extract_images:
        try:
            images_info, image_map = extract_images_from_pdf(pdf_path, doc_id)
        except Exception as e:
            logger.warning(f"图片提取失败: {e}")

    for page_num, page in enumerate(doc):
        page_number = page_num + 1
        
        if page_num > 0:
            md_parts.append("\n---\n")
        md_parts.append(f"## 第 {page_number} 页\n")

        # 提取文本
        raw_text = page.get_text("text", flags=fitz.TEXT_PRESERVE_WHITESPACE | fitz.TEXT_MEDIABOX_CLIP)
        
        if raw_text and raw_text.strip():
            lines = [l.rstrip() for l in raw_text.split("\n") if l.strip()]
            if lines:
                md_parts.append("\n".join(lines) + "\n")
                total_chars += sum(len(l) for l in lines)

        # 添加该页的图片
        if page_number in image_map:
            md_parts.append("\n### 本页图片\n")
            for img_url in image_map[page_number]:
                md_parts.append(f"\n![图片]({img_url})\n")

    page_count = doc.page_count
    doc.close()

    result = "\n".join(md_parts)
    
    logger.info(
        f"[PyMuPDF] 解析完成: {page_count}页, "
        f"有效文字约{total_chars}字符, 提取图片{len(images_info)}张, 总输出{len(result)}字符"
    )
    
    if total_chars < 20:
        logger.warning(
            f"[PyMuPDF] 文字提取过少({total_chars}字符)，"
            f"该PDF可能为扫描件或使用了特殊字体编码。"
            f"建议配置 MINERU_API_TOKEN 启用OCR解析。"
        )

    return {
        "markdown_content": result,
        "images": images_info,
    }


async def parse_with_token_api(file_path: str, filename: str,
                                formula_enable: bool = True,
                                table_enable: bool = True,
                                is_ocr: bool = False) -> dict:
    """
    第一级：调用 MinerU Token 精准解析 API (/api/v4/extract/task)
    """
    headers = {
        "Authorization": f"Bearer {MINERU_TOKEN}",
    }

    async with httpx.AsyncClient(timeout=120.0) as client:
        # Step 1: 创建任务
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
        logger.info(f"[Token API v4] task_id={task_id}")

        # Step 2: 上传文件
        if upload_url:
            logger.info(f"[Token API v4] Step 2: 上传文件")
            with open(file_path, "rb") as f:
                file_data = f.read()
            upload_resp = await client.put(upload_url, content=file_data)
            if upload_resp.status_code not in (200, 201):
                raise Exception(f"[Token API] 文件上传失败: HTTP {upload_resp.status_code}")

        # Step 3: 轮询结果
        logger.info(f"[Token API v4] Step 3: 轮询结果...")
        max_attempts = 60
        poll_interval = 2
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
                data = query_result.get("data", {})
                markdown_content = data.get("markdown_content") or data.get("md_content", "")
                if not markdown_content:
                    md_url = data.get("markdown_url") or data.get("md_url", "")
                    if md_url:
                        dl_resp = await client.get(md_url)
                        if dl_resp.status_code == 200:
                            markdown_content = dl_resp.text
                break
            elif state in ("failed", "error"):
                err_msg = query_result.get("data", {}).get("error_message", "") or \
                          query_result.get("data", {}).get("message", "未知错误")
                raise Exception(f"[Token API] 解析任务失败: {err_msg}")

        if state != "done":
            raise Exception(f"[Token API] 解析超时，最后状态: {state}")

        if not markdown_content:
            raise Exception("[Token API] 解析完成但未获取到 Markdown 内容")

        logger.info(f"[Token API v4] 解析完成! Markdown 大小: {len(markdown_content)} chars")

        return {
            "status": "success",
            "message": f"文档解析成功(MinerU Token精准API v4)",
            "markdown_content": markdown_content,
            "images": [],  # Token API 不返回图片，需要本地提取
        }


async def parse_with_agent_api(file_path: str, filename: str,
                                 formula_enable: bool = True,
                                 table_enable: bool = True,
                                 is_ocr: bool = False) -> dict:
    """
    第二级：Agent 轻量解析 API (/api/v1/agent/parse/file)
    """
    async with httpx.AsyncClient(timeout=120.0) as client:
        # Step 1: 申请上传
        logger.info(f"[Agent API v1] Step 1: 申请文件上传 - {filename}")
        apply_resp = await client.post(
            f"{MINERU_API_BASE}/v1/agent/parse/file",
            json={
                "file_name": filename,
                "language": "ch",
                "enable_formula": formula_enable,
                "enable_table": table_enable,
                "is_ocr": is_ocr,
                "page_range": "1-20",
            }
        )
        apply_result = apply_resp.json()

        if apply_result.get("code") != 0:
            raise Exception(f"[Agent API] 申请上传失败: {apply_result.get('msg', 'unknown error')}")

        task_id = apply_result["data"]["task_id"]
        upload_url = apply_result["data"].get("file_url", "")
        logger.info(f"[Agent API v1] task_id={task_id}")

        # Step 2: 上传文件
        if upload_url:
            logger.info(f"[Agent API v1] Step 2: 上传文件")
            with open(file_path, "rb") as f:
                file_data = f.read()
            upload_resp = await client.put(upload_url, content=file_data)
            if upload_resp.status_code not in (200, 201):
                raise Exception(f"[Agent API] 文件上传失败")

        # Step 3: 轮询结果
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
                raise Exception(f"[Agent API] 解析任务失败")

        if state != "done":
            raise Exception(f"[Agent API] 解析超时，最后状态: {state}")

        # Step 4: 下载结果
        if not markdown_url:
            raise Exception("[Agent API] 未获取到 Markdown URL")

        logger.info(f"[Agent API v1] Step 4: 下载结果")
        download_resp = await client.get(markdown_url)
        if download_resp.status_code != 200:
            raise Exception(f"[Agent API] 下载结果失败")

        markdown_content = download_resp.text
        logger.info(f"[Agent API v1] 解析完成! Markdown 大小: {len(markdown_content)} chars")

        return {
            "status": "success",
            "message": f"文档解析成功(MinerU Agent免登录API)",
            "markdown_content": markdown_content,
            "images": [],
        }


import asyncio


# ============== API 端点 ==============

@app.get("/")
async def root():
    """根端点，返回服务状态和配置信息"""
    return {
        "service": "MinerU API Service (Online Edition)",
        "version": "2.2.0",
        "mode": _get_current_mode(),
        "token_set": bool(MINERU_TOKEN),
        "pymupdf_available": PYMUPDF_AVAILABLE,
        "force_local_mode": FORCE_LOCAL,
        "image_dir": str(IMAGE_DIR),
        "strategy_priority": [
            "1. Token 精准 API (v4/extract/task)" if MINERU_TOKEN else "1. [跳过] 未设Token",
            "2. Agent 免登录 API (v1/agent/parse/file)",
            "3. PyMuPDF 本地降级 (支持图片提取)" if PYMUPDF_AVAILABLE else "3. [不可用] PyMuPDF未安装",
        ],
        "endpoints": {
            "POST /file_parse": "解析上传的文档文件",
            "POST /batch_parse": "批量解析多个文件",
            "GET /images/{doc_id}/{filename}": "访问提取的图片",
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
        "agent_api_ready": True,
        "pymupdf_fallback_ready": PYMUPDF_AVAILABLE,
        "image_extraction_ready": PYMUPDF_AVAILABLE,
    }


@app.post("/file_parse", response_model=ParseResponse)
async def parse_file(
    file: UploadFile = File(...),
    lang_list: str = Query("ch,en", description="语言列表"),
    formula_enable: bool = Query(True, description="启用公式识别"),
    table_enable: bool = Query(True, description="启用表格识别"),
    return_md: bool = Query(True, description="返回 Markdown"),
    return_middle_json: bool = Query(False, description="返回中间 JSON"),
    extract_images: bool = Query(True, description="提取图片"),
    doc_id: str = Query(None, description="文档ID（可选，用于组织图片目录）"),
):
    """
    解析上传的文档文件

    支持的格式：PDF, PNG, JPG, JPEG, DOCX, PPTX 等

    v2.2 新增：
    - extract_images: 是否从PDF中提取图片
    - 返回的Markdown中会包含图片链接
    - 图片可通过 /images/{doc_id}/{filename} 访问
    """
    try:
        if not file or not file.filename:
            raise HTTPException(status_code=400, detail="未提供文件")

        # 生成文档ID
        if doc_id is None:
            import time
            doc_id = hashlib.md5(f"{file.filename}{time.time()}".encode()).hexdigest()[:12]

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

            # 策略1: Token 精准解析 API
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
                except Exception as e:
                    last_error = f"Token API 失败: {e}"
                    logger.warning(f"[策略1-Token API] ❌ {last_error}")

            # 策略2: Agent 免登录 API
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
                except Exception as e:
                    last_error = f"Agent API 失败: {e}"
                    logger.warning(f"[策略2-Agent API] ❌ {last_error}")

            # 策略3: PyMuPDF 本地降级（支持图片提取）
            if result is None and file.content_type == "application/pdf" and PYMUPDF_AVAILABLE:
                logger.info(f"[策略3-PyMuPDF] 本地降级解析: {file.filename}")
                try:
                    pymupdf_result = parse_pdf_with_pymupdf(tmp_path, doc_id, extract_images)
                    result = {
                        "status": "success",
                        "message": "PDF解析成功(PyMuPDF本地降级，含图片提取)",
                        "markdown_content": pymupdf_result["markdown_content"],
                        "images": pymupdf_result["images"],
                    }
                    logger.info(f"[策略3-PyMuPDF] ✅ 成功! 提取图片{len(pymupdf_result['images'])}张")
                except Exception as e:
                    last_error = f"PyMuPDF 也失败了: {e}"
                    logger.error(f"[策略3-PyMuPDF] ❌ {last_error}")

            # 所有策略均失败
            if result is None:
                raise HTTPException(
                    status_code=503,
                    detail=f"所有解析方式均不可用。最后错误: {last_error}"
                )

            return ParseResponse(
                status=result["status"],
                message=result["message"],
                markdown_content=result.get("markdown_content") if return_md else None,
                middle_json=None,
                images=result.get("images"),
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
    """批量解析多个文件"""
    results = []
    for file in files:
        try:
            response = await parse_file(file=file)
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
    print("  MinerU API Service v2.2 (官方在线API版 + 图片提取)")
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
    print(f"  图片目录: {IMAGE_DIR}")
    print(f"  监听: {host}:{port}")
    print("=" * 60)

    uvicorn.run(app, host=host, port=port)
