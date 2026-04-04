#!/usr/bin/env python3
"""
RAGAs 评估服务
提供RESTful API接口，用于评估RAG系统的性能
"""

import os
import sys
import json
import yaml
import asyncio
from typing import List, Dict, Any, Optional
from datetime import datetime
from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
import pandas as pd
from ragas import evaluate
from ragas.metrics.collections import (
    ContextPrecision,
    ContextRecall,
    Faithfulness,
    AnswerRelevancy,
    AnswerCorrectness,
    SemanticSimilarity,
    ContextRelevance,
    ContextEntityRecall,
    ContextPrecisionWithReference,
    ContextPrecisionWithoutReference
)
# from ragas.metrics.critique import harmfulness  # 该模块在RAGAs 0.4.3中不存在
import uvicorn
import logging

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="RAGAs 评估服务",
    description="基于RAGAs框架的RAG系统评估服务",
    version="1.0.0"
)

# 加载配置
config_path = os.path.join(os.path.dirname(__file__), "evaluation", "evaluation_config.yaml")
try:
    with open(config_path, 'r', encoding='utf-8') as f:
        config = yaml.safe_load(f)
    logger.info(f"加载评估配置: {config_path}")
except FileNotFoundError:
    logger.warning(f"配置文件未找到: {config_path}")
    config = {
        "dataset": {
            "questions": "evaluation/questions.json",
            "knowledge_base": "evaluation/seismic_exploration_knowledge.md"
        },
        "evaluation": {
            "metrics": [
                "context_precision",
                "context_recall", 
                "faithfulness",
                "answer_relevancy",
                "answer_correctness",
                "answer_similarity",
                "context_relevancy",
                "context_coverage"
            ],
            "similarity_threshold": 0.7
        }
    }

class EvaluationRequest(BaseModel):
    """评估请求模型"""
    questions: List[str] = Field(..., description="问题列表")
    ground_truths: List[str] = Field(..., description="参考答案列表")
    contexts: List[List[str]] = Field(..., description="检索到的上下文列表，每个问题对应一个上下文列表")
    answers: List[str] = Field(..., description="RAG系统生成的答案列表")
    metrics: Optional[List[str]] = Field(default=None, description="要计算的指标列表，为空则使用配置中的默认指标")

class EvaluationResponse(BaseModel):
    """评估响应模型"""
    status: str
    message: str
    scores: Dict[str, float] = Field(..., description="各项指标的得分")
    details: Dict[str, Any] = Field(default_factory=dict, description="详细评估结果")
    timestamp: str

class BatchEvaluationRequest(BaseModel):
    """批量评估请求模型"""
    evaluations: List[EvaluationRequest]
    batch_id: Optional[str] = Field(default=None, description="批次ID，用于跟踪")

class BatchEvaluationResponse(BaseModel):
    """批量评估响应模型"""
    status: str
    message: str
    batch_id: str
    results: List[EvaluationResponse]
    summary: Dict[str, float] = Field(..., description="批次评估的汇总统计")

@app.get("/")
async def root():
    """根端点，返回服务状态"""
    return {
        "service": "RAGAs Evaluation API",
        "version": "1.0.0",
        "available_metrics": get_available_metrics(),
        "endpoints": {
            "POST /evaluate": "评估单个RAG查询",
            "POST /batch_evaluate": "批量评估多个RAG查询",
            "GET /metrics": "获取可用指标列表",
            "GET /health": "健康检查"
        }
    }

@app.get("/health")
async def health_check():
    """健康检查端点"""
    return {"status": "healthy", "timestamp": datetime.now().isoformat()}

@app.get("/metrics")
async def get_metrics():
    """获取可用指标列表"""
    return {
        "available_metrics": get_available_metrics(),
        "default_metrics": config.get("evaluation", {}).get("metrics", [])
    }

@app.post("/evaluate", response_model=EvaluationResponse)
async def evaluate_rag(request: EvaluationRequest):
    """
    评估单个RAG查询
    """
    try:
        logger.info(f"开始评估，问题数量: {len(request.questions)}")
        
        # 确定要使用的指标
        metrics_to_use = request.metrics or config.get("evaluation", {}).get("metrics", [])
        metrics_objects = get_metrics_objects(metrics_to_use)
        
        # 准备数据集
        dataset_dict = {
            "question": request.questions,
            "answer": request.answers,
            "contexts": request.contexts,
            "ground_truth": request.ground_truths
        }
        
        # 转换为pandas DataFrame（RAGAs需要的格式）
        dataset = pd.DataFrame(dataset_dict)
        
        # 执行评估
        logger.info(f"使用指标进行评估: {metrics_to_use}")
        result = evaluate(
            dataset=dataset,
            metrics=metrics_objects,
            raise_exceptions=False
        )
        
        # 提取得分
        scores = {}
        for metric in metrics_to_use:
            if metric in result:
                scores[metric] = float(result[metric].mean())
            else:
                scores[metric] = 0.0
        
        # 构建响应
        response = EvaluationResponse(
            status="success",
            message="评估完成",
            scores=scores,
            details={
                "total_questions": len(request.questions),
                "metrics_used": metrics_to_use,
                "raw_result": result.to_dict() if hasattr(result, 'to_dict') else {}
            },
            timestamp=datetime.now().isoformat()
        )
        
        logger.info(f"评估完成，平均得分: {scores}")
        return response
        
    except Exception as e:
        logger.error(f"评估失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"评估失败: {str(e)}")

@app.post("/batch_evaluate", response_model=BatchEvaluationResponse)
async def batch_evaluate(request: BatchEvaluationRequest, background_tasks: BackgroundTasks):
    """
    批量评估多个RAG查询
    """
    try:
        batch_id = request.batch_id or f"batch_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
        results = []
        
        logger.info(f"开始批量评估，批次ID: {batch_id}, 评估数量: {len(request.evaluations)}")
        
        # 依次评估每个请求
        for i, eval_request in enumerate(request.evaluations):
            try:
                # 复用单个评估逻辑
                eval_response = await evaluate_rag(eval_request)
                results.append(eval_response)
                logger.debug(f"批次 {batch_id} 第 {i+1} 个评估完成")
            except Exception as e:
                logger.error(f"批次 {batch_id} 第 {i+1} 个评估失败: {str(e)}")
                # 添加失败记录
                results.append(EvaluationResponse(
                    status="error",
                    message=f"评估失败: {str(e)}",
                    scores={},
                    details={"error": str(e)},
                    timestamp=datetime.now().isoformat()
                ))
        
        # 计算汇总统计
        summary = {}
        if results and any(r.status == "success" for r in results):
            successful_results = [r for r in results if r.status == "success"]
            if successful_results:
                # 计算每个指标的平均值
                all_metrics = set()
                for r in successful_results:
                    all_metrics.update(r.scores.keys())
                
                for metric in all_metrics:
                    metric_scores = [r.scores.get(metric, 0) for r in successful_results if metric in r.scores]
                    if metric_scores:
                        summary[f"{metric}_mean"] = sum(metric_scores) / len(metric_scores)
                        summary[f"{metric}_min"] = min(metric_scores)
                        summary[f"{metric}_max"] = max(metric_scores)
        
        response = BatchEvaluationResponse(
            status="success",
            message=f"批量评估完成，成功{len([r for r in results if r.status == 'success'])}个，失败{len([r for r in results if r.status == 'error'])}个",
            batch_id=batch_id,
            results=results,
            summary=summary
        )
        
        logger.info(f"批量评估完成，批次ID: {batch_id}")
        return response
        
    except Exception as e:
        logger.error(f"批量评估失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"批量评估失败: {str(e)}")

@app.post("/evaluate_from_config")
async def evaluate_from_config():
    """
    根据配置文件中的数据集进行评估（用于定期自动评估）
    """
    try:
        # 从配置文件加载数据集
        dataset_config = config.get("dataset", {})
        questions_path = dataset_config.get("questions")
        knowledge_base_path = dataset_config.get("knowledge_base")
        
        if not questions_path or not knowledge_base_path:
            raise HTTPException(status_code=400, detail="配置文件中缺少数据集路径")
        
        # 加载问题数据集
        questions_full_path = os.path.join(os.path.dirname(__file__), questions_path)
        with open(questions_full_path, 'r', encoding='utf-8') as f:
            questions_data = json.load(f)
        
        # 这里需要实际调用RAG系统获取答案和上下文
        # 目前返回占位符
        # TODO: 集成实际的RAG系统调用
        
        return {
            "status": "info",
            "message": "此功能需要集成实际的RAG系统，当前仅返回配置信息",
            "config": {
                "questions_file": questions_path,
                "knowledge_base_file": knowledge_base_path,
                "question_count": len(questions_data) if isinstance(questions_data, list) else 0
            }
        }
        
    except Exception as e:
        logger.error(f"从配置评估失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"从配置评估失败: {str(e)}")

def get_available_metrics() -> Dict[str, str]:
    """获取可用指标及其描述"""
    return {
        "context_precision": "上下文精确率 - 检索到的上下文中与问题相关的比例",
        "context_recall": "上下文召回率 - 检索到的上下文覆盖所有相关知识点的比例",
        "faithfulness": "忠实度 - 答案是否忠实于提供的上下文，没有添加额外信息",
        "answer_relevancy": "答案相关性 - 答案与问题的相关程度",
        "answer_correctness": "答案正确性 - 答案与参考答案的匹配程度",
        "answer_similarity": "答案语义相似度 - 答案与参考答案的语义相似度",
        "context_relevancy": "上下文相关性 - 检索到的上下文与问题的相关程度",
        "context_coverage": "上下文覆盖率 - 检索到的上下文覆盖问题所有方面的程度",
        "context_entity_recall": "上下文实体召回率 - 检索到的上下文中包含的实体比例",
        "context_entity_precision": "上下文实体精确率 - 检索到的上下文中相关实体的比例"
        # "harmfulness": "有害性 - 答案是否包含有害内容"  # 该模块在RAGAs 0.4.3中不存在
    }

def get_metrics_objects(metric_names: List[str]) -> List:
    """根据指标名称获取RAGAs指标对象"""
    metric_map = {
        "context_precision": ContextPrecision,
        "context_recall": ContextRecall,
        "faithfulness": Faithfulness,
        "answer_relevancy": AnswerRelevancy,
        "answer_correctness": AnswerCorrectness,
        "answer_similarity": SemanticSimilarity,
        "context_relevancy": ContextRelevance,
        "context_coverage": ContextRecall,  # ContextCoverage不存在，用ContextRecall替代
        "context_entity_recall": ContextEntityRecall,
        "context_entity_precision": ContextPrecisionWithReference  # 近似替代
        # "harmfulness": harmfulness  # 该模块在RAGAs 0.4.3中不存在
    }
    
    metrics = []
    for name in metric_names:
        if name in metric_map:
            metrics.append(metric_map[name])
        else:
            logger.warning(f"未知指标: {name}，已跳过")
    
    if not metrics:
        # 使用默认指标
        default_metrics = config.get("evaluation", {}).get("metrics", ["answer_correctness", "faithfulness"])
        metrics = [metric_map.get(m) for m in default_metrics if m in metric_map]
    
    return metrics

if __name__ == "__main__":
    # 启动服务
    host = os.getenv("RAGAS_HOST", "0.0.0.0")
    port = int(os.getenv("RAGAS_PORT", "8001"))
    
    logger.info(f"启动 RAGAs 评估服务在 {host}:{port}")
    logger.info(f"可用指标: {list(get_available_metrics().keys())}")
    
    uvicorn.run(
        app,
        host=host,
        port=port,
        log_level="info"
    )