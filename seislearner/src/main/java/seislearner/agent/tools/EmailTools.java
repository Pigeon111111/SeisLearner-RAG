package seislearner.agent.tools;

import seislearner.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailTools implements Tool {

    private final EmailService emailService;

    public EmailTools(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public String getName() {
        return "emailTool";
    }

    @Override
    public String getDescription() {
        return "一个用于发送邮件的工具，可以通过QQ邮件发送邮件给指定的收件人。邮件发送采用异步方式，不会阻塞工具调用。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * 发送邮件（异步执行）
     *
     * @param to      收件人邮箱地址
     * @param subject 邮件主题
     * @param content 邮件内容
     * @return 发送结果信息
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "sendEmail",
            description = "发送邮件到指定的收件人。参数包括：to（收件人邮件地址，必填）、subject（邮件主题，必填）、content（邮件正文内容，必填）。邮件采用异步方式发送，工具调用会立即返回，实际发送在后台执行。"
    )
    public String sendEmail(String to, String subject, String content) {
        // 验证参数
        if (to == null || to.trim().isEmpty()) {
            return "错误：收件人邮件地址不能为空";
        }
        if (subject == null || subject.trim().isEmpty()) {
            return "错误：邮件主题不能为空";
        }
        if (content == null || content.trim().isEmpty()) {
            return "错误：邮件内容不能为空";
        }

        // 验证邮件格式（简单验证）
        if (!to.contains("@")) {
            return "错误：收件人邮件地址格式不正确";
        }

        // 异步发送邮件
        emailService.sendEmailAsync(to.trim(), subject.trim(), content.trim());

        log.info("邮件已提交异步发送，收件人: {}, 主题: {}", to, subject);
        return String.format("邮件已提交发送！\n收件人: %s\n主题: %s\n邮件正在后台异步发送中...", to, subject);
    }
}
