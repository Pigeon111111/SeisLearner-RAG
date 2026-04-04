package seislearner.agent.tools;

import seislearner.service.HybridRetrievalService;
import seislearner.service.RagService;
import seislearner.service.RecursiveRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final HybridRetrievalService hybridRetrievalService;
    private final RecursiveRetrievalService recursiveRetrievalService;

    // 工具返回结果总字符上限（12000中文字 ≈ 6000 tokens，留足空间给LLM回答和历史消息）
    private static final int MAX_TOTAL_OUTPUT_CHARS = 12000;
    // 每次检索最大返回条数（传给递归检索的 initialLimit）
    private static final int INITIAL_LIMIT = 8;

    public KnowledgeTools(RagService ragService, HybridRetrievalService hybridRetrievalService,
                         RecursiveRetrievalService recursiveRetrievalService) {
        this.ragService = ragService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.recursiveRetrievalService = recursiveRetrievalService;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行语义检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回与查询最相关的知识片段，包含出处信息。系统会自动判断查询复杂度，简单查询直接检索，复杂查询会递归多轮检索以获得更全面的结果。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        // 使用带来源信息的递归检索，带字符预算控制
        RecursiveRetrievalService.RecursiveSearchResultWithSources result =
                recursiveRetrievalService.recursiveSearchDetailedWithSources(
                        kbsId, query, INITIAL_LIMIT, MAX_TOTAL_OUTPUT_CHARS);

        List<RecursiveRetrievalService.SourcedContext> contexts = result.getSourcedContexts();

        if (contexts.isEmpty()) {
            return "未找到相关结果。";
        }

        StringBuilder sb = new StringBuilder();
        
        // 检索摘要（简短，让 LLM 知道检索策略）
        sb.append("检索完成: ");
        sb.append(contexts.size()).append("条结果");
        if (result.getRetrievalDepth() > 1) {
            sb.append(", 递归").append(result.getRetrievalDepth()).append("轮");
            sb.append(", 策略:").append(result.getPrimaryStrategy());
        }
        sb.append(", 置信度:").append(String.format("%.0f%%", result.getFinalConfidence() * 100));
        sb.append("\n\n");

        // 输出带引用编号的结果
        for (int i = 0; i < contexts.size(); i++) {
            RecursiveRetrievalService.SourcedContext ctx = contexts.get(i);

            sb.append("【引用 ").append(i + 1).append("】");
            // 在标题中直接标注来源文件名，方便 LLM 引用
            sb.append("(").append(ctx.getFilename());
            sb.append(", 相关度 ").append(String.format("%.0f%%", ctx.getScore() * 100));
            sb.append(")\n");
            if (ctx.getStartOffset() > 0 || ctx.getEndOffset() > 0) {
                sb.append("位置: 第").append(ctx.getStartOffset());
                if (ctx.getEndOffset() > ctx.getStartOffset()) {
                    sb.append("~").append(ctx.getEndOffset());
                }
                sb.append("字符\n");
            }
            sb.append(ctx.getContent()).append("\n\n");

            // 字符预算检查
            if (sb.length() > MAX_TOTAL_OUTPUT_CHARS) {
                sb.append("(已达到输出上限，更多结果省略)\n");
                break;
            }
        }

        // 提示 LLM 必须在回复中带上来源文件名
        sb.append("【回答要求】\n");
        sb.append("- 回答时必须标注引用来源，格式: 【引用N(来源文件名)】，例如「根据【引用1(地震勘探原理.pdf)】所述...」\n");
        sb.append("- 不要只写【引用1】或【引用N】，必须带上括号内的来源文件名\n");
        sb.append("- 如果引用了多条来源，在回答末尾列出所有引用的来源文件名\n");
        sb.append("- 如果知识库中没有相关信息，请明确告知用户，不要编造答案\n");
        sb.append("\n请基于以上检索结果回答用户问题。\n");

        return sb.toString();
    }
}
