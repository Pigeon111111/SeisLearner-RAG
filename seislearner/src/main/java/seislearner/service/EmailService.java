package seislearner.service;

/**
 * 邮件服务接口
 */
public interface EmailService {
    /**
     * 异步发送邮件
     *
     * @param to      收件人邮箱地址
     * @param subject 邮件主题
     * @param content 邮件内容
     */
    void sendEmailAsync(String to, String subject, String content);
}
