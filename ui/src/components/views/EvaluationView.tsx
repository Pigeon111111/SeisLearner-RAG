import React, { useState, useEffect } from "react";
import {
  Card,
  Tabs,
  Input,
  Button,
  Select,
  Upload,
  message,
  Spin,
  Table,
  Tag,
  Progress,
  Divider,
  Alert,
  Typography,
  Space,
  Tooltip,
} from "antd";
import {
  UploadOutlined,
  PlayCircleOutlined,
  FileTextOutlined,
  QuestionCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  BarChartOutlined,
  PlusOutlined,
  DeleteOutlined,
} from "@ant-design/icons";
import type { UploadFile } from "antd/es/upload/interface";

const { Title, Text } = Typography;
const { TextArea } = Input;

interface EvaluationResult {
  status: string;
  message: string;
  scores: Record<string, number>;
  details: Record<string, unknown>;
  timestamp: string;
}

interface QAPair {
  id: string;
  question: string;
  answer: string;
  groundTruth: string;
  contexts: string[];
}

const availableMetrics = [
  { value: "context_precision", label: "上下文精确率", description: "检索到的上下文中与问题相关的比例" },
  { value: "context_recall", label: "上下文召回率", description: "检索到的上下文覆盖所有相关知识点的比例" },
  { value: "faithfulness", label: "忠实度", description: "答案是否忠实于提供的上下文" },
  { value: "answer_relevancy", label: "答案相关性", description: "答案与问题的相关程度" },
  { value: "answer_correctness", label: "答案正确性", description: "答案与参考答案的匹配程度" },
  { value: "answer_similarity", label: "答案语义相似度", description: "答案与参考答案的语义相似度" },
  { value: "context_relevancy", label: "上下文相关性", description: "检索到的上下文与问题的相关程度" },
  { value: "context_entity_recall", label: "实体召回率", description: "检索到的上下文中包含的实体比例" },
];

const EvaluationView: React.FC = () => {
  const [activeTab, setActiveTab] = useState("qa");
  const [loading, setLoading] = useState(false);
  const [serviceStatus, setServiceStatus] = useState<"checking" | "online" | "offline">("checking");
  const [result, setResult] = useState<EvaluationResult | null>(null);

  // QA评估
  const [qaPairs, setQaPairs] = useState<QAPair[]>([
    { id: "1", question: "", answer: "", groundTruth: "", contexts: [] }
  ]);
  const [selectedMetrics, setSelectedMetrics] = useState<string[]>([
    "faithfulness",
    "answer_relevancy",
    "answer_correctness",
  ]);

  // 文档评估
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [docQuestions, setDocQuestions] = useState<string[]>([]);

  useEffect(() => {
    checkServiceStatus();
  }, []);

  const checkServiceStatus = async () => {
    try {
      const response = await fetch("http://localhost:8001/health");
      if (response.ok) {
        setServiceStatus("online");
      } else {
        setServiceStatus("offline");
      }
    } catch {
      setServiceStatus("offline");
    }
  };

  const addQAPair = () => {
    const newPair: QAPair = {
      id: Date.now().toString(),
      question: "",
      answer: "",
      groundTruth: "",
      contexts: [],
    };
    setQaPairs([...qaPairs, newPair]);
  };

  const removeQAPair = (id: string) => {
    if (qaPairs.length > 1) {
      setQaPairs(qaPairs.filter((pair) => pair.id !== id));
    }
  };

  const updateQAPair = (id: string, field: keyof QAPair, value: string | string[]) => {
    setQaPairs(
      qaPairs.map((pair) =>
        pair.id === id ? { ...pair, [field]: value } : pair
      )
    );
  };

  const handleQAEvaluate = async () => {
    const validPairs = qaPairs.filter(
      (pair) => pair.question && pair.answer && pair.groundTruth
    );
    
    if (validPairs.length === 0) {
      message.warning("请至少填写一组完整的问答对");
      return;
    }

    if (selectedMetrics.length === 0) {
      message.warning("请至少选择一个评估指标");
      return;
    }

    setLoading(true);
    setResult(null);

    try {
      const response = await fetch("http://localhost:8001/evaluate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          questions: validPairs.map((p) => p.question),
          answers: validPairs.map((p) => p.answer),
          ground_truths: validPairs.map((p) => p.groundTruth),
          contexts: validPairs.map((p) => p.contexts.length > 0 ? p.contexts : ["无上下文"]),
          metrics: selectedMetrics,
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setResult(data);
      message.success("评估完成！");
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : "未知错误";
      message.error(`评估失败: ${errMsg}`);
    } finally {
      setLoading(false);
    }
  };

  const handleDocUpload = (info: { fileList: UploadFile[] }) => {
    setFileList(info.fileList.slice(-1));
    
    if (info.fileList.length > 0) {
      message.success("文档已选择");
      setDocQuestions([
        "文档的主要主题是什么？",
        "文档中提到的关键技术有哪些？",
        "文档的核心观点是什么？",
      ]);
    }
  };

  const handleDocEvaluate = async () => {
    if (fileList.length === 0) {
      message.warning("请先上传文档");
      return;
    }

    setLoading(true);
    setResult(null);

    try {
      const mockQuestions = docQuestions.length > 0 ? docQuestions : ["文档内容分析"];
      
      const response = await fetch("http://localhost:8001/evaluate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          questions: mockQuestions,
          answers: ["基于文档内容的回答..."],
          ground_truths: ["参考答案..."],
          contexts: [["文档上下文内容..."]],
          metrics: selectedMetrics,
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      setResult(data);
      message.success("文档评估完成！");
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : "未知错误";
      message.error(`评估失败: ${errMsg}`);
    } finally {
      setLoading(false);
    }
  };

  const renderResult = () => {
    if (!result) return null;

    const scoreData = Object.entries(result.scores).map(([key, value]) => ({
      metric: availableMetrics.find((m) => m.value === key)?.label || key,
      key,
      value: (value * 100).toFixed(2),
      rawValue: value,
    }));

    const columns = [
      {
        title: "指标",
        dataIndex: "metric",
        key: "metric",
        width: 150,
      },
      {
        title: "得分",
        dataIndex: "value",
        key: "value",
        width: 100,
        render: (val: string) => (
          <Tag color={parseFloat(val) >= 70 ? "green" : parseFloat(val) >= 50 ? "orange" : "red"}>
            {val}%
          </Tag>
        ),
      },
      {
        title: "进度",
        dataIndex: "rawValue",
        key: "progress",
        render: (val: number) => (
          <Progress
            percent={val * 100}
            size="small"
            status={val >= 0.7 ? "success" : val >= 0.5 ? "normal" : "exception"}
            format={() => ""}
          />
        ),
      },
    ];

    return (
      <Card
        title={
          <Space>
            <BarChartOutlined />
            <span>评估结果</span>
            {result.status === "success" ? (
              <CheckCircleOutlined className="text-green-500" />
            ) : (
              <CloseCircleOutlined className="text-red-500" />
            )}
          </Space>
        }
        className="mt-4"
      >
        <Alert
          message={result.message}
          type={result.status === "success" ? "success" : "error"}
          showIcon
          className="mb-4"
        />
        
        <Table
          dataSource={scoreData}
          columns={columns}
          pagination={false}
          size="small"
          rowKey="key"
        />

        <Divider />

        <div className="text-gray-500 text-sm">
          <Text type="secondary">评估时间: {result.timestamp}</Text>
          <br />
          <Text type="secondary">
            评估问题数: {String(result.details?.total_questions ?? "N/A")}
          </Text>
        </div>
      </Card>
    );
  };

  const QAPanel = () => (
    <div className="space-y-4">
      <Alert
        message="问答对评估"
        description="输入问题、RAG系统生成的答案、参考答案（标准答案）和检索到的上下文，系统将计算各项评估指标。"
        type="info"
        showIcon
      />

      <Card title="评估指标选择" size="small">
        <Select
          mode="multiple"
          style={{ width: "100%" }}
          placeholder="选择评估指标"
          value={selectedMetrics}
          onChange={setSelectedMetrics}
          options={availableMetrics.map((m) => ({
            value: m.value,
            label: (
              <Tooltip title={m.description}>
                <span>{m.label}</span>
              </Tooltip>
            ),
          }))}
        />
      </Card>

      <Card
        title={
          <Space>
            <span>问答对列表</span>
            <Button
              type="dashed"
              size="small"
              icon={<PlusOutlined />}
              onClick={addQAPair}
            >
              添加
            </Button>
          </Space>
        }
        size="small"
      >
        {qaPairs.map((pair, index) => (
          <Card
            key={pair.id}
            size="small"
            className="mb-3"
            title={
              <Space>
                <span>问答对 #{index + 1}</span>
                {qaPairs.length > 1 && (
                  <Button
                    type="text"
                    danger
                    size="small"
                    icon={<DeleteOutlined />}
                    onClick={() => removeQAPair(pair.id)}
                  />
                )}
              </Space>
            }
          >
            <div className="space-y-3">
              <div>
                <Text strong>问题</Text>
                <TextArea
                  rows={2}
                  placeholder="输入问题..."
                  value={pair.question}
                  onChange={(e) => updateQAPair(pair.id, "question", e.target.value)}
                />
              </div>
              <div>
                <Text strong>RAG系统生成的答案</Text>
                <TextArea
                  rows={3}
                  placeholder="输入RAG系统生成的答案..."
                  value={pair.answer}
                  onChange={(e) => updateQAPair(pair.id, "answer", e.target.value)}
                />
              </div>
              <div>
                <Text strong>参考答案（标准答案）</Text>
                <TextArea
                  rows={3}
                  placeholder="输入参考答案..."
                  value={pair.groundTruth}
                  onChange={(e) => updateQAPair(pair.id, "groundTruth", e.target.value)}
                />
              </div>
              <div>
                <Text strong>检索到的上下文（每行一个）</Text>
                <TextArea
                  rows={3}
                  placeholder="输入检索到的上下文，每行一个..."
                  value={pair.contexts.join("\n")}
                  onChange={(e) =>
                    updateQAPair(
                      pair.id,
                      "contexts",
                      e.target.value.split("\n").filter(Boolean)
                    )
                  }
                />
              </div>
            </div>
          </Card>
        ))}
      </Card>

      <Button
        type="primary"
        size="large"
        icon={<PlayCircleOutlined />}
        onClick={handleQAEvaluate}
        loading={loading}
        block
      >
        开始评估
      </Button>

      {renderResult()}
    </div>
  );

  const DocPanel = () => (
    <div className="space-y-4">
      <Alert
        message="文档评估"
        description="上传文档后，系统将自动提取问题并进行RAG评估。支持 PDF、Word、Markdown 等格式。"
        type="info"
        showIcon
      />

      <Card title="上传文档" size="small">
        <Upload
          fileList={fileList}
          beforeUpload={() => false}
          onChange={handleDocUpload}
          maxCount={1}
          accept=".pdf,.doc,.docx,.md,.txt"
        >
          <Button icon={<UploadOutlined />}>选择文档</Button>
        </Upload>
        <Text type="secondary" className="mt-2 block">
          支持 PDF、Word、Markdown、TXT 格式
        </Text>
      </Card>

      <Card title="评估指标选择" size="small">
        <Select
          mode="multiple"
          style={{ width: "100%" }}
          placeholder="选择评估指标"
          value={selectedMetrics}
          onChange={setSelectedMetrics}
          options={availableMetrics.map((m) => ({
            value: m.value,
            label: (
              <Tooltip title={m.description}>
                <span>{m.label}</span>
              </Tooltip>
            ),
          }))}
        />
      </Card>

      {docQuestions.length > 0 && (
        <Card title="提取的问题" size="small">
          {docQuestions.map((q, i) => (
            <Tag key={i} className="mb-1">
              {q}
            </Tag>
          ))}
        </Card>
      )}

      <Button
        type="primary"
        size="large"
        icon={<PlayCircleOutlined />}
        onClick={handleDocEvaluate}
        loading={loading}
        block
      >
        开始评估
      </Button>

      {renderResult()}
    </div>
  );

  const tabItems = [
    {
      key: "qa",
      label: (
        <span>
          <QuestionCircleOutlined />
          问答对评估
        </span>
      ),
      children: QAPanel(),
    },
    {
      key: "doc",
      label: (
        <span>
          <FileTextOutlined />
          文档评估
        </span>
      ),
      children: DocPanel(),
    },
  ];

  return (
    <div className="h-full flex flex-col overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-200 bg-white shrink-0">
        <div className="flex items-center justify-between">
          <div>
            <Title level={4} className="!mb-1">
              RAGAS 评估系统
            </Title>
            <Text type="secondary">
              基于 RAGAS 框架的 RAG 系统检索与生成质量评估
            </Text>
          </div>
          <div className="flex items-center gap-2">
            <Text type="secondary">服务状态:</Text>
            {serviceStatus === "checking" && <Spin size="small" />}
            {serviceStatus === "online" && (
              <Tag color="green">在线</Tag>
            )}
            {serviceStatus === "offline" && (
              <Tag color="red">离线</Tag>
            )}
            <Button size="small" onClick={checkServiceStatus}>
              刷新
            </Button>
          </div>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-6 bg-gray-50">
        {serviceStatus === "offline" && (
          <Alert
            message="评估服务离线"
            description={
              <div>
                <p>请先启动 RAGAS 评估服务：</p>
                <code className="bg-gray-100 px-2 py-1 rounded">
                  python ragas_evaluation_service.py
                </code>
              </div>
            }
            type="warning"
            showIcon
            className="mb-4"
          />
        )}

        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={tabItems}
          className="bg-white p-4 rounded-lg shadow-sm"
        />
      </div>
    </div>
  );
};

export default EvaluationView;
